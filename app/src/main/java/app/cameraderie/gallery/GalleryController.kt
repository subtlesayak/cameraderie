package app.cameraderie.gallery

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.os.*
import app.cameraderie.GalleryService
import app.cameraderie.gallery.ble.GalleryBleClient
import app.cameraderie.gallery.ble.GalleryBleRelease
import app.cameraderie.gallery.net.*
import app.cameraderie.gallery.protocol.*
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import app.cameraderie.gallery.media.*
import app.cameraderie.gallery.media.history.ImportHistoryRepository
import java.util.UUID

/** Main-thread native bindings. Construction never connects, scans, changes the
 * camera, prompts permissions or creates a worker. Entry is an explicit action. */
class GalleryController(private val service: GalleryService) {
    private val main=Handler(Looper.getMainLooper())
    private var selected: BluetoothDevice?=null
    private var selectedLease: CameraSessionOwnership.Lease?=null
    private var selectedRelease: GalleryBleRelease?=null
    private var activeRuntime: NativeRuntime?=null
    private data class MediaRecovery(
        val camera: String,
        val restartId: String?,
        val pauseTicket: String?,
        val alternateHandleJob: String?,
    ) {
        val resumeQueue get() = pauseTicket != null
    }
    private var mediaRecovery: MediaRecovery? = null
    private var notificationProgress: GalleryQueueProgress? = null

    private fun queueNotificationPercent(camera: String): Int? =
        notificationProgress?.takeIf { it.camera == camera }
            ?.percent(GalleryQueueJournal.get(service).snapshot(camera))
    internal val renewingMedia get() = mediaRecovery != null
    internal val resumingQueueAfterRenewal get() = mediaRecovery?.resumeQueue == true
    internal val queueTotalPercent: Int?
        get() {
            serial()
            val camera = mediaRecovery?.camera ?: activeRuntime?.cameraKey
            return camera?.let(::queueNotificationPercent)
        }

