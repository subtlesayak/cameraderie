package app.cameraderie.core

/** All methods run on one owner thread. Transport callback means final GATT write completed. */
class DjiSession(
    private val identity: Long,
    private val address: ByteArray,
    private val now: () -> Long,
    private val send: (ByteArray, (Boolean) -> Unit) -> Unit,
    private val log: (String) -> Unit,
    private val changed: () -> Unit,
    private val authorizedCamera: () -> Unit = {},
) {
    enum class Auth { DISCONNECTED, WAITING_CAMERA, REPLYING, AUTHORIZED, FAILED }
    var auth = Auth.DISCONNECTED; private set
    var camera: CameraStatus? = null; private set
    var recording = Recording.UNKNOWN; private set
    var lastCommand = "No command"; private set
    private var sequence = 0
    private var deviceId = 0L
    private var statusAt = Long.MIN_VALUE
    private var authDeadline = 0L
    private var generation = 0
    private var sleepGeneration = 0
    private var connectSequence = 0
    private data class VersionQuery(val seq: Int, val deadline: Long)
    private var versionQuery: VersionQuery? = null
    var firmwareInfo: DjiFirmwareInfo? = null; private set
    var firmwareResult = "Read camera versions while the camera is awake"; private set
    val firmwareBusy get() = versionQuery != null
    val modelId get() = deviceId.takeIf { auth == Auth.AUTHORIZED }
    val profile get() = modelId?.let(CameraProfiles::find)

    private data class Pending(val seq: Int, val target: Recording, val deadline: Long, val result: ((TargetDelivery) -> Unit)?, var ack: Boolean = false, var observed: Boolean = false, var written: Boolean = false)
    private var pending: Pending? = null
    private data class Auxiliary(
        val sequence: Int,
        val label: String,
        val deadline: Long,
        val result: ((TargetDelivery) -> Unit)?,
    )
    private var auxiliary: Auxiliary? = null
    private data class ModeChange(val seq: Int, val mode: Int, val deadline: Long, var written: Boolean = false, var ack: Boolean = false, var observed: Boolean = false)
    private var modeChange: ModeChange? = null
    private data class SleepChange(val seq: Int, val deadline: Long, var written: Boolean = false, var ack: Boolean = false, var observed: Boolean = false)
    private var sleepChange: SleepChange? = null
    private var sleepGuard = false
    val trafficSuspended get() = sleepGuard || camera?.power == 3
    val sleepPending get() = sleepChange != null
    val busy get() = pending != null || auxiliary != null || modeChange != null || sleepChange != null
    val hasFreshStatus get() = auth == Auth.AUTHORIZED && statusAt != Long.MIN_VALUE && now()-statusAt <= 3000

    private fun next(): Int { sequence = (sequence + 1) and 65535; return sequence }
    fun disconnect() {
        generation++; auth = Auth.DISCONNECTED; camera = null; recording = Recording.UNKNOWN
        statusAt = Long.MIN_VALUE; abandonRecording(); modeChange = null; sleepChange = null; sleepGuard = false
        abandonAuxiliary()
        versionQuery = null; firmwareInfo = null; firmwareResult = "Read camera versions while the camera is awake"; changed()
    }
    fun begin(firstPair: Boolean, code: Int) {
        disconnect(); auth = Auth.WAITING_CAMERA; authDeadline = now() + 45000
        connectSequence = next()
        write(DjiProtocol.Frame(2,connectSequence,0,0x19,DjiProtocol.connection(identity,address,if(firstPair) 1 else 0,code))) {
            if (!it) fail("DJI authentication write failed")
        }
        log("Confirm DJI pairing on the camera."); changed()
    }
    private fun write(f: DjiProtocol.Frame, done: (Boolean) -> Unit = {}) {
        val token = generation
        send(DjiProtocol.encode(f)) { ok -> if (token == generation) done(ok) }
    }
    private fun fail(reason: String) {
        generation++; auth = Auth.FAILED; recording = Recording.UNKNOWN; camera = null; abandonRecording(); modeChange = null; sleepChange = null; sleepGuard = false
        abandonAuxiliary()
        versionQuery = null; firmwareInfo = null
        log(reason); changed()
    }
    fun receive(f: DjiProtocol.Frame) {
        tick()
        if (auth == Auth.DISCONNECTED || auth == Auth.FAILED) return
        if (f.set == 0 && f.id == 0x19) {
            if (f.response) {
                if (auth == Auth.WAITING_CAMERA && f.sequence == connectSequence) {
                    if (f.payload.size < 9 || f.payload[4] != 0.toByte()) fail("DJI rejected/invalid initial authentication ACK")
                    else log("DJI initial ACK; still waiting for camera verification")
                }
                return
            }
            if (f.payload.size != 33 || f.payload[26] != 2.toByte()) { fail("Invalid DJI camera verification packet"); return }
            if (DjiProtocol.u16(f.payload,27) != 0) { fail("Camera refused pairing; reconnect manually to retry"); return }
            if (auth != Auth.WAITING_CAMERA) return
            deviceId = DjiProtocol.u32(f.payload,0)
            if (CameraProfiles.find(deviceId)?.controlsEnabled != true) {
                fail("${CameraProfiles.find(deviceId)?.name ?: "Unknown camera"}: controls disabled pending protocol and physical validation; Action 6 is the tested model")
                return
            }
            auth = Auth.REPLYING
            write(DjiProtocol.Frame(0x20,f.sequence,0,0x19,DjiProtocol.authReply(identity))) { ok ->
                if (!ok) fail("Camera verification response failed to write")
                else {
                    auth = Auth.AUTHORIZED; authorizedCamera(); log("DJI authorized (Action 6); awaiting real status")
                    requestStatus(); changed()
                }
            }
            changed(); return
        }
        if (auth != Auth.AUTHORIZED) return
        if (f.set == 0 && f.id == 0 && f.response) {
            val query = versionQuery ?: return
            if(f.sequence != query.seq) { log("Ignored unmatched firmware response seq=${f.sequence}"); return }
            versionQuery = null
            firmwareInfo = DjiFirmwareInfo.parse(f.payload)
            firmwareResult = if(firmwareInfo != null) "Camera version response received" else "Camera rejected version query or returned an invalid response"
            log(firmwareResult)
            firmwareInfo?.let { log("Camera versions: product=${it.product ?: "unknown"}; SDK=${it.sdk ?: "unknown"}; firmware=${it.firmware ?: "unknown"}; data=${it.rawTailHex}") }
            changed(); return
        }
        if (f.set == 0x1D && f.id == 2 && !f.response) {
            val status = CameraStatus.parse(f.payload)
            if (status == null) { log("Truncated DJI camera status"); return }
            if (camera == null || camera?.mode != status.mode || camera?.state != status.state || recording != status.recording || camera?.power != status.power || camera?.nextMode != status.nextMode) {
                log("DJI status: mode=${status.mode} next=${status.nextMode} state=${status.state} power=${status.power} recording=${status.recording} elapsed=${status.seconds}s battery=${status.battery}%")
            }
            camera = status; statusAt = now(); recording = status.recording
            if(status.power == 3) {
                if (!sleepGuard) sleepGeneration++
                sleepGuard = true
                pending?.let {
                    finishRecording(it, TargetDelivery(TargetDelivery.State.UNKNOWN, "Camera went to sleep before recording confirmation."))
                }
                if(versionQuery != null) { versionQuery = null; firmwareResult = "Version query paused: camera asleep" }
            } else if(status.power == 0 && sleepChange == null) sleepGuard = false
            sleepChange?.let {
                it.observed = it.written && status.power == 3
                finishSleepIfConfirmed(it)
            }
            pending?.let { it.observed = it.written && recording == it.target; finishIfConfirmed(it) }
            modeChange?.let {
                it.observed = it.written && status.mode == it.mode && status.power == 0 && status.state in setOf(0,1)
                finishModeIfConfirmed(it)
            }
            changed()
        }
        // Observed on Action6 SDK-v1.1 / reported FW03.04.80.15: 1D04 replies use 1D03.
        // Only accept this alias for an outstanding mode sequence; fresh target-mode status is still mandatory.
        val action6ModeAlias = profile?.modeAckAliases?.contains(f.id) == true && modeChange?.seq == f.sequence
        if (f.set == 0x1D && (f.id == 4 || action6ModeAlias) && f.response) {
            val change = modeChange ?: return
            if(f.sequence != change.seq) { log("Ignored unmatched mode ACK seq=${f.sequence}"); return }
            if(f.payload.size !in setOf(1,5) || f.payload[0] != 0.toByte()) {
                modeChange = null; lastCommand = "Camera rejected mode selection"; log(lastCommand); changed(); return
            }
            if(action6ModeAlias) log("Observed Action6 mode ACK alias 1D03 seq=${change.seq} payload=${f.payload.hex()}")
            change.ack = true; log("Mode ACK seq=${change.seq}; waiting for fresh active mode status")
            finishModeIfConfirmed(change); changed(); return
        }
        if(f.set == 0 && f.id == 0x1A && f.response) {
            val change = sleepChange ?: return
            if(f.sequence != change.seq) { log("Ignored unmatched sleep ACK seq=${f.sequence}"); return }
            if(f.payload.size != 1 || f.payload[0] != 0.toByte()) {
                sleepChange = null; sleepGuard = camera?.power == 3
                lastCommand = "Camera rejected sleep request"; log(lastCommand); changed(); return
            }
            change.ack = true; log("Sleep request accepted; waiting for observed power3 status")
            finishSleepIfConfirmed(change); changed(); return
        }
        if (f.set == 0x1D && f.id == 3 && f.response) {
            val p = pending ?: return
            if (f.sequence != p.seq) { log("Ignored unmatched record ACK seq=${f.sequence}"); return }
            // Official C struct has one return byte; docs also show four optional reserved bytes.
            if (f.payload.isEmpty() || f.payload[0] != 0.toByte()) {
                finishRecording(p, TargetDelivery.failed("Camera rejected recording command")); changed(); return
            }
            p.ack = true; log("Record ACK seq=${p.seq}; waiting for matching status"); finishIfConfirmed(p); changed()
        }
        if (f.set == 0 && f.id == 0x11 && f.response) {
            val p = auxiliary ?: return
            if (f.sequence != p.sequence) { log("Ignored unmatched key ACK seq=${f.sequence}"); return }
            auxiliary = null
            val accepted = f.payload.isNotEmpty() && f.payload[0] == 0.toByte()
            lastCommand = if (accepted) "Camera acknowledged ${p.label}; verify mode/photo result on camera"
            else "Camera rejected ${p.label}"
            val result = if (accepted) TargetDelivery.State.ACKNOWLEDGED else TargetDelivery.State.FAILED
            p.result?.invoke(TargetDelivery(result, lastCommand))
            log(lastCommand); changed()
        }
    }
    private fun finishIfConfirmed(p: Pending) {
        if (p.ack && p.observed && hasFreshStatus && recording == p.target) {
            finishRecording(p, TargetDelivery(TargetDelivery.State.ACKNOWLEDGED, "Camera confirmed ${p.target.name.lowercase()} (ACK + status)"))
        }
    }
    private fun finishRecording(command: Pending, outcome: TargetDelivery) {
        pending = null
        lastCommand = outcome.detail
        log(lastCommand)
        command.result?.invoke(outcome)
    }

    private fun abandonRecording() {
        val interrupted = pending
        pending = null
        interrupted?.result?.invoke(TargetDelivery(TargetDelivery.State.UNKNOWN, "Camera link ended before recording confirmation."))
    }

    fun queryFirmware() {
        tick()
        if(trafficSuspended || !hasFreshStatus || camera?.power != 0) { firmwareResult = "Fresh awake camera status required to read versions"; changed(); return }
        if(busy || firmwareBusy) { firmwareResult = "Wait for the current camera command"; changed(); return }
        val query = VersionQuery(next(),now()+6000); versionQuery = query
        firmwareInfo = null; firmwareResult = "Reading camera versions"; changed()
        write(DjiProtocol.Frame(2,query.seq,0,0,byteArrayOf())) { ok ->
            if(versionQuery !== query) return@write
            if(!ok) { versionQuery = null; firmwareResult = "Version query write failed"; log(firmwareResult); changed() }
        }
    }
    fun requestStatus() {
        if (auth != Auth.AUTHORIZED) return
        if(trafficSuspended) { log("Camera asleep or sleep requested: status traffic paused"); return }
        write(DjiProtocol.Frame(0,next(),0x1D,5,byteArrayOf(3,20,0,0,0,0))) {
            if (!it) { log("DJI status subscription write failed"); recording = Recording.UNKNOWN; changed() }
        }
    }
    fun toggle(result: ((TargetDelivery) -> Unit)? = null) {
        tick()
        when(recording) {
            Recording.UNKNOWN -> {
                reject("Recording unknown: wait for fresh normal-video status")
                result?.invoke(TargetDelivery.failed(lastCommand))
            }
            Recording.IDLE -> record(true, result)
            Recording.RECORDING -> record(false, result)
        }
    }
    private fun reject(reason: String) { lastCommand = reason; log(reason); changed() }
    internal fun prepareRoutineRecording(start: Boolean): ((TargetDelivery) -> Unit) -> Unit {
        val authorizedGeneration = generation.takeIf { auth == Auth.AUTHORIZED }
        val awakeGeneration = sleepGeneration.takeUnless { trafficSuspended }
        return { complete ->
            when {
                authorizedGeneration == null ->
                    complete(TargetDelivery.failed("No authorized camera when the routine started."))
                generation != authorizedGeneration ->
                    complete(TargetDelivery.failed("Camera session changed. Press again when ready."))
                awakeGeneration != sleepGeneration ->
                    complete(TargetDelivery.failed("Camera sleep interrupted the routine. Press again when ready."))
                else -> record(start, complete)
            }
        }
    }
    fun record(start: Boolean, result: ((TargetDelivery) -> Unit)? = null) {
        fun blocked(reason: String) { reject(reason); result?.invoke(TargetDelivery.failed(reason)) }
        tick()
        if (auth != Auth.AUTHORIZED) { blocked("DJI not authorized"); return }
        if(trafficSuspended) { blocked("Camera asleep or sleep requested: ordinary commands paused"); return }
        if (busy) { blocked("Previous command awaiting camera confirmation; press ignored"); return }
        if (recording == Recording.UNKNOWN) { blocked("Fresh normal-video status required; choose Video on camera"); return }
        if (start && (camera?.heat ?: 0) >= 2) { blocked("Camera overheated; start blocked"); return }
        val target = if(start) Recording.RECORDING else Recording.IDLE
        if (recording == target) {
            val message = "Camera already ${target.name.lowercase()}; no command sent"
            reject(message)
            result?.invoke(TargetDelivery(TargetDelivery.State.ACKNOWLEDGED, message))
            return
        }
        val p = Pending(next(), target, now()+6000, result); pending = p
        lastCommand = "Sending explicit ${if(start) "start" else "stop"}, seq=${p.seq}"
        log(lastCommand); changed()
        write(DjiProtocol.Frame(2,p.seq,0x1D,3,DjiProtocol.record(deviceId,start))) { ok ->
            if (pending !== p) return@write
            if (ok) { p.written = true; log("Record frame written; camera execution not confirmed") }
            else {
                recording = Recording.UNKNOWN
                finishRecording(p, TargetDelivery(TargetDelivery.State.UNKNOWN, "Recording write failed; resync required"))
            }
            changed()
        }
    }
    private fun finishSleepIfConfirmed(change: SleepChange) {
        if(change.ack && change.observed && hasFreshStatus && camera?.power == 3) {
            sleepChange = null
            lastCommand = "Camera confirmed asleep (ACK + power3 status); ordinary traffic paused"
            log(lastCommand)
        }
    }
    fun sleepCamera() {
        tick()
        if(auth != Auth.AUTHORIZED) { reject("DJI not authorized"); return }
        if(trafficSuspended) { reject("Camera already asleep or sleep requested; no command sent"); return }
        if(busy || firmwareBusy) { reject("Wait for the current camera command before sleep"); return }
        val status = camera
        if(!hasFreshStatus || status == null || status.power != 0 || status.state !in setOf(0,1)) {
            reject("Fresh awake idle camera status required; stop recording before sleep"); return
        }
        val change = SleepChange(next(),now()+6000); sleepChange = change; sleepGuard = true; recording = Recording.UNKNOWN
        sleepGeneration++
        lastCommand = "Requesting camera sleep, seq=${change.seq}"; log(lastCommand); changed()
        write(DjiProtocol.Frame(2,change.seq,0,0x1A,byteArrayOf(3))) { ok ->
            if(sleepChange !== change) return@write
            if(ok) { change.written = true; log("Sleep frame written; sleeping state not confirmed") }
            else { sleepChange = null; lastCommand = "Sleep write failed; outcome unknown; ordinary traffic paused until awake status"; log(lastCommand) }
            changed()
        }
    }
    private fun finishModeIfConfirmed(change: ModeChange) {
        if(change.ack && change.observed && hasFreshStatus && camera?.mode == change.mode && camera?.power == 0) {
            modeChange = null
            lastCommand = "Camera confirmed ${modeLabel(change.mode)} mode (ACK + status)"
            log(lastCommand)
        }
    }
    fun selectMode(mode: Int) {
        tick()
        if(mode !in setOf(1,5)) { reject("Only Photo and Video mode selection is enabled"); return }
        if(auth != Auth.AUTHORIZED) { reject("DJI not authorized"); return }
        if(trafficSuspended) { reject("Camera asleep or sleep requested: ordinary commands paused"); return }
        if(busy || firmwareBusy) { reject("Previous command awaiting camera confirmation; press ignored"); return }
        val status = camera
        if(!hasFreshStatus || status == null || status.power != 0) { reject("Fresh awake camera status required for mode selection"); return }
        if(status.state !in setOf(0,1)) { reject("Stop recording or wait for camera idle before selecting mode"); return }
        val label = modeLabel(mode)
        if(status.mode == mode) { reject("Camera already in $label mode; no command sent"); return }
        val change = ModeChange(next(),mode,now()+6000); modeChange = change
        lastCommand = "Selecting $label mode, seq=${change.seq}"; log(lastCommand); changed()
        write(DjiProtocol.Frame(2,change.seq,0x1D,4,DjiProtocol.mode(deviceId,mode))) { ok ->
            if(modeChange !== change) return@write
            if(ok) { change.written = true; log("Mode frame written; active mode not confirmed") }
            else { modeChange = null; lastCommand = "Mode selection write failed; outcome unknown"; log(lastCommand) }
            changed()
        }
    }
    /** Native QS includes camera-configured Custom profiles and firmware-specific modes. */
    fun cycleShootingMode() = nextMode()
    fun nextMode() = keyCommand(2,"QS mode cycle",requirePhoto=false)
    /** Same short shutter event as DJI's remote, in the active shooting mode. */
    fun shutterByMode(result: ((TargetDelivery) -> Unit)? = null) {
        fun blocked(reason: String) { reject(reason); result?.invoke(TargetDelivery.failed(reason)) }
        tick()
        val status = camera
        if (!hasFreshStatus || status == null) { blocked("Fresh camera status required for shutter action"); return }
        if (status.mode in setOf(0x1A,0x23)) { blocked("Leave streaming mode before remote capture"); return }
        if (status.mode == 1) { toggle(result); return }
        keyCommand(1,"${modeLabel(status.mode)} capture",requirePhoto=false,
            allowRecordingStop = status.mode != 5, result = result)
    }
    private fun modeLabel(mode: Int): String = when(mode) {
        0 -> "Slow Motion"; 1 -> "Video"; 2 -> "Timelapse"; 5 -> "Photo"
        10 -> "Hyperlapse"; 0x28 -> "SuperNight"; 0x34 -> "Subject Tracking"
        else -> "Camera mode $mode"
    }
    fun capturePhoto(result: ((TargetDelivery) -> Unit)? = null) =
        keyCommand(1, "photo shutter", requirePhoto = true, result = result)

    private fun abandonAuxiliary() {
        val interrupted = auxiliary
        auxiliary = null
        interrupted?.result?.invoke(TargetDelivery(TargetDelivery.State.UNKNOWN, "Camera link ended before acknowledgment."))
    }
    private fun keyCommand(key: Int, label: String, requirePhoto: Boolean, allowRecordingStop: Boolean = false,
        result: ((TargetDelivery) -> Unit)? = null) {
        fun blocked(reason: String) { reject(reason); result?.invoke(TargetDelivery.failed(reason)) }
        tick()
        if (auth != Auth.AUTHORIZED) { blocked("DJI not authorized"); return }
        if(trafficSuspended) { blocked("Camera asleep or sleep requested: ordinary commands paused"); return }
        if (busy) { blocked("Previous command awaiting camera confirmation; press ignored"); return }
        val status = camera
        if (status == null || statusAt == Long.MIN_VALUE || now()-statusAt > 3000 || status.power != 0) { blocked("Fresh awake camera status required for $label"); return }
        if (status.state !in setOf(0,1) && !(allowRecordingStop && status.state == 3)) {
            blocked("Camera busy/pre-recording; $label blocked"); return
        }
        if (key == 1 && status.state != 3 && status.heat >= 2) { blocked("Camera overheated; capture blocked"); return }
        if (firmwareBusy) { blocked("Wait for the current camera command"); return }
        if (requirePhoto && status.mode != 5) { blocked("Shutter requires Photo mode. Use C1/QS or choose Photo on camera"); return }
        val p = Auxiliary(next(),label,now()+6000,result)
        auxiliary = p
        lastCommand = "Sending $label seq=${p.sequence}"; log(lastCommand); changed()
        write(DjiProtocol.Frame(2,p.sequence,0,0x11,byteArrayOf(key.toByte(),1,0,0))) { ok ->
            if (auxiliary !== p) return@write
            if (!ok) {
                auxiliary = null
                lastCommand = "$label write failed; outcome unknown"
                p.result?.invoke(TargetDelivery(TargetDelivery.State.UNKNOWN, lastCommand))
                log(lastCommand); changed()
            }
            else log("$label written; awaiting camera key ACK")
        }
    }
    fun tick() {
        versionQuery?.let {
            if(now() >= it.deadline) { versionQuery = null; firmwareResult = "No camera version response within 6 seconds; retry when awake"; log(firmwareResult); changed() }
        }
        if (auth in setOf(Auth.WAITING_CAMERA,Auth.REPLYING) && now() >= authDeadline) { fail("DJI pairing timed out; retry from Connect DJI"); return }
        pending?.let {
            if (now() >= it.deadline) {
                recording = Recording.UNKNOWN; statusAt = Long.MIN_VALUE
                finishRecording(it, TargetDelivery(TargetDelivery.State.UNKNOWN, "No matching ACK + recording status within 6s; outcome unknown (no replay)"))
                changed()
            }
        }
        sleepChange?.let {
            if(now() >= it.deadline) {
                sleepChange = null; recording = Recording.UNKNOWN
                lastCommand = if(it.ack) "Sleep accepted; sleeping state not confirmed (no replay)" else "Sleep not confirmed within 6s; outcome unknown (no replay)"
                log(lastCommand); changed()
            }
        }
        modeChange?.let {
            if(now() >= it.deadline) {
                modeChange = null; statusAt = Long.MIN_VALUE; recording = Recording.UNKNOWN
                lastCommand = "No matching ACK + active mode status within 6s; outcome unknown (no replay)"; log(lastCommand); changed()
            }
        }
        auxiliary?.let {
            if (now() >= it.deadline) {
                auxiliary = null
                lastCommand = "No camera ACK for ${it.label}; outcome unknown (no replay)"
                it.result?.invoke(TargetDelivery(TargetDelivery.State.UNKNOWN, lastCommand))
                log(lastCommand); changed()
            }
        }
        if (statusAt != Long.MIN_VALUE && now()-statusAt > 3000 && recording != Recording.UNKNOWN) {
            recording = Recording.UNKNOWN; log("DJI status stale; recording unknown"); changed()
        }
    }
}
