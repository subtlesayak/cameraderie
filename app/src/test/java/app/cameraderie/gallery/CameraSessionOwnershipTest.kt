package app.cameraderie.gallery

import org.junit.Assert.*
import org.junit.Test

class CameraSessionOwnershipTest {
    private fun idle() = CameraSessionOwnership.Evidence(true,true,0,1,false)
    @Test fun entryNeedsFreshAuthorizedAwakeIdleAndNoOutstandingCommand() {
        val invalid=listOf(idle().copy(authorized=false),idle().copy(fresh=false),idle().copy(power=3),
            idle().copy(cameraState=null),idle().copy(cameraState=3),idle().copy(cameraState=5),idle().copy(commandPending=true))
        for(evidence in invalid) {
            val o=CameraSessionOwnership()
            try { o.enter(true,evidence);fail("Unsafe entry admitted") } catch(_:IllegalStateException) {}
            assertTrue(o.bridgeActionsAllowed)
        }
    }
    @Test fun transitionAndGallerySuspendActionsUntilFreshRestoration() {
        val o=CameraSessionOwnership();val lease=o.enter(true,idle())
        assertEquals(CameraSessionOwnership.Owner.GALLERY_TRANSITION,o.owner);assertFalse(o.bridgeActionsAllowed)
        assertTrue(o.connected(lease));assertFalse(o.bridgeActionsAllowed)
        assertTrue(o.exit(lease));assertEquals(CameraSessionOwnership.Owner.RESTORING_BRIDGE,o.owner)
        assertFalse(o.restored(lease,idle().copy(fresh=false)))
        assertFalse(o.restored(lease,idle().copy(authorized=false)))
        assertTrue(o.restored(lease,idle()));assertTrue(o.bridgeActionsAllowed)
    }
    @Test fun repeatedExitCannotRestartRestorationOrClearItsFailure() {
        val o=CameraSessionOwnership();val lease=o.enter(true,idle());assertTrue(o.exit(lease))
        o.restorationFailed(lease,"Reconnect camera")
        assertFalse(o.exit(lease));assertEquals("Reconnect camera",o.failure)
        assertEquals(CameraSessionOwnership.Owner.RESTORING_BRIDGE,o.owner)
    }
    @Test fun previouslyStoppedBridgeIsNeverAutomaticallyStartedOnExit() {
        val o=CameraSessionOwnership();val lease=o.enter(false,idle());assertFalse(o.exit(lease))
        assertTrue(o.bridgeActionsAllowed);assertFalse(o.isCurrent(lease))
    }
    @Test fun explicitStopInvalidatesConnectionAndRestorationCallbacks() {
        for(restore in listOf(false,true)) {
            val o=CameraSessionOwnership();val lease=o.enter(true,idle())
            if(restore)o.exit(lease)
            o.stop();assertFalse(o.connected(lease));assertFalse(o.exit(lease));assertFalse(o.restored(lease,idle()))
            assertFalse(o.isCurrent(lease));assertTrue(o.bridgeActionsAllowed)
        }
    }
    @Test fun oldLeaseCannotMutateNewGalleryAttempt() {
        val o=CameraSessionOwnership();val old=o.enter(false,idle());o.exit(old)
        val current=o.enter(true,idle());assertFalse(o.connected(old));assertFalse(o.exit(old))
        assertTrue(o.isCurrent(current));assertEquals(CameraSessionOwnership.Owner.GALLERY_TRANSITION,o.owner)
    }
    @Test fun restoreFailureKeepsActionsBlockedUntilAnExplicitSuccessfulReconnect() {
        val o=CameraSessionOwnership();val lease=o.enter(true,idle());o.exit(lease)
        assertTrue(o.restorationFailed(lease,"Reconnect camera"));assertFalse(o.bridgeActionsAllowed)
        assertEquals("Reconnect camera",o.failure)
        assertTrue(o.restored(lease,idle().copy(cameraState=3)));assertNull(o.failure)
    }
    @Test fun playbackOrPrerecordStateCannotPrematurelyRestoreBridgeActions() {
        val o=CameraSessionOwnership();val lease=o.enter(true,idle());o.exit(lease)
        for(state in listOf(2,5)) {
            assertFalse(o.restored(lease,idle().copy(cameraState=state)));assertFalse(o.bridgeActionsAllowed)
        }
        assertTrue(o.restored(lease,idle().copy(cameraState=1)))
    }
    @Test fun doubleEntryIsRejectedWithoutChangingOwnership() {
        val o=CameraSessionOwnership();val first=o.enter(true,idle())
        try{o.enter(false,idle());fail("Double entry") } catch(_:IllegalStateException) {}
        assertTrue(o.isCurrent(first))
    }
}
