package app.cameraderie.gallery.media.history

import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import app.cameraderie.gallery.media.AlbumMediaFile
import app.cameraderie.gallery.media.edit.CameraLutGuide
import app.cameraderie.sony.CameraCaptureTime
import java.io.File
import java.io.InputStream
import java.time.ZoneId
import java.time.ZoneOffset

internal data class ImportCaptureMetadata(
    val time: CameraCaptureTime?,
    val source: String?,
    val orientation: Int? = null,
    val cameraModel: String? = null,
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
        fun readDocument(file: AlbumMediaFile, staged: File): ImportCaptureMetadata = when {
            file.image || file.name.endsWith(".insp", ignoreCase = true) ->
                read(null) { staged.inputStream() }
            file.video || file.name.endsWith(".insv", ignoreCase = true) -> {
                val camera = CameraLutGuide.read(staged, null).camera
                val time = runCatching {
                    MediaMetadataRetriever().use { reader ->
                        reader.setDataSource(staged.path)
                        CameraCaptureTime.parse(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
                    }
                }.getOrNull()
                ImportCaptureMetadata(time, time?.let { "VIDEO_CONTAINER" }, cameraModel = camera)
            }
            else -> ImportCaptureMetadata(null, null)
        }

        private fun cameraFrom(exif: ExifInterface): String? {
            fun tag(key: String): String? = exif.getAttribute(key)?.trim()
                ?.takeIf { it.length in 1..100 && it.none(Char::isISOControl) }
            val model = tag(ExifInterface.TAG_MODEL) ?: return null
            val make = tag(ExifInterface.TAG_MAKE)
            return if (make == null || model.startsWith(make, ignoreCase = true)) model else "$make $model"
        }

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
                    val camera = cameraFrom(exif)
                    if (time == null) fallback.copy(orientation = orientation, cameraModel = camera)
                    else ImportCaptureMetadata(time.copy(offset = offset ?: time.offset), "EXIF", orientation, camera)
                }
            }.getOrNull() ?: fallback
        }
    }
}
