package app.cameraderie.gallery.media.edit

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.cameraderie.gallery.media.history.ProjectExportOrigin
import app.cameraderie.projects.ProjectStore
import app.cameraderie.telemetry.RideStore
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToLong

/** An editable cut and its verified source reference, with portable copies of its LUT and ride. */
internal object ResolvePackage {
    internal data class Rate(val timebase: Int, val ntsc: Boolean = false) {
        val fps get() = if (ntsc) timebase * 1_000.0 / 1_001 else timebase.toDouble()
        fun frames(ms: Long) = (ms * fps / 1_000).roundToLong()
    }

    internal data class Media(val rate: Rate, val durationMs: Long, val audio: Boolean) {
        companion object {
            fun inspect(video: VerifiedVideo): Media {
                val reader = MediaExtractor()
                try {
                    reader.setDataSource(video.file.path)
                    val tracks = (0 until reader.trackCount).map(reader::getTrackFormat)
                    val videoTrack = tracks.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    check(videoTrack >= 0)
                    reader.selectTrack(videoTrack)
                    val times = buildList {
                        repeat(120) {
                            val sample = reader.sampleTime
                            if (sample < 0) return@buildList
                            add(sample)
                            if (!reader.advance()) return@buildList
                        }
                    }.distinct().sorted()
                    val steps = times.zipWithNext { first, next -> next - first }.sorted()
                    val measured = steps.getOrNull(steps.size / 2)?.let { 1_000_000.0 / it }
                    val format = tracks[videoTrack]
                    val reported = format.getNumber(MediaFormat.KEY_FRAME_RATE, 0).toDouble()
                    val nominal = measured?.takeIf { it in 1.0..240.1 }
                        ?: reported.takeIf { it in 1.0..240.1 }
                        ?: throw IOException("Couldn’t determine a timeline frame rate for this video.")
                    val rates = (1..240).map(::Rate) + listOf(24, 30, 48, 60, 120, 240).map { Rate(it, true) }
                    val rate = rates.minBy { abs(it.fps - nominal) }
                    val duration = format.getLong(MediaFormat.KEY_DURATION, 0).let { if (it > 0) it / 1_000 else video.durationMs }
                    val audio = tracks.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    return Media(rate, duration.coerceAtLeast(1), audio)
                } finally { reader.release() }
            }
        }
    }

    internal data class Source(
        val name: String, val importId: String, val bytes: Long, val sha256: String,
        val width: Int, val height: Int, val camera: String?,
    )

    private class Cut(
        source: Source, edit: Media3VideoEdit, val media: Media,
        ride: RideStore.ExportSnapshot?,
    ) {
        val name = source.name.substringBeforeLast('.') + " · Cameraderie"
        val filename = source.name.also { value ->
            require(value.isNotBlank() && value != "." && value != ".." && value.none { it == '/' || it == '\\' || it.isISOControl() })
        }
        val path = URI(null, null, "media/$filename", null).toASCIIString()
        val rate = media.rate
        val length = rate.frames(media.durationMs).coerceAtLeast(1)
        val first = rate.frames(edit.trim.startMs).coerceIn(0, length - 1)
        val last = rate.frames(edit.trim.endMs).coerceIn(first + 1, length)
        val frames = last - first
        val markers = ride?.markers.orEmpty().filter { it >= edit.trim.startMs && it < edit.trim.endMs }
            .map { (rate.frames(it) - first).coerceIn(0, frames - 1) }.distinct()
    }

