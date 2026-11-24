package app.cameraderie.sony

import org.junit.Assert.*
import org.junit.Test

class SonyImportSizePolicyTest {
    @Test fun reducedCameraJpegCannotBeClaimedAsOriginal() {
        val reduced=original.copy(width=1616,height=1080,reduced=true)
        assertTrue(SonyImportSizePolicy.choose(SonyImportSize.ORIGINAL_JPEG,listOf(reduced)) is SonyImportSizePolicy.Plan.Unavailable)
        assertTrue(SonyImportSizePolicy.choose(SonyImportSize.TWO_MEGAPIXEL_JPEG,listOf(reduced)) is SonyImportSizePolicy.Plan.CameraFile)
    }
    private val original=SonyImportSizePolicy.Resource("jpg","photo.JPG","image/jpeg",9_000_000,6000,4000)
    private val raw=SonyImportSizePolicy.Resource("arw","photo.ARW","image/x-sony-arw",25_000_000,6000,4000)
    @Test fun originalJpegAndRawAreSeparateExactResources() {
        assertEquals(original,(SonyImportSizePolicy.choose(SonyImportSize.ORIGINAL_JPEG,listOf(raw,original)) as SonyImportSizePolicy.Plan.CameraFile).resource)
        assertEquals(raw,(SonyImportSizePolicy.choose(SonyImportSize.ORIGINAL_RAW,listOf(raw,original)) as SonyImportSizePolicy.Plan.CameraFile).resource)
    }
    @Test fun jpegNameOrParametersCannotInventRaw() {
        val jpeg=original.copy(name="photo.ARW",mime="image/jpeg; filename=photo.arw")
        assertTrue(SonyImportSizePolicy.choose(SonyImportSize.ORIGINAL_RAW,listOf(jpeg)) is SonyImportSizePolicy.Plan.Unavailable)
    }
    @Test fun rawCannotBeResizedOrUsedAsJpegFallback() {
        for(size in listOf(SonyImportSize.ORIGINAL_JPEG,SonyImportSize.TWO_MEGAPIXEL_JPEG,SonyImportSize.VGA_JPEG))
            assertTrue(SonyImportSizePolicy.choose(size,listOf(raw)) is SonyImportSizePolicy.Plan.Unavailable)
    }
    @Test fun knownCamera2MResourceAvoidsFullOriginalTransfer() {
        val small=original.copy(id="2m",bytes=500000,width=1616,height=1080)
        assertEquals(small,(SonyImportSizePolicy.choose(SonyImportSize.TWO_MEGAPIXEL_JPEG,listOf(original,small)) as SonyImportSizePolicy.Plan.CameraFile).resource)
    }
    @Test fun thumbnailIsNeverAnOriginalOrReducedImport() {
        val thumb=original.copy(bytes=10000,width=640,height=426,thumbnail=true)
        assertTrue(SonyImportSizePolicy.choose(SonyImportSize.ORIGINAL_JPEG,listOf(thumb)) is SonyImportSizePolicy.Plan.Unavailable)
        assertTrue(SonyImportSizePolicy.choose(SonyImportSize.VGA_JPEG,listOf(original,thumb)) is SonyImportSizePolicy.Plan.LocalJpeg)
    }
    @Test fun localFallbackIsExplicitAndBoundedWithoutUpscaling() {
        val plan=SonyImportSizePolicy.choose(SonyImportSize.TWO_MEGAPIXEL_JPEG,listOf(original)) as SonyImportSizePolicy.Plan.LocalJpeg
        assertTrue(plan.output!!.pixels<=2_000_000)
        assertEquals(original,plan.source)
        val tiny=SonyImportSizePolicy.Bounds(160,100)
        assertEquals(tiny,SonyImportSizePolicy.outputBounds(SonyImportSize.TWO_MEGAPIXEL_JPEG,tiny))
        assertEquals(tiny,SonyImportSizePolicy.outputBounds(SonyImportSize.VGA_JPEG,tiny))
    }
    @Test fun vgaUsesCorrectPortraitAndLandscapeBounds() {
        val wide=SonyImportSizePolicy.outputBounds(SonyImportSize.VGA_JPEG,SonyImportSizePolicy.Bounds(6000,4000))
        val tall=SonyImportSizePolicy.outputBounds(SonyImportSize.VGA_JPEG,SonyImportSizePolicy.Bounds(4000,6000))
        assertEquals(640,wide.width);assertTrue(wide.height<=480)
        assertEquals(640,tall.height);assertTrue(tall.width<=480)
    }
    @Test fun unknownDimensionsRequireDecodeRatherThanGuessedSize() {
        val plan=SonyImportSizePolicy.choose(SonyImportSize.VGA_JPEG,listOf(original.copy(width=null,height=null))) as SonyImportSizePolicy.Plan.LocalJpeg
        assertNull(plan.output)
    }
    @Test fun wrongAspectCameraResourceDoesNotReplaceRequestedCopy() {
        val square=original.copy(id="square",bytes=100000,width=1300,height=1300)
        assertTrue(SonyImportSizePolicy.choose(SonyImportSize.TWO_MEGAPIXEL_JPEG,listOf(original,square)) is SonyImportSizePolicy.Plan.LocalJpeg)
    }
}
