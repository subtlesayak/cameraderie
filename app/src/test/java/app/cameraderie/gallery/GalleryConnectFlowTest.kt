package app.cameraderie.gallery

import app.cameraderie.gallery.protocol.*
import org.junit.Assert.*
import org.junit.Test

class GalleryConnectFlowTest {
    private class Network: GalleryConnectFlow.Network { var closes=0;override fun close(){closes++} }
    private class Rig(val bridgeActive: Boolean=true, val standaloneCheck: Boolean=false): GalleryConnectFlow.Host {
        val ownership=CameraSessionOwnership();val calls=mutableListOf<String>();val states=mutableListOf<GalleryConnectFlow.State>()
        var token: GalleryConnectFlow.Token?=null;var events: GalleryConnectFlow.Events?=null
        var prepares=0;var onPrepare:(()->Unit)?=null
        var now=0L;var entryFails=false;var entryWifiDisabled=false;var releaseFails=false;var delayFinish=false;var finishCallback:(()->Unit)?=null;var onChanged: ((GalleryConnectFlow.State)->Unit)?=null
        val identity=GalleryIdentity.load(object: GalleryIdentity.Store { override fun read()="a".repeat(32);override fun save(value:String)=true })
        val credentials=CameraWifiCredentials.create("Camera-Test","password123")
        val flow=GalleryConnectFlow(this,{now},{states.add(it);onChanged?.invoke(it)})
        private fun evidence()=CameraSessionOwnership.Evidence(true,true,0,0,false)
        override fun acquire(): CameraSessionOwnership.Lease {
            if(entryFails) throw GalleryConnectFlow.EntryFailure(GalleryConnectFlow.Category.PERMISSION)
            if(entryWifiDisabled) throw GalleryConnectFlow.EntryFailure(GalleryConnectFlow.Category.WIFI_DISABLED,GalleryConnectFlow.Stage.WIFI_JOINING)
            calls.add("acquire");return if(standaloneCheck) ownership.reserveStandaloneGallery() else ownership.enter(bridgeActive,evidence())
        }
        override fun current(lease:CameraSessionOwnership.Lease)=ownership.isCurrent(lease)
        override fun connected(lease:CameraSessionOwnership.Lease)=ownership.connected(lease)
        override fun release(lease:CameraSessionOwnership.Lease):Boolean { calls.add("release");if(releaseFails) throw IllegalStateException();return ownership.exit(lease) }
        override fun retryRestore() { calls.add("retryRestore") }
        override fun runtime(token:GalleryConnectFlow.Token,events:GalleryConnectFlow.Events):GalleryConnectFlow.Runtime {
            this.token=token;this.events=events
            return object: GalleryConnectFlow.Runtime {
                override fun connectBle(){calls.add("ble")}
                override fun joinWifi(credentials:CameraWifiCredentials){calls.add("wifi")}
                override fun startSession(network:GalleryConnectFlow.Network,identity:GalleryIdentity){calls.add("session")}
                override fun prepareExit(){assertTrue(token.active);prepares++;onPrepare?.invoke()}
                override fun finish(done:()->Unit){assertFalse(token.active);assertTrue(prepares>0);if(delayFinish)finishCallback=done else done()}
                override fun close(){assertFalse(token.active);calls.add("close")}
            }
        }
        fun restore(){ check(ownership.restored(token!!.lease,evidence()));flow.restored() }
        fun connect(){flow.start();events!!.credentials(credentials,identity);events!!.network(Network());events!!.ready(listOf(GalleryCameraEvidence.Storage(GalleryCameraEvidence.Store.SD,100,50)))}
    }
    @Test fun actualStagesAcquireOwnershipBeforeConnectingAndCannotSkipToReady() {
        val r=Rig();r.flow.start();r.events!!.ready(listOf(GalleryCameraEvidence.Storage(GalleryCameraEvidence.Store.SD,1,1)))
        assertEquals(listOf("acquire","ble"),r.calls);assertEquals(GalleryConnectFlow.Stage.BLE_CONNECTION,r.flow.state.stage)
        r.events!!.credentials(r.credentials,r.identity);r.events!!.network(Network())
        r.events!!.ready(listOf(GalleryCameraEvidence.Storage(GalleryCameraEvidence.Store.SD,100,50)))
        assertEquals(GalleryConnectFlow.Stage.READY,r.flow.state.stage)
        assertEquals(CameraSessionOwnership.Owner.GALLERY,r.ownership.owner)
    }
    @Test fun cancelClosesBeforeRestoringAndDropsEveryOldCallback() {
        val r=Rig();r.connect();val old=r.events!!;r.flow.cancel()
        assertEquals(listOf("close","release"),r.calls.takeLast(2));assertEquals(GalleryConnectFlow.Stage.RESTORING,r.flow.state.stage)
        val late=Network();old.network(late);old.failed(GalleryConnectFlow.Category.INTERNAL);old.progress(GalleryConnectFlow.Stage.READY)
        assertEquals(1,late.closes);assertEquals(GalleryConnectFlow.Stage.RESTORING,r.flow.state.stage)
        r.restore();assertEquals(GalleryConnectFlow.Stage.CLOSED,r.flow.state.stage)
    }
    @Test fun updatedStoreEvidenceRefreshesReadyWithoutAcquiringOwnershipAgain() {
        val r=Rig();r.connect()
        val updated=listOf(GalleryCameraEvidence.Storage(GalleryCameraEvidence.Store.INTERNAL,80,40))
        r.events!!.ready(updated)
        assertEquals(GalleryConnectFlow.Stage.READY,r.flow.state.stage);assertEquals(updated,r.flow.state.storages)
        assertEquals(1,r.calls.count { it=="session" });assertFalse(r.calls.contains("close"))
    }
    @Test fun cancelPreparesWorkerExitBeforeInvalidatingItsNormalToken() {
        val r=Rig();r.connect();r.flow.cancel()
        assertEquals(1,r.prepares);assertFalse(r.token!!.active)
        assertEquals(listOf("close","release"),r.calls.takeLast(2))
    }
    @Test fun explicitStopNeverSchedulesPlaybackCleanupOrAutomaticRestoration() {
        val r=Rig();r.connect();r.flow.stop()
        assertEquals(0,r.prepares);assertFalse(r.token!!.active)
        assertFalse(r.calls.contains("release"));assertEquals(GalleryConnectFlow.Stage.STOPPED,r.flow.state.stage)
    }
    @Test fun stopDuringExitPreparationCannotResurrectRestoration() {
        val r=Rig();r.connect();r.onPrepare={r.flow.stop();r.ownership.stop()}
        r.flow.cancel()
        assertEquals(GalleryConnectFlow.Stage.STOPPED,r.flow.state.stage)
        assertFalse(r.token!!.active);assertFalse(r.calls.contains("release"))
    }
    @Test fun stopInvalidatesResourcesAndPendingRestartWithoutRestoreOrReplay() {
        val r=Rig();r.connect();r.flow.reconnectFromBeginning();assertEquals(GalleryConnectFlow.Stage.RESTORING,r.flow.state.stage)
        r.flow.stop();r.ownership.stop();r.flow.restored();r.events!!.credentials(r.credentials,r.identity)
        assertEquals(GalleryConnectFlow.Stage.STOPPED,r.flow.state.stage);assertEquals(1,r.calls.count {it=="ble"})
        val a=Rig();a.connect();a.flow.stop();assertFalse(a.calls.contains("release"))
    }
    @Test fun inactiveBridgeIsNeverAutomaticallyStartedOnExit() {
        val r=Rig(false);r.connect();r.flow.cancel()
        assertEquals(GalleryConnectFlow.Stage.CLOSED,r.flow.state.stage);assertEquals(CameraSessionOwnership.Owner.BRIDGE,r.ownership.owner)
    }
    @Test fun standaloneCannotAdvanceBeforeFreshAuthorizedIdleStatus() {
        val r=Rig(false,true)
        assertTrue(r.flow.start())
        val lease=r.token!!.lease
        assertEquals(GalleryConnectFlow.Stage.CHECKING_CAMERA,r.flow.state.stage)
        assertFalse(r.ownership.connected(lease));assertFalse(r.ownership.bridgeActionsAllowed)
        val idle=CameraSessionOwnership.Evidence(true,true,0,1,false)
        assertFalse(r.ownership.authorizeGalleryHandoff(lease,idle.copy(fresh=false)))
        assertFalse(r.ownership.authorizeGalleryHandoff(lease,idle.copy(cameraState=3)))
        r.events!!.credentials(r.credentials,r.identity)
        val stale=Network();r.events!!.network(stale)
        assertEquals(1,stale.closes);assertFalse("wifi" in r.calls);assertFalse("session" in r.calls)
        assertTrue(r.ownership.authorizeGalleryHandoff(lease,idle))
        r.events!!.progress(GalleryConnectFlow.Stage.BLE_CONNECTION)
        r.events!!.credentials(r.credentials,r.identity);r.events!!.network(Network())
        r.events!!.ready(listOf(GalleryCameraEvidence.Storage(GalleryCameraEvidence.Store.SD,100,50)))
        assertEquals(GalleryConnectFlow.Stage.READY,r.flow.state.stage)
        r.flow.cancel()
        assertEquals(GalleryConnectFlow.Stage.CLOSED,r.flow.state.stage)
        assertTrue(r.ownership.bridgeActionsAllowed)
    }
    @Test fun standaloneReconnectWaitsForCleanupAndInvalidatesOldCheck() {
        val r=Rig(false,true);r.delayFinish=true;r.flow.start();val old=r.token!!
        r.flow.reconnectFromBeginning()
        assertEquals(GalleryConnectFlow.Stage.RESTORING,r.flow.state.stage)
        assertEquals(1,r.calls.count { it=="acquire" })
        r.finishCallback!!.invoke()
        assertEquals(2,r.calls.count { it=="acquire" })
        assertEquals(GalleryConnectFlow.Stage.CHECKING_CAMERA,r.flow.state.stage)
        assertFalse(r.ownership.authorizeGalleryHandoff(old.lease,CameraSessionOwnership.Evidence(true,true,0,1,false)))
        assertFalse(old.active)
    }
    @Test fun duplicateCredentialsAndNetworkEventsDoNotOpenMultipleTransports() {
        val r=Rig();r.flow.start();r.events!!.credentials(r.credentials,r.identity);r.events!!.credentials(r.credentials,r.identity)
        r.events!!.network(Network());val duplicate=Network();r.events!!.network(duplicate)
        assertEquals(1,duplicate.closes);assertEquals(1,r.calls.count {it=="wifi"});assertEquals(1,r.calls.count {it=="session"})
    }
    @Test fun repeatedNetworkCallbackCannotCloseTheActiveNetwork() {
        val r=Rig();r.flow.start();r.events!!.credentials(r.credentials,r.identity)
        val network=Network();r.events!!.network(network);r.events!!.network(network)
        assertEquals(0,network.closes);r.flow.cancel();assertEquals(1,network.closes)
    }
    @Test fun bleDropAfterCredentialsDoesNotInventSuccessOrAbortNormalWifiHandoff() {
        val r=Rig();r.flow.start();r.events!!.credentials(r.credentials,r.identity);r.events!!.bleDisconnected()
        assertEquals(GalleryConnectFlow.Stage.WIFI_JOINING,r.flow.state.stage);assertFalse(r.calls.contains("close"))
        r.events!!.failed(GalleryConnectFlow.Category.TIMEOUT)
        assertEquals(GalleryConnectFlow.Stage.WIFI_JOINING,r.flow.state.failure!!.stage)
    }
    @Test fun bleDropBeforeCredentialExchangeFailsAndCleansUp() {
        val r=Rig();r.flow.start();r.events!!.bleDisconnected()
        assertEquals(GalleryConnectFlow.Category.BLUETOOTH,r.flow.state.failure!!.category)
        assertEquals(listOf("close","release"),r.calls.takeLast(2))
    }
    @Test fun failedWriteAndDisconnectOrdersAfterCredentialsDoNotAbortWifiReadiness() {
        val write=app.cameraderie.gallery.ble.GalleryBleClient.Failure.WRITE_FAILED
        val drop=app.cameraderie.gallery.ble.GalleryBleClient.Failure.DISCONNECTED
        for(order in listOf(listOf(write,drop),listOf(drop,write))) {
            val r=Rig();r.flow.start();r.events!!.credentials(r.credentials,r.identity)
            order.forEach { failure ->
                if(GalleryBleLossPolicy.defersToWifi(true,failure,null)) r.events!!.bleDisconnected()
                else r.events!!.failed(GalleryConnectFlow.Category.BLUETOOTH)
            }
            r.events!!.network(Network());r.events!!.ready(listOf(GalleryCameraEvidence.Storage(GalleryCameraEvidence.Store.SD,100,50)))
            assertEquals(GalleryConnectFlow.Stage.READY,r.flow.state.stage);assertFalse(r.calls.contains("close"))
        }
    }
    @Test fun invalidatedOwnershipClosesOldResourcesWithoutReleasingANewerLease() {
        val r=Rig();r.flow.start();val token=r.token!!
        r.ownership.stop();r.events!!.progress(GalleryConnectFlow.Stage.CREDENTIALS)
        assertFalse(token.active);assertTrue(r.calls.contains("close"));assertFalse(r.calls.contains("release"))
        assertEquals(GalleryConnectFlow.Stage.STOPPED,r.flow.state.stage)
    }
    @Test fun failureRetainsExactStageAndMetadataWhileRestorationIsSeparate() {
        val r=Rig();r.flow.start();r.now=2000;r.events!!.failed(GalleryConnectFlow.Category.REJECTED,GalleryConnectFlow.Stage.PLAYBACK,750,2,0xE0)
        val f=r.flow.state.failure!!;assertEquals(GalleryConnectFlow.Stage.PLAYBACK,f.stage);assertEquals(750L,f.elapsedMs);assertEquals(2,f.attempt);assertEquals(0xE0,f.responseCode)
        assertEquals(GalleryConnectFlow.Stage.RESTORING,r.flow.state.stage);r.restore()
        assertEquals(GalleryConnectFlow.Stage.FAILED,r.flow.state.stage);assertEquals(f,r.flow.state.failure)
    }
    @Test fun reconnectWithBridgeOffDoesNotCloseGalleryBetweenSessions() {
        val r = Rig(bridgeActive = false)
        r.connect()
        val old = r.token!!
        r.flow.reconnectFromBeginning()

        assertFalse(old.active)
        assertNotSame(old, r.token)
        assertEquals(2, r.calls.count { it == "ble" })
        assertTrue(r.states.none { it.stage == GalleryConnectFlow.Stage.CLOSED })
        assertEquals(GalleryConnectFlow.Stage.BLE_CONNECTION, r.flow.state.stage)
    }

