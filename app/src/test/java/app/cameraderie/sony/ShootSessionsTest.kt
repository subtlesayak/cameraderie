package app.cameraderie.sony

import org.junit.Assert.*
import org.junit.Test

class ShootSessionsTest {
    private fun item(id:String,time:String?)=ShootSessions.Item(id,CameraCaptureTime.parse(time))
    @Test fun twoHourGapSeparatesSameDayShoots() {
        val groups=ShootSessions().group("camera",listOf(item("a","2026-11-08T09:00:00"),item("b","2026-11-08T10:00:00"),item("c","2026-11-08T12:00:00")),true)
        assertEquals(2,groups.size);assertEquals(setOf(listOf("a","b"),listOf("c")),groups.map { it.itemIds }.toSet())
    }
    @Test fun underTwoHoursIsOneShootAndDayBoundaryAlwaysSplits() {
        assertEquals(1,ShootSessions().group("camera",listOf(item("a","2026-11-08T09:00:00"),item("b","2026-11-08T10:59:59")),true).size)
        assertEquals(2,ShootSessions().group("camera",listOf(item("a","2026-11-08T23:59:00"),item("b","2026-11-09T00:01:00")),true).size)
    }
    @Test fun dateOnlyAndInvalidTimestampsCannotSelectWholeShoot() {
        val groups=ShootSessions().group("camera",listOf(item("a","2026-11-08"),item("b","invalid")),true)
        assertEquals(2,groups.size);assertTrue(groups.none { it.selectableAsShoot })
    }
    @Test fun incompletePaginationDoesNotPretendShootBoundariesAreConfirmed() {
        assertFalse(ShootSessions().group("camera",listOf(item("a","2026-11-08T09:00:00")),false).single().selectableAsShoot)
    }
    @Test fun identifiersAreTimestampBasedDeterministicAndCameraScoped() {
        val items=listOf(item("a","2026-11-08T09:00:00"),item("b","2026-11-08T10:00:00"))
        val first=ShootSessions().group("camera",items,true).single().id
        assertTrue(first.startsWith("2026-11-08_090000-"))
        assertEquals(first,ShootSessions().group("camera",items.reversed(),true).single().id)
        assertNotEquals(first,ShootSessions().group("other-camera",items,true).single().id)
    }
    @Test fun duplicatePagesDeduplicateButConflictingMetadataFails() {
        val same=item("a","2026-11-08T09:00:00")
        assertEquals(1,ShootSessions().group("camera",listOf(same,same),true).single().itemIds.size)
        try { ShootSessions().group("camera",listOf(same,item("a","2026-11-08T10:00:00")),true);fail() } catch(_:IllegalArgumentException) { }
    }
    @Test fun parsesCameraPtpAndIsoTimesWithoutInventingZoneOrTime() {
        assertEquals(CameraCaptureTime.parse("2026-11-08T09:15:30"),CameraCaptureTime.parse("20261108T091530"))
        assertEquals(CameraCaptureTime.parse("2026-11-08T09:15:30Z"),CameraCaptureTime.parse("20261108T091530Z"))
        assertEquals(CameraCaptureTime.parse("2026-11-08T09:15:30"),CameraCaptureTime.parse("2026:11:08 09:15:30"))
        assertNull(CameraCaptureTime.parse("2026-11-08")!!.time)
        assertEquals("+05:30",CameraCaptureTime.parse("2026-11-08T09:15:30+05:30")!!.offset.toString())
        assertNull(CameraCaptureTime.parse("2026-02-30T09:00:00"));assertNull(CameraCaptureTime.parse("0000-01-01T00:00:00"))
    }
    @Test fun timezoneChangesNeverMergeAmbiguousShoots() {
        assertEquals(2,ShootSessions().group("camera",listOf(item("a","2026-11-08T09:00:00Z"),item("b","2026-11-08T09:01:00+01:00")),true).size)
    }
}
