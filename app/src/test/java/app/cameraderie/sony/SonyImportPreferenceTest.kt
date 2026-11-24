package app.cameraderie.sony
import org.junit.Assert.*
import org.junit.Test
class SonyImportPreferenceTest {
    @Test fun missingOrUnknownValueUsesOriginalJpeg() { assertEquals(SonyImportPreference.ORIGINAL,SonyImportPreference.parse(null));assertEquals(SonyImportPreference.ORIGINAL,SonyImportPreference.parse("outdated")) }
    @Test fun stableNamesRoundTripWithoutOrdinalDependence() { SonyImportPreference.entries.forEach { assertEquals(it,SonyImportPreference.parse(it.name)) } }
    @Test fun onlyAskLacksAnImportSize() { assertNull(SonyImportPreference.ASK.size);assertEquals(SonyImportSize.TWO_MEGAPIXEL_JPEG,SonyImportPreference.TWO_M.size);assertEquals(4,SonyImportPreference.entries.count { it.size!=null }) }
}
