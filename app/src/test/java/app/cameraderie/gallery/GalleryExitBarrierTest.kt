package app.cameraderie.gallery

import org.junit.Assert.*
import org.junit.Test

class GalleryExitBarrierTest {
    @Test fun workerFirstStillWaitsForCameraBleRelease() {
        var calls=0;val gate=GalleryExitBarrier { calls++ }
        gate.workerClosed();assertEquals(0,calls);gate.bleClosed();assertEquals(1,calls)
        gate.bleClosed();gate.workerClosed();assertEquals(1,calls)
    }
    @Test fun bleFirstStillWaitsForUdpAndPlaybackCleanup() {
        var calls=0;val gate=GalleryExitBarrier { calls++ }
        gate.bleClosed();assertEquals(0,calls);gate.workerClosed();assertEquals(1,calls)
    }
    @Test fun stopAtEitherHalfPreventsEveryLateCompletionFromRestoringTheBridge() {
        for(workerFirst in listOf(true,false)) {
            var calls=0;val gate=GalleryExitBarrier { calls++ }
            if(workerFirst) gate.workerClosed() else gate.bleClosed()
            gate.cancel();gate.workerClosed();gate.bleClosed();assertEquals(0,calls)
        }
    }
}
