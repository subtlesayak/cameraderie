package app.cameraderie.sony
import org.junit.Assert.*
import org.junit.Test
class SonyShootSummaryTest {
    @Test fun readableTimeAndFilterNeverExposeInternalHash() {
        val shoot=ShootSessions().group("synthetic",listOf(ShootSessions.Item("one",CameraCaptureTime.parse("2026-11-08T09:15:00")),ShootSessions.Item("two",CameraCaptureTime.parse("2026-11-08T10:30:00"))),true).single()
        assertEquals("09:15–10:30",SonyShootSummary.time(shoot));assertEquals("8 Nov · 09:15",SonyShootSummary.filter(shoot))
        assertFalse(SonyShootSummary.filter(shoot).contains(shoot.id))
    }
    @Test fun unknownTimesStayExplicit() {
        val shoot=ShootSessions().group("synthetic",listOf(ShootSessions.Item("one",CameraCaptureTime.parse("2026-11-08"))),true).single()
        assertEquals("Capture time unavailable",SonyShootSummary.time(shoot));assertTrue(SonyShootSummary.filter(shoot).contains("Time unknown"));assertFalse(shoot.selectableAsShoot)
    }
}
