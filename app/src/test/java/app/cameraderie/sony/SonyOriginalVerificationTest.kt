package app.cameraderie.sony

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class SonyOriginalVerificationTest {
    private val arw=byteArrayOf(0x49,0x49,0x2a,0,1,2,3,4)
    private fun rejected(block:()->Unit) { try { block();fail("Expected rejection") } catch(_:IOException) {} }
    @Test fun validatesExactLengthAndReadbackDigest() {
        val receipt=SonyOriginalVerification.verify(ByteArrayInputStream(arw),8,true)
        assertEquals(8L,receipt.bytes);assertEquals(64,receipt.sha256.length)
        assertEquals(receipt,SonyOriginalVerification.verify(ByteArrayInputStream(arw),8,true,sourceSha256=receipt.sha256))
    }
    @Test fun rejectsJpegRenamedToArwAndLengthMismatch() {
        rejected { SonyOriginalVerification.verify(ByteArrayInputStream(byteArrayOf(-1,-40,-1,0)),4,true) }
        rejected { SonyOriginalVerification.verify(ByteArrayInputStream(arw),9,true) }
        rejected { SonyOriginalVerification.verify(ByteArrayInputStream(arw),7,true) }
    }
    @Test fun rejectsSameSizeCorruptionAndCancellation() {
        val sha=SonyOriginalVerification.verify(ByteArrayInputStream(arw),8,true).sha256
        rejected { SonyOriginalVerification.verify(ByteArrayInputStream(arw.copyOf().also { it[7]=5 }),8,true,sourceSha256=sha) }
        rejected { SonyOriginalVerification.verify(ByteArrayInputStream(arw),8,true,{true}) }
    }
}
