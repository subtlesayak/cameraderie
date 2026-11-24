package app.cameraderie.core

/** Readable values from documented 1D02 fields, never fabricated for unknown codes. */
object CameraDetails {
    fun resolution(s: CameraStatus): String = if(s.mode == 5) when(s.resolutionCode) {
        4 -> "Photo L"; 3 -> "Photo M"; else -> unknown(s.resolutionCode)
    } else when(s.resolutionCode) {
        10 -> "1080p"; 16 -> "4K 16:9"; 45 -> "2.7K 16:9"; 66 -> "1080p 9:16"
        67 -> "2.7K 9:16"; 95 -> "2.7K 4:3"; 103 -> "4K 4:3"; 109 -> "4K 9:16"
        else -> unknown(s.resolutionCode)
    }
    fun rate(s: CameraStatus): String {
        if(s.mode == 5) return when(val count = s.fpsCode) {
            null, 0, 255 -> "Unknown"; 1 -> "Single photo"; else -> "$count-photo burst"
        }
        if(s.mode == 0) return s.fpsCode?.takeIf { it in 1..254 }?.let { "Slow-motion rate code $it" } ?: "Unknown"
        val fps = mapOf(1 to 24,2 to 25,3 to 30,4 to 48,5 to 50,6 to 60,10 to 100,7 to 120,19 to 200,8 to 240)[s.fpsCode]
        return fps?.let { "$it fps" } ?: unknown(s.fpsCode)
    }
    fun stabilization(s: CameraStatus): String = when(s.stabilizationCode) {
        0 -> "Off"; 1 -> "RS"; 2 -> "HS"; 3 -> "RS+"; 4 -> "HB"; else -> unknown(s.stabilizationCode)
    }
    fun temperature(s: CameraStatus): String = when(s.heat) {
        0 -> "Normal"; 1 -> "Warm: temperature warning"; 2 -> "High temperature: recording blocked"
        3 -> "Overheating: camera shutting down"; else -> "Unknown (code ${s.heat})"
    }
    fun duration(seconds: Long?): String = seconds?.takeIf { it >= 0 && it != 0xFFFFFFFFL }?.let {
        val hours = it/3600; val minutes = (it%3600)/60; val rest = it%60
        if(hours > 0) "%dh %02dm %02ds".format(hours,minutes,rest) else "%dm %02ds".format(minutes,rest)
    } ?: "Unknown"
    fun text(s: CameraStatus?, fresh: Boolean): String {
        if(s == null || !fresh || s.power != 0) return "Camera details unavailable. Wait for fresh awake status."
        val capacity = s.remainingCapacityMb?.let { "$it MB" } ?: "Unknown"
        return "Format: ${resolution(s)} / ${rate(s)}\nStabilization: ${stabilization(s)}\nSD free: $capacity\nRemaining video: ${duration(s.remainingRecordingSeconds)}\nTemperature: ${temperature(s)}"
    }
    private fun unknown(code: Int?) = code?.let { "Unknown (code $it)" } ?: "Unknown"
}