    private fun renewMedia(
        camera: String,
        resumeQueue: Boolean,
        restartId: String? = null,
        alternateHandleJob: String? = null,
    ): Boolean {
        if (renewingMedia) return false
        val pauseTicket = if (resumeQueue) UUID.randomUUID().toString() else null
        mediaRecovery = MediaRecovery(camera, restartId, pauseTicket, alternateHandleJob)
        service.log("Gallery reopening after the media request counter reached its limit")
        service.showGalleryQueueProgress(
            if (resumeQueue) queueNotificationPercent(camera) else null,
            pauseTicket = pauseTicket,
            reconnecting = resumeQueue
        )
        flow.reconnectFromBeginning()
        return true
    }
    private fun serial()=check(Looper.myLooper()==Looper.getMainLooper())
    private fun live(r: NativeRuntime)=activeRuntime===r && r.token.active && !r.closed.get()
    private fun owns()=activeRuntime?.let { live(it) && host.current(it.token.lease) }==true
    val state get()=flow.state
    data class MediaState(val scope:String,val cameraName:String,val catalogue:GalleryMediaCatalog.Snapshot,
        val progress:List<GalleryListBatch.Progress>,val transfer:String,val percent:Int?,val importing:Boolean,val savedUri:String?,
        val captureArmed:Boolean,val activeQueueJob:String?,val changingMedia:Boolean,val favouritesStatus:String?,
        val mediaRequestBusy:Boolean) {
        val queueRunning get()=activeQueueJob!=null
    }
    val mediaState:MediaState? get() { serial();return activeRuntime?.takeIf { live(it) }?.mediaState() }
    fun loadMedia():Boolean { serial();return activeRuntime?.takeIf { live(it) && state.stage==GalleryConnectFlow.Stage.READY }?.loadMedia() ?: false }
    internal fun stopScanningForDownloads(): Boolean {
        serial()
        return activeRuntime?.takeIf { live(it) && state.stage == GalleryConnectFlow.Stage.READY }
            ?.stopScanningForDownloads() == true
    }
    fun cancelMedia() {
        serial()
        if (resumingQueueAfterRenewal) service.showGalleryQueueProgress(null)
        mediaRecovery = null
        activeRuntime?.cancelMedia()
    }
    internal fun checkFavourites():Boolean {
        serial()
        return activeRuntime?.takeIf { live(it) && state.stage==GalleryConnectFlow.Stage.READY }?.checkFavourites() ?: false
    }
    internal fun cancelFavourites() { serial();activeRuntime?.cancelFavourites() }
    internal fun enqueue(items:List<GalleryMediaCatalog.Located>):Int? = activeRuntime?.takeIf { live(it) }?.enqueue(items)
    internal fun queueItems():List<GalleryDownloadQueue.Job> = GalleryQueueJournal.get(service).snapshot(activeRuntime?.cameraKey)
    internal fun startQueue(restartId:String?):Boolean = activeRuntime?.takeIf { live(it) }?.startQueue(restartId) ?: false
    internal fun pauseQueue() {
        val recovery = mediaRecovery
        if (recovery?.resumeQueue == true) {
            mediaRecovery = recovery.copy(pauseTicket = null)
            service.showGalleryQueueProgress(null)
        }
        activeRuntime?.pauseQueue()
    }
    internal fun pauseQueueFromNotification(ticket: String) {
        serial()
        if (ticket == mediaRecovery?.pauseTicket) pauseQueue()
        else if (owns()) activeRuntime?.pauseQueueFromNotification(ticket)
    }
    internal fun resumeQueueFromNotification(ticket: String) {
        serial()
        if (state.stage == GalleryConnectFlow.Stage.READY && owns() && wifi.hasPermission()) {
            activeRuntime?.resumeQueueFromNotification(ticket)
        }
    }
    internal fun cancelQueue(ids:Set<String>) {
        val runtime = activeRuntime
        val queue=GalleryQueueJournal.get(service)
        queue.cancel(ids)
        runtime?.removeCanceledResumeOffer(ids)
        if(runtime!=null && runtime.activeQueueId?.let(ids::contains)==true)runtime.pauseQueue()
        val workerId=queue.activeId
        val parts=GalleryQueueJournal.partials(service)
        queue.snapshot().filter { it.id in ids && it.state==GalleryDownloadQueue.State.CANCELED && it.id!=workerId }
            .forEach { runCatching { parts.discard(it.id) } }
    }
    fun armMediaCapture():Boolean { serial();return activeRuntime?.takeIf { live(it) }?.armMediaCapture() ?: false }
    internal fun importOriginal(expected:GalleryMediaCatalog.Located,folder:android.net.Uri?=null,companion:Boolean=false,edit:GalleryVideoEdit?=null):Boolean { serial();return activeRuntime?.takeIf { live(it) }?.importOriginal(expected,folder,companion,edit) ?: false }
    internal fun setFavourite(item:GalleryMediaCatalog.Located,on:Boolean):Boolean {
        serial()
        return activeRuntime?.takeIf { live(it) && state.stage==GalleryConnectFlow.Stage.READY }?.setFavourite(item,on) ?: false
    }
    internal fun canDelete(item:GalleryMediaCatalog.Located):Boolean {
        serial()
        return activeRuntime?.takeIf { live(it) && state.stage==GalleryConnectFlow.Stage.READY }?.deleteTarget(item)!=null
    }
    internal fun deleteFile(item:GalleryMediaCatalog.Located):Boolean {
        serial()
        return activeRuntime?.takeIf { live(it) && state.stage==GalleryConnectFlow.Stage.READY }?.deleteFile(item) ?: false
    }
    /** Build an immutable access closure on main; the actual HTTP executes on the
     * browser's bounded workers and rechecks the worker's thread-safe context. */
    fun mediaPreview(expected:GalleryMediaCatalog.Located):(()->ByteArray?)? {
        serial();return activeRuntime?.takeIf { live(it) }?.preview(expected)
    }
    internal fun recordedClip(item:GalleryMediaCatalog.Located):GalleryClipDataSource? {
        serial()
        return activeRuntime?.takeIf { live(it) && state.stage==GalleryConnectFlow.Stage.READY }?.recordedClip(item)
    }
    private val host: GalleryConnectFlow.Host=object: GalleryConnectFlow.Host {
        override fun acquire(): CameraSessionOwnership.Lease {
            if(!service.hasPermissions()) throw GalleryConnectFlow.EntryFailure(GalleryConnectFlow.Category.PERMISSION)
            if(!wifi.hasPermission()) throw GalleryConnectFlow.EntryFailure(GalleryConnectFlow.Category.PERMISSION,GalleryConnectFlow.Stage.WIFI_JOINING)
            if(!wifi.enabled) throw GalleryConnectFlow.EntryFailure(GalleryConnectFlow.Category.WIFI_DISABLED,GalleryConnectFlow.Stage.WIFI_JOINING)
            if(!service.bluetoothEnabled) throw GalleryConnectFlow.EntryFailure(GalleryConnectFlow.Category.BLUETOOTH)
            if (!service.sessionActive || !service.dji.hasAuthorizedBookmark)
                throw GalleryConnectFlow.EntryFailure(GalleryConnectFlow.Category.PRECONDITION, GalleryConnectFlow.Stage.CHECKING_CAMERA)
            val saved=service.dji.savedCamera ?: throw GalleryConnectFlow.EntryFailure(GalleryConnectFlow.Category.PRECONDITION)
            val device=try { service.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(saved.address) }
                catch(_: SecurityException) { throw GalleryConnectFlow.EntryFailure(GalleryConnectFlow.Category.PERMISSION) }
            val release=GalleryBleRelease(service,device,service::log)
            val lease=try {
                service.reserveGalleryOnly()
            } catch(_: SecurityException) {
                release.close();throw GalleryConnectFlow.EntryFailure(GalleryConnectFlow.Category.PERMISSION)
            } catch(error: RuntimeException) { release.close();throw error }
            selectedRelease=release;selected=device;selectedLease=lease;return lease
        }
        override fun current(lease: CameraSessionOwnership.Lease)=service.cameraOwnership.isCurrent(lease) &&
            service.cameraOwnership.owner in setOf(CameraSessionOwnership.Owner.GALLERY_TRANSITION,CameraSessionOwnership.Owner.GALLERY)
        override fun connected(lease: CameraSessionOwnership.Lease)=service.cameraOwnership.connected(lease)
        override fun release(lease: CameraSessionOwnership.Lease): Boolean {
            selectedRelease?.close();selectedRelease=null
            selected=null;selectedLease=null;service.releaseGallery(lease)
            return false
        }
        override fun retryRestore() = Unit
        override fun runtime(token: GalleryConnectFlow.Token,events: GalleryConnectFlow.Events): GalleryConnectFlow.Runtime {
            check(selectedLease===token.lease)
            return NativeRuntime(token,events,checkNotNull(selected),checkNotNull(selectedRelease)).also { activeRuntime=it;selectedRelease=null }
        }
    }
    private val flow: GalleryConnectFlow=GalleryConnectFlow(host,SystemClock::elapsedRealtime) { s ->
        val failure=s.failure
        service.log("Gallery stage=${s.stage} attempt=${s.attempt}"+(if(failure==null) "" else " failed_stage=${failure.stage} category=${failure.category} elapsed_ms=${failure.elapsedMs} response=${failure.responseCode ?: -1}"))
        if (s.stage in setOf(GalleryConnectFlow.Stage.FAILED, GalleryConnectFlow.Stage.CLOSED, GalleryConnectFlow.Stage.STOPPED)) {
            if (resumingQueueAfterRenewal) service.showGalleryQueueProgress(null)
            mediaRecovery = null
        }
        service.onGalleryStage(s.stage)
    }
    private val ble: GalleryBleClient by lazy { GalleryBleClient(service,::owns,object: GalleryBleClient.Listener {
        override fun setupChanged(setup: GalleryBleClient.Setup) {
            service.log("Gallery BLE setup=${setup.name}")
            activeRuntime?.takeIf { live(it) && !it.wifiStarted }?.events?.progress(GalleryConnectFlow.Stage.BLE_CONNECTION)
        }
        override fun protocolChanged(phase: GalleryBleHandshake.Phase) {
            val r=activeRuntime ?: return
            if(live(r) && !r.wifiStarted) phaseStage(phase)?.let { r.events.progress(it) }
        }
        override fun credentialsReady(credentials: CameraWifiCredentials,identity: GalleryIdentity) {
            activeRuntime?.takeIf { live(it) }?.events?.credentials(credentials,identity)
        }
        override fun failed(failure: GalleryBleClient.Failure,protocol: GalleryBleHandshake.Failure?) {
            val r=activeRuntime ?: return;if(!live(r)) return
            if(GalleryBleLossPolicy.defersToWifi(r.wifiStarted,failure,protocol)) {
                service.log("Gallery BLE disconnected after_credentials=${r.wifiStarted}; Wi-Fi/session evidence determines readiness")
                r.events.bleDisconnected();return
            }
            val diagnostic=ble.lastProtocolDiagnostic
            val category=when {
                failure==GalleryBleClient.Failure.PERMISSION_REQUIRED -> GalleryConnectFlow.Category.PERMISSION
                failure==GalleryBleClient.Failure.IDENTITY_UNAVAILABLE -> GalleryConnectFlow.Category.IDENTITY
                failure==GalleryBleClient.Failure.OWNERSHIP_REQUIRED -> GalleryConnectFlow.Category.PRECONDITION
                protocol in setOf(GalleryBleHandshake.Failure.PAIRING_TIMEOUT,GalleryBleHandshake.Failure.APPROVAL_TIMEOUT,GalleryBleHandshake.Failure.CREDENTIAL_TIMEOUT) || failure==GalleryBleClient.Failure.SETUP_TIMEOUT -> GalleryConnectFlow.Category.TIMEOUT
                protocol in setOf(GalleryBleHandshake.Failure.PAIRING_REJECTED,GalleryBleHandshake.Failure.INVALID_PAIRING_REPLY) -> GalleryConnectFlow.Category.REJECTED
                protocol==GalleryBleHandshake.Failure.INVALID_CREDENTIALS -> GalleryConnectFlow.Category.CREDENTIALS
                else -> GalleryConnectFlow.Category.BLUETOOTH
            }
            service.log("Gallery BLE failure=${failure.name} protocol=${protocol?.name ?: "NONE"} negotiated_mtu=${ble.negotiatedMtu ?: 0}")
            ble.lastTransportDiagnostic?.let { service.log("Gallery BLE transport $it") }
            r.events.failed(category,diagnostic?.stage?.let(::phaseStage) ?: GalleryConnectFlow.Stage.BLE_CONNECTION,
                diagnostic?.elapsedMs,diagnostic?.attemptCount,diagnostic?.responseCode)
        }
    }) }
    private val wifi: GalleryWifiClient by lazy { GalleryWifiClient(service,::owns,object: GalleryWifiClient.Listener {
        override fun connected(network: BoundCameraNetwork) {
            val r=activeRuntime
            if(r==null || !live(r)) network.close() else r.events.network(network)
        }
        override fun failed(reason: GalleryWifiClient.Failure) {
            val r=activeRuntime ?: return;if(!live(r)) return
            val category=when(reason) {
                GalleryWifiClient.Failure.PERMISSION_REQUIRED -> GalleryConnectFlow.Category.PERMISSION
                GalleryWifiClient.Failure.WIFI_DISABLED -> GalleryConnectFlow.Category.WIFI_DISABLED
                GalleryWifiClient.Failure.UNAVAILABLE -> GalleryConnectFlow.Category.WIFI_UNAVAILABLE
                GalleryWifiClient.Failure.JOIN_TIMEOUT -> GalleryConnectFlow.Category.TIMEOUT
                GalleryWifiClient.Failure.INVALID_CAMERA_ROUTE -> GalleryConnectFlow.Category.ROUTE
                GalleryWifiClient.Failure.NETWORK_LOST -> GalleryConnectFlow.Category.NETWORK_LOST
                else -> GalleryConnectFlow.Category.INTERNAL
            }
            service.log("Gallery Wi-Fi failure=${reason.name}")
            r.events.failed(category,GalleryConnectFlow.Stage.WIFI_JOINING)
        }
    }) }
    private data class Validation(val item:GalleryMediaCatalog.Located,val ticket:GalleryDatalinkSession.MediaTicket,val folder:android.net.Uri?,val companion:GalleryCompanion?,val edit:GalleryVideoEdit?,
        val remainingFallbacks: List<Long>? = null)
    private enum class FavouriteStep { BEFORE_WRITE, WRITE, READBACK }
    private data class FavouriteChange(val item:GalleryMediaCatalog.Located,val on:Boolean,val step:FavouriteStep,
        val ticketId:Long,val cancel:()->Unit)
    private class Deletion(val item:GalleryMediaCatalog.Located,val target:GalleryDeleteTarget,
        val validation:GalleryDatalinkSession.MediaTicket) {
        var write:GalleryDatalinkSession.MediaWriteTicket?=null
        var receipt:GalleryDatalinkSession.MediaWriteResult?=null
        var readbacks=0
    }

