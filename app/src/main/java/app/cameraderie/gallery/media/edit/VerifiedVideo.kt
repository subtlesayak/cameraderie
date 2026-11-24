package app.cameraderie.gallery.media.edit

import android.content.ContentResolver
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.CancellationSignal
import app.cameraderie.gallery.media.GalleryDerivedMedia
import app.cameraderie.gallery.media.history.ImportHistoryEntry
import app.cameraderie.gallery.media.history.ImportHistoryRepository
import app.cameraderie.gallery.media.history.ImportHistoryStage
import app.cameraderie.gallery.media.history.ImportProvenance
import app.cameraderie.sony.SonyOriginalVerification
import app.cameraderie.telemetry.TelemetryTrack
import java.time.Instant
import java.io.File
import java.io.IOException

/** The editor and exporter only read the private copy whose bytes passed the import receipt check. */
internal data class VerifiedVideo(
    val entry: ImportHistoryEntry,
    val file: File,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val acceptsRec709Lut: Boolean,
    val lutGuide: CameraLutGuide,
    val resumeCompatible: Boolean,
    val captureStart: Instant? = null,
) {
    val name: String get() = entry.receipt?.displayName ?: entry.draft.displayName

    companion object {
        fun supports(entry: ImportHistoryEntry): Boolean {
            val receipt = entry.receipt ?: return false
            return entry.stage == ImportHistoryStage.COMPLETE &&
                (entry.draft.provenance == ImportProvenance.OFFERED_CAMERA_BYTES ||
                    entry.draft.provenance == ImportProvenance.DOCUMENT_BYTES) &&
                entry.draft.offeredReduced != true && receipt.mime in videoTypes && receipt.sha256 != null &&
                receipt.bytes == entry.draft.expectedBytes
        }

        private val videoTypes = setOf("video/mp4", "video/quicktime", "video/mp2ts")

        fun load(
            id: String,
            repository: ImportHistoryRepository,
            resolver: ContentResolver,
            file: File,
            signal: CancellationSignal
        ): VerifiedVideo {
            val entry = repository.store.find(id)?.takeIf(::supports)
                ?: throw IOException("This import has no verified original available for editing.")
            val receipt = checkNotNull(entry.receipt)
            val uri = Uri.parse(entry.draft.uri)
            val local = repository.local.snapshot(entry.draft.uri, signal)
            if (local?.published != true) throw IOException("The saved original is unavailable.")
            if (checkNotNull(file.parentFile).usableSpace < receipt.bytes) {
                throw IOException("Free phone storage before opening this video for editing.")
            }
            try {
                val asset = resolver.openAssetFileDescriptor(uri, "r", signal)
                    ?: throw IOException("The saved original is unavailable.")
                asset.use {
                    file.outputStream().use { output ->
                        asset.createInputStream().use { input ->
                            SonyOriginalVerification.verify(
                                input, receipt.bytes, false, { signal.isCanceled }, receipt.sha256, output
                            )
                        }
                    }
                }
            } catch (e: IOException) {
                throw IOException("Couldn’t verify the original. Check free space or import the original again.", e)
            }
            GalleryDerivedMedia.checkActive { signal.isCanceled }
            val format = MediaExtractor().let { extractor ->
                try {
                    extractor.setDataSource(file.path)
                    (0 until extractor.trackCount).asSequence().map(extractor::getTrackFormat)
                        .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                        ?: throw IOException("This file has no readable video track.")
                } finally {
                    extractor.release()
                }
            }
            val transfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER, 0)
            val standard = format.getInteger(MediaFormat.KEY_COLOR_STANDARD, 0)
            val acceptsLut = transfer != MediaFormat.COLOR_TRANSFER_HLG && transfer != MediaFormat.COLOR_TRANSFER_ST2084 &&
                (standard == 0 || standard == MediaFormat.COLOR_STANDARD_BT709)
            val resumeCompatible = (transfer == MediaFormat.COLOR_TRANSFER_SDR_VIDEO || (transfer == 0 && acceptsLut)) &&
                format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION
            return MediaMetadataRetriever().use { media ->
                media.setDataSource(file.path)
                val duration = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                val width = format.getInteger(MediaFormat.KEY_WIDTH, 0)
                val height = format.getInteger(MediaFormat.KEY_HEIGHT, 0)
                val rotation = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (duration !in 1..Long.MAX_VALUE / 1000 || width <= 0 || height <= 0)
                    throw IOException("Couldn’t read this video’s duration or dimensions.")
                val swap = rotation % 180 != 0
                VerifiedVideo(entry, file, duration, if (swap) height else width, if (swap) width else height,
                    acceptsLut, CameraLutGuide.read(file,entry.draft.cameraModel), resumeCompatible,
                    TelemetryTrack.captureStart(receipt, media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)))
            }
        }
    }
}
