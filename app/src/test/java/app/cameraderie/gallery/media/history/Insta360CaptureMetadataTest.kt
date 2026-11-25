package app.cameraderie.gallery.media.history

import app.cameraderie.gallery.media.AlbumMediaFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.time.LocalTime

class Insta360CaptureMetadataTest {
    @Test fun readsIndexedTrailerModelAndCameraClockDuringDocumentImport() {
        val model = "Insta360 X4".toByteArray()
        val clock = byteArrayOf(0x38, 0xd9.toByte(), 0x8c.toByte(), 0x89.toByte(), 0xb6.toByte(), 0x89.toByte(), 0xcd.toByte(), 0x04)
        val makerNotes = byteArrayOf(0x12, model.size.toByte()) + model + clock
        fixture(trailer(makerNotes)) { staged ->
            val original = checkNotNull(AlbumMediaFile.document("VID_20240415_213145_00_035.INSV"))
            val metadata = ImportCaptureMetadata.readDocument(original, staged)
            assertEquals("Insta360 X4", metadata.cameraModel)
            assertEquals(LocalDate.of(2024, 4, 15), metadata.time?.date)
            assertEquals(LocalTime.of(21, 31, 45), metadata.time?.time)
            assertNull(metadata.time?.offset)
            assertEquals("INSTA360_TRAILER", metadata.source)
        }
    }

    @Test fun rejectsOutOfBoundsRecordsEvenWithTheRightSignature() {
        val damaged = trailer(byteArrayOf(0x12, 0x20))
        ByteBuffer.wrap(damaged).order(ByteOrder.LITTLE_ENDIAN).putInt(damaged.size - 76, Int.MAX_VALUE)
        fixture(damaged) { assertNull(Insta360CaptureMetadata.read(it)) }
    }

    @Test fun keepsTheModelWithoutAUsableCameraClock() {
        val model = "Insta360 X4".toByteArray()
        val makerNotes = byteArrayOf(0x12, model.size.toByte()) + model + byteArrayOf(0x38, 0x01)
        fixture(trailer(makerNotes)) { staged ->
            val metadata = checkNotNull(Insta360CaptureMetadata.read(staged))
            assertEquals("Insta360 X4", metadata.cameraModel)
            assertNull(metadata.time)
            assertNull(metadata.source)
        }
    }

    private fun fixture(bytes: ByteArray, test: (File) -> Unit) {
        val file = File.createTempFile("camera-trailer", ".part")
        try {
            file.writeBytes(ByteArray(32) + bytes)
            test(file)
        } finally { file.delete() }
    }

    private fun trailer(notes: ByteArray): ByteArray {
        val maker = record(0x101, notes)
        val directory = record(0, ByteArray(20))
        val footer = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(32)
            putInt(maker.size + directory.size + capacity())
            putInt(3)
            put("8db42d694ccc418790edff439fe026bf".toByteArray(Charsets.US_ASCII))
        }
        return maker + directory + footer.array()
    }

    private fun record(id: Int, data: ByteArray): ByteArray = data + ByteBuffer.allocate(6)
        .order(ByteOrder.LITTLE_ENDIAN).putShort(id.toShort()).putInt(data.size).array()
}
