package app.cameraderie.gallery.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumMediaFileTest {
    @Test fun classifiesCameraRawAndVideoOfferedAsDocuments() {
        assertEquals("image/x-adobe-dng", AlbumMediaFile.document("IMG.DNG")?.mime)
        assertEquals("image/x-canon-cr3", AlbumMediaFile.document("IMG.CR3")?.mime)
        assertEquals("image/x-nikon-nef", AlbumMediaFile.document("IMG.NEF")?.mime)
        assertEquals("image/x-fuji-raf", AlbumMediaFile.document("IMG.RAF")?.mime)
        assertEquals("image/x-sony-arw", AlbumMediaFile.document("IMG.ARW")?.mime)
        assertEquals("image/heic", AlbumMediaFile.document("IMG.HEIC")?.mime)
        assertTrue(AlbumMediaFile.document("Clip.MOV")!!.video)
    }

    @Test fun keepsTheOriginalNameWithoutRewritingUnsafeNames() {
        assertEquals("Café shoot.JpG", AlbumMediaFile.document("Café shoot.JpG")?.name)
        assertNull(AlbumMediaFile.document("a".repeat(200) + ".JPG"))
        assertNull(AlbumMediaFile.document("folder\\Test.NEF"))
        assertNull(AlbumMediaFile.document("Test\nphoto.NEF"))
    }

    @Test fun rejectsNonMediaAndNamesWithNoStem() {
        assertNull(AlbumMediaFile.document("notes.txt"))
        assertNull(AlbumMediaFile.document(".jpg"))
        assertNull(AlbumMediaFile.document("photo.jpg.exe"))
    }
}
