package app.cameraderie.gallery

import app.cameraderie.gallery.protocol.*
import org.junit.Assert.*
import org.junit.Test

class DumlFrameTest {
    private fun hex(s: String)=s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    // Public Mimo/Nano session-wake capture, not a synthetic encoder output.
    private val fixture=hex("550f04a202f01bcb40002b04009ab9")
    @Test fun matchesPublishedDumlCaptureByteForByte() {
        val frame=DumlFrame.decode(fixture)!!
        assertEquals(2,frame.sender);assertEquals(0xF0,frame.receiver);assertEquals(0xCB1B,frame.sequence)
        assertEquals(0x40,frame.flags);assertEquals(0,frame.commandSet);assertEquals(0x2B,frame.commandId)
        assertArrayEquals(byteArrayOf(4,0),frame.payload);assertArrayEquals(fixture,frame.encode())
    }
    @Test fun eachSingleByteCorruptionAndTruncationIsRejected() {
        for(i in fixture.indices) {val bad=fixture.copyOf();bad[i]=(bad[i].toInt() xor 1).toByte();assertNull(DumlFrame.decode(bad))}
        for(size in 0 until fixture.size) assertNull(DumlFrame.decode(fixture.copyOf(size)))
        assertNull(DumlFrame.decode(fixture+byteArrayOf(0)))
    }
    @Test fun everyBleSplitAndCoalescedFrameIsReassembled() {
        for(split in 0..fixture.size) {
            val f=DumlFramer();val result=f.accept(fixture.copyOfRange(0,split))+f.accept(fixture.copyOfRange(split,fixture.size))
            assertEquals(1,result.size);assertArrayEquals(fixture,result.single().encode())
        }
        val frames=DumlFramer().accept(byteArrayOf(0,0x55,0,0)+fixture+fixture)
        assertEquals(2,frames.size)
    }
    @Test fun maximumLengthUsesTenBitSizeAndRemainsBounded() {
        val frame=DumlFrame(2,1,65535,0x40,0,0x26,ByteArray(1010){0x55})
        val bytes=frame.encode();assertEquals(1023,bytes.size);assertEquals(7,bytes[2].toInt())
        assertArrayEquals(bytes,DumlFramer().accept(bytes).single().encode())
        assertTrue(DumlFramer().accept(ByteArray(100000){0x55}).isEmpty())
    }
    @Test fun callerMutationCannotAlterFrameOrExposeCredentials() {
        val secret="test-password".toByteArray();val frame=DumlFrame(2,7,1,0x40,7,14,secret)
        val before=frame.encode();secret.fill(0);frame.payload.fill(0)
        assertArrayEquals(before,frame.encode());assertFalse(frame.toString().contains("test-password"))
    }
    @Test fun resetDiscardsEarlierIncompleteSessionAndRejectsRsdkFrames() {
        val f=DumlFramer();f.accept(fixture.copyOfRange(0,7));f.reset()
        assertTrue(f.accept(fixture.copyOfRange(7,fixture.size)).isEmpty())
        assertEquals(1,f.accept(fixture).size)
        assertNull(DumlFrame.decode(byteArrayOf(0xAA.toByte())+fixture.copyOfRange(1,fixture.size)))
    }
}
