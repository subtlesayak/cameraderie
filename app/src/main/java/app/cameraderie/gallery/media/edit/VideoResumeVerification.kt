package app.cameraderie.gallery.media.edit

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs

/** Checks the finished output before publication, since the encoder can fall back to a fresh export. */
internal object VideoResumeVerification {
    data class Evidence(val spliceUs: Long, val reusedSamples: Int)
    private data class VideoFormat(
        val mime: String, val width: Int, val height: Int, val colorStandard: Int, val colorTransfer: Int, val colorRange: Int
    )
    private data class VideoScan(
        val format: VideoFormat, val sampleTimesUs: List<Long>, val lastSyncUs: Long, val largestGapUs: Long
    )
    private data class Prefix(val count: Int, val digest: List<Byte>, val firstSyncAtOrAfterSpliceUs: Long?)

    fun canResume(checkpoint: File, durationUs: Long, cancelled: () -> Boolean): Boolean =
        scanVideo(checkpoint, cancelled).lastSyncUs in 1 until durationUs

    fun verify(
        checkpoint: File,
        resumed: File,
        expectedDurationUs: Long,
        source: File = checkpoint,
        trimStartUs: Long = 0L,
        cancelled: () -> Boolean
    ): Evidence {
        val old = scanVideo(checkpoint, cancelled)
        val result = scanVideo(resumed, cancelled)
        check(old.format == result.format) { "The resumed video format changed." }
        val splice = old.lastSyncUs
        val lastSampleUs = result.sampleTimesUs.last()
        check(splice in 1 until expectedDurationUs && lastSampleUs > splice &&
            abs(expectedDurationUs - lastSampleUs) <= END_TOLERANCE_US) {
            "The resumed video did not reach its expected end."
        }
        val sourceTiming = if (source == checkpoint) old else scanVideo(source, cancelled)
        val times = result.sampleTimesUs
        val maxIntervalUs = sourceTiming.largestGapUs + VIDEO_TIMESTAMP_TOLERANCE_US
        val gaps = (1 until times.size).filter { times[it] - times[it - 1] > maxIntervalUs }
        val alignedStart = gaps.singleOrNull() == 1 && abs(times.first()) <= VIDEO_TIMESTAMP_TOLERANCE_US &&
            matchesTrimAlignment(sourceTiming, trimStartUs, times[1] - times[0])
        check(times.first() in -VIDEO_TIMESTAMP_TOLERANCE_US..END_TOLERANCE_US &&
            (gaps.isEmpty() || alignedStart)) {
            "The resumed video has a gap. Splice at ${splice}µs, starts at ${times.first()}µs, " +
                "max frame interval ${result.largestGapUs}µs (source ${sourceTiming.largestGapUs}µs), " +
                "longer intervals at ${gaps.take(4).map { times[it - 1] }}µs."
        }
        val originalPrefix = prefix(checkpoint, splice, cancelled)
        val resumedPrefix = prefix(resumed, splice, cancelled)
        val resumedSyncUs = resumedPrefix.firstSyncAtOrAfterSpliceUs
        // Media3 rounds the clip position to milliseconds while it sets up the resume.
        check(originalPrefix.count > 0 && originalPrefix.count == resumedPrefix.count &&
            originalPrefix.digest == resumedPrefix.digest && originalPrefix.firstSyncAtOrAfterSpliceUs == splice &&
            resumedSyncUs != null && resumedSyncUs - splice <= VIDEO_TIMESTAMP_TOLERANCE_US) {
            "The resumed export did not preserve its checkpoint video. " +
                "At ${splice}µs, checkpoint/result samples ${originalPrefix.count}/${resumedPrefix.count}, " +
                "matching prefix ${originalPrefix.digest == resumedPrefix.digest}, first sync ${resumedSyncUs}µs."
        }
        verifyAudio(resumed, hasAudio(source), expectedDurationUs, cancelled)
        return Evidence(splice, originalPrefix.count)
    }

    private fun matchesTrimAlignment(source: VideoScan, trimStartUs: Long, firstIntervalUs: Long): Boolean {
        // Media3 can move only the first frame to zero for a trim between source frames.
        val times = source.sampleTimesUs
        val nextIndex = times.binarySearch(trimStartUs).let { if (it >= 0) return false else -it - 1 }
        if (nextIndex !in 1 until times.lastIndex) return false
        val nextFrameUs = times[nextIndex]
        val trimOffsetUs = nextFrameUs - trimStartUs
        val frameIntervalUs = times[nextIndex + 1] - nextFrameUs
        return trimOffsetUs in 1..(frameIntervalUs / 2) &&
            abs(firstIntervalUs - (frameIntervalUs + trimOffsetUs)) <= VIDEO_TIMESTAMP_TOLERANCE_US
    }

