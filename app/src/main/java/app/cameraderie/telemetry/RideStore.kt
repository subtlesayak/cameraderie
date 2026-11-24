package app.cameraderie.telemetry

import android.content.Context
import android.net.Uri
import app.cameraderie.io.readBounded
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.time.Instant

internal class RideStore(private val context: Context, sourceHash: String) {
    private val directory = File(context.noBackupFilesDir, "ride-telemetry/$sourceHash")
    private val index = AtomicFile(File(directory, "alignment.json"))
    init { require(hashPattern.matches(sourceHash)) }

    data class Ride(val name: String, val format: RideFormat, val hash: String, val timeline: RideTimeline,
        val capture: Instant?, val offsetMs: Long = 0, val markers: List<Long> = emptyList())

    fun get(): Ride? {
        val bytes = try { index.readFully() } catch (_: FileNotFoundException) { return null }
        val data = JSONObject(bytes.toString(Charsets.UTF_8))
        check(data.getInt("version") == 1)
        val markers = data.getJSONArray("highlightMarkersMs")
        return Ride(data.getString("name"), RideFormat.valueOf(data.getString("format")), data.getString("sha256"),
            RideTimeline(Instant.parse(data.getString("firstSample")), Instant.parse(data.getString("lastSample")), data.getInt("samples")),
            data.optString("captureStart").takeIf(String::isNotBlank)?.let(Instant::parse), data.getLong("offsetMs"),
            List(markers.length()) { markers.getLong(it) })
    }

    fun attach(uri: Uri, capture: Instant?): Ride {
        check(uri.scheme == "content")
        val resolver = context.contentResolver
        val name = checkNotNull(resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)).use {
            check(it.moveToFirst())
            it.getString(0)
        }
        val format = RideFormat.entries.firstOrNull { it.extension.equals(name.substringAfterLast('.'), ignoreCase = true) }
            ?: error("Choose a GPX, CSV or JSON file")
        val bytes = checkNotNull(resolver.openInputStream(uri)).use { it.readBounded(MAX_BYTES) }
        val timeline = TelemetryTrack.read(bytes, format)
        val hash = digest(bytes)
        val ride = Ride(name, format, hash, timeline, capture)
        check(directory.isDirectory || directory.mkdirs())
        write(AtomicFile(payload(ride)), bytes)
        save(ride)
        directory.listFiles()?.filter { payloadPattern.matches(it.name) && it.name != payload(ride).name }
            ?.forEach(File::delete)
        return ride
    }

    fun save(ride: Ride) { write(index, document(ride)) }

    fun export(ride: Ride, destination: Uri, alignment: Boolean) {
        val bytes = if (alignment) document(ride) else payload(ride).readBytes().also { check(digest(it) == ride.hash) }
        checkNotNull(context.contentResolver.openOutputStream(destination, "wt")).use { it.write(bytes) }
    }

    private fun document(ride: Ride): ByteArray = JSONObject().put("version", 1).put("name", ride.name)
        .put("format", ride.format.name).put("sha256", ride.hash).put("samples", ride.timeline.points)
        .put("firstSample", ride.timeline.first.toString()).put("lastSample", ride.timeline.last.toString())
        .putOpt("captureStart", ride.capture?.toString()).put("offsetMs", ride.offsetMs)
        .put("originalVideoSha256", directory.name).put("highlightMarkersMs", JSONArray(ride.markers))
        .toString(2).toByteArray(Charsets.UTF_8)

    private fun payload(ride: Ride) = File(directory, "${ride.hash}.${ride.format.extension}").also { check(hashPattern.matches(ride.hash)) }

    private fun write(file: AtomicFile, bytes: ByteArray) {
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    companion object {
        private const val MAX_BYTES = 16 * 1024 * 1024
        private val hashPattern = Regex("[0-9a-f]{64}")
        private val payloadPattern = Regex("[0-9a-f]{64}\\.(gpx|csv|json)")
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
