package app.cameraderie.gallery

import app.cameraderie.gallery.protocol.*
import org.junit.Assert.*
import org.junit.Test

class GalleryCameraEvidenceTest {
    private var time=0L
    private val evidence=GalleryCameraEvidence({time})
    private fun state(playback: Boolean)=DumlFrame(1,2,9,0,2,0x80,ByteArray(13).also { if(playback) it[3]=0x40 })
    private fun storage(sd: Long=121785,free: Long=109748,inside: Long=51200): DumlFrame {
        val bytes=ByteArray(40);bytes[2]=2;bytes[5]=2
        for((offset,value) in listOf(6 to sd,10 to free,24 to inside,28 to 0L)) repeat(4) { bytes[offset+it]=(value ushr (it*8)).toByte() }
        return DumlFrame(1,2,10,0,2,0xDC,bytes)
    }
    @Test fun receivedPlaybackAckDoesNotProveActualCameraMode() {
        evidence.accept(DumlFrame(1,2,1,0xC0,2,0x0C,byteArrayOf(0)))
        assertNull(evidence.playback);assertFalse(evidence.playbackFresh)
        assertEquals(GalleryCameraEvidence.Readiness.WAITING_PLAYBACK,evidence.readiness)
    }
    @Test fun actualPlaybackAndStorageStillRequireSettlingDelay() {
        evidence.accept(state(true));evidence.accept(storage())
        time=1699;assertEquals(GalleryCameraEvidence.Readiness.SETTLING,evidence.readiness)
        evidence.accept(state(true)) // Periodic pushes must not restart the settle timer.
        time=1700;assertEquals(GalleryCameraEvidence.Readiness.READY,evidence.readiness)
        assertEquals(listOf(GalleryCameraEvidence.Store.SD,GalleryCameraEvidence.Store.INTERNAL),evidence.stores().map { it.store })
        assertEquals(121785L,evidence.stores()[0].totalMiB);assertEquals(109748L,evidence.stores()[0].freeMiB)
    }
    @Test fun missingStorageAndUnavailableStorageAreNotEmptyLibraryResults() {
        evidence.accept(state(true));time=1700
        assertEquals(GalleryCameraEvidence.Readiness.WAITING_STORAGE,evidence.readiness)
        evidence.accept(storage(0,0,0));assertEquals(GalleryCameraEvidence.Readiness.UNAVAILABLE,evidence.readiness)
        evidence.accept(storage(0,0,51200));assertEquals(GalleryCameraEvidence.Readiness.READY,evidence.readiness)
        assertFalse(evidence.stores()[0].available);assertTrue(evidence.stores()[1].available)
    }
    @Test fun stalePlaybackOrStorageBecomesUnknownForReadiness() {
        evidence.accept(state(true));evidence.accept(storage());time=1700
        assertEquals(GalleryCameraEvidence.Readiness.READY,evidence.readiness)
        time=5001;assertEquals(GalleryCameraEvidence.Readiness.WAITING_PLAYBACK,evidence.readiness)
        assertTrue(evidence.stores().isEmpty())
        evidence.accept(state(true));assertEquals(GalleryCameraEvidence.Readiness.SETTLING,evidence.readiness)
        time+=1700;assertEquals(GalleryCameraEvidence.Readiness.WAITING_STORAGE,evidence.readiness)
    }
    @Test fun wrongPeerMalformedFramesAndImpossibleCapacityDoNotCreateEvidence() {
        evidence.accept(DumlFrame(7,2,0,0,2,0x80,state(true).payload))
        evidence.accept(DumlFrame(1,3,0,0,2,0x80,state(true).payload))
        evidence.accept(DumlFrame(1,2,0,0,2,0x80,ByteArray(4)))
        assertNull(evidence.playback)
        evidence.accept(storage(1,2));assertFalse(evidence.storageFresh)
        val short=storage().payload.copyOf(22);evidence.accept(DumlFrame(1,2,0,0,2,0xDC,short));assertFalse(evidence.storageFresh)
    }
    @Test fun leavingPlaybackAndReenteringRequiresNewDelayAndSessionResetClearsEverything() {
        evidence.accept(state(true));evidence.accept(storage());time=2000
        evidence.accept(state(false));assertEquals(GalleryCameraEvidence.Readiness.WAITING_PLAYBACK,evidence.readiness)
        evidence.accept(state(true));time=2001;assertEquals(GalleryCameraEvidence.Readiness.SETTLING,evidence.readiness)
        evidence.reset();assertNull(evidence.playback);assertFalse(evidence.storageFresh);assertTrue(evidence.stores().isEmpty())
    }
    @Test fun measuredAction6StorageByteFiveIsNotAStoreCountMirror() {
        // Numeric fields observed on code35; synthetic payload, not a raw capture.
        val p=storage(120423,8,51397).payload.also { it[5]=17 }
        evidence.accept(state(true));evidence.accept(DumlFrame(1,2,0,0,2,0xDC,p));time=1700
        assertEquals(GalleryCameraEvidence.Readiness.READY,evidence.readiness)
        assertEquals(120423L,evidence.stores()[0].totalMiB);assertEquals(8L,evidence.stores()[0].freeMiB)
        assertEquals(51397L,evidence.stores()[1].totalMiB);assertEquals(0L,evidence.stores()[1].freeMiB)
    }
    @Test fun invalidStoreCountsAndTruncatedSecondStoreRemainRejected() {
        for(count in listOf(0,3,255)) {
            val p=storage().payload.also { it[2]=count.toByte();it[5]=17 }
            evidence.accept(DumlFrame(1,2,0,0,2,0xDC,p));assertFalse(evidence.storageFresh)
        }
        val p=storage().payload.copyOf(22).also { it[5]=17 }
        evidence.accept(DumlFrame(1,2,0,0,2,0xDC,p));assertFalse(evidence.storageFresh)
    }
}
