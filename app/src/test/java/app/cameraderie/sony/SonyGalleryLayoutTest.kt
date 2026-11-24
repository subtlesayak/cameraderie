package app.cameraderie.sony

import org.junit.Assert.*
import org.junit.Test

class SonyGalleryLayoutTest {
    @Test fun normalPhoneCanChooseThreeOrTwoColumnsOrList() {
        assertEquals(3,SonyGalleryLayout.columns(SonyGalleryLayout.Mode.COMPACT,379,1f))
        assertEquals(2,SonyGalleryLayout.columns(SonyGalleryLayout.Mode.COMFORTABLE,379,1f))
        assertEquals(1,SonyGalleryLayout.columns(SonyGalleryLayout.Mode.LIST,379,1f))
    }
    @Test fun largerTextCannotForceCompactColumns() {
        assertEquals(2,SonyGalleryLayout.columns(SonyGalleryLayout.Mode.COMPACT,379,1.3f))
        assertEquals(1,SonyGalleryLayout.columns(SonyGalleryLayout.Mode.COMPACT,379,1.5f))
    }
    @Test fun narrowWindowFallsBackWithoutShrinkingTouchTargets() {
        assertFalse(SonyGalleryLayout.compactAvailable(300,1f))
        assertEquals(2,SonyGalleryLayout.columns(SonyGalleryLayout.Mode.COMPACT,300,1f))
        assertEquals(1,SonyGalleryLayout.columns(SonyGalleryLayout.Mode.COMPACT,260,1f))
    }
}