    @Test fun reconnectWaitsForFreshRestorationThenStartsOneNewAttempt() {
        val r=Rig();r.connect();val old=r.events!!;r.flow.reconnectFromBeginning()
        assertEquals(1,r.calls.count {it=="ble"});r.restore();assertEquals(2,r.calls.count {it=="ble"})
        old.failed(GalleryConnectFlow.Category.INTERNAL);assertEquals(GalleryConnectFlow.Stage.BLE_CONNECTION,r.flow.state.stage)
    }
    @Test fun restorationTimeoutProvidesExplicitRetryInsteadOfLooping() {
        val r=Rig();r.connect();r.flow.cancel();r.flow.restorationFailed()
        assertEquals(GalleryConnectFlow.Stage.RESTORING,r.flow.state.failure!!.stage)
        assertEquals(1,r.calls.count {it=="ble"});r.flow.retry();assertEquals(1,r.calls.count {it=="retryRestore"})
        r.restore();assertEquals(2,r.calls.count {it=="ble"})
    }
    @Test fun entryFailureNeverCreatesTransportsOrMutatesOwnership() {
        val r=Rig();r.entryFails=true;assertFalse(r.flow.start());assertTrue(r.calls.isEmpty())
        assertEquals(GalleryConnectFlow.Category.PERMISSION,r.flow.state.failure!!.category)
        assertEquals(CameraSessionOwnership.Owner.BRIDGE,r.ownership.owner)
    }
    @Test fun cancelDismissesAnEntryErrorWithoutStartingAnything() {
        val r=Rig();r.entryFails=true;r.flow.start();r.flow.cancel()
        assertEquals(GalleryConnectFlow.Stage.CLOSED,r.flow.state.stage);assertTrue(r.calls.isEmpty())
    }
    @Test fun immediateRestoreFailureRetainsActualCategoryAndElapsedTime() {
        val r=Rig();r.connect();r.flow.cancel();r.flow.restorationFailed(5,GalleryConnectFlow.Category.PERMISSION)
        assertEquals(5L,r.flow.state.failure!!.elapsedMs)
        assertEquals(GalleryConnectFlow.Category.PERMISSION,r.flow.state.failure!!.category)
    }
    @Test fun cancellationFromStateObserverCannotStartLateBleWork() {
        val r=Rig();r.onChanged={ if(it.stage==GalleryConnectFlow.Stage.BLE_CONNECTION) r.flow.cancel() }
        assertFalse(r.flow.start());assertFalse(r.calls.contains("ble"));assertTrue(r.calls.contains("release"))
    }
    @Test fun stopFromRestorationObserverCannotReconnectTheCamera() {
        val r=Rig();r.connect();r.onChanged={ if(it.stage==GalleryConnectFlow.Stage.RESTORING) r.flow.stop() }
        r.flow.cancel();assertFalse(r.calls.contains("release"))
        assertEquals(GalleryConnectFlow.Stage.STOPPED,r.flow.state.stage)
    }
    @Test fun releaseExceptionCannotClaimClosedOrSuccessfulRestoration() {
        val r=Rig();r.connect();r.releaseFails=true;r.flow.cancel()
        assertEquals(GalleryConnectFlow.Stage.FAILED,r.flow.state.stage)
        assertEquals(GalleryConnectFlow.Stage.RESTORING,r.flow.state.failure!!.stage)
        assertEquals(GalleryConnectFlow.Category.INTERNAL,r.flow.state.failure!!.category)
    }
    @Test fun boundedPlaybackExitFinishesBeforeBridgeRestoreAndCannotBeOvertakenByRetry() {
        val r=Rig();r.connect();r.delayFinish=true;r.flow.cancel()
        assertFalse(r.token!!.active);assertFalse(r.calls.contains("release"))
        r.flow.retry();assertFalse(r.calls.contains("retryRestore"))
        r.finishCallback!!.invoke();assertEquals(listOf("close","release"),r.calls.takeLast(2))
        r.restore();assertEquals(2,r.calls.count {it=="ble"})
    }
    @Test fun stopDuringPlaybackExitClosesImmediatelyAndIgnoresLateCleanupCompletion() {
        val r=Rig();r.connect();r.delayFinish=true;r.flow.cancel();r.flow.stop()
        assertTrue(r.calls.contains("close"));r.finishCallback!!.invoke()
        assertFalse(r.calls.contains("release"));assertEquals(GalleryConnectFlow.Stage.STOPPED,r.flow.state.stage)
    }
    @Test fun wifiOffReportsItsActualStageWithoutTakingCameraOwnership() {
        val r=Rig();r.entryWifiDisabled=true;assertFalse(r.flow.start())
        assertEquals(GalleryConnectFlow.Stage.WIFI_JOINING,r.flow.state.failure!!.stage)
        assertEquals(GalleryConnectFlow.Category.WIFI_DISABLED,r.flow.state.failure!!.category)
        assertTrue(r.calls.isEmpty());assertEquals(CameraSessionOwnership.Owner.BRIDGE,r.ownership.owner)
    }
}
