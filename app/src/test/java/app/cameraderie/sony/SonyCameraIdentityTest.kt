package app.cameraderie.sony
import org.junit.Assert.*
import org.junit.Test
class SonyCameraIdentityTest {
    @Test fun manufacturerMustBeSonyRatherThanContainItsName() { assertTrue(SonyCameraIdentity.sony("Sony Corporation"));assertFalse(SonyCameraIdentity.sony("Not Sony")) }
    @Test fun unknownModelsAreNotInventedFromAnEditableFriendlyNameAlone() { assertNull(SonyCameraIdentity.description("Sony","Wireless Device","","ILCE-7M3","",null)) }
    @Test fun genericSonyImagingDeviceNeedsItsMediaServerCapability() { assertEquals("Custom Camera",SonyCameraIdentity.description("Sony","SonyImagingDevice","","Custom Camera","urn:schemas-upnp-org:device:MediaServer:1",null));assertNull(SonyCameraIdentity.description("Sony","SonyImagingDevice","","Custom Camera","Other",null)) }
    @Test fun explicitModelMismatchAndConflictsRemainRejected() { assertFalse(SonyCameraIdentity.matchesExpected("ILCE-7M3","ILCE-6400"));assertNull(SonyCameraIdentity.description("Sony","ILCE-7M3","ILCE-6400","","",null)) }
    @Test fun modelLabelsAreBoundedAndDoNotBecomeNetworkCredentials() { assertNull(SonyCameraIdentity.label("bad\nname"));assertNull(SonyCameraIdentity.label("x".repeat(81)));assertEquals("ILCE-7M3",SonyCameraIdentity.expectedFromSsid("DIRECT-Tst1:ILCE-7M3"));assertNull(SonyCameraIdentity.expectedFromSsid("DIRECT-Tst1:Custom name")) }
}
