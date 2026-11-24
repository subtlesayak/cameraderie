package app.cameraderie.gallery

/** Copy follows observed session state. It never turns connection readiness into
 * media-list or download success, and contains no camera identifiers or secrets. */
object GalleryConnectionUi {
    internal enum class Entry { DJI, SONY_USB, CHOOSE }

    internal fun entry(liveDji: Boolean, sonyUsb: Boolean): Entry = when {
        liveDji && sonyUsb -> Entry.CHOOSE
        liveDji -> Entry.DJI
        sonyUsb -> Entry.SONY_USB
        else -> Entry.CHOOSE
    }

    data class Model(val title: String,val detail: String,val busy: Boolean,val connect: Boolean,val cancel: Boolean,val retry: Boolean,val reconnect: Boolean)
    fun render(state: GalleryConnectFlow.State,bridgeActive: Boolean,authorizedBookmark: Boolean=false): Model {
        val stage=state.stage
        val busy=stage !in setOf(GalleryConnectFlow.Stage.IDLE,GalleryConnectFlow.Stage.READY,GalleryConnectFlow.Stage.CLOSED,GalleryConnectFlow.Stage.STOPPED,GalleryConnectFlow.Stage.FAILED)
        val title=if(stage==GalleryConnectFlow.Stage.RESTORING && !bridgeActive) "Closing camera gallery" else label(stage)
        val canConnect=bridgeActive || authorizedBookmark
        val detail=when(stage) {
            GalleryConnectFlow.Stage.IDLE -> if(authorizedBookmark && !bridgeActive)
                "Power on your saved camera. Gallery checks its status before opening playback."
                else if(bridgeActive) "Wait for fresh idle camera status before opening gallery."
                else "Authorize a camera in the Camera tab once to save it for gallery access."
            GalleryConnectFlow.Stage.CHECKING_CAMERA -> "Checking whether the saved camera is awake and idle. Wait for the camera status."
            GalleryConnectFlow.Stage.BLE_CONNECTION -> if(bridgeActive) "Opening the camera’s separate gallery connection. Remote actions are paused."
                else "Opening the camera’s separate gallery connection."
            GalleryConnectFlow.Stage.CAMERA_APPROVAL -> "Approve the connection on your camera."
            GalleryConnectFlow.Stage.CREDENTIALS -> "Waiting for the camera’s local Wi-Fi details."
            GalleryConnectFlow.Stage.WIFI_JOINING -> "Approve the camera Wi-Fi request if Android asks. This network has no internet."
            GalleryConnectFlow.Stage.REACHABILITY -> "Checking the selected camera network."
            GalleryConnectFlow.Stage.REGISTRATION -> "Registering the local camera session."
            GalleryConnectFlow.Stage.PLAYBACK -> "Waiting for the camera to report playback mode."
            GalleryConnectFlow.Stage.STORAGE -> "Waiting for playback to settle and storage status to arrive."
            GalleryConnectFlow.Stage.READY -> {
                val stores=state.storages.filter { it.available }.joinToString(" and ") { if(it.store==app.cameraderie.gallery.protocol.GalleryCameraEvidence.Store.SD) "SD card" else "internal storage" }
                "Camera playback and ${stores.ifEmpty { "storage" }} detected. Open View camera files, then tap the refresh icon to request the actual library. Connection readiness does not establish listing or import completion."+
                    if(bridgeActive) " Exit the session to restore camera control." else " Exit to return the camera to shooting."
            }
            GalleryConnectFlow.Stage.RESTORING -> if(bridgeActive) "Gallery networking is closing. Remote actions stay paused until fresh authorized camera status arrives." else "Ending playback and releasing the camera connection."
            GalleryConnectFlow.Stage.CLOSED -> if(bridgeActive) "The gallery is closed. Camera control resumes only after fresh authorized status." else "The gallery session is closed."
            GalleryConnectFlow.Stage.STOPPED -> "Camera gallery stopped. Connect again when you’re ready."
            GalleryConnectFlow.Stage.FAILED -> "Couldn’t connect to the camera gallery."
        }
        val failure=state.failure?.let { "${label(it.stage)}: ${recovery(it.category, it.stage)}" }
        val terminal=stage in setOf(GalleryConnectFlow.Stage.IDLE,GalleryConnectFlow.Stage.CLOSED,GalleryConnectFlow.Stage.STOPPED)
        return Model(title,if(failure==null) detail else "$detail\n\n$failure",busy,
            canConnect && terminal,stage !in setOf(GalleryConnectFlow.Stage.IDLE,GalleryConnectFlow.Stage.CLOSED,GalleryConnectFlow.Stage.STOPPED),
            canConnect && stage==GalleryConnectFlow.Stage.FAILED,
            canConnect && stage in setOf(GalleryConnectFlow.Stage.FAILED,GalleryConnectFlow.Stage.READY))
    }
    private fun recovery(value: GalleryConnectFlow.Category, stage: GalleryConnectFlow.Stage)=when(value) {
        GalleryConnectFlow.Category.PERMISSION -> "Allow the requested device or Wi-Fi permission, then retry."
        GalleryConnectFlow.Category.PRECONDITION -> "Use an authorized saved camera. Finish any capture and wait for fresh idle status before retrying."
        GalleryConnectFlow.Category.UNSUPPORTED_MODEL -> "This gallery supports Action 6."
        GalleryConnectFlow.Category.CAMERA_BUSY -> "The camera is recording or busy. Finish the capture on the camera before retrying gallery."
        GalleryConnectFlow.Category.CAMERA_ASLEEP -> "Power on the camera and retry gallery."
        GalleryConnectFlow.Category.IDENTITY -> "The saved gallery identity could not be loaded. Keep app data intact and export diagnostics."
        GalleryConnectFlow.Category.CREDENTIALS -> "The camera returned invalid Wi-Fi details. Keep it awake and reconnect from the beginning."
        GalleryConnectFlow.Category.WIFI_DISABLED -> "Turn on Wi-Fi, then retry."
        GalleryConnectFlow.Category.WIFI_UNAVAILABLE -> "The camera Wi-Fi request was not completed. Check the camera and approve Android’s request on retry."
        GalleryConnectFlow.Category.NETWORK_LOST,GalleryConnectFlow.Category.ROUTE -> "The selected camera network is unavailable. Keep the camera nearby and reconnect."
        GalleryConnectFlow.Category.REJECTED -> "The camera rejected this step. Check its approval screen, then retry."
        GalleryConnectFlow.Category.STORAGE_UNAVAILABLE -> "No available camera storage was reported. Check the SD card or internal storage."
        GalleryConnectFlow.Category.STATE_LOST -> "Fresh camera state was lost. Leave playback on the camera and reconnect in the Camera tab."
        GalleryConnectFlow.Category.TIMEOUT -> "The camera did not confirm this step in time. Keep it awake, leave playback if restoring control, then retry."
        GalleryConnectFlow.Category.BLUETOOTH -> if (stage == GalleryConnectFlow.Stage.CHECKING_CAMERA)
            "Couldn’t check the saved camera over Bluetooth. Keep it awake and nearby, close other camera apps, then retry."
            else "Close other camera apps, keep the camera awake and retry. The previous camera link must close before gallery can connect."
        GalleryConnectFlow.Category.INTERNAL -> "The gallery connection ended unexpectedly. Export diagnostics and reconnect from the beginning."
    }
    private fun label(stage: GalleryConnectFlow.Stage)=when(stage) {
        GalleryConnectFlow.Stage.IDLE -> "Ready to connect"
        GalleryConnectFlow.Stage.CHECKING_CAMERA -> "Checking saved camera status"
        GalleryConnectFlow.Stage.BLE_CONNECTION -> "Connecting by Bluetooth"
        GalleryConnectFlow.Stage.CAMERA_APPROVAL -> "Confirm on camera"
        GalleryConnectFlow.Stage.CREDENTIALS -> "Getting camera Wi-Fi"
        GalleryConnectFlow.Stage.WIFI_JOINING -> "Joining camera Wi-Fi"
        GalleryConnectFlow.Stage.REACHABILITY -> "Checking camera reachability"
        GalleryConnectFlow.Stage.REGISTRATION -> "Registering camera session"
        GalleryConnectFlow.Stage.PLAYBACK -> "Confirming playback"
        GalleryConnectFlow.Stage.STORAGE -> "Checking camera storage"
        GalleryConnectFlow.Stage.READY -> "Gallery connected"
        GalleryConnectFlow.Stage.RESTORING -> "Restoring camera control"
        GalleryConnectFlow.Stage.CLOSED -> "Gallery disconnected"
        GalleryConnectFlow.Stage.STOPPED -> "Gallery stopped"
        GalleryConnectFlow.Stage.FAILED -> "Gallery connection failed"
    }
}
