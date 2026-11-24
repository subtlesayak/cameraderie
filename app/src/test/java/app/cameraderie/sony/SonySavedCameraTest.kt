package app.cameraderie.sony

import org.junit.Assert.*
import org.junit.Test

class SonySavedCameraTest {
    @Test fun cameraDetailsAreRedactedInDiagnostics() {
        val camera=SonySavedCamera("01234567-89ab-cdef-0123-456789abcdef","ILCE-6400","DIRECT-Test:ILCE-6400","test-only-123",1)
        assertFalse(camera.toString().contains(camera.password));assertFalse(camera.toString().contains(camera.ssid))
    }
    @Test fun newlyValidatedSonyMediaModelsCanBeRememberedWithoutChangingTheExistingIdentity() {
        val camera=SonySavedCamera("01234567-89ab-cdef-0123-456789abcdef","ILCE-7M3","DIRECT-Test:ILCE-7M3","example123",1)
        assertEquals("ILCE-7M3",camera.model);assertFalse(camera.toString().contains(camera.model))
    }
    @Test fun invalidCredentialsAndUnsafeLabelsCannotBeRemembered() {
        for(model in listOf("","unsafe\nlabel","x".repeat(81))) try {
            SonySavedCamera("01234567-89ab-cdef-0123-456789abcdef",model,"DIRECT-Test:ILCE-6400","test-only-123",1);fail()
        } catch(_:IllegalArgumentException) { }
        try { SonySavedCamera("01234567-89ab-cdef-0123-456789abcdef","ILCE-6400","not-camera","short",1);fail() } catch(_:IllegalArgumentException) { }
    }
}
