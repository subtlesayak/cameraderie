package app.cameraderie.gallery.media

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaParser
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import app.cameraderie.MainActivity
import app.cameraderie.NativeChecks
import app.cameraderie.gallery.protocol.GalleryCameraEvidence
import app.cameraderie.gallery.protocol.GalleryMediaPage
import app.cameraderie.ui.UiKit
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in measurement using the real proxy data source and a synthetic, in-memory HTTP response. */
class GallerySeekProbe {
    @Test fun measureSeekFallback() {
        val context = NativeChecks.instance.targetContext
        require(context.packageName == "app.cameraderie.r8probe.seek")
        val fixture = File(context.cacheDir, "gallery-seek/fixture.mp4")
        require(fixture.length() in 1..32L * 1024 * 1024)
        val bytes = fixture.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val fullSdk = if (Build.VERSION.SDK_INT >= 36) Build.VERSION.SDK_INT_FULL else Build.VERSION.SDK_INT
        val vvc = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && "video/vvc" in it.supportedTypes }.map { it.name }
        Log.i(TAG, "model=${Build.MODEL} sdk=$fullSdk codec_vvc=$vvc proxy_sha256=$hash")

        val times = listOf(9_400_000L, 27_400_000L, 50_400_000L)
        inspectSeekMap(Proxy(bytes), times)
        if (NativeChecks.instance.galleryRenderedSeek) {
            measureRenderedSeek(bytes, times)
            return
        }
        for (position in times) {
            repeat(3) { trial ->
                val proxy = Proxy(bytes)
                proxy.source.use { source ->
                    MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(source)
                        val requestsBefore = proxy.requests.get()
                        val start = SystemClock.elapsedRealtimeNanos()
                        val frame = checkNotNull(retriever.getFrameAtTime(position, MediaMetadataRetriever.OPTION_CLOSEST))
                        val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
                        Log.i(TAG, "seek_us=$position trial=${trial + 1} frame_ms=$elapsed " +
                            "size=${frame.width}x${frame.height} open_ranges=$requestsBefore " +
                            "seek_ranges=${proxy.requests.get() - requestsBefore}")
                        frame.recycle()
                    }
                }
            }
        }
    }

    private fun measureRenderedSeek(bytes: ByteArray, timesUs: List<Long>) {
        val instrument = NativeChecks.instance
        val owner = instrument.startActivitySync(Intent(instrument.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            as AppCompatActivity
        try {
            val expectedColors = listOf(Color.GREEN, Color.BLUE, Color.YELLOW)
            for ((position, expected) in timesUs.zip(expectedColors)) {
                repeat(3) { trial ->
                    val proxy = Proxy(bytes)
                    lateinit var player: GalleryClipPlayer
                    instrument.runOnMainSync {
                        player = GalleryClipPlayer(owner, UiKit(owner), "Synthetic seek recording", proxy.source) {}
                    }
                    try {
                        val play = awaitView<Button> { it.text.toString() == "Play preview" && it.isEnabled }
                        val surface = awaitView<SurfaceView> { it.holder.surface.isValid && it.width > 0 && it.height > 0 }
                        instrument.runOnMainSync { check(play.performClick()) }
                        awaitColor(surface, Color.RED)
                        Log.i(TAG, "initial preview frame rendered before seek_us=$position")
                        val pause = awaitView<Button> { it.text.toString() == "Pause preview" && it.isEnabled }
                        instrument.runOnMainSync { check(pause.performClick()) }
                        awaitView<Button> { it.text.toString() == "Play preview" && it.isEnabled }
                        val seek = awaitView<SeekBar> { it.contentDescription == "Clip position" && it.isEnabled }
                        val initialRequests = proxy.requests.get()
                        val input = Bundle().apply {
                            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, position / 1000f)
                        }
                        var started = 0L
                        instrument.runOnMainSync {
                            started = SystemClock.elapsedRealtimeNanos()
                            check(seek.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, input))
                        }
                        awaitColor(surface, expected)
                        val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
                        Log.i(TAG, "seek_us=$position trial=${trial + 1} rendered_ms=$elapsed " +
                            "open_ranges=$initialRequests seek_ranges=${proxy.requests.get() - initialRequests}")
                    } finally {
                        instrument.runOnMainSync(player::close)
                    }
                }
            }
        } finally {
            instrument.runOnMainSync(owner::finish)
        }
    }

    private inline fun <reified T : View> awaitView(crossinline matches: (T) -> Boolean): T {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            var result: T? = null
            NativeChecks.instance.runOnMainSync {
                result = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::views)
                    .filterIsInstance<T>().firstOrNull { it.isShown && matches(it) }
            }
            result?.let { return it }
            SystemClock.sleep(20)
        }
        error("Preview did not show the expected ${T::class.java.simpleName}.")
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
        }
    }

    private fun awaitColor(surface: SurfaceView, expected: Int) {
        val deadline = SystemClock.uptimeMillis() + 8000
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val main = Handler(Looper.getMainLooper())
        var lastResult = PixelCopy.ERROR_UNKNOWN
        var lastColor: Int? = null
        try {
            while (SystemClock.uptimeMillis() < deadline) {
                val copied = CountDownLatch(1)
                PixelCopy.request(surface, bitmap, { value ->
                    lastResult = value
                    copied.countDown()
                }, main)
                check(copied.await(2, TimeUnit.SECONDS)) { "Timed out sampling the preview surface." }
                if (lastResult == PixelCopy.SUCCESS) {
                    val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    lastColor = color
                    if (listOf(Color::red, Color::green, Color::blue).all {
                        kotlin.math.abs(it(color) - it(expected)) < 45
                    }) return
                }
                SystemClock.sleep(12)
            }
            error("Expected preview color ${expected.toUInt().toString(16)}, observed " +
                "${lastColor?.toUInt()?.toString(16)} (PixelCopy status $lastResult).")
        } finally {
            bitmap.recycle()
        }
    }

    private fun inspectSeekMap(proxy: Proxy, times: List<Long>) = proxy.source.use { source ->
        val tracks = linkedMapOf<Int, String>()
        var seekMap: MediaParser.SeekMap? = null
        var trackCount = -1
        val discarded = ByteArray(4096)
        val consumer = object : MediaParser.OutputConsumer {
            override fun onSeekMapFound(map: MediaParser.SeekMap) { seekMap = map }
            override fun onTrackCountFound(count: Int) { trackCount = count }
            override fun onTrackDataFound(index: Int, data: MediaParser.TrackData) {
                tracks[index] = checkNotNull(data.mediaFormat.getString(MediaFormat.KEY_MIME))
            }
            override fun onSampleDataFound(index: Int, reader: MediaParser.InputReader) {
                val remaining = reader.length - reader.position
                if (remaining > 0) reader.read(discarded, 0, minOf(remaining, discarded.size.toLong()).toInt())
            }
            override fun onSampleCompleted(index: Int, timeUs: Long, flags: Int, size: Int, offset: Int,
                cryptoInfo: MediaCodec.CryptoInfo?) = Unit
        }
        val reader = object : MediaParser.SeekableInputReader {
            private var position = 0L
            override fun getPosition() = position
            override fun getLength() = source.size
            override fun seekToPosition(position: Long) { this.position = position }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                source.readAt(position, buffer, offset, length).also { if (it > 0) position += it }
        }
        val parser = MediaParser.createByName(MediaParser.PARSER_NAME_MP4, consumer)
        try {
            while ((seekMap == null || tracks.size != trackCount) && parser.advance(reader)) { }
            val map = checkNotNull(seekMap)
            check(map.isSeekable && tracks.size == trackCount && times.all { it < map.durationMicros })
            Log.i(TAG, "parser_open_ranges=${proxy.requests.get()} tracks=$tracks")
            val perTrack = Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.CINNAMON_BUN_2
            for (position in times) {
                Log.i(TAG, "seek_us=$position container=${map.getSeekPoints(position)}")
                if (perTrack) {
                    for ((index, type) in tracks) {
                        Log.i(TAG, "seek_us=$position track=$index mime=$type own_cues=${map.trackHasSeekPoints(index)} " +
                            "points=${map.getSeekPoints(position, index)}")
                    }
                }
            }
            if (!perTrack) Log.i(TAG, "per_track_seek_unavailable")
        } finally {
            parser.release()
        }
    }

    private class Proxy(private val bytes: ByteArray) {
        val requests = AtomicInteger()
        private val clip = GalleryMediaPage.Media(
            GalleryMediaPage.Key(GalleryCameraEvidence.Store.INTERNAL, "DCIM/fixture.MP4"),
            "MISC/fixture.scr", 1, bytes.size.toLong(), 3, null
        )
        val source = GalleryClipDataSource(GalleryHttpMedia({ uri ->
            check(uri.path == "/v2" && uri.query.endsWith("path=DCIM/fixture.LRF"))
            val connection = object : HttpURLConnection(URL("http://localhost/")) {
                private val bounds by lazy {
                    val (start, end) = checkNotNull(getRequestProperty("Range")).removePrefix("bytes=")
                        .split("-").map(String::toInt)
                    check(start in bytes.indices && end >= start)
                    start..minOf(end, bytes.lastIndex)
                }
                override fun getResponseCode(): Int {
                    requests.incrementAndGet()
                    return 206
                }
                override fun getHeaderField(name: String) = when (name) {
                    "Content-Range" -> "bytes ${bounds.first}-${bounds.last}/${bytes.size}"
                    "ETag" -> "\"synthetic-proxy\""
                    else -> null
                }
                override fun getContentLengthLong() = (bounds.last - bounds.first + 1).toLong()
                override fun getInputStream() = ByteArrayInputStream(bytes, bounds.first, contentLengthLong.toInt())
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
            }
            object : GalleryHttpMedia.Request {
                override val connection = connection
                override fun close() = connection.disconnect()
            }
        }, { true }), clip)
    }

    private companion object { const val TAG = "CameraderieSeekProbe" }
}
