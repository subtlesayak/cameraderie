package app.cameraderie.gallery

import app.cameraderie.gallery.media.GalleryListCapture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class GalleryListCaptureTest {
    @Test fun mediaRecordCarriesExactValidatedPayloadWithBoundedHeader() {
        val c=GalleryListCapture();val payload=byteArrayOf(0x4a,1,2,3,9)
        c.record(1,0x27,65535,0x40,payload,123)
        payload.fill(0)
        val out=c.finish()!!;assertEquals("GBMD1",String(out,0,5,Charsets.US_ASCII))
        val b=ByteBuffer.wrap(out,5,out.size-5).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1,b.get().toInt());assertEquals(0x27,b.get().toInt());assertEquals(65535,b.short.toInt() and 65535)
        assertEquals(0x40,b.get().toInt());assertEquals(123L,b.long);assertEquals(5,b.int)
        assertArrayEquals(byteArrayOf(0x4a,1,2,3,9),ByteArray(5).also { b.get(it) });assertNull(c.finish())
    }
    @Test fun unrelatedCommandsAreNeverRecordedAndRetirementRejectsLateFrames() {
        val c=GalleryListCapture()
        for(command in listOf(0x19,0x0F,0x10,0x88))try { c.record(1,command,1,0,byteArrayOf(1),0);fail() } catch(_:IllegalArgumentException) {}
        assertEquals(5,c.finish()!!.size);c.record(1,0x27,1,0,byteArrayOf(1),0);assertNull(c.finish())
    }
    @Test fun byteAndRecordBudgetsDoNotProducePartialRecords() {
        val c=GalleryListCapture(32,1);c.record(0,0x26,1,0,ByteArray(10),0);c.record(1,0x27,2,0,byteArrayOf(2),1)
        assertEquals(1,c.dropped);assertEquals(32,c.finish()!!.size)
        val large=GalleryListCapture(32);large.record(1,0x27,1,0,ByteArray(11),0);assertEquals(5,large.finish()!!.size);assertEquals(1,large.dropped)
    }
}
