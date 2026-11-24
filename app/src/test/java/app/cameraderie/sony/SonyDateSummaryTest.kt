package app.cameraderie.sony

import org.junit.Assert.*
import org.junit.Test

class SonyDateSummaryTest {
    private fun item(id:String,time:String?=null,video:Boolean=false)=SonyDateSummary.Item(id,time?.let { CameraCaptureTime.parse(it) },video)
    @Test fun allCategoriesAndUnknownDatesReconcileFromTheSameUniverse() {
        val summary=SonyDateSummary.summarize(listOf(item("a","2026-06-10T09:00:00"),item("b","2026-06-10T12:00:00",true),item("c","2026-06-11"),item("u"),item("v",video=true)),true)
        assertEquals(SonyDateSummary.Counts(5,3,2),summary.counts)
        for(scope in SonyDateSummary.Scope.entries) {
            assertEquals(summary.counts.inScope(scope),summary.datedCounts(scope).values.sum()+summary.unknown(scope))
        }
        assertNull(summary.groups.last().date)
    }
    @Test fun dateOnlyDoesNotInventAShootTimeAndUnknownDoesNotInventADate() {
        val source=item("date","2026-06-10");val summary=SonyDateSummary.summarize(listOf(source,item("unknown")),true)
        assertNull(source.capture!!.wallTime)
        assertEquals(1,summary.unknown(SonyDateSummary.Scope.PHOTOS))
        assertEquals(1,summary.datedCounts(SonyDateSummary.Scope.PHOTOS).size)
    }
    @Test fun duplicatePageMetadataCannotDoubleCount() {
        val source=item("same","2026-06-10T09:00:00")
        assertEquals(1,SonyDateSummary.summarize(listOf(source,source),true).counts.all)
    }
    @Test(expected=IllegalArgumentException::class) fun changedIdentityCannotSilentlyCountAsAnotherFile() {
        SonyDateSummary.summarize(listOf(item("same"),item("same",video=true)),true)
    }
    @Test fun incompleteSnapshotsStayProvisionalWhileCountsRemainConsistent() {
        val result=SonyDateSummary.summarize(listOf(item("known","2026-06-10"),item("unknown")),false)
        assertFalse(result.complete);assertEquals(2,result.counts.all)
        assertEquals(2,result.groups.sumOf { it.counts.all })
    }
}
