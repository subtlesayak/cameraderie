package app.cameraderie.telemetry

import app.cameraderie.gallery.media.history.ImportHistoryReceipt
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class RideFormat(val mime: String) {
    GPX("application/gpx+xml"), CSV("text/csv"), JSON("application/json");
    val extension get() = name.lowercase(Locale.ROOT)
}

internal data class RideTimeline(val first: Instant, val last: Instant, val points: Int) {
    fun overlap(capture: Instant, durationMs: Long, offsetMs: Long): ClosedRange<Long>? {
        val start = first.plusMillis(offsetMs).toEpochMilli() - capture.toEpochMilli()
        val end = last.plusMillis(offsetMs).toEpochMilli() - capture.toEpochMilli()
        val from = maxOf(0L, start)
        val through = minOf(durationMs, end)
        return if (from <= through) from..through else null
    }
}

internal object TelemetryTrack {
    private val timeKeys = setOf("time", "timestamp", "datetime", "timestamp_ms", "time_ms")
    private val containerDate = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss[.SSS]X", Locale.ROOT)

    fun instant(value: String): Instant? {
        val text = value.trim()
        return runCatching {
            text.toLongOrNull()?.let { raw ->
                require(raw in 946_684_800L..4_102_444_800_000L)
                if (raw < 10_000_000_000L) Instant.ofEpochSecond(raw) else Instant.ofEpochMilli(raw)
            } ?: Instant.parse(text)
        }.getOrNull()?.takeIf { it.epochSecond in 0L..32_503_680_000L }
    }

    fun captureStart(receipt: ImportHistoryReceipt, container: String?): Instant? {
        val local = receipt.captureDate?.let { day -> receipt.captureTime?.let { LocalDateTime.parse("${day}T$it") } }
        val offset = receipt.captureOffset?.let(ZoneOffset::of)
        if (local != null && offset != null) return local.toInstant(offset)
        container?.let { value ->
            (instant(value) ?: runCatching { Instant.from(containerDate.parse(value)) }.getOrNull())?.let { return it }
        }
        val zone = receipt.indexingZone?.let(ZoneId::of)
        return if (local != null && zone != null) local.atZone(zone).toInstant() else null
    }

    fun read(bytes: ByteArray, format: RideFormat): RideTimeline {
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val times = when (format) {
            RideFormat.GPX -> gpx(text)
            RideFormat.CSV -> csv(text)
            RideFormat.JSON -> json(text)
        }
        var first: Instant? = null
        var last: Instant? = null
        var count = 0
        for (time in times) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            if (first == null || time < first) first = time
            if (last == null || time > last) last = time
            check(++count <= 500_000) { "Too many ride samples" }
        }
        return RideTimeline(checkNotNull(first) { "Ride file has no absolute timestamps" }, checkNotNull(last), count)
    }

    private fun gpx(text: String): Sequence<Instant> = sequence {
        require(!text.contains("<!DOCTYPE", ignoreCase = true))
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(text.reader())
        var pointDepth = 0
        var rootSeen = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                if (parser.depth == 1) {
                    check(parser.name == "gpx")
                    rootSeen = true
                }
                if (parser.name == "trkpt" || parser.name == "rtept") pointDepth = parser.depth
                if (rootSeen && pointDepth > 0 && parser.depth == pointDepth + 1 && parser.name == "time") {
                    instant(parser.nextText())?.let { yield(it) }
                }
            }
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == pointDepth) pointDepth = 0
            parser.next()
        }
    }

    private fun csv(text: String): Sequence<Instant> = sequence {
        val lines = text.lineSequence().filter(String::isNotBlank).iterator()
        check(lines.hasNext())
        val index = columns(lines.next()).indexOfFirst { it.trim().lowercase(Locale.ROOT) in timeKeys }
        check(index >= 0) { "CSV needs a time or timestamp column" }
        for (line in lines) columns(line).getOrNull(index)?.let(::instant)?.let { yield(it) }
    }

    private fun columns(line: String): List<String> {
        val values = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index++]
            when {
                char == '"' && quoted && line.getOrNull(index) == '"' -> { value.append('"'); index++ }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> { values.add(value.toString()); value.clear() }
                else -> value.append(char)
            }
        }
        check(!quoted) { "Incomplete quoted CSV field" }
        return values + value.toString()
    }

    private fun json(text: String): Sequence<Instant> {
        val root = JsonParser.parseString(text)
        val rows = if (root.isJsonObject) listOf("points", "samples", "locations", "trackpoints")
            .firstNotNullOfOrNull { root.asJsonObject.get(it)?.takeIf(JsonElement::isJsonArray) } else root
        return checkNotNull(rows).asJsonArray.asSequence().mapNotNull { row ->
            row.takeIf(JsonElement::isJsonObject)?.asJsonObject?.entrySet()?.firstOrNull {
                it.key.lowercase(Locale.ROOT) in timeKeys && it.value.isJsonPrimitive
            }?.value?.asString?.let(::instant)
        }
    }
}
