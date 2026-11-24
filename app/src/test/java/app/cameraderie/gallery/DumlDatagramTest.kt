package app.cameraderie.gallery

import app.cameraderie.gallery.protocol.*
import org.junit.Assert.*
import org.junit.Test

class DumlDatagramTest {
    private fun u16(b: ByteArray,o: Int)=(b[o].toInt() and 255) or ((b[o+1].toInt() and 255) shl 8)
    private fun status(session: Int,channel: Int,video: Int,download: Int): ByteArray {
        val bytes=ByteArray(26)
        for((offset,value) in listOf(0 to channel,2 to video,10 to download)) { bytes[offset]=value.toByte();bytes[offset+1]=(value ushr 8).toByte() }
        return DumlDatagram(session,0,1,bytes).encode()
    }
    @Test fun knownHeaderLayoutMatchesIndependentXorFixture() {
        val bytes=DumlDatagram(0x1234,0x5678,5,ByteArray(25)).encode()
        assertArrayEquals(byteArrayOf(0x21,0x80.toByte(),0x34,0x12,0x78,0x56,5,0xAC.toByte()),bytes.copyOf(8))
        assertEquals(25,DumlDatagram.decode(bytes,0x1234)!!.payload.size)
    }
    @Test fun truncatedWrongSessionLengthFlagOrHeaderXorIsRejectedBeforeCountersAreRead() {
        val bytes=status(0x1234,0x3458,100,200)
        for(n in bytes.indices) assertNull(DumlDatagram.decode(bytes.copyOf(n),0x1234))
        assertNull(DumlDatagram.decode(bytes,0x1235))
        for(i in 0..7) {
            val wrong=bytes.copyOf();wrong[i]=(wrong[i].toInt() xor 1).toByte();assertNull(DumlDatagram.decode(wrong,0x1234))
        }
        val state=DumlDatalinkState(0x1234,0x2000);state.receive(bytes.copyOf(12));assertFalse(state.synchronizeToPeer())
    }
    @Test fun sendSequenceRequiresObservedHeartbeatAndKeepsOwnSpaceWhenPeerAdvances() {
        val s=DumlDatalinkState(9,0x2000)
        try { s.command(1,0,0x26,byteArrayOf());fail() } catch(_: IllegalStateException) {}
        assertFalse(s.synchronizeToPeer())
        s.receive(status(9,0x3000,0,0));assertTrue(s.synchronizeToPeer())
        val first=s.command(1,0,0x26,byteArrayOf())
        assertEquals(0x3008,u16(first,4));assertEquals(0x3000,u16(first,8));assertEquals(0x3008,u16(first,10))
        s.receive(status(9,0x7000,0,0))
        assertTrue(s.synchronizeToPeer()) // Never reset an already-running command sequence to telemetry.
        val second=s.command(1,0,0x26,byteArrayOf())
        assertEquals(0x3010,u16(second,4));assertEquals(0x3008,u16(second,8));assertEquals(0x3010,u16(second,10))
        assertEquals(2,second[16].toInt());assertEquals(1,second[17].toInt())
    }
    @Test fun downloadWindowAckEchoesBothLatestCursorsWithoutAdvancingCommandSpace() {
        val s=DumlDatalinkState(9,0x2000);s.receive(status(9,0x3000,0x1111,0x2222));s.synchronizeToPeer()
        val ack=s.acknowledge();assertEquals(34,ack.size);assertEquals(0,u16(ack,4));assertEquals(4,ack[6].toInt())
        assertEquals(0x1111,u16(ack,8));assertEquals(0x1111,u16(ack,10));assertEquals(0x2222,u16(ack,16));assertEquals(0x2222,u16(ack,18))
        assertEquals(0x2000,u16(ack,24));assertEquals(0x2000,u16(ack,26))
        assertEquals(0x3008,u16(s.command(1,0,0x26,byteArrayOf()),4))
    }
    @Test fun sequenceWrapAndDumlFrameLengthsCrcRemainValid() {
        val s=DumlDatalinkState(9,0xFFF8);s.receive(status(9,0xFFF8,0,0));s.synchronizeToPeer()
        val cmd=s.command(1,0,0x26,ByteArray(500))
        assertEquals(0,u16(cmd,4));assertEquals(0xFFF8,u16(cmd,8))
        val frame=DumlFrame.decode(cmd.copyOfRange(20,cmd.size))!!
        assertEquals(500,frame.payload.size);assertEquals(0xA000,frame.sequence)
    }
    @Test fun envelopePayloadOwnershipIsDefensiveAndDiagnosticsNeverContainPayload() {
        val secret="private-media-path".toByteArray();val frame=DumlDatagram(9,0,3,secret)
        secret.fill(0);val copy=frame.payload;copy.fill(0)
        assertEquals("private-media-path",String(frame.payload));assertFalse(frame.toString().contains("private-media"))
    }
    @Test fun freshBaseIsEncodedInHandshakeAndNoReusedCameraChannelLeaks() {
        val first=DumlDatalinkState(8,0x1238);val second=DumlDatalinkState(9,0x4560)
        assertEquals(0x1238,u16(first.handshake(),8));assertEquals(0x4560,u16(second.handshake(),8))
        assertNull(second.peerChannel);assertFalse(second.synchronizeToPeer())
    }
}
