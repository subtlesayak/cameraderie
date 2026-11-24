package app.cameraderie.gallery

import app.cameraderie.gallery.protocol.*
import org.junit.Assert.*
import org.junit.Test

class GalleryUdpDiagnosticsTest {
    @Test fun acceptedAndRejectedSynEchoesAreCountedWithoutRetainingBytes() {
        val d=GalleryUdpDiagnostics();d.sent();d.received(DumlDatagram(9,0,0,byteArrayOf(0x38,0x12)).encode(),9,0x1238)
        d.received(DumlDatagram(9,0,0,byteArrayOf(0,0)).encode(),9,0x1238)
        assertTrue(d.summary().contains("tx=1 rx=2"));assertTrue(d.summary().contains("syn=2 echo_match=1 echo_mismatch=1"))
    }
    @Test fun foreignSessionBadLengthAndXorAreDifferentFailures() {
        val d=GalleryUdpDiagnostics();val packet=DumlDatagram(9,0,1,ByteArray(26)).encode()
        d.received(packet,10,0x1238);d.received(packet.copyOf(5),9,0x1238)
        d.received(packet.copyOf().also { it[7]=(it[7].toInt() xor 1).toByte() },9,0x1238)
        assertTrue(d.summary().contains("bad_length=1"));assertTrue(d.summary().contains("bad_xor=1 session_mismatch=1"))
    }
    @Test fun mediaPayloadAndNumericSessionIdentityAreNotSerialized() {
        val d=GalleryUdpDiagnostics();d.received(DumlDatagram(24689,0,5,"private-media-credential-path".toByteArray()).encode(),24689,0x1238)
        assertFalse(d.summary().contains("private"));assertFalse(d.summary().contains("24689"));assertTrue(d.summary().contains("types=5:1"))
    }
    @Test fun frameHeaderMetadataIsBoundedAndNeverRetainsPayloadOrSequence() {
        val d=GalleryUdpDiagnostics()
        repeat(50) { i -> d.frame(DumlFrame(1,2,24689,0xC0,2,i,"secret-media-path".toByteArray())) }
        val summary=d.summary()
        assertTrue(summary.contains("frames=50"));assertTrue(summary.contains("1/2/192/2/0"))
        assertFalse(summary.contains("1/2/192/2/49"));assertFalse(summary.contains("secret"));assertFalse(summary.contains("24689"))
    }
    @Test fun unsupportedAndBadCrcFramesAreCountedButNeverAcceptedOrSerialized() {
        val d=GalleryUdpDiagnostics()
        val bad=DumlFrame(1,2,24689,0,2,0x80,"private-frame-body".toByteArray()).encode().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() }
        val valid=DumlFrame(1,2,24689,0,2,0xDC,ByteArray(40)).encode()
        d.received(DumlDatagram(9,0,2,bad+valid).encode(),9,1)
        assertTrue(d.summary().contains("rejected_frames=1 rejected_headers=1/1/2/2/128"))
        assertFalse(d.summary().contains("private"));assertFalse(d.summary().contains("24689"))
        assertEquals(1,DumlDatagramFrames.decode(DumlDatagram(9,0,2,bad+valid)).size)
    }
    @Test fun rejectedMetadataCannotBeInjectedByForeignSessionOrCorruptEnvelope() {
        val d=GalleryUdpDiagnostics()
        val bad=DumlFrame(1,2,0,0,2,0x80,ByteArray(13)).encode().also { it[it.lastIndex]=0 }
        val packet=DumlDatagram(9,0,2,bad).encode()
        d.received(packet,10,1);d.received(packet.copyOf().also { it[7]=(it[7].toInt() xor 1).toByte() },9,1)
        assertTrue(d.summary().contains("rejected_frames=0 rejected_headers="))
    }
    @Test fun storageDiagnosticsExposeOnlyBoundedNumericLayoutAndCapacityFields() {
        val d=GalleryUdpDiagnostics()
        val p=ByteArray(40).also { it[2]=2;it[5]=1;it[6]=100;it[10]=50;it[24]=80;it[28]=40 }
        d.frame(DumlFrame(1,2,24689,0,2,0xDC,p))
        assertTrue(d.summary().contains("storage_shapes=40/2/1/100/50/80/40"))
        assertFalse(d.summary().contains("24689"))
        d.frame(DumlFrame(1,2,0,0,2,0xDC,ByteArray(1)))
        assertTrue(d.summary().contains("1/-1/-1/-1/-1/-1/-1"))
    }
}
