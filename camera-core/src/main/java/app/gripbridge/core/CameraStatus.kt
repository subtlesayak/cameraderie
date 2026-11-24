package app.gripbridge.core

enum class Recording { UNKNOWN, IDLE, RECORDING }
data class CameraStatus(val mode: Int, val state: Int, val seconds: Int, val battery: Int, val power: Int, val heat: Int, val nextMode: Int = mode,
    val resolutionCode: Int? = null, val fpsCode: Int? = null, val stabilizationCode: Int? = null,
    val remainingCapacityMb: Long? = null, val remainingRecordingSeconds: Long? = null,
) {
    val recording: Recording get() = when {
        mode != 1 || power != 0 -> Recording.UNKNOWN
        state == 3 -> Recording.RECORDING
        state == 0 || state == 1 -> Recording.IDLE
        else -> Recording.UNKNOWN
    }
    companion object {
        fun parse(b: ByteArray): CameraStatus? {
            if (b.size < 38) return null
            fun u(i: Int) = b[i].toInt() and 255
            fun counter(i: Int) = DjiProtocol.u32(b,i).takeUnless { it == 0xFFFFFFFFL }
            return CameraStatus(u(0),u(1),DjiProtocol.u16(b,5),u(37),u(28),u(30),u(29),
                u(2),u(3),u(4),counter(15),counter(23))
        }
    }
}
