package app.cameraderie.sony

import app.cameraderie.camera.PtpDataset
import app.gripbridge.core.Recording
import java.io.IOException

internal enum class SonyUsbAction(val label: String) {
    FOCUS("Autofocus"), PHOTO("Take photo"), START_RECORDING("Start recording"), STOP_RECORDING("Stop recording");
}

internal data class SonyUsbStatus(
    val recording: Recording,
    val iso: String?,
    val focus: Int? = null,
    val mediaInMemory: Int? = null,
    val availableActions: Set<SonyUsbAction> = emptySet(),
) {
    companion object {
        val unknown = SonyUsbStatus(Recording.UNKNOWN, null)

        /** Decode the Sony version 2 property list. A partial reply cannot authorize a handoff. */
        fun parse(data: ByteArray): SonyUsbStatus {
            try {
                val values = PtpDataset(data)
                val count = values.u32()
                if (count > 512) return unknown
                values.skip(4)
                var recording: Int? = null
                var iso: Long? = null
                var focus: Int? = null
                val properties = mutableMapOf<Int, Int>()
                val controls = mutableSetOf<Int>()
                repeat(count.toInt()) {
                    val property = values.u16()
                    val type = values.u16()
                    val access = values.u8()
                    val enabled = values.u8() == 1
                    values.skipValue(type) // Factory default.
                    val control = enabled && access == BUTTON && type == UINT16 && property in controlIds
                    when {
                        property == MOVIE_STATE && type == UINT8 -> {
                            if (recording != null) return unknown
                            recording = values.u8()
                        }
                        property == ISO && type == UINT32 -> {
                            if (iso != null) return unknown
                            iso = values.u32()
                        }
                        property == FOCUS_RESULT && type == UINT8 -> {
                            if (focus != null) return unknown
                            focus = values.u8()
                        }
                        property in captureProperties && type == UINT16 -> {
                            if (properties.put(property, values.u16()) != null) return unknown
                        }
                        else -> values.skipValue(type)
                    }
                    when (values.u8()) {
                        0 -> Unit
                        1 -> repeat(3) { values.skipValue(type) }
                        2 -> {
                            val size = values.u16()
                            if (control && size <= 32) {
                                val offered = List(size) { values.u16() }
                                if (1 in offered && 2 in offered) controls.add(property)
                            } else repeat(size) { values.skipValue(type) }
                        }
                        else -> return unknown
                    }
                }
                if (values.remaining != 0) return unknown
                val state = when (recording) {
                    0 -> Recording.IDLE
                    1, 2 -> Recording.RECORDING
                    else -> Recording.UNKNOWN
                }
                val sensitivity = when (val reported = iso) {
                    AUTO_ISO -> "Auto"
                    in 25L..409600L -> reported.toString()
                    else -> null
                }
                val actions = buildSet {
                    if (state == Recording.IDLE) {
                        if (FOCUS_BUTTON in controls && properties[FOCUS_MODE] in autoFocusModes && focus != null) {
                            add(SonyUsbAction.FOCUS)
                        }
                        if (FOCUS_BUTTON in controls && PHOTO_BUTTON in controls &&
                            properties[EXPOSURE_MODE] in stillExposureModes && properties[DRIVE_MODE] == 1 &&
                            properties[SAVE_DESTINATION] in cardDestinations && properties[MEDIA_IN_MEMORY] == 0) {
                            add(SonyUsbAction.PHOTO)
                        }
                        if (MOVIE_BUTTON in controls) add(SonyUsbAction.START_RECORDING)
                    }
                    if (state == Recording.RECORDING && MOVIE_BUTTON in controls) add(SonyUsbAction.STOP_RECORDING)
                }
                return SonyUsbStatus(state, sensitivity, focus, properties[MEDIA_IN_MEMORY], actions)
            } catch (_: IOException) {
                return unknown
            }
        }

        private fun PtpDataset.skipValue(type: Int) {
            when (type) {
                in 1..10 -> skip(1 shl ((type - 1) / 2))
                in 0x4001..0x400a -> {
                    val count = u32()
                    val width = 1 shl ((type - 0x4001) / 2)
                    if (count > remaining.toLong() / width) throw IOException("Invalid Sony property array.")
                    skip(count.toInt() * width)
                }
                0xffff -> string()
                else -> throw IOException("Unknown Sony property data type.")
            }
        }

        const val FOCUS_BUTTON = 0xd2c1
        const val PHOTO_BUTTON = 0xd2c2
        const val MOVIE_BUTTON = 0xd2c8
        private const val BUTTON = 0x81
        private const val FOCUS_MODE = 0x500a
        private const val EXPOSURE_MODE = 0x500e
        private const val DRIVE_MODE = 0x5013
        private const val FOCUS_RESULT = 0xd213
        private const val MEDIA_IN_MEMORY = 0xd215
        private const val SAVE_DESTINATION = 0xd222
        private const val UINT16 = 4
        private val controlIds = setOf(FOCUS_BUTTON, PHOTO_BUTTON, MOVIE_BUTTON)
        private val captureProperties = setOf(FOCUS_MODE, EXPOSURE_MODE, DRIVE_MODE, MEDIA_IN_MEMORY, SAVE_DESTINATION)
        private val autoFocusModes = setOf(2, 0x8004, 0x8005)
        private val stillExposureModes = setOf(1, 2, 3, 4)
        private val cardDestinations = setOf(16, 17)
        private const val MOVIE_STATE = 0xd21d
        private const val ISO = 0xd21e
        private const val UINT8 = 2
        private const val UINT32 = 6
        private const val AUTO_ISO = 0x00ffffffL
    }
}
