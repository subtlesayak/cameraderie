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

    internal data class Input(
        val source: Source, val edit: Media3VideoEdit, val media: Media,
        val origin: ProjectExportOrigin?, val ride: RideStore.ExportSnapshot?,
    ) {
        companion object {
            fun from(context: Context, video: VerifiedVideo, edit: Media3VideoEdit, origin: ProjectExportOrigin?): Input {
                val receipt = checkNotNull(video.entry.receipt)
                val hash = checkNotNull(receipt.sha256)
                val source = Source(video.name, video.entry.draft.id, receipt.bytes, hash,
                    video.width, video.height, video.entry.draft.cameraModel)
                return Input(source, edit, Media.inspect(video), origin, RideStore.exportSnapshot(context, hash))
            }
        }
    }

    internal data class Project(val name: String, val id: String, val shootId: String?)

    private class Cut(input: Input, val index: Int, val timelineStartSeconds: Double, project: Boolean) {
        val source = input.source
        val media = input.media
        val folder = if (project) "clips/$index/" else ""
        val filename = source.name.also { value ->
            require(value.isNotBlank() && value != "." && value != ".." && value.none { it == '/' || it == '\\' || it.isISOControl() })
        }
        val path = URI(null, null, if (project) "media/$index/$filename" else "media/$filename", null).toASCIIString()
        val rate = media.rate
        val length = rate.frames(media.durationMs).coerceAtLeast(1)
        val first = rate.frames(input.edit.trim.startMs).coerceIn(0, length - 1)
        val last = rate.frames(input.edit.trim.endMs).coerceIn(first + 1, length)
        val frames = last - first
        val timelineEndSeconds = timelineStartSeconds + frames / rate.fps
        val markers = input.ride?.markers.orEmpty().filter { it >= input.edit.trim.startMs && it < input.edit.trim.endMs }
            .map { (rate.frames(it) - first).coerceIn(0, frames - 1) }.distinct().sorted()
    }

    private data class Position(val cut: Cut, val start: Long, val end: Long)

    private class Timeline(val name: String, val rate: Rate, val cuts: List<Cut>) {
        val audio = cuts.any { it.media.audio }
        val positions = buildList {
            var cursor = 0L
            for (cut in cuts) {
                val end = (cut.timelineEndSeconds * rate.fps).roundToLong().coerceAtLeast(cursor + 1)
                add(Position(cut, cursor, end))
                cursor = end
            }
        }
        val xmlMarkers = positions.flatMap { (cut, start, end) ->
            cut.markers.map { frame ->
                (start + (frame / cut.rate.fps * rate.fps).roundToLong()).coerceAtMost(end - 1)
            }
        }
    }

    fun save(context: Context, destination: Uri, video: VerifiedVideo, edit: Media3VideoEdit, cancelled: () -> Boolean) {
        val origin = edit.projectId?.let { id -> ProjectStore(context).use { it.exportOrigin(id, video.entry) } }
        val input = Input.from(context, video, edit, origin)
        saveArchive(context, destination, cancelled) {
            write(it, input.source, input.edit, input.media, input.origin, input.ride)
        }
    }

    fun saveProject(context: Context, destination: Uri, project: Project, clips: Sequence<Input>, cancelled: () -> Boolean) =
        saveArchive(context, destination, cancelled) { writeProject(it, project, clips, cancelled) }

    private fun saveArchive(context: Context, destination: Uri, cancelled: () -> Boolean, write: (File) -> Unit) {
        val stage = File.createTempFile("resolve-package-", ".zip", context.cacheDir)
        try {
            write(stage)
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
    ) =
        writeArchive(file, sequenceOf(Input(source, edit, media, origin, ride)), null) { false }

    internal fun writeProject(file: File, project: Project, clips: Sequence<Input>, cancelled: () -> Boolean = { false }) =
        writeArchive(file, clips, project, cancelled)

    private fun writeArchive(file: File, inputs: Sequence<Input>, project: Project?, cancelled: () -> Boolean) {
        val files = JSONObject()
        val cuts = mutableListOf<Cut>()
        val clips = mutableListOf<JSONObject>()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun add(path: String, bytes: ByteArray) {
                checkActive(cancelled)
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                files.put(path, JSONObject().put("bytes", bytes.size).put("sha256", hash))
                zip.entry(path, bytes)
            }
            var next = 0.0
            for (input in inputs) {
                checkActive(cancelled)
                if (project != null) {
                    val origin = checkNotNull(input.origin)
                    check(origin.projectId == project.id && origin.sourceImportId == input.source.importId &&
                        origin.sourceSha256 == input.source.sha256)
                    check(project.shootId == null || origin.shootId == project.shootId)
                }
                val cut = Cut(input, cuts.size + 1, next, project != null)
                next = cut.timelineEndSeconds
                cuts.add(cut)
                val clip = manifestClip(input, cut)
                clips.add(clip)
                input.edit.lut?.let {
                    val path = cut.folder + "look.cube"
                    add(path, cube(it).toByteArray(Charsets.UTF_8))
                    if (project != null) clip.put("lut", path)
                }
                input.ride?.let {
                    val alignment = cut.folder + "ride/alignment.json"
                    val track = cut.folder + "ride/track.${it.extension}"
                    add(alignment, it.alignment)
                    add(track, it.track)
                    if (project != null) clip.put("ride", JSONObject().put("alignment", alignment).put("track", track))
                }
            }
            val first = cuts.firstOrNull() ?: throw IOException("Mark at least one verified original video as a selected take.")
            val timeline = Timeline(project?.name ?: first.filename.substringBeforeLast('.') + " · Cameraderie", first.rate, cuts)
            add("timeline.otio", otio(timeline).toString(2).toByteArray(Charsets.UTF_8))
            add("timeline.xml", xml(timeline).toByteArray(Charsets.UTF_8))
            val manifest = if (project == null) clips.single().put("version", 1)
            else JSONObject().put("version", 2).put("projectId", project.id).putOpt("shootId", project.shootId)
                .put("timelineFps", timeline.rate.fps).put("clips", JSONArray(clips))
            manifest.put("files", files).put("timelineMarkers", JSONArray(timeline.xmlMarkers))
            zip.entry("manifest.json", manifest.toString(2).toByteArray(Charsets.UTF_8))
        }
    }

    private fun manifestClip(input: Input, cut: Cut): JSONObject {
        val source = input.source
        val reference = JSONObject().put("name", cut.filename).put("relativeUrl", cut.path)
            .put("importId", source.importId).put("bytes", source.bytes).put("sha256", source.sha256)
            .putOpt("camera", source.camera)
        val trim = input.edit.trim
        val range = JSONObject().put("startMs", trim.startMs).put("endMs", trim.endMs)
            .put("inFrame", cut.first).put("outFrame", cut.last).put("fps", cut.rate.fps)
        val clip = JSONObject().put("source", reference).put("trim", range)
            .putOpt("projectId", input.origin?.projectId).putOpt("shootId", input.origin?.shootId)
            .putOpt("telemetryOffsetMs", input.ride?.offsetMs)
        if (cut.folder.isNotEmpty()) clip.put("timelineStartSeconds", cut.timelineStartSeconds)
            .put("timelineEndSeconds", cut.timelineEndSeconds).put("sourceMarkers", JSONArray(cut.markers.map { cut.first + it }))
        input.edit.crop?.let { crop ->
            clip.put("crop", JSONObject().put("left", crop.left).put("right", crop.right)
                .put("top", crop.top).put("bottom", crop.bottom).put("width", crop.width).put("height", crop.height))
        }
        return clip
    }

    private fun otio(timeline: Timeline): JSONObject {
        fun typed(schema: String) = JSONObject().put("OTIO_SCHEMA", schema)
        fun time(frame: Double, rate: Rate) = typed("RationalTime.1").put("value", frame).put("rate", rate.fps)
        fun range(start: Double, duration: Double, rate: Rate) = typed("TimeRange.1")
            .put("start_time", time(start, rate)).put("duration", time(duration, rate))
        fun node(schema: String, label: String) = typed(schema).put("name", label).put("metadata", JSONObject())
            .put("source_range", JSONObject.NULL).put("effects", JSONArray()).put("markers", JSONArray())
        val kinds = if (timeline.audio) listOf("Video", "Audio") else listOf("Video")
        val tracks = kinds.map { kind ->
            val clips = timeline.cuts.map { cut ->
                val rate = cut.rate
                if (kind == "Audio" && !cut.media.audio) node("Gap.1", "")
                    .put("source_range", range(0.0, cut.frames.toDouble(), rate))
                else {
                    val reference = typed("ExternalReference.1").put("name", cut.filename).put("metadata", JSONObject())
                        .put("available_range", range(0.0, cut.length.toDouble(), rate)).put("target_url", cut.path)
                    node("Clip.1", cut.filename).put("source_range", range(cut.first.toDouble(), cut.frames.toDouble(), rate))
                        .put("media_reference", reference)
                }
            }
            node("Track.1", kind).put("kind", kind).put("children", JSONArray(clips))
        }
        val positions = timeline.cuts.flatMap { cut ->
            cut.markers.map { (cut.timelineStartSeconds + it / cut.rate.fps) * timeline.rate.fps }
        }
        val markers = positions.mapIndexed { index, frame ->
            typed("Marker.2").put("metadata", JSONObject()).put("name", "Highlight ${index + 1}")
                .put("marked_range", range(frame, 0.0, timeline.rate)).put("color", "YELLOW").put("comment", "")
        }
        val stack = node("Stack.1", "tracks").put("children", JSONArray(tracks)).put("markers", JSONArray(markers))
        return typed("Timeline.1").put("metadata", JSONObject()).put("name", timeline.name)
            .put("global_start_time", time(0.0, timeline.rate)).put("tracks", stack)
    }

    private fun xml(timeline: Timeline): String {
        val result = StringWriter()
        val xml = XmlPullParserFactory.newInstance().newSerializer().apply {
            setOutput(result)
            startDocument("UTF-8", true)
        }
        fun XmlSerializer.rate(value: Rate) = tag("rate") {
            textTag("timebase", value.timebase)
            textTag("ntsc", if (value.ntsc) "TRUE" else "FALSE")
        }
        fun XmlSerializer.clip(position: Position, audio: Boolean, audioIndex: Int) {
            val (cut, start, end) = position
            val videoId = "clipitem-${2 * cut.index - 1}"
            val audioId = "clipitem-${2 * cut.index}"
            tag("clipitem", "id" to if (audio) audioId else videoId) {
                textTag("name", cut.filename)
                rate(cut.rate)
                textTag("duration", cut.frames)
                textTag("start", start)
                textTag("end", end)
                textTag("in", cut.first)
                textTag("out", cut.last)
                tag("file", "id" to "file-${cut.index}") {
                    if (!audio) {
                        textTag("name", cut.filename)
                        textTag("pathurl", cut.path)
                        rate(cut.rate)
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
                    for ((linkId, type) in listOf(videoId to "video", audioId to "audio")) tag("link") {
                        textTag("linkclipref", linkId)
                        textTag("mediatype", type)
                        textTag("trackindex", 1)
                        textTag("clipindex", if (type == "audio") audioIndex else cut.index)
                    }
                }
            }
        }
        val withAudio = timeline.positions.filter { it.cut.media.audio }
        val audioIndices = withAudio.mapIndexed { index, position -> position.cut.index to index + 1 }.toMap()
        val source = timeline.cuts.first().source
        xml.tag("xmeml", "version" to "4") {
            tag("sequence", "id" to "sequence-1") {
                textTag("name", timeline.name)
                textTag("duration", timeline.positions.last().end)
                rate(timeline.rate)
                tag("media") {
                    tag("video") {
                        tag("format") { tag("samplecharacteristics") {
                            rate(timeline.rate)
                            textTag("width", source.width)
                            textTag("height", source.height)
                            textTag("pixelaspectratio", "square")
                        } }
                        tag("track") { timeline.positions.forEach { clip(it, false, audioIndices[it.cut.index] ?: 0) } }
                    }
                    if (timeline.audio) tag("audio") { tag("track") {
                        withAudio.forEachIndexed { index, position -> clip(position, true, index + 1) }
                    } }
                }
                timeline.xmlMarkers.forEachIndexed { index, frame -> tag("marker") {
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
