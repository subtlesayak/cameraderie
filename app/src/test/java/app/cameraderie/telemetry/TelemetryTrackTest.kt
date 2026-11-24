package app.cameraderie.telemetry

import app.cameraderie.gallery.media.history.ImportHistoryReceipt
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TelemetryTrackTest {
    @Test fun csvOffsetsAlignClipAndTrackWindows() {
        val csv = "\uFEFFtimestamp,description\n\"2026-01-01T01:00:05Z\",\"turn, right\"\n2026-01-01T01:00:00Z,start"
        val ride = TelemetryTrack.read(csv.toByteArray(), RideFormat.CSV)
        val clip = Instant.parse("2026-01-01T01:00:03Z")
        assertEquals(2, ride.points)
        assertEquals(0L..2000L, ride.overlap(clip, 5000, 0))
        assertEquals(1000L..5000L, ride.overlap(clip, 5000, 4000))
        assertNull(ride.overlap(clip, 5000, -3000))
    }

    @Test fun jsonReadsEpochSecondsAndMillisecondsAndDoesNotGuessLocalTime() {
        val json = """{"samples":[{"time":"2026-01-01T01:00:00"},{"time":1767225600},{"timestamp_ms":1767225605000}]}"""
        val ride = TelemetryTrack.read(json.toByteArray(), RideFormat.JSON)
        assertEquals(2, ride.points)
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), ride.first)
        assertEquals(Instant.parse("2026-01-01T00:00:05Z"), ride.last)
    }

    @Test fun captureTimeUsesExplicitOffsetBeforeContainerMetadata() {
        val receipt = ImportHistoryReceipt(1, null, "video/mp4", false, captureDate = "2026-01-02",
            captureTime = "08:00:00", captureOffset = "+05:30")
        assertEquals(Instant.parse("2026-01-02T02:30:00Z"), TelemetryTrack.captureStart(receipt, "20260102T030000.000Z"))
        assertEquals(Instant.parse("2026-01-02T03:00:00Z"), TelemetryTrack.captureStart(receipt.copy(captureOffset = null), "20260102T030000.000Z"))
        assertNull(TelemetryTrack.captureStart(receipt.copy(captureOffset = null), null))
    }
}
