package app.cameraderie.gallery

import app.cameraderie.gallery.protocol.GalleryCameraEvidence
import org.junit.Assert.*
import org.junit.Test

class GalleryConnectionUiTest {
    @Test fun usesLiveEvidenceAndAsksWhenTheCameraChoiceIsAmbiguous() {
        val entry = GalleryConnectionUi::entry
        assertEquals(GalleryConnectionUi.Entry.DJI, entry(true, false))
        assertEquals(GalleryConnectionUi.Entry.SONY_USB, entry(false, true))
        assertEquals(GalleryConnectionUi.Entry.CHOOSE, entry(true, true))
        assertEquals(GalleryConnectionUi.Entry.CHOOSE, entry(false, false))
    }
    @Test fun readyReportsOnlyObservedStorageAndRequiresExplicitMediaRequest() {
        val model=GalleryConnectionUi.render(GalleryConnectFlow.State(GalleryConnectFlow.Stage.READY,storages=listOf(
            GalleryCameraEvidence.Storage(GalleryCameraEvidence.Store.SD,100,50),
            GalleryCameraEvidence.Storage(GalleryCameraEvidence.Store.INTERNAL,0,0))),true)
        assertTrue(model.detail.contains("SD card"));assertFalse(model.detail.contains("internal storage"))
        assertTrue(model.detail.contains("refresh icon"));assertTrue(model.detail.contains("does not establish listing or import completion"));assertFalse(model.busy)
        assertFalse(model.connect);assertTrue(model.cancel)
    }
    @Test fun failureDuringRestorationRetainsBothOriginalStepAndCurrentOwnership() {
        val model=GalleryConnectionUi.render(GalleryConnectFlow.State(GalleryConnectFlow.Stage.RESTORING,
            failure=GalleryConnectFlow.Failure(GalleryConnectFlow.Stage.WIFI_JOINING,GalleryConnectFlow.Category.WIFI_UNAVAILABLE,200,1)),true)
        assertTrue(model.title.contains("Restoring"));assertTrue(model.detail.contains("Joining camera Wi-Fi"))
        assertTrue(model.busy);assertFalse(model.retry);assertFalse(model.connect)
    }
    @Test fun stoppedBridgeWithoutAuthorizedBookmarkCannotLaunchAnyConnectionOrRetry() {
        for(stage in GalleryConnectFlow.Stage.entries) {
            val model=GalleryConnectionUi.render(GalleryConnectFlow.State(stage),false)
            assertFalse(model.connect);assertFalse(model.retry);assertFalse(model.reconnect)
        }
    }
    @Test fun authorizedBookmarkEnablesGalleryWithBridgeOffAndOffersBusyRecovery() {
        val initial=GalleryConnectionUi.render(GalleryConnectFlow.State(),false,true)
        assertTrue(initial.connect)
        val checking=GalleryConnectionUi.render(GalleryConnectFlow.State(GalleryConnectFlow.Stage.CHECKING_CAMERA),false,true)
        assertTrue(checking.busy);assertTrue(checking.cancel);assertFalse(checking.connect)
        val failure=GalleryConnectFlow.Failure(GalleryConnectFlow.Stage.CHECKING_CAMERA,GalleryConnectFlow.Category.CAMERA_BUSY,1000,1)
        val busy=GalleryConnectionUi.render(GalleryConnectFlow.State(GalleryConnectFlow.Stage.FAILED,failure=failure),false,true)
        assertTrue(busy.retry);assertTrue(busy.detail.contains("Finish the capture"))
        val closing=GalleryConnectionUi.render(GalleryConnectFlow.State(GalleryConnectFlow.Stage.RESTORING),false,true)
        assertEquals("Closing camera gallery",closing.title)
    }
    @Test fun bluetoothFailureDuringReadinessCheckReportsThatTheSavedCameraCouldNotBeChecked() {
        val failure = GalleryConnectFlow.Failure(GalleryConnectFlow.Stage.CHECKING_CAMERA,
            GalleryConnectFlow.Category.BLUETOOTH, 1000, 1)
        val model = GalleryConnectionUi.render(
            GalleryConnectFlow.State(GalleryConnectFlow.Stage.FAILED, failure = failure), false, true)
        assertTrue(model.detail.contains("Couldn’t check the saved camera over Bluetooth"))
        assertFalse(model.detail.contains("previous camera link"))
        assertTrue(model.retry)
    }

    @Test fun unknownPermissionResultCannotTurnIntoReadiness() {
        val model=GalleryConnectionUi.render(GalleryConnectFlow.State(GalleryConnectFlow.Stage.FAILED,
            failure=GalleryConnectFlow.Failure(GalleryConnectFlow.Stage.BLE_CONNECTION,GalleryConnectFlow.Category.PERMISSION,0,0)),true)
        assertTrue(model.detail.contains("permission"));assertFalse(model.busy);assertTrue(model.retry)
        assertFalse(model.title.contains("ready"))
    }
    @Test fun wifiOffHasAnActionableRadioMessageWithoutInventingAConnectedCamera() {
        val model=GalleryConnectionUi.render(GalleryConnectFlow.State(GalleryConnectFlow.Stage.FAILED,
            failure=GalleryConnectFlow.Failure(GalleryConnectFlow.Stage.WIFI_JOINING,GalleryConnectFlow.Category.WIFI_DISABLED,0,0)),true)
        assertTrue(model.detail.contains("Turn on Wi-Fi, then retry."))
        assertTrue(model.retry);assertFalse(model.connect);assertFalse(model.busy)
    }
}
