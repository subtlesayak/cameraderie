package app.cameraderie.sony
import org.junit.Assert.*
import org.junit.Test
class SonyScreenProtectionTest {
    @Test fun withoutConsentBothSetupAndGalleryRemainProtected() { assertTrue(SonyScreenProtection.required(false,false));assertTrue(SonyScreenProtection.required(false,true)) }
    @Test fun consentAllowsOnlyTheVisibleGallery() { assertFalse(SonyScreenProtection.required(true,true)) }
    @Test fun connectionDetailsRemainProtectedWithConsent() { assertTrue(SonyScreenProtection.required(true,false)) }
}
