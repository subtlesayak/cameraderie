package app.cameraderie

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.cameraderie.ble.DjiCameraClient
import app.cameraderie.camera.DjiCaptureAdapter
import app.cameraderie.core.DjiSession
import app.cameraderie.gallery.CameraSessionOwnership
import app.cameraderie.gallery.GalleryConnectFlow
import app.cameraderie.gallery.GalleryController
import app.cameraderie.gallery.GalleryPreflight
import app.cameraderie.gallery.GalleryQueueNotificationCommand
import app.cameraderie.gallery.media.GalleryMediaCatalog
import app.cameraderie.gallery.media.GalleryVideoEdit

@SuppressLint("MissingPermission")
class GalleryService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@GalleryService }
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private var galleryController: GalleryController? = null
    val cameraOwnership = CameraSessionOwnership()
    lateinit var dji: DjiCameraClient
        private set
    var sessionActive = false
        private set
    internal lateinit var capture: DjiCaptureAdapter
        private set
    var shooting = false
        private set
    private var preflightUsesShootingLink = false
    var pairing = false
        private set
    var pairingMessage: String? = null
        private set

    private data class CameraCheck(
        val lease: CameraSessionOwnership.Lease,
        val deadline: Long,
        val resolve: (GalleryPreflight.Outcome) -> Unit,
    )
    private var cameraCheck: CameraCheck? = null

    override fun onCreate() {
        super.onCreate()
        dji = DjiCameraClient(this, ::log, {}, {}, {})
        capture = DjiCaptureAdapter(dji)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Camera sessions", NotificationManager.IMPORTANCE_LOW))
        handler.post(tick)
    }

    override fun onBind(intent: Intent) = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            OPEN -> openGallery()
            CLOSE -> if (sessionActive) closeCameraGallery() else stopSelf(startId)
            GalleryQueueNotificationCommand.PAUSE.action -> if (sessionActive) {
                GalleryQueueNotificationCommand.PAUSE.ticket(intent)?.let { galleryController?.pauseQueueFromNotification(it) }
            } else stopSelf(startId)
            GalleryQueueNotificationCommand.RESUME.action -> if (sessionActive) {
                GalleryQueueNotificationCommand.RESUME.ticket(intent)?.let { galleryController?.resumeQueueFromNotification(it) }
            } else stopSelf(startId)
            else -> if (!sessionActive) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun openGallery() {
        if (sessionActive) return
        try {
            startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            if (shooting && !evidence().idle) {
                endSession()
                return
            }
            sessionActive = true
            cancelPairing()
            preflightUsesShootingLink = shooting
            shooting = false
            if (!gallery().connect()) endSession()
        } catch (error: RuntimeException) {
            log("Could not start gallery: ${error.javaClass.simpleName}")
            endSession()
        }
    }

    fun hasPermissions() = cameraPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    val bluetoothEnabled get() = getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true

    fun connectShooting() {
        check(!sessionActive && !pairing && dji.hasAuthorizedBookmark)
        dji.connectSaved()
        shooting = true
    }

    fun closeShooting() {
        check(!sessionActive)
        shooting = false
        dji.disconnect()
    }

    fun canOpenGallery() = !shooting || evidence().idle

    fun scan() {
        check(!sessionActive && !shooting)
        cancelPairing()
        pairingMessage = null
        dji.scan()
    }

    fun pair(index: Int) {
        check(!sessionActive && !shooting)
        pairingMessage = null
        dji.connect(index, forcePair = false)
        pairing = true
    }

    fun cancelPairing() {
        if (pairing) {
            pairing = false
            dji.disconnect()
        }
        if (dji.scanning) dji.stopScan()
    }

    internal fun reserveGalleryOnly(): CameraSessionOwnership.Lease {
        check(sessionActive)
        return cameraOwnership.reserveStandaloneGallery()
    }

    internal fun checkGalleryCamera(lease: CameraSessionOwnership.Lease, resolved: (GalleryPreflight.Outcome) -> Unit) {
        check(sessionActive && cameraOwnership.isCurrent(lease) && lease.galleryPreflightPending && cameraCheck == null)
        check(dji.hasAuthorizedBookmark)
        if (!preflightUsesShootingLink) dji.connectSaved()
        preflightUsesShootingLink = false
        cameraCheck = CameraCheck(lease, SystemClock.elapsedRealtime() + 27_000, resolved)
    }

    private fun evidence() = CameraSessionOwnership.Evidence(
        dji.session.auth == DjiSession.Auth.AUTHORIZED, dji.session.hasFreshStatus,
        dji.session.camera?.power, dji.session.camera?.state, dji.session.busy || dji.session.firmwareBusy,
    )

    internal fun releaseGalleryPreflightLink(lease: CameraSessionOwnership.Lease) {
        check(dji.session.profile?.id == 0xFF55 && cameraOwnership.authorizeGalleryHandoff(lease, evidence()))
        dji.disconnect()
    }

    internal fun releaseGallery(lease: CameraSessionOwnership.Lease) {
        if (!cameraOwnership.isCurrent(lease)) return
        if (cameraCheck?.lease === lease) cameraCheck = null
        if (dji.hasOpenGatt) runCatching { dji.disconnect() }
        cameraOwnership.exit(lease)
    }

    internal fun onGalleryStage(stage: GalleryConnectFlow.Stage) {
        if (stage in setOf(GalleryConnectFlow.Stage.CLOSED, GalleryConnectFlow.Stage.STOPPED, GalleryConnectFlow.Stage.FAILED)) {
            endSession()
        }
    }

    private fun endSession() {
        sessionActive = false
        cameraCheck = null
        if (preflightUsesShootingLink) {
            preflightUsesShootingLink = false
            runCatching { dji.disconnect() }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                if (pairing || shooting || cameraCheck != null) dji.tick()
                if (shooting && !dji.hasOpenGatt) shooting = false
                if (pairing && dji.session.auth == DjiSession.Auth.AUTHORIZED) {
                    pairingMessage = if (dji.session.profile?.id == 0xFF55 && dji.hasAuthorizedBookmark) {
                        "Camera saved. Connect to browse its files."
                    } else {
                        dji.forgetSavedCamera()
                        "Choose a DJI Osmo Action 6 for gallery access."
                    }
                    cancelPairing()
                } else if (pairing && !dji.hasOpenGatt) {
                    pairing = false
                    pairingMessage = dji.link
                }
                cameraCheck?.let { check ->
                    val outcome = GalleryPreflight.evaluate(evidence(), dji.session.profile?.id, dji.hasOpenGatt,
                        SystemClock.elapsedRealtime(), check.deadline)
                    if (outcome != GalleryPreflight.Outcome.WAITING) {
                        cameraCheck = null
                        if (cameraOwnership.isCurrent(check.lease)) check.resolve(outcome)
                    }
                }
                if (sessionActive && !hasPermissions()) galleryController?.cancel()
            } catch (_: SecurityException) {
                pairing = false
                shooting = false
                pairingMessage = "Allow Bluetooth permissions and try again."
                if (sessionActive) galleryController?.cancel()
            }
            handler.postDelayed(this, 250)
        }
    }

    internal fun log(message: String) { Log.d("CameraderieGallery", message) }

    internal fun showGalleryQueueProgress(percent: Int?, resumeTicket: String? = null,
        pauseTicket: String? = null, reconnecting: Boolean = false) {
        if (sessionActive) runCatching {
            notifications.notify(NOTIFICATION, notification(percent, resumeTicket, pauseTicket, reconnecting))
        }
    }

    internal fun notification(percent: Int? = null, resumeTicket: String? = null,
        pauseTicket: String? = null, reconnecting: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val close = PendingIntent.getService(this, 0, Intent(this, GalleryService::class.java).setAction(CLOSE), PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_gallery)
            .setContentTitle(when {
                resumeTicket != null -> "Camera downloads paused"
                reconnecting -> "Reconnecting to camera"
                percent != null -> "Downloading camera originals"
                else -> "DJI camera gallery"
            })
            .setContentText(when {
                reconnecting -> percent?.let { "Total progress · $it% · Refreshing library" } ?: "Refreshing camera library."
                percent != null -> "Total progress · $percent%"
                resumeTicket != null -> "Resume checks the saved bytes before continuing."
                else -> "Open Cameraderie to view the camera session."
            })
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        if (percent != null) builder.setProgress(100, percent, false)
        if (pauseTicket != null) builder.addAction(GalleryQueueNotificationCommand.PAUSE.action(pauseTicket))
        if (resumeTicket != null) builder.addAction(GalleryQueueNotificationCommand.RESUME.action(resumeTicket))
        return builder.addAction(Notification.Action.Builder(null, "Disconnect", close).build()).build()
    }

    private fun GalleryQueueNotificationCommand.action(ticket: String) =
        Notification.Action.Builder(null, label, pendingIntent(this@GalleryService, ticket)).build()

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        galleryController?.stop()
        runCatching { dji.disconnect() }
        super.onDestroy()
    }

    companion object {
        const val OPEN = "app.cameraderie.OPEN_GALLERY"
        private const val CLOSE = "app.cameraderie.CLOSE_GALLERY"
        private const val CHANNEL = "camera_sessions"
        private const val NOTIFICATION = 1
        val cameraPermissions = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    }

    val galleryState get() = galleryController?.state ?: GalleryConnectFlow.State()
    val galleryMediaState get() = galleryController?.mediaState
    fun loadGalleryMedia() = galleryController?.loadMedia() ?: false
    internal val renewingGalleryMedia get() = galleryController?.renewingMedia == true
    internal val resumingGalleryQueue get() = galleryController?.resumingQueueAfterRenewal == true
    internal val galleryQueueTotalPercent get() = galleryController?.queueTotalPercent
    internal fun stopGalleryScanForDownloads() = galleryController?.stopScanningForDownloads() == true
    fun cancelGalleryMedia() { galleryController?.cancelMedia() }
    fun armGalleryMediaCapture() = galleryController?.armMediaCapture() ?: false
    fun galleryMediaPreview(item:GalleryMediaCatalog.Located) = galleryController?.mediaPreview(item)
    internal fun galleryClip(item:GalleryMediaCatalog.Located) = galleryController?.recordedClip(item)
    internal fun setGalleryFavourite(item:GalleryMediaCatalog.Located,on:Boolean) =
        galleryController?.setFavourite(item,on) ?: false
    internal fun checkGalleryFavourites()=galleryController?.checkFavourites() ?: false
    internal fun cancelGalleryFavourites() { galleryController?.cancelFavourites() }
    internal fun canDeleteGalleryFile(item:GalleryMediaCatalog.Located) =
        galleryController?.canDelete(item)==true
    internal fun deleteGalleryFile(item:GalleryMediaCatalog.Located) =
        galleryController?.deleteFile(item)==true
    fun importGalleryOriginal(item:GalleryMediaCatalog.Located,folder:android.net.Uri?=null,companion:Boolean=false) = galleryController?.importOriginal(item,folder,companion) ?: false
    internal fun exportGalleryVideo(item:GalleryMediaCatalog.Located,edit:GalleryVideoEdit) =
        galleryController?.importOriginal(item,edit=edit) ?: false
    internal fun enqueueGalleryOriginals(items:List<GalleryMediaCatalog.Located>) = galleryController?.enqueue(items)
    internal fun galleryQueueItems() = gallery().queueItems()
    internal fun startGalleryQueue(restartId:String?=null) = galleryController?.startQueue(restartId) ?: false
    internal fun pauseGalleryQueue() { galleryController?.pauseQueue() }
    internal fun cancelGalleryQueue(ids:Set<String>) { gallery().cancelQueue(ids) }
    private fun gallery() = galleryController ?: GalleryController(this).also { galleryController = it }
    fun closeCameraGallery() { galleryController?.cancel() }
}
