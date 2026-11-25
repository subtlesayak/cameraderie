package app.cameraderie.gallery.media.edit

import app.cameraderie.gallery.media.CameraMetadataProto
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

internal data class CameraLutGuide(val camera: String?, val profile: String?) {
    val recommendation: String
        get() = when {
            profile != null -> "Look for a .cube that converts $profile to Rec.709${camera?.let { " for $it" }.orEmpty()}."
            camera != null -> "Look for a .cube for $camera and the colour profile used to record this clip."
            else -> "Choose a .cube for the camera and colour profile used to record this clip."
        }

    fun score(filename: String): Int {
        val name = normalized(filename)
        val model = camera?.let(::normalized).orEmpty()
        val cameraMatch = model.length >= 4 && name.contains(model)
        val fileProfile = detectProfile(filename)
        if (profile != null && fileProfile != null && profile != fileProfile) return 0
        return (if (cameraMatch) 4 else 0) +
            (if (profile != null && fileProfile == profile) 8 else 0) +
            (if (name.contains("rec709") && (cameraMatch || fileProfile == profile && profile != null)) 1 else 0)
    }

    companion object {
        private val profiles = linkedMapOf(
            "D-Log M" to "dlogm", "D-Log" to "dlog", "S-Log3" to "slog3", "S-Log2" to "slog2",
            "C-Log3" to "c(?:anon)?log3", "C-Log2" to "c(?:anon)?log2", "C-Log" to "c(?:anon)?log",
            "V-Log" to "vlog", "F-Log2" to "flog2", "F-Log" to "flog", "N-Log" to "nlog",
            "Apple Log 2" to "applelog2", "Apple Log" to "applelog", "GP-Log" to "gplog",
            "Blackmagic Film" to "blackmagic(?:design)?film", "LogC4" to "logc4", "LogC3" to "logc3"
        )

        private fun normalized(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        internal fun detectProfile(value: String): String? {
            val compact = normalized(value)
            return profiles.entries.firstOrNull { (_, pattern) -> Regex(pattern).containsMatchIn(compact) }?.key
        }

        fun read(file: File, importedCamera: String?): CameraLutGuide {
            val metadata = runCatching { VideoContainerMetadata.read(file) }.getOrNull()
            return CameraLutGuide(metadata?.camera ?: importedCamera?.takeIf { it.isNotBlank() },
                metadata?.profile)
        }
    }
}

/** Reads container metadata and the first DJI camera-metadata sample. Video payloads are skipped. */
internal object VideoContainerMetadata {
    private data class Box(val type: String, val data: Long, val end: Long)
    private const val MAX_TEXT = 4096
    private const val MAX_BOXES = 4096

    fun read(file: File): CameraLutGuide = RandomAccessFile(file, "r").use { input ->
        val moov = input.boxes(0, input.length()).firstOrNull { it.type == "moov" }
            ?: return CameraLutGuide(null, null)
        val movie = input.boxes(moov.data, moov.end)
        val userData = movie.firstOrNull { it.type == "udta" }
        val userBoxes = userData?.let { input.boxes(it.data, it.end) }.orEmpty()
        val tags = (movie + userBoxes).filter { it.type == "meta" }.flatMap { input.tags(it) }.toMap()
        val make = tags["com.apple.quicktime.make"] ?: tags["©mak"] ?: tag(tags,"make","cameramake","manufacturer")
        val model = tags["com.apple.quicktime.model"] ?: tags["©mod"] ?: tag(tags,"model","cameramodel","devicemodel")
        val camera = model?.let {
            listOfNotNull(make?.takeUnless { brand -> it.startsWith(brand, ignoreCase = true) }, it).joinToString(" ")
        } ?: tags["©too"]?.takeIf { it.startsWith("DJI Osmo", ignoreCase = true) }
            ?.replace(Regex("(?i)OsmoAction([0-9])"), "Osmo Action $1")
        val profile = tags.asSequence().filter { (key, _) ->
            key.contains("profile", ignoreCase = true) || key.contains("gamma", ignoreCase = true) ||
                key.contains("color", ignoreCase = true) || key.contains("colour", ignoreCase = true)
        }.mapNotNull { CameraLutGuide.detectProfile(it.value) }.firstOrNull()
            ?: input.djiAction6Profile(movie, camera)
        CameraLutGuide(camera, profile)
    }

    private fun tag(tags:Map<String,String>,vararg names:String) = tags.entries.firstOrNull {
        it.key.substringAfterLast('.').replace("_","").lowercase(Locale.ROOT) in names
    }?.value

