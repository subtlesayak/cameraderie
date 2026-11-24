package app.cameraderie.sony

import app.gripbridge.core.Recording
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class SonyUsbStatusTest {
    @Test fun readsIdleAndIsoFromSonyPropertyList() {
        assertEquals(SonyUsbStatus(Recording.IDLE, "500"), SonyUsbStatus.parse(properties(recording = 0, iso = 500)))
        assertEquals(SonyUsbStatus(Recording.IDLE, "Auto"), SonyUsbStatus.parse(properties(recording = 0, iso = 0xffffff)))
    }

    @Test fun activeAndPausedRecordingBothPreventGalleryHandoff() {
        for (value in listOf(1, 2)) {
            assertEquals(Recording.RECORDING, SonyUsbStatus.parse(properties(recording = value, iso = 640)).recording)
        }
        assertEquals(Recording.UNKNOWN, SonyUsbStatus.parse(properties(recording = 3, iso = 640)).recording)
    }

    @Test fun malformedPropertyListsCannotAuthorizeHandoff() {
        val valid = properties(recording = 0, iso = 500)
        assertEquals(SonyUsbStatus.unknown, SonyUsbStatus.parse(valid.copyOf(valid.size - 1)))
        assertEquals(SonyUsbStatus.unknown, SonyUsbStatus.parse(valid + byteArrayOf(0)))
        assertEquals(SonyUsbStatus.unknown, SonyUsbStatus.parse(valid.copyOf().apply { this[0] = 3 }))
    }

    private fun properties(recording: Int, iso: Int): ByteArray {
        val dataset = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(2).putInt(0)
            .putShort(0xd21d.toShort()).putShort(2).put(0).put(2)
            .put(0).put(recording.toByte()).put(1).put(0).put(2).put(1)
            .putShort(0xd21e.toShort()).putShort(6).put(0).put(1)
            .putInt(0xffffff).putInt(iso).put(2).putShort(1).putInt(iso)
        return dataset.array().copyOf(dataset.position())
    }
}
