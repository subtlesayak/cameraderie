package app.cameraderie.gallery.media.edit

import android.opengl.EGL14
import android.opengl.GLES30
import android.util.Half
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import app.cameraderie.NativeChecks
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(UnstableApi::class)
class Main10LutNativeTest {
    @Test fun interpolatesUnquantizedRgbInTheExportShader() {
        val display = GlUtil.getDefaultEglDisplay()
        val context = GlUtil.createEglContext(EGL14.EGL_NO_CONTEXT, display, 3,
            GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
        val surface = GlUtil.createFocusedPlaceholderEglSurface(context, display)
        try {
            val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).split(' ')
            val supportsFloat = "GL_EXT_color_buffer_float" in extensions
            assumeTrue(supportsFloat || "GL_EXT_color_buffer_half_float" in extensions)
            val lut = CubeLut.read("""
                LUT_3D_SIZE 2
                0.5001 0.2499 0.75
                0.5081 0.2499 0.75
                0.5001 0.2499 0.25
                0.5081 0.2499 0.25
                0.5001 0.4499 0.75
                0.5081 0.4499 0.75
                0.5001 0.4499 0.25
                0.5081 0.4499 0.25
            """.trimIndent().byteInputStream())
            val input = GlUtil.createTexture(1, 1, true)
            val inputBuffer = GlUtil.createFboForTexture(input)
            val output = GlUtil.createTexture(1, 1, true)
            val outputBuffer = GlUtil.createFboForTexture(output)
            fun assertSample(cube: CubeLut, r: Float, g: Float, b: Float, expected: FloatArray) {
                GlUtil.focusFramebufferUsingCurrentContext(inputBuffer, 1, 1)
                GLES30.glClearColor(r, g, b, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                GlUtil.focusFramebufferUsingCurrentContext(outputBuffer, 1, 1)
                val shader = Main10LutEffect(cube).toGlShaderProgram(NativeChecks.instance.targetContext, false)
                try {
                    shader.drawFrame(input, 0)
                    val data = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
                    val readType = if (supportsFloat) GLES30.GL_FLOAT else GLES30.GL_HALF_FLOAT
                    GLES30.glReadPixels(0, 0, 1, 1, GLES30.GL_RGBA, readType, data)
                    GlUtil.checkGlError()
                    val pixel = FloatArray(4) { index ->
                        if (supportsFloat) data.getFloat(index * 4) else Half.toFloat(data.getShort(index * 2))
                    }
                    val description = "${GLES30.glGetString(GLES30.GL_RENDERER)} LUT size ${cube.size} RGBA: ${pixel.joinToString()}"
                    expected.forEachIndexed { index, value -> assertEquals(description, value, pixel[index], 0.0006f) }
                    assertEquals(description, 1f, pixel[3], 0f)
                    Log.i("Main10LutNativeTest", description)
                } finally { shader.release() }
            }
            // Quantizing the original LUT to 8-bit would yield red at about 0.50392.
            assertSample(lut, 0.25f, 0.5f, 0.75f, floatArrayOf(0.5021f, 0.3999f, 0.5f))
            var component = 0
            val spanningRows = CubeLut.fromCheckpoint(9) {
                val index = component++
                val texel = index / 3
                when (index % 3) {
                    0 -> 0.3f + texel % 9 * 0.04f
                    1 -> 0.1f + texel / 81 * 0.07f
                    else -> 0.1f + texel / 9 % 9 * 0.1f
                }
            }
            // Blue at 7.5 blends the last slice of row one with the first of row two.
            assertSample(spanningRows, 0.3125f, 0.5625f, 0.9375f, floatArrayOf(0.4f, 0.625f, 0.55f))
        } finally {
            // The FBOs and textures are local to this standalone context.
            GlUtil.destroyEglContext(display, context)
            GlUtil.destroyEglSurface(display, surface)
        }
    }
}
