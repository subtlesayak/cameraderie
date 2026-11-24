package app.cameraderie.sony

import org.junit.Assert.*
import org.junit.Test

class SonyCatalogScannerTest {
    private fun folder(id:String)=SonyCatalogScanner.Entry(id,id,true,0,false,null)
    private fun file(id:String,video:Boolean=false)=SonyCatalogScanner.Entry(id,id,false,10,video,null)
    @Test fun loadsEveryPageAndPhotoVideoFoldersBeforeReturning() {
        val calls=mutableListOf<Pair<String,Int>>()
        val files=SonyCatalogScanner().scan({ parent,start,_ ->
            calls.add(parent to start)
            when(parent) {
                "0" -> SonyCatalogScanner.Page(listOf(folder("ImageRoot"),folder("VideoRoot")),2)
                "ImageRoot" -> SonyCatalogScanner.Page((start until minOf(start+40,41)).map { file("photo$it") },41)
                else -> SonyCatalogScanner.Page(listOf(file("movie",true)),1)
            }
        },{false})
        assertEquals(42,files.size);assertTrue(files.any { it.video });assertTrue(("ImageRoot" to 40) in calls)
    }
    @Test fun sameObjectInDifferentCollectionsIsDeduplicated() {
        val entries=SonyCatalogScanner().scan({ parent,_,_ -> SonyCatalogScanner.Page(if(parent=="0") listOf(folder("a"),folder("b")) else listOf(file("same"))) },{false})
        assertEquals(1,entries.size)
    }
    @Test fun folderCyclesDoNotLoop() {
        var calls=0;SonyCatalogScanner().scan({ _,_,_ -> calls++;SonyCatalogScanner.Page(listOf(folder("0"))) },{false});assertEquals(1,calls)
    }
    @Test fun repeatedPagesAndChangedCountsFailInsteadOfClaimingComplete() {
        try { SonyCatalogScanner().scan({ _,_,_ -> SonyCatalogScanner.Page((0 until 40).map { file("$it") }) },{false});fail() } catch(_:IllegalArgumentException) { }
        try { SonyCatalogScanner().scan({ _,start,_ -> SonyCatalogScanner.Page(if(start==0) listOf(file("a")) else emptyList(),2) },{false});fail() } catch(_:IllegalArgumentException) { }
    }
    @Test fun cancellationCannotPublishPartialCatalogue() {
        var stopped=false
        try { SonyCatalogScanner().scan({ _,_,_ -> stopped=true;SonyCatalogScanner.Page(listOf(file("a"))) },{stopped});fail() } catch(_:java.io.InterruptedIOException) { }
    }
    @Test fun conflictingMetadataAndChangedUpdateIdsAreRejected() {
        try { SonyCatalogScanner().scan({ _,start,_ -> SonyCatalogScanner.Page(listOf(file("a")),2,if(start==0) "1" else "2") },{false});fail() } catch(_:IllegalArgumentException) { }
    }
}
