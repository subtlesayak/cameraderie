package app.cameraderie.gallery

import org.junit.Assert.*
import org.junit.Test

class GalleryReleaseGateTest {
    @Test fun elapsedTimeWithoutObservedDisconnectNeverOpensTheGalleryLink() {
        var now=0L;val gate=GalleryReleaseGate({now});gate.begin()
        now=5999;gate.tick();assertEquals(GalleryReleaseGate.State.WAITING,gate.state)
        now=6000;gate.tick();assertEquals(GalleryReleaseGate.State.TIMED_OUT,gate.state)
        assertFalse(gate.disconnected());assertEquals(GalleryReleaseGate.State.TIMED_OUT,gate.state)
    }
    @Test fun observedReleaseMustSettleBeforeOpeningAndCannotBeConsumedTwice() {
        var now=0L;val gate=GalleryReleaseGate({now});gate.begin();now=100
        assertTrue(gate.disconnected());assertFalse(gate.disconnected())
        now=349;gate.tick();assertEquals(GalleryReleaseGate.State.SETTLING,gate.state)
        now=350;gate.tick();assertEquals(GalleryReleaseGate.State.OPEN,gate.state)
        assertFalse(gate.disconnected())
    }
    @Test fun observingBeforeSdkCloseDoesNotLoseAFastDisconnectBeforeAwait() {
        var now=0L;val gate=GalleryReleaseGate({now});gate.disconnected();now=300
        assertEquals(GalleryReleaseGate.State.OBSERVING,gate.state)
        gate.begin();assertEquals(GalleryReleaseGate.State.OPEN,gate.state)
    }
    @Test fun stopDuringWaitOrSettleIgnoresLateDisconnection() {
        for(releaseFirst in listOf(false,true)) {
            var now=0L;val gate=GalleryReleaseGate({now});gate.begin()
            if(releaseFirst) gate.disconnected()
            gate.cancel();now=9000;gate.tick();assertFalse(gate.disconnected())
            assertEquals(GalleryReleaseGate.State.CANCELED,gate.state)
        }
    }
    @Test fun releaseAtDeadlineDoesNotRestartOrExtendItsBoundedWait() {
        var now=0L;val gate=GalleryReleaseGate({now});gate.begin();now=5990;gate.disconnected()
        now=6000;gate.tick();assertEquals(GalleryReleaseGate.State.TIMED_OUT,gate.state)
    }
}
