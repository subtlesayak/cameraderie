package app.cameraderie.sony

import org.junit.Assert.*
import org.junit.Test

class JpegSamplingTest {
    private fun jpeg(y:Int,cb:Int,cr:Int)=byteArrayOf(-1,-40,-1,-64,0,17,8,0,16,0,16,3,1,y.toByte(),0,2,cb.toByte(),1,3,cr.toByte(),1,-1,-39)
    @Test fun distinguishes422From420IncludingEquivalentVerticalFactors() {
        assertTrue(JpegSampling.read(jpeg(0x21,0x11,0x11).inputStream()).is422)
        assertTrue(JpegSampling.read(jpeg(0x22,0x12,0x12).inputStream()).is422)
        assertFalse(JpegSampling.read(jpeg(0x22,0x11,0x11).inputStream()).is422)
    }
    @Test fun rejectsTruncatedOrNonJpegHeaders() {
        for(bytes in listOf(byteArrayOf(),byteArrayOf(1,2,3),jpeg(0x21,0x11,0x11).copyOf(10))) {
            try { JpegSampling.read(bytes.inputStream());fail() } catch(_:java.io.IOException) { }
        }
    }
}
