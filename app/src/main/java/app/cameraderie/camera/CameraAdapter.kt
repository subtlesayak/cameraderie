package app.cameraderie.camera

import app.cameraderie.core.Recording

/** UI-facing descriptions keep camera transports and capability decisions in the adapter. */
internal object CameraAdapters {
    val action6 = CameraAdapterProfile(
        "DJI Osmo Action 6", listOf("Bluetooth", "Local Wi-Fi"),
        setOf(CameraFeature.PHOTO, CameraFeature.RECORDING, CameraFeature.SHOOTING_MODE, CameraFeature.GALLERY),
    )
    val sonyA6400 = CameraAdapterProfile("Sony a6400", listOf("Local Wi-Fi", "USB"), setOf(CameraFeature.GALLERY))
}

internal enum class CameraFeature(val label: String) {
    LIVE_PREVIEW("Live preview"), PHOTO("Photo shutter"), RECORDING("Video recording"),
    FOCUS("Focus"), EXPOSURE("Exposure"), SHOOTING_MODE("Shooting mode"), GALLERY("Camera files"),
}

internal data class CameraAdapterProfile(val name: String, val transports: List<String>, val features: Set<CameraFeature>) {
    val unverifiedControls get() = CameraFeature.entries.filter { it !in features }.map(CameraFeature::label)
}

internal interface CaptureAdapter {
    val profile: CameraAdapterProfile
    fun snapshot(): CaptureStatus
    fun send(command: CaptureCommand)
}

internal enum class CaptureCommand { PHOTO, RECORD_START, RECORD_STOP, VIDEO_MODE, PHOTO_MODE, READ_FIRMWARE }

internal data class CaptureStatus(
    val connection: String,
    val details: String,
    val recording: Recording,
    val permitted: Set<CaptureCommand>,
    val feedback: String,
)
