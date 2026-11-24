package app.cameraderie.gallery.media

import app.cameraderie.gallery.protocol.GalleryCameraEvidence.Store
import app.cameraderie.gallery.protocol.GalleryMediaPage
import org.junit.Assert.*
import org.junit.Test

class GalleryDeleteTargetTest {
    private val files=javaClass.getResourceAsStream("/gallery-fixtures/oa6_sd_3.bin")!!.use {
        GalleryMediaPage.decode(it.readBytes(),Store.SD).entries
    }
    private val photo=files.first()
    private val others=files.drop(1)

    @Test fun capturedSingleFileNeedsTwoIndependentWitnessesAndAUniqueHandle() {
        assertNotNull(GalleryDeleteTarget.from(photo,files))
        assertNull(GalleryDeleteTarget.from(photo,files.take(2)))
        val otherStore=others.first().copy(key=others.first().key.copy(store=Store.INTERNAL),handle=photo.handle)
        assertNull(GalleryDeleteTarget.from(photo,files+otherStore))
        val wrongHandle=photo.copy(handle=photo.handle+64)
        assertNull(GalleryDeleteTarget.from(wrongHandle,listOf(wrongHandle)+others))
        for(unsuitable in listOf(photo.copy(modifiedStamp=null),photo.copy(singleFileRecord=false),
            photo.copy(key=photo.key.copy(path=photo.key.path.replace("_D.", "_D_001."))))) {
            assertNull(GalleryDeleteTarget.from(unsuitable,listOf(unsuitable)+others))
        }
    }

    @Test fun deletionReadbackDistinguishesAbsentFromMissingEvidenceOrReplacedFiles() {
        val target=GalleryDeleteTarget.from(photo,files)!!
        assertEquals(GalleryDeleteTarget.Readback.ABSENT,target.verify(others))
        assertEquals(GalleryDeleteTarget.Readback.STILL_LISTED,target.verify(others+photo.copy(favourite=false)))
        assertEquals(GalleryDeleteTarget.Readback.UNVERIFIED,target.verify(emptyList()))
        assertEquals(GalleryDeleteTarget.Readback.UNVERIFIED,target.verify(others+photo.copy(size=photo.size+1)))
        assertEquals(GalleryDeleteTarget.Readback.UNVERIFIED,target.verify(others+photo.copy(key=photo.key.copy(path="DCIM/OTHER/NEW.JPG"))))
        assertEquals(GalleryDeleteTarget.Readback.UNVERIFIED,target.verify(others.map { it.copy(modifiedStamp=9L) }))
    }
}
