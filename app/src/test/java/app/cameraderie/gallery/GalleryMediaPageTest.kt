package app.cameraderie.gallery

import app.cameraderie.gallery.protocol.*
import app.cameraderie.gallery.media.GalleryOriginalImporter
import org.junit.Assert.*
import org.junit.Test

class GalleryMediaPageTest {
    private fun fixture(name: String="oa6_sd_3.bin")=javaClass.classLoader!!.getResourceAsStream("gallery-fixtures/$name")!!.use { it.readBytes() }
    private val sd=GalleryCameraEvidence.Store.SD
    private fun invalid(bytes: ByteArray,error: GalleryMediaPage.Error,store: GalleryCameraEvidence.Store=sd) {
        try { GalleryMediaPage.decode(bytes,store);fail("Expected a rejected page") }
        catch(failure: GalleryMediaPage.Invalid) { assertEquals(error,failure.error) }
    }
    @Test fun externalAction6SdCaptureMatchesKnownFilesAndOwnRecordMetadata() {
        val page=GalleryMediaPage.decode(fixture(),sd)
        assertEquals(3,page.entries.size);assertTrue(page.last);assertEquals(3,page.declared)
        assertEquals(listOf(0x1000C0L,0x100080L,0x100040L),page.entries.map { it.handle })
        assertEquals(listOf(2408448L,127391571L,42051246L),page.entries.map { it.size })
        assertEquals(listOf(null,10,9),page.entries.map { it.durationSeconds })
        assertEquals(listOf(true,false,false),page.entries.map { it.favourite })
        assertTrue(page.entries.first().name.endsWith("0003_D.JPG"))
        assertTrue(page.entries.last().key.path.endsWith("0001_D.MP4"))
        assertTrue(page.entries.all { it.thumbnailPath.startsWith("MISC/THM/") && it.thumbnailPath.endsWith(".scr") })
    }
    @Test fun externalInternalCaptureKeepsSeparateStorageAndKnownSizes() {
        val page=GalleryMediaPage.decode(fixture("oa6_internal_2.bin"),GalleryCameraEvidence.Store.INTERNAL)
        assertEquals(listOf(0x40100080L,0x40100040L),page.entries.map { it.handle })
        assertEquals(listOf(2834432L,39513171L),page.entries.map { it.size });assertTrue(page.last)
        assertTrue(page.entries.all { it.key.store==GalleryCameraEvidence.Store.INTERNAL })
        assertEquals(listOf(false,true),page.entries.map { it.favourite })
    }
    @Test fun unknownFavouriteFlagDoesNotBecomeAFalseValueOrChangeFileIdentity() {
        val original=GalleryMediaPage.decode(fixture(),sd).entries.first()
        val bytes=fixture().apply { this[34]=2 }
        val changed=GalleryMediaPage.decode(bytes,sd).entries.first()
        assertNull(changed.favourite)
        assertTrue(original.sameFile(changed))
        assertFalse(original.sameFile(changed.copy(handle=changed.handle+64)))
    }
    @Test fun aVideoWithUnreportedSizeRemainsInThePageButCannotBeImported() {
        val bytes=fixture()
        val markers=(22 until bytes.lastIndex).filter {
            bytes[it]==0x19.toByte() && bytes[it+1]==6.toByte() &&
                (bytes[it-2].toInt() and 255) in setOf(0,3,4) &&
                (bytes[it-1].toInt() and 255) in setOf(0xFF,0xFE,0xF6,0xC7,0xE5,0xE7,0xF2)
        }
        assertEquals(3,markers.size)
        val movieMarker=markers[1]
        bytes.fill(0,movieMarker-14,movieMarker-10)
        val page=GalleryMediaPage.decode(bytes,sd)
        assertTrue(page.last);assertEquals(3,page.entries.size)
        val movie=page.entries[1]
        assertTrue(movie.video);assertEquals(0L,movie.size);assertEquals(0x100080L,movie.handle)
        assertFalse(GalleryOriginalImporter.supports(movie))
        assertTrue(GalleryOriginalImporter.supports(page.entries[0]))
        assertTrue(GalleryOriginalImporter.supports(page.entries[2]))
        invalid(bytes.copyOf().apply { fill(0,movieMarker-10,movieMarker-6) },GalleryMediaPage.Error.UNSUPPORTED_LAYOUT)
        val photoMarker=markers[0]
        invalid(bytes.copyOf().apply { fill(0,photoMarker-14,photoMarker-10) },GalleryMediaPage.Error.UNSUPPORTED_LAYOUT)
    }
    @Test fun cameraReportedCountMismatchCannotBecomeACompletePage() {
        val bytes=fixture();bytes[0]=4;invalid(bytes,GalleryMediaPage.Error.INCOMPLETE)
        bytes[0]=0;assertEquals(3,GalleryMediaPage.decode(bytes,sd).entries.size)
    }
    @Test fun cutOldestRecordOrThumbnailCannotBecomeAnEmptyOrShortCompletePage() {
        invalid(fixture().copyOf(835),GalleryMediaPage.Error.UNSUPPORTED_LAYOUT)
        invalid(fixture().copyOf(915),GalleryMediaPage.Error.INCOMPLETE)
    }
    @Test fun shortPageWithoutActualLastFileTagRemainsIncomplete() {
        val bytes=fixture();bytes[802]=0
        invalid(bytes,GalleryMediaPage.Error.INCOMPLETE)
        assertFalse(GalleryMediaPage.decode(bytes,sd,3).last)
    }
    @Test fun echoedRequestStoreIsAuthoritativeInsteadOfHandleNamespace() {
        val internal=GalleryMediaPage.decode(fixture(),GalleryCameraEvidence.Store.INTERNAL)
        assertTrue(internal.entries.all { it.key.store==GalleryCameraEvidence.Store.INTERNAL })
        val sdPage=GalleryMediaPage.decode(fixture(),sd)
        assertEquals(6,(sdPage.entries+internal.entries).map { it.key }.toSet().size)
        assertEquals(sdPage.entries.map { it.handle },internal.entries.map { it.handle })
    }
    @Test fun noMediaDataCannotBeCalledAnEmptyCameraLibrary() {
        invalid(ByteArray(4),GalleryMediaPage.Error.EMPTY_NOT_CONFIRMED)
        invalid(byteArrayOf(1,0,0,0),GalleryMediaPage.Error.INCOMPLETE)
    }
    @Test fun traversalInAnOtherwiseValidLengthDelimitedPathIsRejected() {
        val bytes=fixture();val replacement="../_001/".toByteArray();replacement.copyInto(bytes,176)
        try { GalleryMediaPage.decode(bytes,sd);fail() } catch(_: GalleryMediaPage.Invalid) {}
    }
    @Test fun filenameCannotInjectSeparatorsTraversalOrOverlongFinalPaths() {
        val original=fixture()
        val name=GalleryMediaPage.decode(original,sd).entries.first().name
        val nameBytes=name.toByteArray(Charsets.US_ASCII)
        val at=(2..original.size-nameBytes.size).first { p ->
            original[p-2]==13.toByte() && (original[p-1].toInt() and 255)==nameBytes.size &&
                original.copyOfRange(p,p+nameBytes.size).contentEquals(nameBytes)
        }
        val base=name.substringBeforeLast('.')
        for(replacement in listOf("$base.x/../../../../outside.JPG","$base.x\\outside.JPG","$base.%2foutside.JPG","$base.%5coutside.JPG","$base..JPG","$base."+"x".repeat(245-base.length)+".JPG")) {
            val value=replacement.toByteArray(Charsets.US_ASCII)
            require(value.size<=255)
            val changed=original.copyOfRange(0,at-1)+byteArrayOf(value.size.toByte())+value+original.copyOfRange(at+nameBytes.size,original.size)
            invalid(changed,GalleryMediaPage.Error.MALFORMED)
        }
    }
    @Test fun diagnosticsNeverSerializeMediaPaths() {
        val page=GalleryMediaPage.decode(fixture(),sd);val media=page.entries.first()
        for(value in listOf(page.toString(),media.toString(),media.key.toString())) assertFalse(value.contains("DCIM/"))
    }
}
