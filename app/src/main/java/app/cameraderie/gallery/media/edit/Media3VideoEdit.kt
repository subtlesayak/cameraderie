package app.cameraderie.gallery.media.edit

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.SingleColorLut
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import app.cameraderie.gallery.media.GalleryDerivedMedia
import app.cameraderie.gallery.media.MediaImportTransaction
import app.cameraderie.gallery.media.history.ImportHistoryDraft
import app.cameraderie.gallery.media.history.ImportProvenance
import java.io.File
import java.io.IOException
import java.util.UUID

@OptIn(UnstableApi::class)
internal data class Media3VideoEdit(
    val trim: TrimRange,
    val crop: CropFrame?,
    val lut: CubeLut?,
    val format: VideoOutput = VideoOutput.AVC
) {
    val durationUs: Long get() = (trim.endMs - trim.startMs) * 1000

    fun item(video: VerifiedVideo): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.fromFile(video.file))
        if (trim.startMs != 0L || trim.endMs != video.durationMs) {
            builder.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(trim.startMs)
                .setEndPositionMs(trim.endMs)
                .build())
        }
        return builder.build()
    }

    // The two pipelines have separate GL contexts. Each gets fresh effect instances.
    fun effects(keepLutPrecision: Boolean = false): List<Effect> = buildList {
        crop?.let { add(Crop(it.left, it.right, it.bottom, it.top)) }
        lut?.let { table ->
            add(if (keepLutPrecision) Main10LutEffect(table) else SingleColorLut.createFromCube(table.toArgbCube()))
        }
    }

    fun composition(video: VerifiedVideo): Composition {
        val edited = EditedMediaItem.Builder(item(video))
            .setEffects(Effects(emptyList(), effects(keepLutPrecision = format == VideoOutput.MAIN10)))
            .build()
        val sequence = EditedMediaItemSequence.withAudioAndVideoFrom(listOf(edited))
        return Composition.Builder(sequence)
            .setHdrMode(if (video.resumeCompatible) Composition.HDR_MODE_KEEP_HDR
                else Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
    }

    fun publish(context: Context, video: VerifiedVideo, file: File, cancelled: () -> Boolean): String {
        GalleryDerivedMedia.checkActive(cancelled)
        if (format == VideoOutput.MAIN10) Main10Export.verify(file)
        val id = UUID.randomUUID().toString()
        val stem = video.name.substringBeforeLast('.').replace(unsafeFilename, "_").trim().take(80)
        val name = "${stem.ifEmpty { "Video" }}-edit-${id.take(8)}.mp4"
        val bytes = file.length()
        if (bytes <= 0) throw IOException("The encoder produced an empty file.")
        val item = MediaImportTransaction.Item(id, name, bytes)
        val result = GalleryDerivedMedia.publish(context, file, item, cancelled, { uri ->
            ImportHistoryDraft(id, uri, name, bytes, System.currentTimeMillis(), ImportProvenance.EDITED_FROM_ORIGINAL,
                cameraModel = video.entry.draft.cameraModel, videoSource = video.name,
                trimStartUs = trim.startMs * 1000, trimEndUs = trim.endMs * 1000)
        }, {})
        if (result.completed.isNotEmpty()) return name
        if (result.reconciliationRequired || result.cleanupFailed)
            throw IOException("Check Imported Images to confirm whether the MP4 was saved.")
        throw IOException("Couldn’t save the MP4. Check free space and try again.")
    }

    companion object {
        private val unsafeFilename = Regex("[\\p{Cntrl}/\\\\:*?\"<>|]")
    }
}