    private data class QueueResumeOffer(val ticket: String, val jobId: String)

    @android.annotation.SuppressLint("MissingPermission")
    private inner class NativeRuntime(val token: GalleryConnectFlow.Token,val events: GalleryConnectFlow.Events,private val device: BluetoothDevice,private val release: GalleryBleRelease): GalleryConnectFlow.Runtime {
        val closed=AtomicBoolean(false)
        var wifiStarted=false
        private var network: BoundCameraNetwork?=null
        private var session: GalleryDatalinkSession?=null
        private var executor: ExecutorService?=null
        private val catalogue=GalleryMediaCatalog()
        private val favouritesCatalogue=GalleryMediaCatalog()
        private var favouritesContext:GalleryDatalinkSession.MediaContext?=null
        private var favouritesRequest:Pair<GalleryMediaCatalog.Request,GalleryDatalinkSession.MediaTicket>?=null
        private var favouritesStatus:String?=null
        private val scope=UUID.randomUUID().toString()
        private val cameraName=service.dji.savedCamera?.name ?: "Action camera"
        val cameraKey = GalleryDownloadQueue.camera(device.address)
        private val queue by lazy { GalleryQueueJournal.get(service) }
        private val partials by lazy { GalleryQueueJournal.partials(service) }
        private var queueClaim: GalleryDownloadQueue.Claim? = null
        private var explicitRestartId: String? = null
        private var alternateHandleJob: String? = null
        val activeQueueId get()=queueClaim?.job?.id
        private var queueRunning = false
        private var requestedPauseId: String? = null
        private var queuePauseTicket: String? = null
        private var lastQueueNotificationPercent: Int? = null
        private var lastQueueNotificationAt = 0L
        private var queueResumeOffer: QueueResumeOffer? = null
        private var listRequest:Pair<GalleryMediaCatalog.Request,GalleryDatalinkSession.MediaTicket>?=null
        private var validation:Validation?=null
        private var favouriteChange:FavouriteChange?=null
        private var deletion:Deletion?=null
        private val changingMedia get()=favouriteChange!=null || deletion!=null
        private var mediaProgress=emptyList<GalleryListBatch.Progress>()
        private var transfer="";private var percent:Int?=null;private var savedUri:String?=null
        private var importing=false
        private var transferCancel:AtomicBoolean?=null
        private var transferHttp:GalleryHttpMedia?=null
        private var clipHttp:GalleryHttpMedia?=null
        private var transferExecutor:ExecutorService?=null
        private val transferThread=java.util.concurrent.atomic.AtomicReference<Thread?>(null)
        private var captureArmed=false
        @Volatile private var capture:GalleryListCapture?=null
        @Volatile private var captureAt=0L
        fun mediaState()=MediaState(scope,cameraName,catalogue.snapshot,mediaProgress,transfer,percent,importing,savedUri,captureArmed,activeQueueId,changingMedia,favouritesStatus,session?.mediaBusy==true)
        fun armMediaCapture():Boolean {
            if(service.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE==0 || importing || changingMedia || session?.ready!=true || session?.mediaBusy==true)return false
            captureArmed=true;return true
        }
        private fun finishCapture() {
            val recorder=capture ?: return;capture=null
            val data=recorder.finish() ?: return
            val pool=Executors.newSingleThreadExecutor { task -> Thread(task,"GripBridge-private-list-diagnostic").apply { isDaemon=true } }
            pool.execute {
                var saved=false
                try { synchronized(captureFileLock) {
                    val dir=java.io.File(service.noBackupFilesDir,"gallery-list-diagnostics");check(dir.isDirectory || dir.mkdirs())
                    val older=dir.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("list-[a-f0-9-]{36}\\.bin")) }.sortedByDescending { it.lastModified() }
                    older.drop(1).forEach { check(it.delete()) }
                    val file=java.io.File(dir,"list-${UUID.randomUUID()}.bin")
                    try { file.outputStream().use { it.write(data) };saved=true } catch(error:Exception) { file.delete();throw error }
                } } catch(_:Exception) {} finally { data.fill(0) }
                main.post { service.log("Gallery private media diagnostic saved=$saved bytes=${data.size} dropped=${recorder.dropped} export_included=false") };pool.shutdown()
            }
        }
        fun loadMedia(afterRenewal: Boolean = false):Boolean {
            serial();val worker=session ?: return false;val context=worker.mediaContext ?: return false
            if(importing || changingMedia || worker.mediaBusy)return false
            if (!afterRenewal && worker.mediaCountersExhausted) return renewMedia(cameraKey, false)
            clearQueueResumeOffer()
            cancelFavourites()
            finishCapture()
            if(captureArmed) { captureArmed=false;captureAt=SystemClock.elapsedRealtime();capture=GalleryListCapture() }
            closeClip()
            catalogue.start(context);mediaProgress=emptyList();transfer="";savedUri=null;percent=null;advancePages();return true
        }
        private fun advancePages() {
            val worker=session ?: return
            val request=catalogue.nextRequest() ?: run {
                finishCapture();deletion?.takeIf { it.receipt!=null }?.let(::finishDeleteReadback)
                finishMediaRecovery(worker)
                return
            }
            val ticket=worker.requestMediaPages(request.cursors,request.context)
            if(ticket==null) {
                catalogue.apply(request,GalleryListBatch.failed(request.cursors.keys,GalleryListBatch.Error.CANCELED))
                deletion?.takeIf { it.receipt!=null }?.let(::finishDeleteReadback)
                finishMediaRecovery(worker)
            } else listRequest=request to ticket
        }
        private fun finishMediaRecovery(worker: GalleryDatalinkSession) {
            val recovery = mediaRecovery?.takeIf { it.camera == cameraKey } ?: return
            mediaRecovery = null
            if (!recovery.resumeQueue) return
            if (catalogue.snapshot.complete && !worker.mediaCountersExhausted &&
                startQueue(recovery.restartId, recovery.alternateHandleJob)
            ) return
            transfer = "Downloads are paused. Refresh the camera library before resuming."
            service.showGalleryQueueProgress(null)
        }
        fun stopScanningForDownloads(): Boolean {
            serial()
            if (importing || changingMedia || renewingMedia ||
                catalogue.snapshot.entries.none { GalleryOriginalImporter.supports(it.media) } ||
                !catalogue.stopForDownloads()
            ) return false
            listRequest?.second?.cancel()
            listRequest = null
            mediaProgress = emptyList()
            finishCapture()
            transfer = ""
            return true
        }

        fun cancelMedia() {
            serial()
            queueRunning=false
            requestedPauseId=null
            queuePauseTicket=null
            clearQueueResumeOffer()
            finishCapture();captureArmed=false;listRequest?.second?.cancel();listRequest=null;catalogue.cancel()
            cancelFavourites()
            favouriteChange?.let { change ->
                change.cancel()
                if(change.step!=FavouriteStep.BEFORE_WRITE)catalogue.updateFavourite(change.item,null)
                favouriteChange=null
            }
            deletion?.let { change -> change.validation.cancel();change.write?.cancel() }
            deletion=null
            cancelTransfer()
        }
        private fun cancelTransfer() {
            validation?.ticket?.cancel();validation=null
            transferCancel?.set(true);transferHttp?.close();transferThread.get()?.interrupt()
            if(importing && transferExecutor==null) {
                importing=false;percent=null
                val claim=queueClaim
                if(claim!=null) finishQueueClaim(claim.job.state)
                else transfer="Import canceled before download."
            }
        }
        fun enqueue(items:List<GalleryMediaCatalog.Located>):Int? {
            serial()
            val worker = session ?: return null
            if(items.size !in 1..GalleryDownloadQueue.MAX_FILES || importing || changingMedia || !catalogue.snapshot.downloadsAvailable ||
                items.any { !catalogue.current(it) || !worker.currentMediaContext(it.context, it.media.key.store) ||
                    !GalleryOriginalImporter.supports(it.media) })return null
            return queue.enqueue(cameraKey,items.map { GalleryDownloadQueue.source(it.media) })
        }
        private fun queueEligibility(): (GalleryDownloadQueue.Job) -> Boolean {
            val library = catalogue.snapshot
            if (library.complete) return { true }
            val found = library.entries.filter { GalleryOriginalImporter.supports(it.media) }
                .mapTo(HashSet()) { GalleryDownloadQueue.source(it.media) }
            return { it.source in found }
        }

        fun startQueue(restartId:String?, retryJob:String? = null):Boolean {
            serial()
            if(!service.sessionActive || importing || changingMedia || queueRunning || !catalogue.snapshot.downloadsAvailable || session?.ready!=true || session?.mediaBusy!=false)return false
            queueResumeOffer=null
            requestedPauseId=null
            explicitRestartId=restartId
            alternateHandleJob=retryJob
            val eligible = queueEligibility()
            val pending = queue.snapshot(cameraKey).filter { it.state.pending && eligible(it) }
            val previous = notificationProgress
            if (previous?.camera == cameraKey && previous.includesPending(pending)) previous.include(pending)
            else notificationProgress = GalleryQueueProgress(cameraKey, pending)
            queueRunning=true
            return advanceQueue()
        }
        fun pauseQueue() {
            serial()
            if(!queueRunning && queueClaim==null)return
            queueRunning=false
            queuePauseTicket=null
            requestedPauseId = activeQueueId
            if(queueClaim!=null)cancelTransfer()
        }
        fun pauseQueueFromNotification(ticket: String) {
            if (ticket == queuePauseTicket) pauseQueue()
        }
        fun resumeQueueFromNotification(ticket: String) {
            val offer = queueResumeOffer ?: return
            if (ticket != offer.ticket) return
            if (!queue.canResumeHead(cameraKey, offer.jobId, queueEligibility())) {
                clearQueueResumeOffer()
                return
            }
            startQueue(null)
        }
        fun removeCanceledResumeOffer(ids: Set<String>) {
            if (queueResumeOffer?.jobId in ids) clearQueueResumeOffer()
        }
        private fun clearQueueResumeOffer() {
            if (queueResumeOffer == null) return
            queueResumeOffer = null
            if (activeRuntime === this) service.showGalleryQueueProgress(null)
        }
        private fun advanceQueue():Boolean {
            val eligible = queueEligibility()
            if (session?.mediaCountersExhausted == true && queue.snapshot(cameraKey).any { it.state.pending && eligible(it) }) {
                val restartId = explicitRestartId
                queueRunning = false
                explicitRestartId = null
                return renewMedia(cameraKey, true, restartId)
            }
            val claim = queue.claim(cameraKey, eligible)
            if(claim==null) {
                queueRunning=false
                explicitRestartId=null
                notificationProgress = null
                service.showGalleryQueueProgress(null)
                return false
            }
            queueClaim=claim
            if(claim.job.state==GalleryDownloadQueue.State.RESTART && explicitRestartId!=claim.job.id) {
                finishQueueClaim(GalleryDownloadQueue.State.RESTART)
                return true
            }
            val source=claim.job.source
            val item=catalogue.snapshot.entries.singleOrNull { located ->
                located.media.key.store==source.store && located.media.name==source.name &&
                    located.media.size==source.bytes && GalleryDownloadQueue.source(located.media)==source
            }
            val preferAlternateHandle = claim.job.id == alternateHandleJob
            if(item==null || !importOriginal(item, preferAlternateHandle = preferAlternateHandle)) {
                finishQueueClaim(GalleryDownloadQueue.State.REFRESH)
                return true
            }
            val pauseTicket = UUID.randomUUID().toString()
            queuePauseTicket = pauseTicket
            if (claim.job.state == GalleryDownloadQueue.State.RESTART) notificationProgress?.record(claim.job.id, 0)
            lastQueueNotificationPercent = queueNotificationPercent(cameraKey) ?: 0
            lastQueueNotificationAt = SystemClock.elapsedRealtime()
            service.showGalleryQueueProgress(lastQueueNotificationPercent, pauseTicket = pauseTicket)
            return true
        }
        private fun finishQueueClaim(result: GalleryDownloadQueue.State,notice:String?=null) {
            val claim=queueClaim ?: return
            queueClaim=null
            queuePauseTicket=null
            val wasPaused = requestedPauseId == claim.job.id
            requestedPauseId = null
            val saved=runCatching { queue.finish(claim,result) }.isSuccess
            if(saved && queue.snapshot().any { it.id==claim.job.id && !it.state.pending })runCatching { partials.discard(claim.job.id) }
            percent=null
            transfer=when {
                !saved -> "Could not update the download queue. Reopen it to check the last file."
                notice!=null -> notice
                result==GalleryDownloadQueue.State.COMPLETE -> "Queued original imported and verified."
                result==GalleryDownloadQueue.State.REFRESH -> "Queued file could not be found. Refresh the library or cancel that file in the download queue."
                result==GalleryDownloadQueue.State.REVIEW -> "Check the last file in Imported Images before restarting the queue."
                result==GalleryDownloadQueue.State.RESTART -> "The camera could not confirm the saved download. Select Restart file to start it again."
                else -> "Download queue paused. Resume checks any saved bytes before continuing."
            }
            if(saved && live(this) && queueRunning && result==GalleryDownloadQueue.State.COMPLETE) {
                if(advanceQueue())return
                transfer=if(!catalogue.snapshot.complete && queue.snapshot(cameraKey).any { it.state.pending })
                    "Downloads from the scan finished. Refresh the library to find the remaining files."
                else "Download queue finished. Verified files are in Imported Images."
            }
            queueRunning=false
            explicitRestartId=null
            if(activeRuntime===this) {
                queueResumeOffer = if (wasPaused && saved && live(this) && state.stage == GalleryConnectFlow.Stage.READY &&
                    catalogue.snapshot.downloadsAvailable && queue.canResumeHead(cameraKey, claim.job.id, queueEligibility())) {
                    QueueResumeOffer(UUID.randomUUID().toString(), claim.job.id)
                } else null
                service.showGalleryQueueProgress(
                    queueResumeOffer?.let { queueNotificationPercent(cameraKey) },
                    resumeTicket = queueResumeOffer?.ticket,
                )
            }
        }
        private fun http(item:GalleryMediaCatalog.Located,cancel:AtomicBoolean?=null):GalleryHttpMedia? {
            val worker=session ?: return null;val bound=network ?: return null
            return GalleryHttpMedia({ uri -> val handle=bound.http(uri)
                object:GalleryHttpMedia.Request { override val connection get()=handle.value;override fun close()=handle.close() }
            },{ token.active && !closed.get() && cancel?.get()!=true && worker.currentMediaContext(item.context,item.media.key.store) })
        }
        fun preview(item:GalleryMediaCatalog.Located):(()->ByteArray?)? {
            if(!catalogue.current(item))return null
            val worker=session ?: return null;val bound=network ?: return null
            return { GalleryHttpMedia({ uri -> val handle=bound.http(uri)
                object:GalleryHttpMedia.Request { override val connection get()=handle.value;override fun close()=handle.close() }
            },{ token.active && !closed.get() && worker.currentMediaContext(item.context,item.media.key.store) }).use { it.preview(item.media) } }
        }
        private fun closeClip() {
            clipHttp?.close()
            clipHttp=null
        }
        fun recordedClip(item:GalleryMediaCatalog.Located):GalleryClipDataSource? {
            if(importing || !catalogue.snapshot.complete || !catalogue.current(item) ||
                session?.mediaBusy!=false || !GalleryHttpMedia.supportsProxy(item.media)) return null
            val access=http(item) ?: return null
            closeClip()
            clipHttp=access
            return GalleryClipDataSource(access,item.media)
        }
        fun importOriginal(
            item:GalleryMediaCatalog.Located,folder:android.net.Uri?=null,companion:Boolean=false,edit:GalleryVideoEdit?=null,
            preferAlternateHandle:Boolean=false,
        ):Boolean {
            serial();val worker=session ?: return false;val key=item.media.key
            val library = catalogue.snapshot
            if(importing || changingMedia || !library.downloadsAvailable || !catalogue.current(item) ||
                !worker.currentMediaContext(item.context,key.store) || worker.mediaBusy)return false
            if (!library.complete && (edit != null || companion)) return false
            if(edit!=null && (companion || folder!=null || queueClaim!=null ||
                !GalleryFrameExporter.supports(item.media))) return false
            val related = if(companion) {
                if(folder==null || queueClaim!=null) return false
                GalleryCompanion.forParent(item.media) ?: return false
            } else {
                if(!GalleryOriginalImporter.supports(item.media)) return false
                null
            }
            val alternateHandles = if (preferAlternateHandle) catalogue.validationFallbacks(item) else emptyList()
            val cursor = alternateHandles.firstOrNull() ?: item.requestCursor
            val ticket=worker.requestMediaPages(mapOf(key.store to cursor),item.context) ?: return false
            closeClip()
            importing=true;transfer="Confirming the selected camera file…";percent=null;savedUri=null
            val remainingHandles = if (alternateHandles.isNotEmpty()) alternateHandles.drop(1) + item.requestCursor else null
            transferCancel=AtomicBoolean(false)
            validation=Validation(item,ticket,folder,related,edit,remainingHandles)
            return true
        }
        fun setFavourite(item:GalleryMediaCatalog.Located,on:Boolean):Boolean {
            serial();val worker=session ?: return false
            if(importing || queueRunning || changingMedia || !catalogue.favouriteTarget(item))return false
            val ticket=readFilePage(worker,item) ?: return false
            closeClip()
            favouriteChange=FavouriteChange(item,on,FavouriteStep.BEFORE_WRITE,ticket.id,ticket::cancel)
            transfer="Checking the camera file…";percent=null;savedUri=null
            return true
        }
        fun checkFavourites():Boolean {
            val worker=session ?: return false
            val context=worker.mediaContext ?: return false
            if(importing || changingMedia || worker.mediaBusy || !catalogue.snapshot.complete || favouritesStatus!=null)return false
            favouritesContext=context
            favouritesStatus="Checking favourites on the camera…"
            favouritesCatalogue.start(context)
            advanceFavourites(worker)
            return true
        }
        fun cancelFavourites() {
            favouritesRequest?.second?.cancel()
            favouritesRequest=null;favouritesContext=null;favouritesStatus=null
            favouritesCatalogue.close()
        }
        private fun advanceFavourites(worker:GalleryDatalinkSession) {
            val request=favouritesCatalogue.nextRequest()
            if(request!=null) {
                val ticket=worker.requestMediaPages(request.cursors,request.context,favouritesOnly=true)
                if(ticket!=null) {
                    favouritesRequest=request to ticket
                    return
                }
                favouritesCatalogue.apply(request,GalleryListBatch.failed(request.cursors.keys,GalleryListBatch.Error.CANCELED))
            }
            val context=favouritesContext ?: return
            val verified = catalogue.reconcileFavourites(favouritesCatalogue.snapshot, context)
            val remaining = catalogue.snapshot.stores
                .filter { it.phase != GalleryMediaCatalog.Phase.UNAVAILABLE && it.store !in verified }
                .map { it.store }
            fun name(store: GalleryCameraEvidence.Store) = when (store) {
                GalleryCameraEvidence.Store.SD -> "SD card"
                GalleryCameraEvidence.Store.INTERNAL -> "internal storage"
            }
            favouritesStatus = when {
                verified.isEmpty() -> "Camera favourites could not be confirmed. Showing the latest catalogue flags."
                remaining.isEmpty() -> "Camera favourites verified."
                else -> "Camera favourites verified for ${verified.joinToString(transform = ::name)}. " +
                    "Showing catalogue flags for ${remaining.joinToString(transform = ::name)}."
            }
            service.log("Gallery favourite filter verified=${verified.map { it.name }} unverified=${remaining.map { it.name }}")
            favouritesContext=null;favouritesCatalogue.close()
        }
        private fun readFilePage(worker:GalleryDatalinkSession,item:GalleryMediaCatalog.Located) =
            worker.requestMediaPages(mapOf(item.media.key.store to item.requestCursor),item.context)

        private fun favouritePageFinished(worker:GalleryDatalinkSession,change:FavouriteChange,result:GalleryListBatch.Result) {
            val item=change.item;val store=item.media.key.store
            val outcome=result.stores[store]
            val observed=outcome?.page?.entries?.singleOrNull { it.sameFile(item.media) }?.favourite
            if(outcome?.error!=null || observed==null || !catalogue.favouriteTarget(item) ||
                !worker.currentMediaContext(item.context,store)) {
                finishFavourite(change,null)
                return
            }
            if(change.step==FavouriteStep.READBACK || observed==change.on) {
                finishFavourite(change,observed)
                return
            }
            val ticket=worker.requestFavourite(item.media,change.on,item.context)
            if(ticket==null)finishFavourite(change,null)
            else {
                favouriteChange=change.copy(step=FavouriteStep.WRITE,ticketId=ticket.id,cancel=ticket::cancel)
                transfer="Updating favourite on camera…"
            }
        }
        private fun favouriteFinished(ticketId:Long,result:GalleryDatalinkSession.MediaWriteResult) {
            val change=favouriteChange?.takeIf { it.step==FavouriteStep.WRITE && it.ticketId==ticketId } ?: return
            service.log("Gallery favourite write=${result.name}")
            val worker=session
            val readback=if(result==GalleryDatalinkSession.MediaWriteResult.STALE || worker==null) null
                else readFilePage(worker,change.item)
            if(readback==null)finishFavourite(change,null)
            else {
                favouriteChange=change.copy(step=FavouriteStep.READBACK,ticketId=readback.id,cancel=readback::cancel)
                transfer="Verifying favourite on camera…"
            }
        }
        private fun finishFavourite(change:FavouriteChange,observed:Boolean?) {
            favouriteChange=null
            if(observed==null)favouritesStatus=null
            val updated=catalogue.updateFavourite(change.item,observed)
            transfer=when {
                !updated || observed==null -> "Favourite could not be verified. Refresh the camera library."
                observed!=change.on -> "The camera did not change this favourite."
                observed -> "Added to camera favourites."
                else -> "Removed from camera favourites."
            }
        }
        private fun deleteEvidence(item:GalleryMediaCatalog.Located):GalleryDeleteTarget? {
            val snapshot=catalogue.snapshot
            if(!snapshot.complete || !catalogue.current(item))return null
            val source=GalleryDownloadQueue.source(item.media)
            if(runCatching { queue.snapshot(cameraKey).any { it.state.pending && it.source==source } }.getOrDefault(true))return null
            return GalleryDeleteTarget.from(item.media,snapshot.entries.map { it.media })
        }
        fun deleteTarget(item:GalleryMediaCatalog.Located):GalleryDeleteTarget? =
            if(importing || queueRunning || changingMedia || session?.mediaBusy!=false)null else deleteEvidence(item)

        fun deleteFile(item:GalleryMediaCatalog.Located):Boolean {
            serial();val worker=session ?: return false
            val target=deleteTarget(item) ?: return false
            val ticket=readFilePage(worker,item) ?: return false
            closeClip()
            deletion=Deletion(item,target,ticket)
            transfer="Checking the file before deletion…";percent=null;savedUri=null
            return true
        }
        private fun deletePageFinished(worker:GalleryDatalinkSession,change:Deletion,result:GalleryListBatch.Result) {
            val item=change.item;val store=item.media.key.store
            val outcome=result.stores[store]
            val confirmed=outcome?.error==null && outcome?.page?.entries?.singleOrNull { it.handle==item.media.handle }
                ?.sameFile(item.media)==true
            val ticket=if(confirmed && deleteEvidence(item)!=null)worker.requestDelete(item.media,item.context) else null
            if(ticket==null) {
                deletion=null;transfer="File identity could not be confirmed. Refresh the camera library."
            } else {
                change.write=ticket
                transfer="Deleting file from camera…"
            }
        }
        private fun mediaWriteFinished(ticketId:Long,result:GalleryDatalinkSession.MediaWriteResult) {
            val change=deletion?.takeIf { it.write?.id==ticketId }
            if(change==null) {
                favouriteFinished(ticketId,result)
                return
            }
            service.log("Gallery delete write=${result.name}")
            val context=session?.mediaContext
            if(result==GalleryDatalinkSession.MediaWriteResult.STALE || context==null) {
                deletion=null
                transfer="Deletion could not be verified. Refresh the camera library."
                return
            }
            change.receipt=result
            beginDeleteReadback(change,context)
        }
        private fun beginDeleteReadback(change:Deletion,context:GalleryDatalinkSession.MediaContext) {
            change.readbacks++
            cancelFavourites()
            catalogue.start(context);mediaProgress=emptyList()
            transfer="Refreshing the camera library to verify deletion…"
            advancePages()
        }
        private fun finishDeleteReadback(change:Deletion) {
            val store=change.item.media.key.store
            val refreshed=catalogue.snapshot.stores.singleOrNull { it.store==store }
            val oldCapacity=change.item.context.stores[store]?.totalMiB
            val context=session?.mediaContext
            val sameCapacity=oldCapacity!=null && context?.stores?.get(store)?.totalMiB==oldCapacity
            if(refreshed?.phase==GalleryMediaCatalog.Phase.CHANGED && sameCapacity && change.readbacks==1) {
                // Free-space telemetry can arrive while the first post-delete list is being read.
                beginDeleteReadback(change,context)
                return
            }
            deletion=null
            val observed=if(refreshed?.phase==GalleryMediaCatalog.Phase.COMPLETE && sameCapacity)
                change.target.verify(refreshed.entries.map { it.media })
                else GalleryDeleteTarget.Readback.UNVERIFIED
            transfer=when(observed) {
                GalleryDeleteTarget.Readback.ABSENT -> if(change.receipt==GalleryDatalinkSession.MediaWriteResult.ACKNOWLEDGED)
                    "Deleted from camera." else "The file is absent from the refreshed camera library."
                GalleryDeleteTarget.Readback.STILL_LISTED -> "The camera still lists this file."
                GalleryDeleteTarget.Readback.UNVERIFIED -> "Deletion could not be verified. Refresh the camera library."
            }
            service.log("Gallery delete readback=${observed.name}")
        }
        private fun pagesFinished(ticketId:Long,result:GalleryListBatch.Result) {
            serial();val worker=session ?: return;worker.mediaContext?.let(catalogue::observe)
            service.log("Gallery media page results="+result.stores.entries.joinToString { (store,outcome) ->
                "${store.name}:${outcome.error?.name ?: "VALID"}:records=${outcome.page?.entries?.size ?: 0}:last=${outcome.page?.last ?: false}:chunks=${outcome.stream?.chunks ?: 0}:bytes=${outcome.stream?.bytes ?: 0}:parser=${outcome.parserError?.name ?: "NONE"}" })
            favouritesRequest?.takeIf { it.second.id==ticketId }?.let { pending ->
                favouritesRequest=null
                worker.mediaContext?.let(favouritesCatalogue::observe)
                favouritesCatalogue.apply(pending.first,result)
                advanceFavourites(worker)
                return
            }
            favouriteChange?.takeIf { it.ticketId==ticketId && it.step!=FavouriteStep.WRITE }?.let { change ->
                favouritePageFinished(worker,change,result);return
            }
            deletion?.takeIf { it.validation.id==ticketId }?.let { change ->
                deletePageFinished(worker,change,result);return
            }
            val check=validation
            if(check?.ticket?.id==ticketId) {
                validation=null;val item=check.item;val store=item.media.key.store;val outcome=result.stores[store]
                val current = catalogue.current(item) && worker.currentMediaContext(item.context,store)
                if (current && outcome?.endedWithoutPageData == true) {
                    val cursors = check.remainingFallbacks ?: catalogue.validationFallbacks(item)
                    if (cursors.isNotEmpty()) {
                        if (worker.mediaCountersExhausted) {
                            if (retryQueueVerificationAfterRenewal()) return
                        } else {
                            val ticket = worker.requestMediaPages(mapOf(store to cursors.first()), item.context)
                            if (ticket != null) {
                                validation = check.copy(ticket = ticket, remainingFallbacks = cursors.drop(1))
                                transfer = "Retrying confirmation of the selected camera file…"
                                return
                            }
                        }
                    }
                }
                val confirmed=outcome?.error==null && outcome?.page?.entries?.any { it.sameFile(item.media) }==true
                if(!confirmed || !current) {
                    importing=false
                    if(queueClaim!=null)finishQueueClaim(GalleryDownloadQueue.State.REFRESH,
                        "The camera could not confirm this file. Refresh the library before retrying.")
                    else transfer="File identity could not be confirmed. Refresh the library before importing."
                    return
                }
                download(item,check.folder,check.companion,check.edit);return
            }
            val pending=listRequest ?: return
            if(pending.second.id!=ticketId)return
            listRequest=null
            catalogue.apply(pending.first,result)
            if (!changingMedia && result.stores.values.any { it.error == GalleryListBatch.Error.COUNTER_LIMIT } &&
                renewMedia(cameraKey, false)
            ) return
            advancePages()
        }
        private fun retryQueueVerificationAfterRenewal(): Boolean {
            val claim = queueClaim ?: return false
            val id = claim.job.id
            if (!queueRunning || renewingMedia || id == alternateHandleJob || !queue.release(claim)) return false
            val restartId = explicitRestartId
            queueClaim = null
            queuePauseTicket = null
            queueRunning = false
            explicitRestartId = null
            importing = false
            transferCancel = null
            return renewMedia(cameraKey, resumeQueue = true, restartId = restartId, alternateHandleJob = id)
        }
        private fun download(item:GalleryMediaCatalog.Located,folder:android.net.Uri?,companion:GalleryCompanion?,edit:GalleryVideoEdit?) {
            val cancel=transferCancel ?: return
            val access=http(item,cancel) ?: run {
                importing=false
                if(queueClaim!=null)finishQueueClaim(GalleryDownloadQueue.State.REFRESH) else transfer="Camera connection ended."
                return
            }
            transferHttp=access
            transfer=when {
                edit is GalleryVideoEdit.Frame -> "Reading original video for the frame…"
                companion!=null -> "Looking for the camera’s ${companion.extension} companion…"
                else -> "Importing original…"
            }
            percent=null
            val pool=Executors.newSingleThreadExecutor { task -> Thread(task,"GripBridge-gallery-import").apply { isDaemon=true } }
            transferExecutor=pool
            val worker=session
            val claim=queueClaim
            val restart=claim!=null && claim.job.id==explicitRestartId && claim.job.state==GalleryDownloadQueue.State.RESTART
            explicitRestartId=null
            val cancelled={ cancel.get() || closed.get() || !token.active || worker?.currentMediaContext(item.context,item.media.key.store)!=true || Thread.currentThread().isInterrupted }
            pool.execute {
                transferThread.set(Thread.currentThread())
                var lastPercent=-1
                var prior:GalleryOriginalImporter.PriorAttempt?=null
                var restartRequired=false
                var storageFull=false
                var companionAbsent=false
                var companionError: String? = null
                val progress:(Long,Long)->Unit={ done,total ->
                    val p=(done*100/total).toInt().coerceIn(0,99)
                    if(p!=lastPercent) {
                        lastPercent=p
                        post { if(transferCancel===cancel && !cancel.get()) {
                            percent=p
                            if (claim != null) queuePauseTicket?.let {
                                notificationProgress?.record(claim.job.id, done)
                                val totalPercent = queueNotificationPercent(cameraKey)
                                val at = SystemClock.elapsedRealtime()
                                if (totalPercent != null && totalPercent != lastQueueNotificationPercent &&
                                    at - lastQueueNotificationAt >= 500L
                                ) {
                                    lastQueueNotificationPercent = totalPercent
                                    lastQueueNotificationAt = at
                                    service.showGalleryQueueProgress(totalPercent, pauseTicket = it)
                                }
                            }
                        } }
                    }
                }
                val result=try { access.use {
                    val historyId=if(claim==null)UUID.randomUUID().toString() else {
                        prior=claim.job.attempt?.let { id -> GalleryOriginalImporter.priorAttempt(ImportHistoryRepository.get(service),id,cancelled) }
                        if(prior==GalleryOriginalImporter.PriorAttempt.COMPLETE || prior==GalleryOriginalImporter.PriorAttempt.REVIEW)
                            return@use MediaImportTransaction.Result(emptyList())
                        check(!cancelled())
                        if(restart)partials.discard(claim.job.id)
                        checkNotNull(queue.beginAttempt(claim))
                    }
                    val staged=claim?.let {
                        partials.prepare(it.job,item.media,access,cancelled,progress).also { prepared ->
                            post { if(transferCancel===cancel && !cancel.get()) {
                                transfer="Verifying and saving original…"
                                prepared.rangeStart?.let { start -> service.log("Gallery download range verified start=$start saved_bytes=${prepared.savedBytes}") }
                            } }
                        }
                    }
                    val onPublished:(String)->Unit={ uri -> main.post { if(live(this))savedUri=uri } }
                    if(edit is GalleryVideoEdit.Frame) {
                        GalleryFrameExporter.save(service,item.media,edit.positionMs,cameraName,it,cancelled,onPublished)
                    } else if(companion==null) {
                        GalleryOriginalImporter.run(service,item.media,scope,cameraName,it,cancelled,
                            progress,onPublished,historyId,staged,folder)
                    } else companion.importToFolder(service,item.media,cameraKey,cameraName,it,checkNotNull(folder),
                        cancelled,progress,onPublished) ?: run {
                        companionAbsent=true
                        MediaImportTransaction.Result(emptyList())
                    }
                } } catch(error:Exception) {
                    restartRequired=error is GalleryHttpMedia.RestartRequired
                    storageFull=error is GalleryPartialDownloads.NoSpace
                    if (error is GalleryCompanion.TooLarge || error is FolderImportOutput.NoSpace) {
                        companionError = error.message
                    }
                    MediaImportTransaction.Result(emptyList(),cancelled=cancelled(),failedAt=MediaImportTransaction.Phase.COPY)
                }
                transferThread.compareAndSet(Thread.currentThread(),null)
                main.post {
                    if(claim!=null && queueClaim===claim) {
                        importing=false;transferHttp=null;transferExecutor=null
                        finishQueueClaim(when {
                            result.completed.isNotEmpty() || prior==GalleryOriginalImporter.PriorAttempt.COMPLETE -> GalleryDownloadQueue.State.COMPLETE
                            result.reconciliationRequired || result.cleanupFailed || prior==GalleryOriginalImporter.PriorAttempt.REVIEW -> GalleryDownloadQueue.State.REVIEW
                            restartRequired -> GalleryDownloadQueue.State.RESTART
                            else -> GalleryDownloadQueue.State.RESUME
                        },when {
                            result.filenameConflict -> "The phone album cannot keep the original filename. Use a different destination for this file."
                            storageFull -> "Free phone storage, then resume the download queue."
                            else -> null
                        })
                    } else if(live(this) && transferCancel===cancel) {
                        importing=false;transferHttp=null;transferExecutor=null;percent=if(result.completed.isNotEmpty())100 else null
                        transfer=when {
                            result.completed.isNotEmpty() && edit is GalleryVideoEdit.Frame -> "Full-resolution JPEG saved in Imported Images."
                            edit!=null && result.failedAt==MediaImportTransaction.Phase.COPY && !result.cancelled ->
                                "Couldn’t extract a full-resolution frame. Check the camera connection and try another position."
                            companionAbsent -> "The camera does not have a ${companion?.extension} companion for this file."
                            result.completed.isNotEmpty() && companion!=null -> "Verified ${companion.extension} companion saved to the selected folder."
                            result.completed.isNotEmpty() -> (if(folder==null) "Original imported and verified in Gallery → GripBridge." else "Verified original is available in the selected folder and Imported Images.")+(if(result.followUpFailed) " Saved-file details need a refresh." else "")
                            result.reconciliationRequired -> "Import outcome needs checking in Imported Images before retrying."
                            result.cleanupFailed -> "Import stopped. Pending-file cleanup could not be confirmed."
                            result.cancelled -> "Import canceled. Completed files remain on the phone."
                            result.filenameConflict -> "The destination cannot keep the original filename. Choose another folder."
                            companionError != null -> checkNotNull(companionError)
                            else -> "Import failed during ${result.failedAt?.name?.lowercase() ?: "verification"}. Refresh the library to retry."
                        }
                    }
                };pool.shutdown()
            }
        }
        private var finishCallback: (() -> Unit)?=null
        private var exitBarrier: GalleryExitBarrier?=null
        private var exitRelease: GalleryBleRelease?=null
        private val finishDeadline=Runnable { exitBarrier?.workerClosed() }
        override fun connectBle() {
            serial();if(!live(this)) return
            if(token.lease.galleryPreflightPending) {
                try { service.checkGalleryCamera(token.lease,::preflightFinished) }
                catch(error: RuntimeException) {
                    events.failed(if(error is SecurityException) GalleryConnectFlow.Category.PERMISSION else GalleryConnectFlow.Category.BLUETOOTH,
                        GalleryConnectFlow.Stage.CHECKING_CAMERA)
                }
            } else awaitStatusLinkRelease()
        }
        private fun preflightFinished(outcome:GalleryPreflight.Outcome) {
            if(!live(this))return
            val failure=when(outcome) {
                GalleryPreflight.Outcome.READY -> null
                GalleryPreflight.Outcome.ASLEEP -> GalleryConnectFlow.Category.CAMERA_ASLEEP
                GalleryPreflight.Outcome.BUSY -> GalleryConnectFlow.Category.CAMERA_BUSY
                GalleryPreflight.Outcome.UNSUPPORTED -> GalleryConnectFlow.Category.UNSUPPORTED_MODEL
                GalleryPreflight.Outcome.TIMEOUT -> GalleryConnectFlow.Category.TIMEOUT
                GalleryPreflight.Outcome.LINK_LOST -> GalleryConnectFlow.Category.BLUETOOTH
                GalleryPreflight.Outcome.WAITING -> return
            }
            if(failure!=null) {
                events.failed(failure,GalleryConnectFlow.Stage.CHECKING_CAMERA)
                return
            }
            try {
                release.watch()
                service.releaseGalleryPreflightLink(token.lease)
            } catch(error:RuntimeException) {
                events.failed(if(error is SecurityException) GalleryConnectFlow.Category.PERMISSION else GalleryConnectFlow.Category.PRECONDITION,
                    GalleryConnectFlow.Stage.CHECKING_CAMERA)
                return
            }
            events.progress(GalleryConnectFlow.Stage.BLE_CONNECTION)
            if(live(this))awaitStatusLinkRelease()
        }
        private fun awaitStatusLinkRelease() {
            release.await(
                { if(live(this)) ble.connect(device) },
                { if(live(this)) events.failed(GalleryConnectFlow.Category.BLUETOOTH,GalleryConnectFlow.Stage.BLE_CONNECTION,6000,1) })
        }
        override fun joinWifi(credentials: CameraWifiCredentials) { serial();if(live(this)) { wifiStarted=true;wifi.join(credentials) } }
        private fun post(action: ()->Unit) { main.post { if(live(this)) action() } }
        override fun startSession(network: GalleryConnectFlow.Network,identity: GalleryIdentity) {
            serial();check(live(this) && session==null)
            val bound=network as? BoundCameraNetwork ?: throw IllegalArgumentException("Wrong gallery network adapter")
            this.network=bound
            // Keep the gallery BLE session/keepalive while the local network is
            // registered, as the reference does. A camera-initiated BLE drop is
            // still handled independently by Wi-Fi/UDP evidence.
            val worker=GalleryDatalinkSession(BoundGalleryWire(bound),identity,SystemClock::elapsedRealtime,
                { token.active && !closed.get() },object: GalleryDatalinkSession.Listener {
                    override fun stageChanged(stage: GalleryDatalinkSession.Stage,attempt: Int) { workerStage(stage)?.let { post { events.progress(it,attempt) } } }
                    override fun ready(storages: List<GalleryCameraEvidence.Storage>) { post {
                        session?.mediaContext?.let(catalogue::observe)
                        events.ready(storages)
                        val recovery = mediaRecovery
                        if (recovery != null && state.stage == GalleryConnectFlow.Stage.READY && !catalogue.snapshot.started) {
                            if (recovery.camera != cameraKey || !loadMedia(afterRenewal = true)) {
                                mediaRecovery = null
                                if (recovery.resumeQueue) service.showGalleryQueueProgress(null)
                            }
                        }
                    } }
                    override fun frameReceived(frame:DumlFrame) {
                        if(frame.sender==1 && frame.receiver==2 && frame.commandSet==0 && frame.commandId in setOf(0x26,0x27))
                            capture?.record(1,frame.commandId,frame.sequence,frame.flags,frame.payload,(SystemClock.elapsedRealtime()-captureAt).coerceAtLeast(0))
                    }
                    override fun mediaCommandPayload(ticketId:Long,sequence:Int,payload:ByteArray) {
                        capture?.record(0,0x26,sequence,0x40,payload,(SystemClock.elapsedRealtime()-captureAt).coerceAtLeast(0))
                    }
                    override fun mediaCommand(ticketId:Long,role:GalleryListBatch.Role,counter:Int,sequence:Int,elapsedMs:Long) {
                        post { service.log("Gallery media command role=${role.name} counter=$counter sequence=$sequence elapsed_ms=$elapsedMs") }
                    }
                    override fun mediaProgress(ticketId:Long,stores:List<GalleryListBatch.Progress>) { post {
                        if(listRequest?.second?.id==ticketId || validation?.ticket?.id==ticketId)mediaProgress=stores
                    } }
                    override fun mediaPagesFinished(ticketId:Long,result:GalleryListBatch.Result) { post { pagesFinished(ticketId,result) } }
                    override fun mediaWriteFinished(ticketId:Long,result:GalleryDatalinkSession.MediaWriteResult) {
                        post { this@NativeRuntime.mediaWriteFinished(ticketId,result) }
                    }
                    override fun closed(playbackExit: GalleryDatalinkSession.PlaybackExit) {
                        main.post {
                            if(activeRuntime===this@NativeRuntime && !closed.get()) {
                                service.log("Gallery playback_exit=${playbackExit.name}; bridge still requires fresh authorized status")
                                main.removeCallbacks(finishDeadline)
                                exitBarrier?.workerClosed()
                            }
                        }
                    }
                    override fun failed(diagnostic: GalleryDatalinkSession.Diagnostic) {
                        post {
                            diagnostic.transport?.let { service.log("Gallery UDP transport $it") }
                            diagnostic.socketStep?.let { service.log("Gallery socket step=${it.name} reason=${diagnostic.socketReason?.name}") }
                            events.failed(workerCategory(diagnostic),workerStage(diagnostic.stage) ?: GalleryConnectFlow.Stage.READY,
                            diagnostic.elapsedMs,diagnostic.attempt,diagnostic.responseCode)
                        }
                    }
                })
            session=worker
            executor=Executors.newSingleThreadExecutor { task -> Thread(task,"GripBridge-gallery").apply { isDaemon=true } }
            executor!!.execute { worker.run() }
        }
        override fun prepareExit() {
            serial()
            // Signal the worker before its normal ownership token is invalidated.
            // Otherwise it can observe token loss and retire before finish() gives
            // it explicit responsibility for the bounded playback-exit command.
            closeClip()
            cancelMedia();session?.requestExit()
        }
        override fun finish(done: ()->Unit) {
            serial()
            if(closed.get()) { done();return }
            finishCallback=done
            val barrier=GalleryExitBarrier {
                val callback=finishCallback;finishCallback=null
                if(!closed.get() && activeRuntime===this) callback?.invoke()
            }
            exitBarrier=barrier
            // Register before close, while normal work is already invalidated.
            // UDP playback cleanup and physical BLE release can finish in either
            // order; neither alone starts the R-SDK controller.
            if(ble.active) {
                val watcher=GalleryBleRelease(service,device) { service.log(it.replace("handoff=","exit_handoff=")) }
                exitRelease=watcher
                try {
                    watcher.watch();ble.close()
                    watcher.await({ barrier.bleClosed() },{ barrier.bleClosed() })
                } catch(_: RuntimeException) {
                    watcher.close();ble.close();barrier.bleClosed()
                }
            } else barrier.bleClosed()
            val worker=session
            if(worker!=null && worker.requestExit()) main.postDelayed(finishDeadline,600)
            else barrier.workerClosed()
        }
        override fun close() {
            serial();if(!closed.compareAndSet(false,true)) return
            main.removeCallbacks(finishDeadline);finishCallback=null;exitBarrier?.cancel();exitBarrier=null
            closeClip()
            cancelMedia();catalogue.close();transferExecutor?.shutdown();transferExecutor=null;transferHttp=null
            release.close();exitRelease?.close();exitRelease=null
            // Token was invalidated first. Closing IO unblocks the worker before
            // Android's network request is released or bridge restore is attempted.
            try { session?.close() } catch(_: RuntimeException) {}
            executor?.shutdownNow();executor=null;session=null
            try { network?.close() } catch(_: RuntimeException) {};network=null
            if(activeRuntime===this) {
                activeRuntime=null
                try { ble.close() } catch(_: RuntimeException) {}
                try { wifi.close() } catch(_: RuntimeException) {}
            }
        }
    }
    fun connect(): Boolean { serial();return flow.start() }
    fun cancel() { serial();mediaRecovery=null;flow.cancel() }
    fun retry() { serial();flow.retry() }
    fun reconnectFromBeginning() { serial();flow.reconnectFromBeginning() }
    fun restored() { serial();flow.restored() }
    fun restorationFailed(elapsedMs: Long=30000,category: GalleryConnectFlow.Category=GalleryConnectFlow.Category.TIMEOUT) { serial();flow.restorationFailed(elapsedMs,category) }
    fun stop() { serial();mediaRecovery=null;flow.stop();selectedRelease?.close();selectedRelease=null;selected=null;selectedLease=null }
    companion object {
        // Process-wide: overlapping runtimes may finish a capture together.
        private val captureFileLock=Any()
        private fun phaseStage(phase: GalleryBleHandshake.Phase): GalleryConnectFlow.Stage? = when(phase) {
            GalleryBleHandshake.Phase.NEW,GalleryBleHandshake.Phase.SESSION_OPEN,GalleryBleHandshake.Phase.PAIRING -> GalleryConnectFlow.Stage.BLE_CONNECTION
            GalleryBleHandshake.Phase.CAMERA_APPROVAL -> GalleryConnectFlow.Stage.CAMERA_APPROVAL
            GalleryBleHandshake.Phase.WIFI_START,GalleryBleHandshake.Phase.NETWORK_NAME,GalleryBleHandshake.Phase.PASSPHRASE -> GalleryConnectFlow.Stage.CREDENTIALS
            else -> null
        }
        private fun workerStage(stage: GalleryDatalinkSession.Stage): GalleryConnectFlow.Stage?=when(stage) {
            GalleryDatalinkSession.Stage.REACHABILITY -> GalleryConnectFlow.Stage.REACHABILITY
            GalleryDatalinkSession.Stage.REGISTRATION -> GalleryConnectFlow.Stage.REGISTRATION
            GalleryDatalinkSession.Stage.PLAYBACK -> GalleryConnectFlow.Stage.PLAYBACK
            GalleryDatalinkSession.Stage.STORAGE -> GalleryConnectFlow.Stage.STORAGE
            GalleryDatalinkSession.Stage.READY -> null
        }
        private fun workerCategory(diagnostic: GalleryDatalinkSession.Diagnostic): GalleryConnectFlow.Category=when(diagnostic.error) {
            GalleryDatalinkSession.Error.TIMEOUT -> GalleryConnectFlow.Category.TIMEOUT
            GalleryDatalinkSession.Error.TRANSPORT -> when(diagnostic.socketReason) {
                GalleryDatalinkSession.SocketReason.PERMISSION -> GalleryConnectFlow.Category.PERMISSION
                GalleryDatalinkSession.SocketReason.TIMED_OUT -> GalleryConnectFlow.Category.TIMEOUT
                GalleryDatalinkSession.SocketReason.UNREACHABLE,GalleryDatalinkSession.SocketReason.REFUSED -> GalleryConnectFlow.Category.ROUTE
                else -> GalleryConnectFlow.Category.NETWORK_LOST
            }
            GalleryDatalinkSession.Error.REJECTED -> GalleryConnectFlow.Category.REJECTED
            GalleryDatalinkSession.Error.STORAGE_UNAVAILABLE -> GalleryConnectFlow.Category.STORAGE_UNAVAILABLE
            GalleryDatalinkSession.Error.CAMERA_STATE_LOST -> GalleryConnectFlow.Category.STATE_LOST
            else -> GalleryConnectFlow.Category.INTERNAL
        }
    }
}
