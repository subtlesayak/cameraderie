package app.cameraderie.gallery

import app.cameraderie.gallery.media.GalleryWave
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertThrows
import org.junit.Test

class GalleryWaveTest {
    @Test fun acceptsRiffWithOddSizedMetadataAndRf64WithDataSizeSentinel() {
        val metadata = chunk("JUNK", byteArrayOf(1, 2, 3))
        verify(pcmWave(metadata))

        val data = chunk("data", byteArrayOf(1, 0), -1)
        val riffSize = 4L + 36 + format.size + data.size
        val ds64 = chunk("ds64", littleEndian(28).putLong(riffSize).putLong(2).putLong(1).putInt(0).array())
        verify(header("RF64", -1) + ds64 + format + data)
    }

    @Test fun rejectsHeaderOnlyMissingFormatAndAudioPastTheRiffExtent() {
        assertThrows(IllegalStateException::class.java) { verify(header("RIFF", 4)) }
        assertThrows(IllegalStateException::class.java) {
            verify(header("RIFF", 14) + chunk("data", byteArrayOf(1, 0)))
        }
        val truncatedData = pcmWave().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 8) }
        assertThrows(IllegalStateException::class.java) { verify(truncatedData) }
    }

    private fun verify(bytes: ByteArray) = GalleryWave.verify(bytes.inputStream(), bytes.size.toLong())
}

internal fun pcmWave(metadata: ByteArray = byteArrayOf()): ByteArray {
    val body = metadata + format + chunk("data", byteArrayOf(1, 0))
    return header("RIFF", 4 + body.size) + body
}

private val format = chunk("fmt ", littleEndian(16)
    .putShort(1).putShort(1).putInt(48_000).putInt(96_000).putShort(2).putShort(16).array())

private fun header(id: String, size: Int): ByteArray =
    littleEndian(12).put(id.toByteArray(Charsets.US_ASCII)).putInt(size).put("WAVE".toByteArray()).array()

private fun chunk(id: String, body: ByteArray, advertisedSize: Int = body.size): ByteArray =
    littleEndian(8 + body.size + (body.size and 1)).put(id.toByteArray(Charsets.US_ASCII))
        .putInt(advertisedSize).put(body).array()

private fun littleEndian(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
