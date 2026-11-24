package app.cameraderie.gallery

import app.cameraderie.gallery.protocol.*
import org.junit.Assert.*
import org.junit.Test

class GalleryManifestStreamTest {
    private fun frame(sub: Int,sequence: Int=0,data: ByteArray=byteArrayOf(),counter: Int=2,set: Int=0,id: Int=0x27)=
        DumlFrame(1,2,1,0x40,set,id,ByteArray(10).also { it[0]=0x4A;it[1]=sub.toByte();it[4]=counter.toByte();it[6]=sequence.toByte();it[7]=(sequence ushr 8).toByte() }+data)
    private fun stream(maxBytes: Int=1024)=GalleryManifestStream(GalleryCameraEvidence.Store.INTERNAL,2,maxBytes)
    @Test fun actualReferenceRawStreamUsesSequenceOrderAndDeduplicatesRepeatedEnds() {
        val bytes=javaClass.classLoader!!.getResourceAsStream("gallery-fixtures/nano_45.bin")!!.use { it.readBytes() }
        val s=stream(100000);DumlFramer().accept(bytes).forEach(s::accept)
        assertEquals(GalleryManifestStream.Status.COLLECTED,s.snapshot.status)
        assertEquals(17,s.snapshot.chunks);assertEquals(16154,s.snapshot.bytes)
        assertNotNull(s.collectedBytes());assertFalse(s.snapshot.started)
    }
    @Test fun reorderedChunksAndIdenticalRetransmissionsProduceOneOrderedPayload() {
        val s=stream();s.accept(frame(4));s.accept(frame(1,1,byteArrayOf(3,4)));s.accept(frame(3))
        assertNull(s.collectedBytes());s.accept(frame(1,0,byteArrayOf(1,2)));s.accept(frame(1,1,byteArrayOf(3,4)))
        assertArrayEquals(byteArrayOf(1,2,3,4),s.collectedBytes());assertEquals(4,s.snapshot.bytes)
        val copy=s.collectedBytes()!!;copy.fill(0);assertEquals(1,s.collectedBytes()!![0].toInt())
    }
    @Test fun startOnlyIsNotAnEmptySuccessfulListEvenAfterEnd() {
        val s=stream();s.accept(frame(4));s.accept(frame(3))
        assertEquals(GalleryManifestStream.Status.RECEIVING,s.snapshot.status);assertNull(s.collectedBytes())
    }
    @Test fun matchingPayloadPrefixOnOtherCommandsAndOtherCountersIsIgnored() {
        val s=stream();s.accept(frame(1,data=byteArrayOf(1),set=2,id=0x80));s.accept(frame(1,data=byteArrayOf(1),counter=1))
        assertEquals(GalleryManifestStream.Status.WAITING,s.snapshot.status)
    }
    @Test fun conflictingRetransmissionFailsInsteadOfCorruptingOrChoosingOne() {
        val s=stream();s.accept(frame(1,data=byteArrayOf(1)));s.accept(frame(1,data=byteArrayOf(2)));s.accept(frame(3))
        assertEquals(GalleryManifestStream.Error.CONFLICT,s.snapshot.error);assertNull(s.collectedBytes());assertEquals(0,s.snapshot.bytes)
    }
    @Test fun dataNeedsEndAndEverySequenceStartingAtZero() {
        val s=stream();s.accept(frame(1,0,byteArrayOf(1)));s.accept(frame(1,2,byteArrayOf(3)));s.accept(frame(3))
        assertNull(s.collectedBytes());s.accept(frame(1,1,byteArrayOf(2)));assertArrayEquals(byteArrayOf(1,2,3),s.collectedBytes())
        val noEnd=stream();noEnd.accept(frame(1,0,byteArrayOf(1)));assertNull(noEnd.collectedBytes())
    }
    @Test fun boundsAndUnknownSubtypesFailClosed() {
        val s=stream(1);s.accept(frame(1,data=byteArrayOf(1,2)));assertEquals(GalleryManifestStream.Error.LIMIT,s.snapshot.error)
        val invalid=stream();invalid.accept(frame(2));assertEquals(GalleryManifestStream.Error.MALFORMED,invalid.snapshot.error)
        val seq=stream();seq.accept(frame(1,2048,byteArrayOf(1)));assertEquals(GalleryManifestStream.Error.LIMIT,seq.snapshot.error)
    }
    @Test fun cancelClearsBufferedMediaAndAllLateFramesStayInert() {
        val s=stream();s.accept(frame(1,data="private-path".toByteArray()));s.cancel();s.accept(frame(3));s.accept(frame(1,data=byteArrayOf(1)))
        assertEquals(GalleryManifestStream.Status.CANCELED,s.snapshot.status);assertEquals(0,s.snapshot.bytes);assertNull(s.collectedBytes())
        assertFalse(s.toString().contains("private-path"))
    }
}
