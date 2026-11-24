package app.cameraderie.gallery.media.edit

import org.junit.Assert.*
import org.junit.Test

class CubeLutTest {
    private val identity = """
        # Fixture written in red-fastest order
        TITLE "Identity"
        LUT_3D_SIZE 2
        DOMAIN_MIN 0 0 0
        DOMAIN_MAX 1 1 1
        0 0 0
        1 0 0
        0 1 0
        1 1 0
        0 0 1
        1 0 1
        0 1 1
        1 1 1
    """.trimIndent()

    @Test fun assignsRedFastestCubeRowsToTheCorrectMedia3Axes() {
        val cube = CubeLut.read(("\uFEFF" + identity.replace("\n", "\r\n")).byteInputStream()).toArgbCube()
        assertEquals(0xffff0000.toInt(), cube[1][0][0])
        assertEquals(0xff00ff00.toInt(), cube[0][1][0])
        assertEquals(0xff0000ff.toInt(), cube[0][0][1])
        assertEquals(0xffffffff.toInt(), cube[1][1][1])
    }

    @Test fun clipsFiniteOutputToSdrRange() {
        val cube = CubeLut.read(identity
            .replaceAfterLast('\n', "-0.1 1.2 0.5").byteInputStream()).toArgbCube()
        assertEquals(0xff00ff80.toInt(), cube[1][1][1])
    }

    @Test fun preservesComponentsBetweenEightBitStepsInRedFastestOrder() {
        val lut = CubeLut.read(identity
            .replace("\n1 0 0", "\n0.5001 0.5009 0.3123")
            .replace("\n0 1 0", "\n0.6671 0.4219 0.6537")
            .byteInputStream())
        assertEquals(2, lut.size)
        assertArrayEquals(floatArrayOf(0.5001f, 0.5009f, 0.3123f), lut.rgb.copyOfRange(3, 6), 0f)
        assertArrayEquals(floatArrayOf(0.6671f, 0.4219f, 0.6537f), lut.rgb.copyOfRange(6, 9), 0f)
        assertEquals(0xff808050.toInt(), lut.toArgbCube()[1][0][0])
    }

    @Test fun rejectsInvalidProfilesAndTruncatedOrOverlongColorTables() {
        for (lut in listOf(identity.replace("LUT_3D_SIZE", "LUT_1D_SIZE"),
            identity.replace("DOMAIN_MIN 0", "DOMAIN_MIN -1"),
            identity.replace("0 0 1", "NaN 0 1"),
            identity.substringBeforeLast('\n'), identity + "\n1 1 1", "#".repeat(4097) + "\n" + identity)) {
            assertThrows(IllegalArgumentException::class.java) { CubeLut.read(lut.byteInputStream()) }
        }
    }
}