    private fun RandomAccessFile.tags(meta: Box): List<Pair<String, String>> {
        val boxes = boxes(meta.data + 4, meta.end)
        val keys = boxes.firstOrNull { it.type == "keys" }?.let { box ->
            if (box.end - box.data < 8) return@let emptyList()
            seek(box.data + 4)
            val count = readInt().takeIf { it in 1..256 } ?: return@let emptyList()
            buildList {
                repeat(count) {
                    val offset = filePointer
                    if (offset + 8 > box.end) return@buildList
                    val size = readInt()
                    if (size !in 8..MAX_TEXT || offset + size > box.end) return@buildList
                    skipBytes(4)
                    add(text(filePointer, offset + size).orEmpty())
                    seek(offset + size)
                }
            }
        }.orEmpty()
        val list = boxes.firstOrNull { it.type == "ilst" } ?: return emptyList()
        return boxes(list.data, list.end).mapNotNull { entry ->
            val index = entry.type.toByteArray(Charsets.ISO_8859_1).fold(0) { n, byte -> n shl 8 or (byte.toInt() and 255) }
            val key = keys.getOrNull(index - 1) ?: entry.type
            if (key !in setOf("©mak", "©mod", "©too") && !key.startsWith("com.")) return@mapNotNull null
            val data = boxes(entry.data, entry.end).firstOrNull { it.type == "data" } ?: return@mapNotNull null
            text(data.data + 8, data.end)?.let { key to it }
        }
    }

    private fun RandomAccessFile.djiAction6Profile(movie: List<Box>, camera: String?): String? {
        if (camera?.replace(" ", "")?.contains("OsmoAction6", ignoreCase = true) != true) return null
        for (track in movie.filter { it.type == "trak" }) {
            val table = child(track, "mdia")?.let { child(it, "minf") }?.let { child(it, "stbl") } ?: continue
            val description = child(table, "stsd") ?: continue
            if (boxes(description.data + 8, description.end).none { it.type == "djmd" }) continue
            val offsets = child(table, "stco") ?: child(table, "co64") ?: continue
            val sizes = child(table, "stsz") ?: continue
            if (offsets.end - offsets.data < if (offsets.type == "stco") 12 else 16) continue
            if (sizes.end - sizes.data < 12) continue
            seek(offsets.data + 4)
            if (readInt() <= 0) continue
            val offset = if (offsets.type == "stco") readInt().toLong() and 0xffffffffL else readLong()
            seek(sizes.data + 4)
            val common = readInt()
            if (readInt() <= 0) continue
            val size = if (common > 0) common else if (sizes.end - filePointer >= 4) readInt() else 0
            if (size !in 1..MAX_TEXT || offset < 0 || offset > length() - size) continue
            seek(offset)
            val packet = ByteArray(size).also(::readFully)
            // Action 6 CAM meta protobuf: stream description (2), colour settings (4), ColorMode (1).
            val cameraData = CameraMetadataProto(packet).bytes(2) ?: continue
            val colour = CameraMetadataProto(cameraData).bytes(4) ?: continue
            if (colour.contentEquals(byteArrayOf(8, 19))) return "D-Log M"
        }
        return null
    }

    private fun RandomAccessFile.child(parent: Box, name: String) =
        boxes(parent.data, parent.end).firstOrNull { it.type == name }

    private fun RandomAccessFile.boxes(start: Long, end: Long): List<Box> = buildList {
        var offset = start
        while (offset <= end - 8 && size < MAX_BOXES) {
            seek(offset)
            val size32 = readInt().toLong() and 0xffffffffL
            val type = ByteArray(4).also(::readFully).toString(Charsets.ISO_8859_1)
            val header = if (size32 == 1L) 16L else 8L
            if (end - offset < header) break
            val length = when (size32) {
                0L -> end - offset
                1L -> readLong()
                else -> size32
            }
            if (length !in header..end - offset) break
            add(Box(type, offset + header, offset + length))
            offset += length
        }
    }

    private fun RandomAccessFile.text(start: Long, end: Long): String? {
        if (end - start !in 1..MAX_TEXT.toLong()) return null
        seek(start)
        return ByteArray((end - start).toInt()).also(::readFully).toString(Charsets.UTF_8)
            .trim { it.isWhitespace() || it == '\u0000' }.takeIf { value ->
                value.length in 1..200 && value.none(Char::isISOControl)
            }
    }
}
