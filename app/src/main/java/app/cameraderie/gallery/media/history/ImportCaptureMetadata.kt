package app.cameraderie.gallery.media.history

import androidx.exifinterface.media.ExifInterface
import app.cameraderie.sony.CameraCaptureTime
import java.io.InputStream
import java.time.ZoneId
import java.time.ZoneOffset

internal data class ImportCaptureMetadata(
    val time: CameraCaptureTime?,
    val source: String?,
    val orientation: Int? = null
) {
    fun describe(receipt: ImportHistoryReceipt, indexingZone: ZoneId? = null) = receipt.copy(
        exifOrientation = orientation,
        captureDate = time?.date?.toString(),
        captureTime = time?.time?.toString(),
        captureOffset = time?.offset?.id,
        captureSource = source,
        indexingZone = indexingZone?.id?.takeIf { time?.time != null && time.offset == null }
    )

    companion object {
        fun read(cameraTime: CameraCaptureTime?, openImage: (() -> InputStream?)? = null): ImportCaptureMetadata {
            val fallback = ImportCaptureMetadata(cameraTime, cameraTime?.let { "CAMERA_METADATA" })
            return runCatching {
                openImage?.invoke()?.use { input ->
                    val exif = ExifInterface(input)
                    val original = CameraCaptureTime.parse(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                    val time = original ?: CameraCaptureTime.parse(exif.getAttribute(ExifInterface.TAG_DATETIME))
                    val offsetTag = if (original != null) ExifInterface.TAG_OFFSET_TIME_ORIGINAL else ExifInterface.TAG_OFFSET_TIME
                    val offset = exif.getAttribute(offsetTag)?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
                    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0).takeIf { it in 0..8 }
                    if (time == null) fallback.copy(orientation = orientation)
                    else ImportCaptureMetadata(time.copy(offset = offset ?: time.offset), "EXIF", orientation)
                }
            }.getOrNull() ?: fallback
        }
    }
}
