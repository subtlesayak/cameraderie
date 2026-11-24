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

    @Test fun enablesSonyCaptureOnlyWithFreshMatchingCapabilities() {
        assertEquals(
            setOf(SonyUsbAction.FOCUS, SonyUsbAction.PHOTO, SonyUsbAction.START_RECORDING),
            SonyUsbStatus.parse(captureProperties()).availableActions,
        )
        assertEquals(setOf(SonyUsbAction.STOP_RECORDING),
            SonyUsbStatus.parse(captureProperties(recording = 1)).availableActions)
        assertEquals(emptySet<SonyUsbAction>(),
            SonyUsbStatus.parse(captureProperties(recording = 3)).availableActions)
        assertEquals(emptySet<SonyUsbAction>(),
            SonyUsbStatus.parse(captureProperties(buttonsEnabled = false)).availableActions)
    }

    @Test fun protectsCardDestinationAndSingleShotRequirements() {
        for (data in listOf(
            captureProperties(saveDestination = 1),
            captureProperties(driveMode = 2),
            captureProperties(inMemory = 0x8001),
            captureProperties(exposureMode = 0x8050),
        )) {
            assertEquals(setOf(SonyUsbAction.FOCUS, SonyUsbAction.START_RECORDING),
                SonyUsbStatus.parse(data).availableActions)
        }
        assertEquals(setOf(SonyUsbAction.PHOTO, SonyUsbAction.START_RECORDING),
            SonyUsbStatus.parse(captureProperties(focusMode = 1)).availableActions)
    }

    private fun captureProperties(
        recording: Int = 0,
        saveDestination: Int = 17,
        driveMode: Int = 1,
        inMemory: Int = 0,
        exposureMode: Int = 1,
        focusMode: Int = 2,
        buttonsEnabled: Boolean = true,
    ): ByteArray {
        val dataset = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN).putInt(10).putInt(0)
        for ((code, value) in listOf(0xd21d to recording, 0xd213 to 1)) {
            dataset.putShort(code.toShort()).putShort(2).put(0).put(2)
                .put(0).put(value.toByte()).put(0)
        }
        for ((code, value) in listOf(
            0x500a to focusMode, 0x500e to exposureMode, 0x5013 to driveMode,
            0xd215 to inMemory, 0xd222 to saveDestination,
        )) {
            dataset.putShort(code.toShort()).putShort(4).put(0).put(2)
                .putShort(0).putShort(value.toShort()).put(0)
        }
        for (code in listOf(0xd2c1, 0xd2c2, 0xd2c8)) {
            dataset.putShort(code.toShort()).putShort(4).put(0x81.toByte())
                .put(if (buttonsEnabled) 1 else 0)
                .putShort(1).putShort(1).put(2).putShort(2).putShort(1).putShort(2)
        }
        return dataset.array().copyOf(dataset.position())
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
