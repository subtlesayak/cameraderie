package app.cameraderie.gallery

import app.cameraderie.gallery.media.ImportPercentage
import org.junit.Assert.*
import org.junit.Test

class ImportPercentageTest {
    @Test fun reportsActualCopyPercentageAndClampsTheEnd() {
        assertEquals(0,ImportPercentage.copied(0,100));assertEquals(37,ImportPercentage.copied(37,100));assertEquals(100,ImportPercentage.copied(101,100))
    }
    @Test fun unknownTotalsRemainIndeterminate() {
        assertNull(ImportPercentage.copied(10,0));assertNull(ImportPercentage.copied(-1,100))
    }
    @Test fun largeLengthsCannotOverflowTheCalculation() { assertEquals(50,ImportPercentage.copied(Long.MAX_VALUE/2,Long.MAX_VALUE)) }
}
