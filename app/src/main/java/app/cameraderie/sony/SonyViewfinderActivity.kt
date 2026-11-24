package app.cameraderie.sony

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.cameraderie.ui.CameraderieActivity
import app.cameraderie.ui.UiKit
import app.gripbridge.core.Recording
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

class SonyViewfinderActivity : CameraderieActivity() {
    private val manager by lazy { getSystemService(UsbManager::class.java) }
    private val worker = Executors.newSingleThreadExecutor()
    private val frameSlot = Semaphore(1)
    private val permissionAction get() = "$packageName.SONY_VIEWFINDER_USB_PERMISSION"
    @Volatile private var generation = 0
    @Volatile private var galleryRequest: Int? = null
    private var pendingDevice: Int? = null
    private var activeDevice: Int? = null
    private lateinit var status: TextView
    private lateinit var connect: Button
    private lateinit var disconnect: Button
    private lateinit var preview: ImageView
    private lateinit var cameraState: TextView
    private lateinit var gallery: Button

    private val usbEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            } ?: return
            when (intent.action) {
                permissionAction -> {
                    if (pendingDevice != device.deviceId) return
                    pendingDevice = null
                    if (manager.hasPermission(device)) openPreview(device)
                    else reset("USB access was declined. Tap Connect over USB to retry.")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device.deviceId == activeDevice || device.deviceId == pendingDevice) reset("USB camera disconnected.")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val ui = UiKit(this)
        val (scroll, body) = ui.page()
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        ui.add(body, ui.text("Sony viewfinder", UiKit.Type.title, bold = true).apply { isAccessibilityHeading = true }, 16)
        status = ui.text("Set USB Connection to PC Remote and attach the camera to this phone.").apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ui.add(body, status, 8)
        preview = ImageView(this).apply {
            contentDescription = "Camera live view"
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        body.addView(preview, LinearLayout.LayoutParams(-1, ui.dp(278)).apply { topMargin = ui.dp(12) })
        connect = ui.action("Connect over USB", primary = true, onClick = ::chooseCamera)
        ui.add(body, connect, 12)
        disconnect = ui.action("Disconnect") { reset("USB camera disconnected.") }.apply { visibility = View.GONE }
        ui.add(body, disconnect, 12)
        cameraState = ui.text("", UiKit.Type.small, secondary = true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        ui.add(body, cameraState, 8)
        gallery = ui.action("Open camera gallery") {
            gallery.isEnabled = false
            cameraState.text = "Checking camera before opening the gallery…"
            galleryRequest = generation
        }.apply {
            isEnabled = false
            visibility = View.GONE
        }
        ui.add(body, gallery, 8)
        val filter = IntentFilter(permissionAction).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbEvents, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(usbEvents, filter)
        setContentView(scroll)
    }

    private fun chooseCamera() {
        val devices = SonyUsbViewfinder.candidates(manager)
        when (devices.size) {
            0 -> status.text = "No Sony USB camera found. Set PC Remote on the camera and use a USB data cable."
            1 -> requestAccess(devices.single())
            else -> MaterialAlertDialogBuilder(this).setTitle("Choose a Sony camera")
                .setItems(devices.mapIndexed { index, device ->
                    SonyCameraIdentity.label(device.productName) ?: "Sony camera ${index + 1}"
                }.toTypedArray()) { _, index -> requestAccess(devices[index]) }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun requestAccess(device: UsbDevice) {
        if (manager.hasPermission(device)) {
            openPreview(device)
            return
        }
        pendingDevice = device.deviceId
        connect.isEnabled = false
        status.text = "Waiting for Android USB permission…"
        val reply = PendingIntent.getBroadcast(this, device.deviceId, Intent(permissionAction).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        runCatching { manager.requestPermission(device, reply) }
            .onFailure { reset("Could not request Android USB access. Reconnect the cable and retry.") }
    }

    private fun openPreview(device: UsbDevice) {
        val token = ++generation
        activeDevice = device.deviceId
        connect.isEnabled = false
        disconnect.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "Opening the camera’s PC Remote session…"
        worker.execute {
            try {
                if (token != generation) return@execute
                var openGallery = false
                SonyUsbViewfinder.open(manager, device).use { session ->
                    val label = listOfNotNull(session.model, session.firmware?.let { "Firmware $it" }, "USB").joinToString(" · ")
                    runOnUiThread {
                        if (token == generation) status.text = "$label\nWaiting for live view…"
                    }
                    var lastFrame = SystemClock.elapsedRealtime()
                    var streaming = false
                    var nextStateAt = 0L
                    var displayed: SonyUsbStatus? = null
                    while (token == generation) {
                        val handoff = galleryRequest == token
                        if (handoff || SystemClock.elapsedRealtime() >= nextStateAt) {
                            val camera = session.status(fresh = handoff)
                            nextStateAt = SystemClock.elapsedRealtime() + 750
                            if (handoff && camera.recording == Recording.IDLE) {
                                openGallery = true
                                break
                            }
                            if (handoff || displayed != camera) {
                                if (displayed == null) android.util.Log.i(
                                    "CameraderieViewfinder", "Sony USB connected, camera status ${camera.recording}",
                                )
                                displayed = camera
                                runOnUiThread {
                                    if (token == generation) {
                                        if (handoff) galleryRequest = null
                                        showCameraState(camera)
                                    }
                                }
                            }
                        }
                        if (!frameSlot.tryAcquire()) {
                            SystemClock.sleep(20)
                            continue
                        }
                        var queued = false
                        try {
                            val frame = session.frame()
                            if (frame == null) {
                                if (SystemClock.elapsedRealtime() - lastFrame > 8000) throw IOException("The camera did not send live view. Check PC Remote and shooting mode.")
                                SystemClock.sleep(80)
                                continue
                            }
                            lastFrame = SystemClock.elapsedRealtime()
                            val first = !streaming
                            streaming = true
                            queued = true
                            runOnUiThread {
                                try {
                                    if (token != generation) {
                                        frame.recycle()
                                        return@runOnUiThread
                                    }
                                    preview.setImageBitmap(frame)
                                    if (first) {
                                        android.util.Log.i("CameraderieViewfinder", "Sony USB live view decoded at ${frame.width} × ${frame.height}")
                                        preview.visibility = View.VISIBLE
                                        status.text = "$label\nLive view"
                                    }
                                } finally {
                                    frameSlot.release()
                                }
                            }
                            SystemClock.sleep(55)
                        } finally {
                            if (!queued) frameSlot.release()
                        }
                    }
                }
                if (openGallery) runOnUiThread {
                    if (token == generation) {
                        reset("USB viewfinder closed. Select a connection to browse camera files.")
                        startActivity(SonyGalleryActivity.connectionIntent(this, useUsb = false))
                    }
                }
            } catch (error: Exception) {
                val message = (error as? IOException)?.message ?: "The camera connection ended. Check the USB cable and retry."
                runOnUiThread { if (token == generation) reset(message) }
            }
        }
    }

    private fun showCameraState(camera: SonyUsbStatus) {
        val recording = when (camera.recording) {
            Recording.IDLE -> "Camera idle"
            Recording.RECORDING -> "Finish recording on the camera before opening the gallery."
            Recording.UNKNOWN -> "Waiting for camera recording status."
        }
        cameraState.text = listOfNotNull(recording, camera.iso?.let { "ISO $it" }).joinToString(" · ")
        cameraState.visibility = View.VISIBLE
        gallery.visibility = View.VISIBLE
        gallery.isEnabled = galleryRequest == null && camera.recording == Recording.IDLE
    }

    private fun reset(message: String) {
        generation++
        galleryRequest = null
        cameraState.visibility = View.GONE
        gallery.visibility = View.GONE
        gallery.isEnabled = false
        activeDevice = null
        pendingDevice = null
        preview.setImageDrawable(null)
        preview.visibility = View.GONE
        connect.isEnabled = true
        disconnect.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = message
    }

    override fun onStop() {
        super.onStop()
        if (activeDevice != null) reset("USB session closed. Tap Connect over USB to return to live view.")
    }

    override fun onDestroy() {
        generation++
        unregisterReceiver(usbEvents)
        worker.shutdown()
        super.onDestroy()
    }
}
