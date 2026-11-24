package app.cameraderie.gallery

import app.cameraderie.gallery.protocol.*
import org.junit.Assert.*
import org.junit.Test

class DumlDatagramFramesTest {
    private val frame=DumlFrame(1,2,55,0,2,0x80,ByteArray(13))
    private fun packet(bytes: ByteArray,type: Int=2)=DumlDatagram(1234,0,type,bytes)
    @Test fun dataChannelsDecodeWithDifferentRoutingPrefixes() {
        for(type in listOf(1,2,3,5)) for(prefix in listOf(0,4,8,12)) {
            val out=DumlDatagramFrames.decode(packet(ByteArray(prefix)+frame.encode(),type))
            assertEquals(1,out.size);assertEquals(0x80,out.single().commandId);assertEquals(55,out.single().sequence)
        }
    }
    @Test fun validButIncompleteNoiseHeaderCannotHideARealFrameLaterInTheDatagram() {
        val incomplete=DumlFrame(1,2,2,0,0,0,ByteArray(887)).encode().copyOf(4)
        val out=DumlDatagramFrames.decode(packet(incomplete+byteArrayOf(7,8,9)+frame.encode()))
        assertEquals(1,out.size);assertEquals(55,out.single().sequence)
    }
    @Test fun bothCrcChecksRejectDamagedNoiseBeforeCoalescedValidFrames() {
        val wrong=frame.encode().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() }
        val second=DumlFrame(1,2,56,0,2,0xDC,ByteArray(40))
        val out=DumlDatagramFrames.decode(packet(wrong+byteArrayOf(0x55,0,0,0)+frame.encode()+second.encode()))
        assertEquals(listOf(0x80,0xDC),out.map { it.commandId })
    }
    @Test fun incompleteFramesCannotBeSplicedAcrossDatagramsAndControlPacketsAreNotStatus() {
        val bytes=frame.encode()
        assertTrue(DumlDatagramFrames.decode(packet(bytes.copyOfRange(0,18))).isEmpty())
        assertTrue(DumlDatagramFrames.decode(packet(bytes.copyOfRange(18,bytes.size))).isEmpty())
        for(type in listOf(0,4)) assertTrue(DumlDatagramFrames.decode(packet(bytes,type)).isEmpty())
    }
}
