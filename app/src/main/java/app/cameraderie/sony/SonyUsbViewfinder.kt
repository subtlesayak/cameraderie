package app.cameraderie.sony

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
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
) : Closeable {
    fun frame(): Bitmap? {
        val objectInfo = ptp.command(GET_OBJECT_INFO, PREVIEW_HANDLE, maxData = 4096)
        if (objectInfo.code in temporaryPreviewResponses) return null
        objectInfo.requireOk(GET_OBJECT_INFO)
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
        private const val GET_OBJECT_INFO = 0x1008
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
                val info = UsbPtpTransport.GET_DEVICE_INFO
                val raw = ptp.command(info, maxData = 64 * 1024).requireOk(info).data
                    ?: throw IOException("USB camera did not identify itself.")
                val identity = PtpDeviceInfo.parse(raw)
                val model = SonyCameraIdentity.ptp(identity.manufacturer, identity.model, null)
                    ?: throw IOException("The connected device did not identify itself as a Sony camera.")
                if (!identity.operations.containsAll(setOf(SDIO_CONNECT, SDIO_DEVICE_INFO, GET_OBJECT_INFO, GET_OBJECT))) {
                    throw IOException("$model is not in PC Remote mode. Set USB Connection to PC Remote on the camera.")
                }
                ptp.open()
                ptp.command(SDIO_CONNECT, 1, 0, 0, maxData = 4096).requireOk(SDIO_CONNECT)
                ptp.command(SDIO_CONNECT, 2, 0, 0, maxData = 4096).requireOk(SDIO_CONNECT)
                ptp.command(SDIO_DEVICE_INFO, 0x12c, 1, maxData = 64 * 1024).requireOk(SDIO_DEVICE_INFO)
                ptp.command(SDIO_CONNECT, 3, 0, 0, maxData = 4096).requireOk(SDIO_CONNECT)
                if (GET_CAMERA_PROPERTIES in identity.operations) {
                    ptp.command(GET_CAMERA_PROPERTIES, maxData = 64 * 1024)
                }
                return SonyUsbViewfinder(model, SonyCameraIdentity.label(identity.version), connection, link, ptp)
            } catch (error: Exception) {
                if (claimed) runCatching { ptp.close() }
                if (claimed) connection.releaseInterface(link.descriptor)
                connection.close()
                throw error
            }
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
