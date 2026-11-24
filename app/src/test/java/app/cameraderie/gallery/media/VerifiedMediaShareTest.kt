package app.cameraderie.gallery.media

import org.junit.Assert.*
import org.junit.Test

class VerifiedMediaShareTest {
    private fun item(id:Int=1,mime:String="image/jpeg",pending:Boolean=false,owned:Boolean=true)=VerifiedMediaShare.Item("content://media/external_primary/images/media/$id",mime,100,pending,owned)
    @Test fun singleAndMultiplePlansUsePublishedContentUris() {
        assertFalse(VerifiedMediaShare.plan(listOf(item())).multiple)
        val multiple=VerifiedMediaShare.plan(listOf(item(),item(2)))
        assertTrue(multiple.multiple);assertEquals("image/jpeg",multiple.mime);assertEquals(2,multiple.uris.size)
    }
    @Test fun mixedImageFormatsUseImageWildcardAndMixedMediaUsesAny() {
        assertEquals("image/*",VerifiedMediaShare.plan(listOf(item(),item(2,"image/x-sony-arw"))).mime)
        assertEquals("*/*",VerifiedMediaShare.plan(listOf(item(),item(2,"video/mp4"))).mime)
    }
    @Test fun rejectsPendingUnownedAndNonMediaNetworkUris() {
        for(bad in listOf(item(pending=true),item(owned=false),item().copy(uri="http://192.168.122.1/file.JPG"),item().copy(uri="file:///private/photo.JPG"),item().copy(uri="content://media@evil/external_primary/images/media/1"),item().copy(uri="content://media/external_primary/images/media/1?token=secret"))) {
            try { VerifiedMediaShare.plan(listOf(bad));fail() } catch(_:IllegalArgumentException) { }
        }
    }
    @Test fun duplicateUriCannotCreateDuplicateShareAttachment() {
        assertEquals(1,VerifiedMediaShare.plan(listOf(item(),item())).uris.size)
    }
    @Test fun conflictingMetadataForSameUriIsNotShared() {
        try { VerifiedMediaShare.plan(listOf(item(),item().copy(bytes=200)));fail() } catch(_:IllegalArgumentException) { }
    }
}
