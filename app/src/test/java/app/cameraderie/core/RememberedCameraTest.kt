package app.cameraderie.core

import app.gripbridge.core.CameraBookmark
import app.gripbridge.core.CameraBookmarkStore
import app.gripbridge.core.RememberedCamera
import org.junit.Assert.*
import org.junit.Test

class RememberedCameraTest {
    private class Store : CameraBookmarkStore {
        var camera: CameraBookmark? = null
        var migrated = false
        var history = listOf<String>()
        var writable = true
        override fun read() = camera
        override fun migrationDone() = migrated
        override fun authorizedAddresses() = history
        override fun save(camera: CameraBookmark?, migrated: Boolean): Boolean {
            if (!writable) return false
            this.camera = camera; this.migrated = migrated; return true
        }
    }
    private val first = "02:00:00:00:00:01"
    private val second = "02:00:00:00:00:02"
    @Test fun emptyInstallDoesNotSelectAnUnknownCamera() {
        val store = Store(); assertNull(RememberedCamera(store).current); assertTrue(store.migrated)
    }
    @Test fun singlePreviouslyAuthorizedCameraIsMigratedWithoutScanning() {
        val store = Store(); store.history = listOf(first)
        assertEquals(first,RememberedCamera(store).current?.address)
        assertTrue(store.migrated)
    }
    @Test fun ambiguousHistoryRequiresUserSelection() {
        val store = Store(); store.history = listOf(first,second)
        assertNull(RememberedCamera(store).current)
    }
    @Test fun explicitSelectionWinsOverHistory() {
        val store = Store(); store.history = listOf(first); store.camera = CameraBookmark(second,"Studio camera")
        assertEquals(second,RememberedCamera(store).current?.address)
    }
    @Test fun authorizedChoiceSurvivesNewControllerAndNormalizesLabel() {
        val store = Store(); val memory = RememberedCamera(store)
        assertTrue(memory.rememberAuthorized(first.lowercase(),"  Action 6\n  "))
        assertEquals(CameraBookmark(first,"Action 6"),RememberedCamera(store).current)
    }
    @Test fun forgetDoesNotDeleteHistoryOrResurrectAfterRestart() {
        val store = Store(); store.history = listOf(first); val memory = RememberedCamera(store)
        assertTrue(memory.forget()); assertNull(RememberedCamera(store).current)
        assertEquals(listOf(first),store.history)
    }
    @Test fun invalidAddressesCannotReplaceSavedCamera() {
        val store = Store(); val memory = RememberedCamera(store)
        memory.rememberAuthorized(first,"Main camera")
        assertFalse(memory.rememberAuthorized("invalid","Other")); assertEquals(first,memory.current?.address)
        val invalid = Store(); invalid.camera = CameraBookmark("broken","Invalid"); invalid.migrated = true
        assertNull(RememberedCamera(invalid).current)
    }
    @Test fun failedStorageIsNotReportedAsSaved() {
        val store = Store(); store.writable = false; val memory = RememberedCamera(store)
        assertFalse(memory.rememberAuthorized(first,"Camera")); assertNull(memory.current)
    }
    @Test fun duplicateHistoryAddressesAreOneCamera() {
        val store = Store(); store.history = listOf(first,first.lowercase())
        assertEquals(first,RememberedCamera(store).current?.address)
    }
}