    fun save(context: Context, destination: Uri, video: VerifiedVideo, edit: Media3VideoEdit, cancelled: () -> Boolean) {
        val receipt = checkNotNull(video.entry.receipt)
        val hash = checkNotNull(receipt.sha256)
        val source = Source(video.name, video.entry.draft.id, receipt.bytes, hash,
            video.width, video.height, video.entry.draft.cameraModel)
        val origin = edit.projectId?.let { id -> ProjectStore(context).use { it.exportOrigin(id, video.entry) } }
        val ride = RideStore.exportSnapshot(context, hash)
        val media = Media.inspect(video)
        val stage = File.createTempFile("resolve-package-", ".zip", context.cacheDir)
        try {
            write(stage, source, edit, media, origin, ride)
            checkActive(cancelled)
            val resolver = context.contentResolver
            val expected = stage.inputStream().use { digest(it, cancelled) }
            checkNotNull(resolver.openOutputStream(destination, "wt")).use { output ->
                stage.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkActive(cancelled)
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(expected.contentEquals(checkNotNull(resolver.openInputStream(destination)).use { digest(it, cancelled) })) {
                "The saved package couldn’t be verified."
            }
        } finally { stage.delete() }
    }

    internal fun write(
        file: File, source: Source, edit: Media3VideoEdit, media: Media,
        origin: ProjectExportOrigin?, ride: RideStore.ExportSnapshot?,
    ) {
        val cut = Cut(source, edit, media, ride)
        val files = JSONObject()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun add(path: String, bytes: ByteArray) {
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                files.put(path, JSONObject().put("bytes", bytes.size).put("sha256", hash))
                zip.entry(path, bytes)
            }
            add("timeline.otio", otio(cut).toString(2).toByteArray(Charsets.UTF_8))
            add("timeline.xml", xml(cut, source).toByteArray(Charsets.UTF_8))
            edit.lut?.let { add("look.cube", cube(it).toByteArray(Charsets.UTF_8)) }
            ride?.let {
                add("ride/alignment.json", it.alignment)
                add("ride/track.${it.extension}", it.track)
            }
            val reference = JSONObject().put("name", cut.filename).put("relativeUrl", cut.path)
                .put("importId", source.importId).put("bytes", source.bytes).put("sha256", source.sha256)
                .putOpt("camera", source.camera)
            val range = JSONObject().put("startMs", edit.trim.startMs).put("endMs", edit.trim.endMs)
                .put("inFrame", cut.first).put("outFrame", cut.last).put("fps", cut.rate.fps)
            val manifest = JSONObject().put("version", 1).put("source", reference).put("trim", range).put("files", files)
                .putOpt("projectId", origin?.projectId).putOpt("shootId", origin?.shootId)
                .putOpt("telemetryOffsetMs", ride?.offsetMs).put("timelineMarkers", JSONArray(cut.markers))
            edit.crop?.let { crop ->
                manifest.put("crop", JSONObject().put("left", crop.left).put("right", crop.right)
                    .put("top", crop.top).put("bottom", crop.bottom).put("width", crop.width).put("height", crop.height))
            }
            zip.entry("manifest.json", manifest.toString(2).toByteArray(Charsets.UTF_8))
        }
    }

    private fun otio(cut: Cut): JSONObject {
        fun typed(schema: String) = JSONObject().put("OTIO_SCHEMA", schema)
        fun time(frame: Long) = typed("RationalTime.1").put("value", frame).put("rate", cut.rate.fps)
        fun range(start: Long, duration: Long) = typed("TimeRange.1")
            .put("start_time", time(start)).put("duration", time(duration))
        fun node(schema: String, label: String) = typed(schema).put("name", label).put("metadata", JSONObject())
            .put("source_range", JSONObject.NULL).put("effects", JSONArray()).put("markers", JSONArray())
        val kinds = if (cut.media.audio) listOf("Video", "Audio") else listOf("Video")
        val tracks = kinds.map { kind ->
            val reference = typed("ExternalReference.1").put("name", cut.filename).put("metadata", JSONObject())
                .put("available_range", range(0, cut.length)).put("target_url", cut.path)
            val item = node("Clip.1", cut.filename).put("source_range", range(cut.first, cut.frames))
                .put("media_reference", reference)
            node("Track.1", kind).put("kind", kind).put("children", JSONArray(listOf(item)))
        }
        val markers = cut.markers.mapIndexed { index, frame ->
            typed("Marker.2").put("metadata", JSONObject()).put("name", "Highlight ${index + 1}")
                .put("marked_range", range(frame, 0)).put("color", "YELLOW").put("comment", "")
        }
        val stack = node("Stack.1", "tracks").put("children", JSONArray(tracks)).put("markers", JSONArray(markers))
        return typed("Timeline.1").put("metadata", JSONObject()).put("name", cut.name)
            .put("global_start_time", time(0)).put("tracks", stack)
    }

