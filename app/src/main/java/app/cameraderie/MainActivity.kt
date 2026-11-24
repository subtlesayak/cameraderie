package app.cameraderie

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import app.cameraderie.camera.CameraAdapters
import app.cameraderie.camera.CaptureCommand
import app.cameraderie.core.DjiSession
import app.cameraderie.core.Recording
import app.cameraderie.gallery.GalleryConnectFlow
import app.cameraderie.gallery.GalleryConnectionUi
import app.cameraderie.gallery.media.DocumentImportActivity
import app.cameraderie.gallery.media.GalleryMediaActivity
import app.cameraderie.gallery.media.history.ImportedImagesActivity
import app.cameraderie.gallery.net.CameraWifiAccess
import app.cameraderie.sony.SonyGalleryActivity
import app.cameraderie.sony.SonyImportPreferences
import app.cameraderie.ui.CameraderieActivity
import app.cameraderie.ui.UiKit
import app.cameraderie.ui.openAppPermissionSettings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : CameraderieActivity() {
    private lateinit var ui: UiKit
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var chooseDji: Button
    private lateinit var connectDji: Button
    private lateinit var browseDji: Button
    private lateinit var closeDji: Button
    private lateinit var openSony: Button
    private lateinit var shoot: Button
    private lateinit var shootingPanel: View
    private lateinit var shootingStatus: TextView
    private lateinit var shootingDetails: TextView
    private lateinit var shootingFeedback: TextView
    private val cameraButtons = mutableMapOf<CaptureCommand, Button>()
    private var chooser: androidx.appcompat.app.AlertDialog? = null
    private var scanRows = emptyList<String>()
    private var service: GalleryService? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 400)
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as GalleryService.LocalBinder).service
            render()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            render()
        }
    }
    private val nearby = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (bluetoothAllowed()) toast("Bluetooth access allowed. Tap the camera action again.")
        else MaterialAlertDialogBuilder(this).setTitle("Bluetooth access is off")
            .setMessage("Allow Nearby devices in app settings to scan for and connect to the DJI camera.")
            .setNegativeButton("Close", null)
            .setPositiveButton("App settings") { _, _ -> openAppPermissionSettings(this) }.show()
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { beginDji() }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ui = UiKit(this)
        val (scroll, body) = ui.page()
        scroll.setBackgroundColor(ui.tone.background)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        ui.add(body, ui.text("Cameraderie", UiKit.Type.display, bold = true).apply { isAccessibilityHeading = true }, 20)
        val library = ui.card(body, "Library", prominent = true)
        ui.add(library, ui.action("Open library", primary = true) { open(ImportedImagesActivity::class.java) })
        ui.add(library, ui.action("Import from files or a memory card") { open(DocumentImportActivity::class.java) }, 8)
        ui.add(library, ui.action("Relink OpenGrip library") { open(LibraryTransferActivity::class.java) }, 8)

        val dji = ui.card(body, CameraAdapters.action6.name)
        status = ui.text("Checking camera access…", UiKit.Type.body, bold = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        detail = ui.text("", UiKit.Type.small, secondary = true)
        ui.add(dji, status)
        ui.add(dji, detail, 4)
        chooseDji = ui.action("Find DJI camera") { scanDji() }
        connectDji = ui.action("Connect to gallery", primary = true) { requestGallery() }
        browseDji = ui.action("View camera files", primary = true) { open(GalleryMediaActivity::class.java) }
        closeDji = ui.action("Disconnect") { runCamera {
            when {
                it.pairing -> it.cancelPairing()
                it.shooting -> it.closeShooting()
                else -> it.closeCameraGallery()
            }
        } }
        shoot = ui.action("Open shooting controls") {
            if (requireBluetooth()) runCamera { it.connectShooting() }
        }
        listOf(chooseDji, shoot, connectDji, browseDji, closeDji).forEach { ui.add(dji, it, 8) }
        ui.add(dji, ui.action("Download queue") {
            startActivity(Intent(this, GalleryMediaActivity::class.java).putExtra(GalleryMediaActivity.EXTRA_SHOW_QUEUE, true))
        }, 8)

        val controls = ui.card(body, "Shooting controls")
        shootingPanel = controls
        shootingStatus = ui.text("", bold = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        shootingDetails = ui.text("", secondary = true)
        shootingFeedback = ui.text("", UiKit.Type.small, secondary = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        ui.add(controls, shootingStatus)
        ui.add(controls, shootingDetails, 8)
        ui.add(controls, ui.text("Unavailable on this connection · ${CameraAdapters.action6.unverifiedControls.joinToString()}", secondary = true), 8)
        listOf(CaptureCommand.PHOTO to "Take photo", CaptureCommand.RECORD_START to "Start recording",
            CaptureCommand.RECORD_STOP to "Stop recording", CaptureCommand.PHOTO_MODE to "Photo mode",
            CaptureCommand.VIDEO_MODE to "Video mode", CaptureCommand.READ_FIRMWARE to "Check firmware").forEach { (command, label) ->
            val button = ui.action(label) { runCamera { if (it.shooting) it.capture.send(command) } }
            cameraButtons[command] = button
            ui.add(controls, button, 8)
        }
        ui.add(controls, shootingFeedback, 12)
        val sony = ui.card(body, CameraAdapters.sonyA6400.name)
        openSony = ui.action("Connect Sony · Wi-Fi or USB") {
            startActivity(SonyGalleryActivity.connectionIntent(this, SonyGalleryActivity.attachedUsbCamera(this) != null))
        }
        ui.add(sony, openSony)
        ui.add(sony, ui.text("Camera files via ${CameraAdapters.sonyA6400.transports.joinToString(" or ")}", secondary = true), 8)
        ui.add(sony, ui.action("Default photo import quality") { SonyImportPreferences(this).show(this) }, 8)
        val privacy = ui.card(body, "Privacy")
        val prefs = SonyImportPreferences(this)
        val screenshots = ui.toggle("Allow gallery screenshots") { allowed ->
            runCatching { prefs.allowGalleryScreenshots = allowed }.onFailure { toast("Couldn’t save the screenshot preference.") }
        }
        screenshots.isChecked = prefs.allowGalleryScreenshots
        ui.add(privacy, screenshots)
        ui.add(privacy, ui.action("Dependencies and licenses") { open(app.cameraderie.ui.NoticesActivity::class.java) }, 8)
        setContentView(scroll)
        bound = bindService(Intent(this, GalleryService::class.java), connection, Context.BIND_AUTO_CREATE)
        render()
    }

    private fun open(activity: Class<*>) = startActivity(Intent(this, activity))
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun bluetoothAllowed() = GalleryService.cameraPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun requireBluetooth(): Boolean {
        if (!bluetoothAllowed()) {
            nearby.launch(GalleryService.cameraPermissions.toTypedArray())
            return false
        }
        if (service?.bluetoothEnabled != true) {
            MaterialAlertDialogBuilder(this).setTitle("Turn on Bluetooth")
                .setMessage("Bluetooth is needed to find and authorize the DJI camera.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Bluetooth settings") { _, _ -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }.show()
            return false
        }
        return true
    }

    private fun scanDji() {
        if (!requireBluetooth()) return
        runCamera { it.scan() }
        scanRows = emptyList()
        chooser = MaterialAlertDialogBuilder(this).setTitle("Choose your DJI camera")
            .setItems(arrayOf("Searching for nearby cameras…"), null)
            .setNegativeButton("Cancel", null)
            .create().apply {
                setOnDismissListener {
                    chooser = null
                    if (service?.pairing != true) runCatching { service?.dji?.stopScan() }
                }
                show()
            }
    }

    private fun updateChooser(current: GalleryService) {
        val dialog = chooser ?: return
        val cameras = current.dji.cameras
        val labels = cameras.map { "${it.name} · ${it.rssi} dBm" }
        val rows = labels.ifEmpty { listOf(if (current.dji.scanning) "Searching for nearby cameras…" else "No cameras found. Turn on the Action 6 and try again.") }
        if (rows == scanRows) return
        scanRows = rows
        dialog.listView.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        dialog.listView.setOnItemClickListener { _, _, index, _ ->
            if (index in cameras.indices) {
                runCamera { it.pair(index) }
                dialog.dismiss()
            }
        }
    }

    private fun requestGallery() {
        if (!requireBluetooth()) return
        if (service?.canOpenGallery() != true) {
            toast("Finish recording and wait for the camera to report idle before opening playback.")
            return
        }
        val missing = CameraWifiAccess.missing(this)
        if (missing.isNotEmpty()) {
            CameraWifiAccess.request(this, missing, WIFI_PERMISSION)
            return
        }
        if (!getSystemService(android.net.wifi.WifiManager::class.java).isWifiEnabled) {
            MaterialAlertDialogBuilder(this).setTitle("Turn on Wi-Fi")
                .setMessage("Cameraderie joins the camera’s local Wi-Fi to browse and transfer files.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Wi-Fi panel") { _, _ -> startActivity(Intent(Settings.Panel.ACTION_WIFI)) }.show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("askedForNotifications", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedForNotifications", true).apply()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else beginDji()
    }

    private fun beginDji() {
        if (service?.dji?.hasAuthorizedBookmark != true || !bluetoothAllowed() || CameraWifiAccess.missing(this).isNotEmpty()) return
        try { startForegroundService(Intent(this, GalleryService::class.java).setAction(GalleryService.OPEN)) }
        catch (_: RuntimeException) { toast("Couldn’t start the camera session. Keep Cameraderie open and try again.") }
    }

    private fun runCamera(action: (GalleryService) -> Unit) {
        val current = service ?: return
        try { action(current) }
        catch (_: RuntimeException) { toast("Couldn’t perform the camera action. Check Bluetooth and try again.") }
        render()
    }

    private fun render() {
        val current = service
        if (current == null) {
            listOf(chooseDji, connectDji, browseDji, closeDji, shoot, shootingPanel).forEach { it.visibility = View.GONE }
            return
        }
        val state = current.galleryState
        val model = GalleryConnectionUi.render(state, false, current.dji.hasAuthorizedBookmark)
        val ready = state.stage == GalleryConnectFlow.Stage.READY
        val pairing = current.pairing
        val saved = current.dji.savedCamera?.name
        val title = when {
            pairing -> "Authorizing camera"
            current.sessionActive || state.failure != null -> model.title
            saved != null -> "$saved saved"
            else -> "Choose a camera"
        }
        val message = when {
            pairing && current.dji.session.auth in setOf(DjiSession.Auth.WAITING_CAMERA, DjiSession.Auth.REPLYING) ->
                "Confirm code %04d on the camera screen.".format(current.dji.verificationCode)
            pairing -> current.dji.link
            current.sessionActive || state.failure != null -> model.detail
            else -> current.pairingMessage ?: if (saved == null) "Power on the camera and find it over Bluetooth." else "Power on the camera before connecting."
        }
        if (status.text != title) status.text = title
        if (detail.text != message) detail.text = message
        val shooting = current.shooting
        chooseDji.visibility = shown(!current.sessionActive && !pairing && !shooting)
        shoot.visibility = shown(!current.sessionActive && !pairing && !shooting && current.dji.hasAuthorizedBookmark)
        shootingPanel.visibility = shown(shooting)
        if (shooting) {
            val capture = current.capture.snapshot()
            if (shootingStatus.text != capture.connection) shootingStatus.text = capture.connection
            if (shootingDetails.text != capture.details) shootingDetails.text = capture.details
            if (shootingFeedback.text != capture.feedback) shootingFeedback.text = capture.feedback
            cameraButtons.forEach { (command, button) -> button.isEnabled = command in capture.permitted }
            cameraButtons[CaptureCommand.RECORD_START]?.visibility = shown(capture.recording != Recording.RECORDING)
            cameraButtons[CaptureCommand.RECORD_STOP]?.visibility = shown(capture.recording == Recording.RECORDING)
        }
        chooseDji.text = if (saved == null) "Find DJI camera" else "Choose another DJI camera"
        connectDji.visibility = shown(!current.sessionActive && !pairing && current.dji.hasAuthorizedBookmark)
        connectDji.text = if (state.failure == null) "Connect to gallery" else "Retry gallery connection"
        browseDji.visibility = shown(ready)
        closeDji.visibility = shown(current.sessionActive || pairing || shooting)
        closeDji.isEnabled = state.stage != GalleryConnectFlow.Stage.RESTORING
        closeDji.text = if (pairing) "Cancel authorization" else "Disconnect"
        openSony.isEnabled = !current.sessionActive && !pairing && !shooting
        updateChooser(current)
    }

    private fun shown(visible: Boolean) = if (visible) View.VISIBLE else View.GONE

    override fun onStart() {
        super.onStart()
        handler.post(refresh)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        super.onStop()
    }

    override fun onDestroy() {
        chooser?.dismiss()
        if (bound) unbindService(connection)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != WIFI_PERMISSION) return
        val missing = CameraWifiAccess.missing(this)
        if (missing.isEmpty()) toast("Wi-Fi access allowed. Tap Connect to gallery again.")
        else CameraWifiAccess.showDenied(this, missing)
    }

    companion object { private const val WIFI_PERMISSION = 101 }
}
