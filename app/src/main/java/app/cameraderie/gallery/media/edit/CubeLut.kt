package app.cameraderie.gallery.media.edit

import java.io.InputStream
import java.io.Reader
import kotlin.math.roundToInt

/** Interleaved RGB rows in the original red-fastest .cube order. */
internal class CubeLut private constructor(val size: Int, val rgb: FloatArray) {
    /** Media3's SDR preview and AVC effect takes 8-bit ARGB entries in [red][green][blue] order. */
    fun toArgbCube(): Array<Array<IntArray>> = Array(size) { red ->
        Array(size) { green ->
            IntArray(size) { blue ->
                val index = 3 * (red + size * (green + size * blue))
                fun channel(offset: Int) = (rgb[index + offset] * 255f).roundToInt()
                (255 shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
            }
        }
    }

    companion object {
        internal fun fromCheckpoint(size: Int, read: () -> Float): CubeLut {
            require(size in 2..63)
            val colors = FloatArray(3 * size * size * size) {
                read().also { require(it in 0f..1f) }
            }
            return CubeLut(size, colors)
        }

        fun read(input: InputStream): CubeLut {
            var cube: FloatArray? = null
            var size = 0
            var entries = 0
            val reader = input.bufferedReader(Charsets.UTF_8)
            var chars = 0
            while (true) {
                val line = readLine(reader) ?: break
                chars += line.length + 1
                require(chars <= 12 * 1024 * 1024)
                val text = line.removePrefix("\uFEFF").substringBefore('#').trim()
                if (text.isEmpty()) continue
                val parts = text.split(whitespace)
                when (parts[0]) {
                    "TITLE" -> require(entries == 0)
                    "LUT_3D_SIZE" -> {
                        require(cube == null && parts.size == 2)
                        size = requireNotNull(parts[1].toIntOrNull())
                        require(size in 2..63)
                        cube = FloatArray(3 * size * size * size)
                    }
                    "DOMAIN_MIN", "DOMAIN_MAX" -> {
                        val bound = if (parts[0] == "DOMAIN_MIN") 0f else 1f
                        require(entries == 0 && parts.size == 4 && parts.drop(1).all { it.toFloatOrNull() == bound })
                    }
                    "LUT_3D_INPUT_RANGE" ->
                        require(entries == 0 && parts.size == 3 && parts[1].toFloatOrNull() == 0f &&
                            parts[2].toFloatOrNull() == 1f)
                    else -> {
                        val colors = requireNotNull(cube)
                        require(parts.size == 3 && entries < colors.size / 3)
                        val index = 3 * entries
                        colors[index] = channel(parts[0])
                        colors[index + 1] = channel(parts[1])
                        colors[index + 2] = channel(parts[2])
                        entries++
                    }
                }
            }
            val colors = requireNotNull(cube)
            require(entries == colors.size / 3)
            return CubeLut(size, colors)
        }

        private val whitespace = Regex("\\s+")

        private fun readLine(reader: Reader): String? {
            val line = StringBuilder()
            while (true) {
                val char = reader.read()
                if (char == -1) return line.toString().ifEmpty { null }
                if (char == '\n'.code) return line.toString()
                require(line.length < 4096)
                line.append(char.toChar())
            }
        }

        private fun channel(text: String): Float {
            val value = requireNotNull(text.toFloatOrNull())
            require(value.isFinite())
            return value.coerceIn(0f, 1f)
        }
    }
}
