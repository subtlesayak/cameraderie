package app.cameraderie.gallery

import app.cameraderie.gallery.GalleryPreflight.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryPreflightTest {
    private val idle = CameraSessionOwnership.Evidence(true,true,0,1,false)
    private fun evaluate(evidence: CameraSessionOwnership.Evidence=idle, model: Int?=0xFF55,
        link: Boolean=true, now: Long=1) = GalleryPreflight.evaluate(evidence,model,link,now,100)

    @Test fun requiresFreshAuthorizedAwakeIdleStatusFromAction6() {
        assertEquals(Outcome.READY,evaluate())
        assertEquals(Outcome.WAITING,evaluate(idle.copy(authorized=false)))
        assertEquals(Outcome.WAITING,evaluate(idle.copy(fresh=false)))
        assertEquals(Outcome.UNSUPPORTED,evaluate(model=null))
        assertEquals(Outcome.UNSUPPORTED,evaluate(model=0xFF44))
        assertEquals(Outcome.ASLEEP,evaluate(idle.copy(power=3)))
    }

    @Test fun rejectsRecordingFinalizingUnknownAndPendingCameraOperations() {
        for(state in listOf(null,2,3,4,255)) assertEquals(Outcome.BUSY,evaluate(idle.copy(cameraState=state)))
        assertEquals(Outcome.BUSY,evaluate(idle.copy(commandPending=true)))
    }

    @Test fun lostLinkAndExpiredDeadlineCannotReuseEvenIdleEvidence() {
        assertEquals(Outcome.LINK_LOST,evaluate(link=false))
        assertEquals(Outcome.TIMEOUT,evaluate(now=100))
        assertEquals(Outcome.TIMEOUT,evaluate(idle.copy(authorized=false, fresh=false),now=100))
    }
}
