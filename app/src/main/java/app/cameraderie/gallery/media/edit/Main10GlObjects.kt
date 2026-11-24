package app.cameraderie.gallery.media.edit

import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultGlObjectsProvider

/** Media3 normally uses 8-bit textures and an 8-bit EGL window for SDR. */
@OptIn(UnstableApi::class)
internal class Main10GlObjects(
    private val defaults: GlObjectsProvider = DefaultGlObjectsProvider()
) : GlObjectsProvider by defaults {
    override fun createEglContext(display: EGLDisplay, openGlVersion: Int, configAttributes: IntArray): EGLContext =
        defaults.createEglContext(display, maxOf(3, openGlVersion), GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_1010102)

    override fun createEglSurface(
        display: EGLDisplay, surface: Any, colorTransfer: Int, isEncoderInputSurface: Boolean
    ): EGLSurface {
        if (!isEncoderInputSurface) return defaults.createEglSurface(display, surface, colorTransfer, false)
        // The HDR selector asks GlUtil for RGB10_A2. For an encoder it adds no EGL color-space
        // attribute. The graph and codec retain their SDR transfer and Rec.709 metadata.
        return GlUtil.createEglSurface(display, surface, C.COLOR_TRANSFER_HLG, true)
    }

    override fun createBuffersForTexture(texId: Int, width: Int, height: Int): GlTextureInfo {
        // The default processor calls this for its freshly allocated render targets.
        val previous = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_2D, previous, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        try {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, width, height, 0,
                GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
            GlUtil.checkGlError()
        } finally {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, previous[0])
        }
        return defaults.createBuffersForTexture(texId, width, height)
    }
}
