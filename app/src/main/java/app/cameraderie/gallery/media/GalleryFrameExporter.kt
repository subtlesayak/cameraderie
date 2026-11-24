package app.cameraderie.gallery.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import app.cameraderie.gallery.media.GalleryDerivedMedia.checkActive
import app.cameraderie.gallery.media.edit.VerifiedVideo
import app.cameraderie.gallery.media.history.ImportHistoryDraft
import app.cameraderie.gallery.media.history.ImportProvenance
import app.cameraderie.gallery.protocol.GalleryMediaPage
import java.io.File
import java.util.UUID

/** Decode one frame using the original rather than the preview stream. */
internal object GalleryFrameExporter {
    fun supports(media:GalleryMediaPage.Media) = media.size>0 && GalleryHttpMedia.supportsProxy(media)

    fun saveLocal(context: Context, source: VerifiedVideo, positionMs: Long, cancelled: () -> Boolean) {
        require(positionMs in 0 until source.durationMs)
        checkActive(cancelled)
        val scratch = File.createTempFile("cameraderie-frame-", ".jpg", context.cacheDir)
        try {
            MediaMetadataRetriever().use { reader ->
                reader.setDataSource(source.file.path)
                decodeFrame(reader, scratch, positionMs, source.width, source.height, cancelled)
            }
            val result = publish(context, scratch, source.name, positionMs, source.entry.draft.cameraModel, cancelled, {})
            check(result.completed.isNotEmpty())
        } finally {
            scratch.delete()
        }
    }

    fun save(context:Context, media:GalleryMediaPage.Media, positionMs:Long, cameraName:String,
        http:GalleryHttpMedia, cancelled:()->Boolean, published:(String)->Unit):MediaImportTransaction.Result {
        require(supports(media) && positionMs in 0..Int.MAX_VALUE.toLong())
        checkActive(cancelled)
        val scratch=File.createTempFile("cameraderie-frame-", ".jpg",context.cacheDir)
        try {
            GalleryClipDataSource(http,media,original=true).use { source ->
                MediaMetadataRetriever().use { reader ->
                    reader.setDataSource(source)
                    val width=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: error("No video width")
                    val height=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: error("No video height")
                    val rotation=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    val duration=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("No video duration")
                    check(width>0 && height>0 && duration>0 && positionMs<=duration)
                    val rotated = rotation == 90 || rotation == 270
                    decodeFrame(reader, scratch, positionMs, if (rotated) height else width,
                        if (rotated) width else height, cancelled)
                }
            }
            return publish(context, scratch, media.name, positionMs, cameraName, cancelled, published)
        } finally {
            scratch.delete()
        }
    }

    private fun decodeFrame(reader: MediaMetadataRetriever, output: File, positionMs: Long, width: Int,
        height: Int, cancelled: () -> Boolean) {
        checkActive(cancelled)
        val frame = checkNotNull(reader.getFrameAtTime(positionMs * 1_000, MediaMetadataRetriever.OPTION_CLOSEST))
        try {
            check(frame.width == width && frame.height == height)
            checkActive(cancelled)
            output.outputStream().buffered().use { check(frame.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        } finally {
            frame.recycle()
        }
    }

    private fun publish(context: Context, scratch: File, sourceName: String, positionMs: Long, cameraName: String?,
        cancelled: () -> Boolean, published: (String) -> Unit): MediaImportTransaction.Result {
        checkActive(cancelled)
        val id = UUID.randomUUID().toString()
        val stem = sourceName.substringBeforeLast('.').replace(Regex("[\\p{Cntrl}/\\\\]"), "_").take(130)
        val name = "${stem}-frame-${positionMs}ms.jpg"
        val item = MediaImportTransaction.Item(id, name, scratch.length())
        return GalleryDerivedMedia.publish(context, scratch, item, cancelled, { uri ->
            ImportHistoryDraft(id, uri, name, item.bytes, System.currentTimeMillis(),
                ImportProvenance.FRAME_FROM_ORIGINAL, cameraModel = cameraName, videoSource = sourceName, framePositionMs = positionMs)
        }, published)
    }
}
