package app.cameraderie.core

/** Bounded wake experiment. Advertising completion alone is never camera success. */
class DjiWakeAttempt(private val now: () -> Long, private val reconnect: () -> Unit, private val report: (String) -> Unit) {
    enum class State { IDLE, ADVERTISING, WAITING_RESPONSE, RECONNECTING, CONFIRMED, FAILED }
    var state = State.IDLE; private set
    val active get() = state in setOf(State.ADVERTISING,State.WAITING_RESPONSE,State.RECONNECTING)
    private var reconnectAt = 0L
    private var deadline = 0L
    private var broadcastStarted = false
    fun begin() {
        check(!active) { "Wake test already running" }
        state = State.ADVERTISING; broadcastStarted = false
        report("Experimental wake: sending advertisement; camera response not confirmed")
    }
    fun cancel() { state = State.IDLE }
    fun advertisingStarted() {
        if (state == State.ADVERTISING) broadcastStarted = true
    }
    fun advertised(ok: Boolean) {
        if(state != State.ADVERTISING) return
        if(!ok) { fail("Wake advertisement failed; power on camera and reconnect"); return }
        state = State.WAITING_RESPONSE; reconnectAt = now()+3000; deadline = now()+30000
        report("Wake advertisement finished; waiting for awake camera status")
    }
    fun disconnected() {
        if(state == State.WAITING_RESPONSE) reconnectOnce()
    }
    fun observeStatus(authorized: Boolean, fresh: Boolean, awake: Boolean): Boolean {
        val awaitingAwake = when (state) {
            State.ADVERTISING -> broadcastStarted
            State.WAITING_RESPONSE, State.RECONNECTING -> true
            else -> false
        }
        if (!awaitingAwake || !authorized || !fresh || !awake) return false
        state = State.CONFIRMED
        report("Awake camera status received after wake test; no recording command replayed")
        return true
    }
    /** Only the transport's exact saved-address/connectable scan match may call this. */
    fun savedCameraAdvertising() {
        if(state == State.WAITING_RESPONSE) reconnectOnce()
    }
    private fun reconnectOnce() {
        if(state != State.WAITING_RESPONSE) return
        state = State.RECONNECTING
        report("Wake test: reconnecting saved camera once; no recording replay")
        reconnect()
    }
    fun tick() {
        if(!active || state == State.ADVERTISING) return
        if(now() >= deadline) { fail("Wake not confirmed: no awake camera response; power on camera and reconnect"); return }
        if(state == State.WAITING_RESPONSE && now() >= reconnectAt) reconnectOnce()
    }
    fun fail(reason: String) { if(active) { state = State.FAILED; report(reason) } }
}
