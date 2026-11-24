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
    @Test fun savedImportsUseExtraColumnWhenContentAndTypeFit() {
        val compact = SonyGalleryLayout.Mode.COMPACT
        assertEquals(4, SonyGalleryLayout.importedColumns(compact, 352, 1.15f))
        assertEquals(3, SonyGalleryLayout.importedColumns(compact, 351, 1f))
        assertEquals(2, SonyGalleryLayout.importedColumns(compact, 300, 1f))
        assertEquals(2, SonyGalleryLayout.importedColumns(compact, 379, 1.3f))
        assertEquals(1, SonyGalleryLayout.importedColumns(compact, 379, 2f))
        assertEquals(2, SonyGalleryLayout.importedColumns(SonyGalleryLayout.Mode.COMFORTABLE, 379, 1f))
        assertEquals(3, SonyGalleryLayout.columns(compact, 379, 1f))
    }
}
