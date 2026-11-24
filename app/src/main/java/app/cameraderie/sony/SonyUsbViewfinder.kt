package app.cameraderie.sony

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import app.gripbridge.core.Recording
import app.cameraderie.camera.PtpDataset
import app.cameraderie.camera.PtpDeviceInfo
import app.cameraderie.camera.UsbPtpTransport
import java.io.Closeable
import java.io.IOException

/** Sony PC Remote preview over the camera's PTP USB interface. */
internal class SonyUsbViewfinder private constructor(
    val model: String,
    val firmware: String?,
    private val connection: UsbDeviceConnection,
    private val link: Interface,
    private val ptp: UsbPtpTransport,
    private val readsStatus: Boolean,
    private val sendsControls: Boolean,
    private var initialStatus: SonyUsbStatus?,
) : Closeable {
    fun status(fresh: Boolean = false): SonyUsbStatus {
        val initial = initialStatus
        initialStatus = null
        if (!readsStatus) return SonyUsbStatus.unknown
        val actual = if (!fresh && initial != null) initial
            else parseStatus(ptp.command(GET_CAMERA_PROPERTIES, maxData = MAX_PROPERTIES))
        return if (sendsControls) actual else actual.copy(availableActions = emptySet())
    }

    fun command(action: SonyUsbAction, active: () -> Boolean): Pair<SonyUsbStatus, String> {
        val current = status(fresh = true)
        if (!active() || action !in current.availableActions) {
            return current to "The camera’s current mode does not allow this action."
        }
        val result = when (action) {
            SonyUsbAction.FOCUS -> {
                val focus = hold(SonyUsbStatus.FOCUS_BUTTON) {
                    readUntil(active, 1200) { it.focus in setOf(2, 3, 6, 7) }
                }
                if (focus.focus in setOf(2, 6)) "Camera confirmed focus."
                else "The camera did not confirm focus."
            }
            SonyUsbAction.PHOTO -> {
                hold(SonyUsbStatus.FOCUS_BUTTON) {
                    if (active()) hold(SonyUsbStatus.PHOTO_BUTTON) { SystemClock.sleep(300) }
                }
                val photo = readUntil(active, 2000) { (it.mediaInMemory ?: 0) > 0 }
                if ((photo.mediaInMemory ?: 0) > 0) "Camera reported a new photo. Check it on the card."
                else "Shutter accepted. Check the camera for the saved photo."
            }
            SonyUsbAction.START_RECORDING, SonyUsbAction.STOP_RECORDING -> {
                val starts = action == SonyUsbAction.START_RECORDING
                button(SonyUsbStatus.MOVIE_BUTTON, if (starts) 2 else 1)
                val desired = if (starts) Recording.RECORDING else Recording.IDLE
                val movie = readUntil(active, 2400) { it.recording == desired }
                when {
                    movie.recording != desired -> "Camera did not confirm the recording change."
                    starts -> "Camera confirmed recording."
                    else -> "Camera confirmed recording stopped."
                }
            }
        }
        return status(fresh = true) to result
    }

    private fun button(code: Int, value: Byte) {
        ptp.command(SET_CONTROL, code, outgoing = byteArrayOf(value, 0)).requireOk(SET_CONTROL)
    }

    private fun <T> hold(code: Int, action: () -> T): T {
        button(code, 2)
        try {
            return action()
        } finally {
            button(code, 1)
        }
    }

    private fun readUntil(active: () -> Boolean, timeoutMs: Long, stop: (SonyUsbStatus) -> Boolean): SonyUsbStatus {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var observed = status(fresh = true)
        while (active() && !stop(observed) && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(120)
            if (!active()) break
            observed = status(fresh = true)
        }
        return observed
    }

    fun frame(): Bitmap? {
        val reply = ptp.command(GET_OBJECT, PREVIEW_HANDLE, maxData = 2 * 1024 * 1024)
        if (reply.code in temporaryPreviewResponses) return null
        val data = reply.requireOk(GET_OBJECT).data ?: return null
        val start = (0 until minOf(4096, data.size - 2)).firstOrNull { index ->
            data[index] == 0xff.toByte() && data[index + 1] == 0xd8.toByte() && data[index + 2] == 0xff.toByte()
        } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, start, data.size - start, bounds)
        if (bounds.outMimeType != "image/jpeg" || bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = if (maxOf(bounds.outWidth, bounds.outHeight) > 1920) 2 else 1
        }
        return BitmapFactory.decodeByteArray(data, start, data.size - start, options)
    }

    override fun close() {
        try {
            runCatching { ptp.close() }
        } finally {
            connection.releaseInterface(link.descriptor)
            connection.close()
        }
    }

    private data class Interface(val descriptor: UsbInterface, val inbound: UsbEndpoint, val outbound: UsbEndpoint)

    companion object {
        private const val SONY_VENDOR = 0x054c
        private const val SDIO_CONNECT = 0x9201
        private const val SDIO_DEVICE_INFO = 0x9202
        private const val GET_CAMERA_PROPERTIES = 0x9209
        private const val SET_CONTROL = 0x9207
        private const val MAX_PROPERTIES = 64 * 1024
        private const val SONY_PROPERTY_FORMAT_2 = 200
        private const val GET_OBJECT = 0x1009
        private const val PREVIEW_HANDLE = -16382 // 0xffffc002
        private const val TIMEOUT_MS = 2500
        private val temporaryPreviewResponses = setOf(0x2009, 0x200f, 0x2019, 0xa102)

        fun candidates(manager: UsbManager): List<UsbDevice> = manager.deviceList.values
            .filter { it.vendorId == SONY_VENDOR && interfaceFor(it) != null }
            .sortedBy { it.deviceId }

        fun open(manager: UsbManager, device: UsbDevice): SonyUsbViewfinder {
            val link = interfaceFor(device) ?: throw IOException("No USB camera interface found.")
            val connection = manager.openDevice(device) ?: throw IOException("USB camera is unavailable.")
            var claimed = false
            val ptp = UsbPtpTransport(
                write = { connection.bulkTransfer(link.outbound, it, it.size, TIMEOUT_MS) },
                read = { buffer, count -> connection.bulkTransfer(link.inbound, buffer, count, TIMEOUT_MS) },
                packetSize = link.inbound.maxPacketSize,
            )
            try {
                // Leave an interface already owned by another app alone.
                claimed = connection.claimInterface(link.descriptor, false)
                if (!claimed) throw IOException("USB camera is in use. Close the other camera app, then retry.")
                ptp.open()
                val info = UsbPtpTransport.GET_DEVICE_INFO
                val raw = ptp.command(info, maxData = 64 * 1024).requireOk(info).data
                    ?: throw IOException("USB camera did not identify itself.")
                val identity = PtpDeviceInfo.parse(raw)
                val model = SonyCameraIdentity.ptp(identity.manufacturer, identity.model, null)
                    ?: throw IOException("The connected device did not identify itself as a Sony camera.")
                if (!identity.operations.containsAll(setOf(SDIO_CONNECT, SDIO_DEVICE_INFO, GET_OBJECT))) {
                    throw IOException("$model is not in PC Remote mode. Set USB Connection to PC Remote on the camera.")
                }
                ptp.command(SDIO_CONNECT, 1, 0, 0, maxData = 4096).requireOk(SDIO_CONNECT)
                ptp.command(SDIO_CONNECT, 2, 0, 0, maxData = 4096).requireOk(SDIO_CONNECT)
                val extension = ptp.command(SDIO_DEVICE_INFO, 0x12c, 1, maxData = MAX_PROPERTIES)
                    .requireOk(SDIO_DEVICE_INFO).data
                ptp.command(SDIO_CONNECT, 3, 0, 0, maxData = 4096).requireOk(SDIO_CONNECT)
                val hasProperties = GET_CAMERA_PROPERTIES in identity.operations
                val propertyFormat = extension?.takeIf { it.size >= 2 }?.let { PtpDataset(it).u16() }
                val readsStatus = hasProperties && propertyFormat == SONY_PROPERTY_FORMAT_2
                val properties = if (hasProperties) ptp.command(GET_CAMERA_PROPERTIES, maxData = MAX_PROPERTIES) else null
                return SonyUsbViewfinder(
                    model, SonyCameraIdentity.label(identity.version), connection, link, ptp,
                    readsStatus, SET_CONTROL in identity.operations,
                    if (readsStatus && properties != null) parseStatus(properties) else null,
                )
            } catch (error: Exception) {
                if (claimed) runCatching { ptp.close() }
                if (claimed) connection.releaseInterface(link.descriptor)
                connection.close()
                throw error
            }
        }

        private fun parseStatus(reply: UsbPtpTransport.Response): SonyUsbStatus {
            val data = reply.data
            return if (reply.code == 0x2001 && data != null) SonyUsbStatus.parse(data) else SonyUsbStatus.unknown
        }

        private fun interfaceFor(device: UsbDevice): Interface? {
            for (index in 0 until device.interfaceCount) {
                val descriptor = device.getInterface(index)
                if (descriptor.interfaceClass != UsbConstants.USB_CLASS_STILL_IMAGE) continue
                val bulk = (0 until descriptor.endpointCount).map(descriptor::getEndpoint)
                    .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
                val inbound = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: continue
                val outbound = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: continue
                return Interface(descriptor, inbound, outbound)
            }
            return null
        }
    }
}