    private fun xml(cut: Cut, source: Source): String {
        val result = StringWriter()
        val xml = XmlPullParserFactory.newInstance().newSerializer().apply {
            setOutput(result)
            startDocument("UTF-8", true)
        }
        fun XmlSerializer.rate() = tag("rate") {
            textTag("timebase", cut.rate.timebase)
            textTag("ntsc", if (cut.rate.ntsc) "TRUE" else "FALSE")
        }
        fun XmlSerializer.clip(audio: Boolean) = tag("clipitem", "id" to if (audio) "clipitem-2" else "clipitem-1") {
            textTag("name", cut.filename)
            rate()
            textTag("duration", cut.frames)
            textTag("start", 0)
            textTag("end", cut.frames)
            textTag("in", cut.first)
            textTag("out", cut.last)
            tag("file", "id" to "file-1") {
                if (!audio) {
                    textTag("name", cut.filename)
                    textTag("pathurl", cut.path)
                    rate()
                    textTag("duration", cut.length)
                    tag("media") {
                        tag("video") { }
                        if (cut.media.audio) tag("audio") { }
                    }
                }
            }
            tag("sourcetrack") {
                textTag("mediatype", if (audio) "audio" else "video")
                textTag("trackindex", 1)
            }
            if (cut.media.audio) {
                for ((linkId, type) in listOf("clipitem-1" to "video", "clipitem-2" to "audio")) tag("link") {
                    textTag("linkclipref", linkId)
                    textTag("mediatype", type)
                    textTag("trackindex", 1)
                    textTag("clipindex", 1)
                }
            }
        }
        xml.tag("xmeml", "version" to "4") {
            tag("sequence", "id" to "sequence-1") {
                textTag("name", cut.name)
                textTag("duration", cut.frames)
                rate()
                tag("media") {
                    tag("video") {
                        tag("format") { tag("samplecharacteristics") {
                            rate()
                            textTag("width", source.width)
                            textTag("height", source.height)
                            textTag("pixelaspectratio", "square")
                        } }
                        tag("track") { clip(false) }
                    }
                    if (cut.media.audio) tag("audio") { tag("track") { clip(true) } }
                }
                cut.markers.forEachIndexed { index, frame -> tag("marker") {
                    textTag("name", "Highlight ${index + 1}")
                    textTag("in", frame)
                    textTag("out", frame)
                } }
            }
        }
        xml.endDocument()
        return result.toString()
    }

    private inline fun XmlSerializer.tag(name: String, attribute: Pair<String, String>? = null, body: XmlSerializer.() -> Unit) {
        startTag(null, name)
        attribute?.let { attribute(null, it.first, it.second) }
        body()
        endTag(null, name)
    }

    private fun XmlSerializer.textTag(name: String, value: Any) = tag(name) { text(value.toString()) }

    private fun ZipOutputStream.entry(path: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(path))
        write(bytes)
        closeEntry()
    }

    private fun cube(lut: CubeLut) = buildString {
        append("LUT_3D_SIZE ").append(lut.size).append('\n')
        for (index in lut.rgb.indices step 3) {
            append(lut.rgb[index]).append(' ').append(lut.rgb[index + 1]).append(' ').append(lut.rgb[index + 2]).append('\n')
        }
    }

    private fun digest(input: java.io.InputStream, cancelled: () -> Boolean): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkActive(cancelled)
            val count = input.read(buffer)
            if (count < 0) return digest.digest()
            digest.update(buffer, 0, count)
        }
    }

    private fun checkActive(cancelled: () -> Boolean) {
        if (cancelled()) throw IOException("Package export interrupted.")
    }
}
