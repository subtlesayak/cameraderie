package app.cameraderie.sony

import app.cameraderie.camera.PtpDataset
import app.gripbridge.core.Recording
import java.io.IOException

internal data class SonyUsbStatus(val recording: Recording, val iso: String?) {
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
                repeat(count.toInt()) {
                    val property = values.u16()
                    val type = values.u16()
                    values.skip(2) // Access and availability.
                    values.skipValue(type) // Factory default.
                    when {
                        property == MOVIE_STATE && type == UINT8 -> {
                            if (recording != null) return unknown
                            recording = values.u8()
                        }
                        property == ISO && type == UINT32 -> {
                            if (iso != null) return unknown
                            iso = values.u32()
                        }
                        else -> values.skipValue(type)
                    }
                    when (values.u8()) {
                        0 -> Unit
                        1 -> repeat(3) { values.skipValue(type) }
                        2 -> repeat(values.u16()) { values.skipValue(type) }
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
                return SonyUsbStatus(state, sensitivity)
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

        private const val MOVIE_STATE = 0xd21d
        private const val ISO = 0xd21e
        private const val UINT8 = 2
        private const val UINT32 = 6
        private const val AUTO_ISO = 0x00ffffffL
    }
}
