package app.cameraderie.camera

import app.gripbridge.ble.DjiCameraClient
import app.cameraderie.core.CameraDetails
import app.gripbridge.core.DjiSession
import app.gripbridge.core.Recording

internal class DjiCaptureAdapter(private val client: DjiCameraClient) : CaptureAdapter {
    override val profile = CameraAdapters.action6
    private val session get() = client.session

    override fun snapshot(): CaptureStatus {
        val current = session.camera
        val authorized = session.auth == DjiSession.Auth.AUTHORIZED && session.profile?.id == 0xFF55
        val fresh = authorized && session.hasFreshStatus && current?.power == 0 && !session.trafficSuspended
        val recording = if (fresh) session.recording else Recording.UNKNOWN
        val ready = fresh && !session.busy && !session.firmwareBusy
        val idle = current?.state in setOf(0, 1)
        val commands = buildSet {
            if (ready && idle) {
                if (current?.mode != 1) add(CaptureCommand.VIDEO_MODE)
                if (current?.mode != 5) add(CaptureCommand.PHOTO_MODE)
                if (current?.mode == 5) add(CaptureCommand.PHOTO)
                add(CaptureCommand.READ_FIRMWARE)
            }
            if (ready && recording == Recording.IDLE && (current?.heat ?: Int.MAX_VALUE) in 0..1) add(CaptureCommand.RECORD_START)
            if (ready && recording == Recording.RECORDING) add(CaptureCommand.RECORD_STOP)
        }
        val connection = when {
            recording == Recording.RECORDING -> "Recording on the camera"
            !authorized -> client.link
            !fresh -> "Waiting for fresh camera status"
            current?.mode == 1 -> "Video mode"
            current?.mode == 5 -> "Photo mode"
            else -> "Camera mode ${current?.mode}"
        }
        return CaptureStatus(connection, CameraDetails.text(current, fresh), recording, commands,
            listOf(session.lastCommand.takeUnless { it == "No command" }, session.firmwareResult).filterNotNull().joinToString("\n"))
    }

    override fun send(command: CaptureCommand) {
        check(command in snapshot().permitted) { "Wait for fresh camera status and try again." }
        when (command) {
            CaptureCommand.PHOTO -> session.capturePhoto()
            CaptureCommand.RECORD_START -> session.record(true)
            CaptureCommand.RECORD_STOP -> session.record(false)
            CaptureCommand.VIDEO_MODE -> session.selectMode(1)
            CaptureCommand.PHOTO_MODE -> session.selectMode(5)
            CaptureCommand.READ_FIRMWARE -> session.queryFirmware()
        }
    }
}
