package app.cameraderie.sony

import org.junit.Assert.*
import org.junit.Test

class SonyImportPlanTest {
    @Test fun allImportsUseOneFixedAlbumAndActualRepresentationMime() {
        assertEquals("DCIM/Cameraderie/",SonyImportPlan.ALBUM)
        assertEquals("image/jpeg",SonyImportPlan.file("PHOTO.JPG").mime)
        assertTrue(SonyImportPlan.file("PHOTO.ARW").raw)
        assertTrue(SonyImportPlan.file("CLIP.MP4").video)
    }
    @Test fun remotePathsCannotChangeDestinationFolder() {
        assertEquals("PHOTO.JPG",SonyImportPlan.file("../../other/PHOTO.JPG").name)
        assertEquals("PHOTO.JPG",SonyImportPlan.file("other\\PHOTO.JPG").name)
    }
    @Test fun unknownOrMissingExtensionsAreNotPublishedAsPhotos() {
        for(name in listOf("PHOTO.bin","no-extension","")) {
            try { SonyImportPlan.file(name);fail() } catch(_:IllegalArgumentException) { }
        }
    }
}
