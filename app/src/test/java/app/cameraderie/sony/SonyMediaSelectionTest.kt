package app.cameraderie.sony

import org.junit.Assert.*
import org.junit.Test

class SonyMediaSelectionTest {
    private fun item(id:String,folder:Boolean=false,downloadable:Boolean=true,size:Long=10)=SonyMediaSelection.Item(id,"$id.JPG",size,folder,downloadable)
    @Test fun togglesIndependentItemsAndKeepsSelectionOrder() {
        val s=SonyMediaSelection();s.toggle(item("a"));s.toggle(item("b"));assertEquals(listOf("a","b"),s.keys())
        s.toggle(item("a"));assertEquals(listOf("b"),s.keys())
    }
    @Test fun neverSelectsFolderOrUnknownOriginal() {
        val s=SonyMediaSelection();s.selectAll(listOf(item("folder",true),item("unknown",downloadable=false),item("zero",size=0),item("file")))
        assertEquals(listOf("file"),s.keys())
    }
    @Test fun boundsSelectAllAndDeduplicatesLoadedPages() {
        val s=SonyMediaSelection(2);s.selectAll(listOf(item("a"),item("a"),item("b"),item("c")));assertEquals(listOf("a","b"),s.keys())
    }
    @Test fun changedMetadataCannotSilentlyKeepOldSelection() {
        val s=SonyMediaSelection();s.toggle(item("a"));s.retain(listOf(item("a",size=11)));assertTrue(s.keys().isEmpty())
    }
    @Test fun paginationRetainsExistingSelectionAndFolderChangeClearsIt() {
        val s=SonyMediaSelection();s.toggle(item("a"));s.retain(listOf(item("a"),item("b")));assertTrue(s.contains("a"))
        s.retain(emptyList());assertTrue(s.keys().isEmpty())
    }
    @Test fun completedImportsRemoveOnlyTheirOwnSelection() {
        val s=SonyMediaSelection();s.selectAll(listOf(item("a"),item("b")));s.remove("a");assertEquals(listOf("b"),s.keys());s.clear();assertTrue(s.keys().isEmpty())
    }
}
