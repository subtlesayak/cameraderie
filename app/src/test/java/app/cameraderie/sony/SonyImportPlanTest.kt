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
    @Test fun removesTheSonyTransportMarkerFromCameraBasenames() {
        assertEquals("DSC01234.JPG", SonyImportPlan.representationName("ORG_DSC01234.JPG", "Photo", "image/jpeg"))
        assertEquals("C0044.MP4", SonyImportPlan.representationName("ORG_C0044.MP4", "C0044.MP4", "video/mp4"))
        assertEquals("ORG_Field.JPG", SonyImportPlan.representationName("ORG_Field.JPG", "ORG_Field.JPG", "image/jpeg"))
        assertEquals("ORG_DSC01234.JPG", SonyImportPlan.file("ORG_DSC01234.JPG").name)
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
