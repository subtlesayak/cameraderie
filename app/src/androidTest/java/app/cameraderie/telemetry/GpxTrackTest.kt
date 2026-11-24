package app.cameraderie.telemetry

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class GpxTrackTest {
    @Test fun readsTrackTimesWithoutPickingUpMetadataOrWaypointTimes() {
        val source = """<gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1">
            <metadata><time>2020-01-01T00:00:00Z</time></metadata>
            <wpt lat="0" lon="0"><time>2020-01-02T00:00:00Z</time></wpt>
            <trk><trkseg><trkpt lat="0" lon="0"><time>2026-01-01T00:00:00Z</time></trkpt>
            <trkpt lat="0" lon="0"><time>2026-01-01T00:00:02Z</time></trkpt></trkseg></trk></gpx>"""
        val timeline = TelemetryTrack.read(source.toByteArray(), RideFormat.GPX)
        assertEquals(2, timeline.points)
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), timeline.first)
        assertEquals(Instant.parse("2026-01-01T00:00:02Z"), timeline.last)
    }

    @Test fun rejectsExternalEntityDeclarations() {
        val source = """<!DOCTYPE gpx [<!ENTITY remote SYSTEM "file:///test.gpx">]><gpx>&remote;</gpx>"""
        assertTrue(runCatching { TelemetryTrack.read(source.toByteArray(), RideFormat.GPX) }.isFailure)
    }
}
