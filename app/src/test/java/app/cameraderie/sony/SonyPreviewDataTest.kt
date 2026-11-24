package app.cameraderie.sony

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class SonyPreviewDataTest {
    private val jpeg=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xd9.toByte())
    private fun reject(block:()->Unit) { try { block();fail("Expected preview rejection") } catch(_:IOException) { } }
    @Test fun acceptsBoundedJpegWithoutClaimingOriginalIntegrity() {
        assertArrayEquals(jpeg,SonyPreviewData.read(ByteArrayInputStream(jpeg),jpeg.size.toLong()))
    }
    @Test fun rejectsTruncatedOrMismatchedPreview() {
        reject { SonyPreviewData.read(ByteArrayInputStream(jpeg),5) }
        reject { SonyPreviewData.read(ByteArrayInputStream(jpeg),3) }
    }
    @Test fun rejectsHugePreviewBeforeReading() {
        reject { SonyPreviewData.read(ByteArrayInputStream(jpeg),SonyPreviewData.MAX_BYTES+1L) }
    }
    @Test fun boundsUnknownLengthStreams() {
        val bytes=ByteArray(SonyPreviewData.MAX_BYTES+1);jpeg.copyInto(bytes)
        reject { SonyPreviewData.read(ByteArrayInputStream(bytes),null) }
    }
    @Test fun rejectsHtmlRawAndEmptyAsImages() {
        for(bytes in listOf("<html>not a preview</html>".toByteArray(),byteArrayOf(0x49,0x49,0x2a,0),byteArrayOf()))
            reject { SonyPreviewData.read(ByteArrayInputStream(bytes),null) }
    }
    @Test fun acceptsPngSignatureForSubsequentDecoderValidation() {
        val png=byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)
        assertArrayEquals(png,SonyPreviewData.read(ByteArrayInputStream(png),null))
    }
}
