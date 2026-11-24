package app.cameraderie.gallery.media

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Checks the container and the first audio extent against the independently verified byte count. */
internal object GalleryWave {
    private const val SIZE_64 = 0xffff_ffffL

    fun verify(input: InputStream, length: Long) {
        val stream = DataInputStream(input)
        val header = ByteArray(12).also(stream::readFully)
        check(header.id(8) == "WAVE")
        var remaining = length - header.size
        var data64: Long? = null
        var riffSize = header.size32(4)
        val chunk = ByteArray(8)
        when (header.id()) {
            "RIFF" -> Unit
            "RF64" -> {
                check(remaining >= 36)
                stream.readFully(chunk)
                check(chunk.id() == "ds64")
                val size = chunk.size32(4)
                val padded = paddedSize(size, remaining - chunk.size)
                check(size >= 28)
                val ds64 = ByteArray(28).also(stream::readFully)
                val sizes = ByteBuffer.wrap(ds64).order(ByteOrder.LITTLE_ENDIAN)
                if (riffSize == SIZE_64) riffSize = sizes.getLong(0)
                data64 = sizes.getLong(8)
                val entries = ds64.size32(24)
                check(entries <= (size - ds64.size) / 12)
                stream.skipBytesExactly(padded - ds64.size)
                remaining -= chunk.size + padded
            }
            else -> error("Camera audio is not a WAV file")
        }
        check(riffSize == length - 8)
        var formatFound = false
        while (remaining >= chunk.size) {
            stream.readFully(chunk)
            remaining -= chunk.size
            val id = chunk.id()
            val size32 = chunk.size32(4)
            val size = if (id == "data" && size32 == SIZE_64) checkNotNull(data64) else size32
            val padded = paddedSize(size, remaining)
            when (id) {
                "fmt " -> {
                    check(!formatFound && size >= 16)
                    val format = ByteArray(16).also(stream::readFully)
                    val values = ByteBuffer.wrap(format).order(ByteOrder.LITTLE_ENDIAN)
                    check(values.getShort(0).toInt() != 0 && values.getShort(2).toInt() != 0 &&
                        format.size32(4) > 0 && values.getShort(12).toInt() != 0)
                    stream.skipBytesExactly(padded - format.size)
                    formatFound = true
                }
                "data" -> {
                    check(formatFound && size > 0)
                    // Publication already checks the complete file length and digest.
                    return
                }
                else -> stream.skipBytesExactly(padded)
            }
            remaining -= padded
        }
        error("Camera WAV has no audio data")
    }

    private fun paddedSize(size: Long, remaining: Long): Long {
        check(size in 0..remaining)
        val padded = size + (size and 1L)
        check(padded <= remaining)
        return padded
    }

    private fun ByteArray.id(offset: Int = 0) = String(this, offset, 4, Charsets.US_ASCII)
    private fun ByteArray.size32(offset: Int) =
        ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).getInt(offset).toLong() and SIZE_64

    private fun DataInputStream.skipBytesExactly(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = skip(remaining)
            remaining -= if (skipped > 0) skipped else { readByte(); 1L }
        }
    }
}
