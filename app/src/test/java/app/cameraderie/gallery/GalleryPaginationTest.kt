package app.cameraderie.gallery

import app.cameraderie.gallery.protocol.*
import org.junit.Assert.*
import org.junit.Test

class GalleryPaginationTest {
    private val sd=GalleryCameraEvidence.Store.SD
    private val internal=GalleryCameraEvidence.Store.INTERNAL
    private fun media(handle: Long,store: GalleryCameraEvidence.Store=sd,path: String="DCIM/CUSTOM/FILE_$handle.MP4",video: Boolean=true)=
        GalleryMediaPage.Media(GalleryMediaPage.Key(store,path),"MISC/THM/CUSTOM/FILE.scr",handle,100,if(video)3 else 0,if(video)5 else null)
    private fun page(vararg entries: GalleryMediaPage.Media,last: Boolean=false,store: GalleryCameraEvidence.Store=sd)=GalleryMediaPage.Page(store,entries.toList(),last,entries.size)
    @Test fun capturedListQueryBytesAndCursorRoundTripArePinned() {
        val bytes=GalleryListQuery.page(1,sd)
        assertEquals("4a002a10010000000000010000002d000d0100ffffffffffffffff000100000000000000000000000000",bytes.joinToString(""){"%02x".format(it)})
        assertArrayEquals(byteArrayOf(0xC0.toByte(),0x36,0x10,0x40),GalleryListQuery.page(2,internal,0x401036C0).copyOfRange(10,14))
        assertEquals(2,GalleryListQuery.trigger(2)[4].toInt())
    }
    @Test fun overlappingBoundaryDeduplicatesWithinStoreAndAdvancesToOlderVideo() {
        val p=GalleryPagination(sd);p.accept(page(media(300),media(200)));assertEquals(200L,p.cursor)
        val delta=p.accept(page(media(200),media(100),last=true));assertEquals(1,delta.size);assertEquals(3,p.size);assertTrue(p.finished)
    }
    @Test fun identicalPathsInDifferentStoresRemainDifferentMediaKeys() {
        val path="DCIM/CUSTOM/SAME.MP4";val a=media(100,sd,path);val b=media(0x40000064,internal,path)
        val left=GalleryPagination(sd);val right=GalleryPagination(internal)
        left.accept(page(a,last=true));right.accept(page(b,last=true,store=internal))
        assertEquals(2,(left.entries()+right.entries()).map { it.key }.distinct().size)
    }
    @Test fun changedOverlappingMediaRejectsWholePageWithoutCommittingItsOtherFiles() {
        val p=GalleryPagination(sd);p.accept(page(media(300),media(200)));val before=p.entries()
        try { p.accept(page(media(200).copy(size=999),media(100),last=true));fail() }
        catch(error: GalleryPagination.Invalid) { assertEquals(GalleryPagination.Error.CHANGED_MEDIA,error.error) }
        assertEquals(before,p.entries());assertEquals(200L,p.cursor);assertFalse(p.finished)
    }
    @Test fun repeatedNewestPageCannotSilentlyClaimEndOrLoopForever() {
        val p=GalleryPagination(sd);val value=page(media(300),media(200));p.accept(value)
        try { p.accept(value);fail() } catch(error: GalleryPagination.Invalid) { assertEquals(GalleryPagination.Error.CURSOR_STALLED,error.error) }
        assertEquals(2,p.size);assertFalse(p.finished)
    }
    @Test fun photoOnlyPagesAdvanceWithinTheirStorageInsteadOfStoppingAtFirstPage() {
        val p=GalleryPagination(sd);p.accept(page(media(200,video=false),media(100,video=false)))
        assertEquals(100L,p.cursor);assertEquals(2,p.size);assertFalse(p.finished)
        p.accept(page(media(100,video=false),media(50,video=false),last=true));assertTrue(p.finished);assertEquals(3,p.size)
    }
    @Test fun wrongStoreAndResourceLimitNeverAdvanceCursor() {
        val p=GalleryPagination(sd,1)
        try { p.accept(page(media(100,internal),last=true,store=internal));fail() } catch(_: GalleryPagination.Invalid) {}
        try { p.accept(page(media(300),media(200),last=true));fail() }
        catch(error: GalleryPagination.Invalid) { assertEquals(GalleryPagination.Error.LIMIT,error.error) }
        assertEquals(0,p.size);assertEquals(sd.newestCursor,p.cursor)
    }
    @Test fun olderPerStoreCursorDoesNotRequireAHandleNamespaceBit() {
        assertArrayEquals(byteArrayOf(0x80.toByte(),0,0x10,0),GalleryListQuery.page(3,internal,0x00100080).copyOfRange(10,14))
        assertArrayEquals(byteArrayOf(0x80.toByte(),0,0x10,0x40),GalleryListQuery.page(4,sd,0x40100080).copyOfRange(10,14))
        assertArrayEquals(byteArrayOf(-1,-1,-1,-1),GalleryListQuery.page(5,internal,0xFFFFFFFFL).copyOfRange(10,14))
    }
}
