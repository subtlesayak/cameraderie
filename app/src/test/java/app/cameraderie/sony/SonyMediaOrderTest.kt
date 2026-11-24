package app.cameraderie.sony

import org.junit.Assert.*
import org.junit.Test

class SonyMediaOrderTest {
    private data class Item(val id:String,val name:String,val capture:CameraCaptureTime?)
    private fun item(id:String,date:String?=null,name:String=id)=Item(id,name,CameraCaptureTime.parse(date))
    private fun ordered(vararg items:Item)=SonyMediaOrder.newestFirst(items.toList(),{it.capture},{it.name},{it.id}).map { it.id }
    @Test fun newestCaptureWinsOverFilenameAndArrivalOrder() {
        assertEquals(listOf("B","Z","A"),ordered(item("A","2026-10-31T12:00:00"),item("Z","2026-10-31T13:00:00"),item("B","2026-11-01T09:00:00")))
    }
    @Test fun unknownDatesUseDescendingNamesAfterKnownDates() {
        assertEquals(listOf("known","last","first"),ordered(item("first",name="C0001.MP4"),item("known","2026-01-01"),item("last",name="C0010.MP4")))
    }
    @Test fun dateOnlyDoesNotInventTimeAndEqualNamesUseIdentity() {
        assertEquals(listOf("timed","z","a"),ordered(item("a","2026-11-08",name="same"),item("z","2026-11-08",name="same"),item("timed","2026-11-08T00:00:00")))
    }
    @Test fun repeatedProgressSnapshotsKeepDeterministicOrder() {
        val old=item("old","2026-01-01T10:00:00");val recent=item("recent","2026-11-08T10:00:00")
        assertEquals(ordered(old,recent),ordered(recent,old));assertEquals(listOf("recent","old"),ordered(old,recent))
    }
}