    private fun scanVideo(file: File, cancelled: () -> Boolean): VideoScan = extract(file) { reader ->
        val format = reader.getTrackFormat(selectVideo(reader))
        val config = VideoFormat(
            checkNotNull(format.getString(MediaFormat.KEY_MIME)),
            format.getInteger(MediaFormat.KEY_WIDTH),
            format.getInteger(MediaFormat.KEY_HEIGHT),
            format.getInteger(MediaFormat.KEY_COLOR_STANDARD, 0),
            format.getInteger(MediaFormat.KEY_COLOR_TRANSFER, 0),
            format.getInteger(MediaFormat.KEY_COLOR_RANGE, 0),
        )
        val times = ArrayList<Long>()
        var sync = -1L
        while (reader.sampleTrackIndex >= 0) {
            checkActive(cancelled)
            val time = reader.sampleTime
            times.add(time)
            if (reader.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) sync = maxOf(sync, time)
            reader.advance()
        }
        check(times.isNotEmpty()) { "The video track has no samples." }
        // MP4 samples are read in decode order. B-frames can move backward in presentation time.
        times.sort()
        var largestGap = 0L
        for (index in 1 until times.size) {
            largestGap = maxOf(largestGap, times[index] - times[index - 1])
        }
        VideoScan(config, times, sync, largestGap)
    }

    private fun prefix(file: File, splice: Long, cancelled: () -> Boolean): Prefix = extract(file) { reader ->
        selectVideo(reader)
        val digest = MessageDigest.getInstance("SHA-256")
        val metadata = ByteBuffer.allocate(Long.SIZE_BYTES + Int.SIZE_BYTES * 2)
        var data = ByteBuffer.allocate(0)
        var count = 0
        var firstSync: Long? = null
        while (reader.sampleTrackIndex >= 0) {
            checkActive(cancelled)
            val time = reader.sampleTime
            val flags = reader.sampleFlags
            if (time < splice) {
                val bytes = reader.sampleSize
                check(bytes in 1..MAX_SAMPLE_BYTES)
                val size = bytes.toInt()
                if (data.capacity() < size) data = ByteBuffer.allocate(size)
                data.clear()
                check(reader.readSampleData(data, 0) == size)
                metadata.clear()
                metadata.putLong(time).putInt(flags).putInt(size)
                digest.update(metadata.array())
                data.position(0).limit(size)
                digest.update(data)
                count++
            } else if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                firstSync = minOf(firstSync ?: time, time)
            }
            reader.advance()
        }
        Prefix(count, digest.digest().toList(), firstSync)
    }

    private fun verifyAudio(file: File, expected: Boolean, durationUs: Long, cancelled: () -> Boolean) {
        extract(file) { reader ->
            val tracks = tracks(reader, "audio/")
            check(tracks.size == if (expected) 1 else 0) { "The resumed export has unexpected audio tracks." }
            val track = tracks.singleOrNull() ?: return@extract
            val format = reader.getTrackFormat(track)
            check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC)
            val packetUs = 1_024_000_000L / format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            reader.selectTrack(track)
            var last = reader.sampleTime
            // AAC encoder priming may precede zero on the MP4 presentation timeline.
            check(reader.sampleTrackIndex >= 0 && last in (-2 * packetUs)..END_TOLERANCE_US) {
                "The resumed audio starts outside the expected range."
            }
            while (reader.advance()) {
                checkActive(cancelled)
                val time = reader.sampleTime
                check(abs(time - last - packetUs) <= 2) { "The resumed audio has a gap." }
                last = time
            }
            check(abs(durationUs - last) <= END_TOLERANCE_US) { "The resumed audio did not reach its expected end." }
        }
    }

    private fun hasAudio(file: File) = extract(file) { tracks(it, "audio/").isNotEmpty() }

    private fun selectVideo(reader: MediaExtractor) = tracks(reader, "video/").single().also(reader::selectTrack)

    private fun tracks(reader: MediaExtractor, mimePrefix: String) = (0 until reader.trackCount).filter {
        reader.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true
    }

    private inline fun <T> extract(file: File, read: (MediaExtractor) -> T): T {
        val reader = MediaExtractor()
        try {
            reader.setDataSource(file.path)
            return read(reader)
        } finally {
            reader.release()
        }
    }

    private fun checkActive(cancelled: () -> Boolean) {
        if (cancelled()) throw java.io.InterruptedIOException("Resume verification canceled")
    }

    private const val END_TOLERANCE_US = 100_000L
    // Source and output MP4 timescales can quantize the same frame time differently.
    private const val VIDEO_TIMESTAMP_TOLERANCE_US = 1_000L
    private const val MAX_SAMPLE_BYTES = 32L * 1024 * 1024
}
