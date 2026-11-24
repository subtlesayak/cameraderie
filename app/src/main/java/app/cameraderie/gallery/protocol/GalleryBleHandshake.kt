package app.cameraderie.gallery.protocol

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Main-thread protocol engine. GATT setup/identity persistence are supplied separately.
 * Reference: Osmosis 207c5ca MEDIA_PROTOCOL.md, documented 07/46 ACK payload 00.
 * A successful local write is never treated as camera authorization or Wi-Fi readiness. */
class GalleryBleHandshake(
    private val identity: GalleryIdentity,
    private val now: () -> Long,
    private val send: (DumlFrame) -> Boolean,
    private val listener: Listener,
    initialSequence: Int = 0xA000,
) {
    enum class Phase { NEW, SESSION_OPEN, PAIRING, CAMERA_APPROVAL, WIFI_START, NETWORK_NAME, PASSPHRASE, READY, FAILED, CLOSED }
    enum class Failure { WRITE_FAILED, PAIRING_TIMEOUT, APPROVAL_TIMEOUT, PAIRING_REJECTED, INVALID_PAIRING_REPLY, CREDENTIAL_TIMEOUT, INVALID_CREDENTIALS, TRANSPORT_LOST }
    data class Diagnostic(val stage: Phase,val elapsedMs: Long,val attemptCount: Int,val error: Failure?,val responseCode: Int?)
    interface Listener {
        fun phaseChanged(phase: Phase)
        fun credentialsReady(credentials: CameraWifiCredentials)
        fun failed(reason: Failure)
    }
    var phase=Phase.NEW; private set
    private var sequence=initialSequence and 65535
    private var due=0L
    private var deadline=0L
    private var pairedAt=0L
    private var nextPing=Long.MAX_VALUE
    private var attempts=0
    private val expected=mutableSetOf<Int>()
    private var ssid: String?=null
    private var approved=false
    private var stageStartedAt=now()
    private var failureDiagnostic: Diagnostic?=null
    val diagnostic get()=failureDiagnostic ?: Diagnostic(phase,(now()-stageStartedAt).coerceAtLeast(0),attempts,null,null)
    private fun change(value: Phase) { if(phase != value) { phase=value;stageStartedAt=now();listener.phaseChanged(value) } }
    private fun next() = sequence.also { sequence=(sequence+1) and 65535 }
    private fun request(receiver: Int, set: Int, id: Int, payload: ByteArray=byteArrayOf(), match: Boolean=false): Boolean {
        if(phase in setOf(Phase.CLOSED,Phase.FAILED)) return false
        val message=DumlFrame(2,receiver,next(),0x40,set,id,payload)
        if(match) expected.add(message.sequence)
        if(!send(message)) { fail(Failure.WRITE_FAILED);return false }
        return true
    }
    /** Call only after both CCCDs, successful FFF4 value write and its 200 ms settle. */
    fun start() {
        check(phase == Phase.NEW)
        change(Phase.SESSION_OPEN)
        if(request(0xF0,0,0x2B,byteArrayOf(4,0))) due=now()+120
    }
    fun tick() {
        if(phase in setOf(Phase.NEW,Phase.CLOSED,Phase.FAILED)) return
        val t=now()
        if(approved && t >= nextPing) {
            nextPing=t+1000
            if(!request(0xF0,0,0x2B,byteArrayOf(1,1))) return
        }
        when(phase) {
            Phase.SESSION_OPEN -> if(t >= due) { change(Phase.PAIRING);attempts=0;deadline=t+8000;pair() }
            Phase.PAIRING -> if(t >= deadline) fail(Failure.PAIRING_TIMEOUT) else if(t >= due && attempts < 3) pair()
            Phase.CAMERA_APPROVAL -> if(t >= deadline) fail(Failure.APPROVAL_TIMEOUT)
            Phase.WIFI_START -> if(t >= due) {
                if(request(0x1C,0x53,0x10,byteArrayOf(0,0,0,0))) {
                    change(Phase.NETWORK_NAME);attempts=0;expected.clear();due=pairedAt+900;deadline=due+11000
                }
            }
            Phase.NETWORK_NAME,Phase.PASSPHRASE -> if(t >= deadline) fail(Failure.CREDENTIAL_TIMEOUT)
                else if(t >= due && attempts < 3) {
                    attempts++
                    request(7,7,if(phase == Phase.NETWORK_NAME) 7 else 0x0E,match=true)
                    due=t+if(attempts == 1) 2500 else 4000
                }
            else -> Unit
        }
    }
    private fun pair() {
        val payload=GalleryCommands.pairing(identity).payload
        attempts++;due=now()+if(attempts == 1) 2500 else 3000
        request(7,7,0x45,payload,match=true)
        payload.fill(0)
    }
    fun accept(frame: DumlFrame) {
        if(phase in setOf(Phase.NEW,Phase.CLOSED,Phase.FAILED) || frame.receiver != 2) return
        val payload=frame.payload
        if(frame.flags == 0x40) {
            // Only these documented inbound requests receive an ACK. Unknown requests are not
            // granted success. Transport identity/generation is checked by GalleryBleClient.
            if(frame.commandSet == 0 && frame.commandId == 0x81) {
                if(!send(DumlFrame(2,frame.sender,frame.sequence,0xC0,0,0x81,GalleryCommands.deviceInfo()))) fail(Failure.WRITE_FAILED)
            } else if(frame.sender == 7 && frame.commandSet == 7 && frame.commandId == 0x46 &&
                (phase == Phase.CAMERA_APPROVAL || phase == Phase.PAIRING || approved)) {
                if(payload.contentEquals(byteArrayOf(1))) {
                    if(send(DumlFrame(2,7,frame.sequence,0xC0,7,0x46,byteArrayOf(0)))) paired()
                    else fail(Failure.WRITE_FAILED)
                } else if(!approved) fail(Failure.PAIRING_REJECTED)
            }
            return
        }
        if(frame.sender != 7 || frame.flags != 0xC0 || frame.commandSet != 7 || frame.sequence !in expected) return
        if(frame.commandId == 0x45 && phase in setOf(Phase.PAIRING,Phase.CAMERA_APPROVAL)) {
            if(payload.size != 2) { fail(Failure.INVALID_PAIRING_REPLY);return }
            if(payload[0].toInt() != 0) { fail(Failure.INVALID_PAIRING_REPLY,payload[0].toInt() and 255);return }
            when(payload[1].toInt() and 255) {
                1 -> paired()
                2 -> if(phase != Phase.CAMERA_APPROVAL) { change(Phase.CAMERA_APPROVAL);deadline=now()+60000 }
                else -> fail(Failure.PAIRING_REJECTED,payload[1].toInt() and 255)
            }
        } else if((phase == Phase.NETWORK_NAME && frame.commandId == 7) ||
            (phase == Phase.PASSPHRASE && frame.commandId == 0x0E)) {
            // Never turn unknown credential-response bytes into diagnostic fields.
            val value=parseCredentialReply(payload) ?: run { fail(Failure.INVALID_CREDENTIALS);return }
            expected.clear()
            if(phase == Phase.NETWORK_NAME) {
                if(value.toByteArray(Charsets.UTF_8).size !in 1..32) { fail(Failure.INVALID_CREDENTIALS);return }
                ssid=value;attempts=0;change(Phase.PASSPHRASE);due=maxOf(now()+500,pairedAt+1400);deadline=due+11000
            } else {
                val credentials=try { CameraWifiCredentials.create(ssid ?: "",value) }
                    catch(_: IllegalArgumentException) { fail(Failure.INVALID_CREDENTIALS);return }
                ssid=null;change(Phase.READY)
                if(phase == Phase.READY) listener.credentialsReady(credentials)
            }
        }
    }
    private fun paired() {
        if(approved) return
        approved=true;pairedAt=now();nextPing=pairedAt+1000
        expected.clear();attempts=0;change(Phase.WIFI_START);due=pairedAt+100
    }
    fun transportLost() { if(phase != Phase.CLOSED) fail(Failure.TRANSPORT_LOST) }
    private fun fail(reason: Failure,responseCode: Int?=null) {
        if(phase in setOf(Phase.FAILED,Phase.CLOSED)) return
        failureDiagnostic=Diagnostic(phase,(now()-stageStartedAt).coerceAtLeast(0),attempts,reason,responseCode)
        ssid=null;expected.clear();approved=false;nextPing=Long.MAX_VALUE
        change(Phase.FAILED)
        if(phase==Phase.FAILED) listener.failed(reason)
    }
    fun close() { ssid=null;expected.clear();approved=false;nextPing=Long.MAX_VALUE;change(Phase.CLOSED) }
    override fun toString() = "GalleryBleHandshake(phase=$phase)"
    companion object {
        internal fun parseCredentialReply(payload: ByteArray): String? {
            if(payload.size < 3 || payload[0].toInt() != 0) return null
            val size=payload[1].toInt() and 255
            // Zero padding exists in reference captures; nonzero trailing data is not ignored.
            if(size == 0 || size+2 > payload.size || payload.drop(size+2).any { it.toInt() != 0 }) return null
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload,2,size)).toString().takeIf { value -> value.none { it.code < 32 || it.code == 127 } }
            } catch(_: java.nio.charset.CharacterCodingException) { null }
        }
    }
}
