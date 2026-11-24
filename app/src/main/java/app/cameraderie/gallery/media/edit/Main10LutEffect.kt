package app.cameraderie.gallery.media.edit

import android.content.Context
import android.opengl.GLES30
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import app.cameraderie.R
import java.io.IOException

/** A floating-point 3D texture and render target for the Rec.709 Main 10 export graph. */
@OptIn(UnstableApi::class)
internal class Main10LutEffect(private val lut: CubeLut) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram {
        check(!useHdr) { "The LUT expects SDR Rec.709." }
        return try {
            LutProgram(context, lut)
        } catch (error: IOException) {
            throw VideoFrameProcessingException(error)
        } catch (error: GlUtil.GlException) {
            throw VideoFrameProcessingException(error)
        }
    }

    private class LutProgram(context: Context, lut: CubeLut) : BaseGlShaderProgram(true, 1) {
        private val program = GlProgram(context, R.raw.main10_lut_vertex, R.raw.main10_lut_fragment)
        private val texture: Int

        init {
            try {
                GLES30.glUniform1i(program.getUniformLocation("sourceFrame"), 0)
                GLES30.glUniform1i(program.getUniformLocation("colorCube"), 1)
                GLES30.glUniform1f(program.getUniformLocation("cubeSize"), lut.size.toFloat())
                texture = uploadCube(lut)
            } catch (error: GlUtil.GlException) {
                try {
                    program.delete()
                } catch (cleanup: GlUtil.GlException) {
                    error.addSuppressed(cleanup)
                }
                throw error
            }
        }

        override fun configure(inputWidth: Int, inputHeight: Int) = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                program.use()
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexId)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texture)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GlUtil.checkGlError()
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error, presentationTimeUs)
            }
        }

        override fun release() {
            try {
                super.release()
            } finally {
                GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
                program.delete()
            }
        }

        private fun uploadCube(lut: CubeLut): Int {
            val id = IntArray(1)
            GLES30.glGenTextures(1, id, 0)
            val target = GLES30.GL_TEXTURE_3D
            try {
                GLES30.glBindTexture(target, id[0])
                GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
                val size = lut.size
                GLES30.glTexStorage3D(target, 1, GLES30.GL_RGB16F, size, size, size)
                // Each blue slice holds a tightly packed, x-fastest RGB plane. Upload
                // each slice so GLES implementations receive its bytes and depth offset.
                val componentsPerSlice = size * size * 3
                val source = GlUtil.createBuffer(lut.rgb)
                for (slice in 0 until size) {
                    source.position(slice * componentsPerSlice)
                    source.limit((slice + 1) * componentsPerSlice)
                    GLES30.glTexSubImage3D(target, 0, 0, 0, slice, size, size, 1,
                        GLES30.GL_RGB, GLES30.GL_FLOAT, source)
                }
                GlUtil.checkGlError()
                return id[0]
            } catch (error: GlUtil.GlException) {
                GLES30.glDeleteTextures(1, id, 0)
                throw error
            } finally {
                GLES30.glBindTexture(target, 0)
            }
        }
    }
}
