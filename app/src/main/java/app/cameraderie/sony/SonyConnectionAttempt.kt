package app.cameraderie.sony

/** One bounded Wi-Fi-to-media opening attempt. The caller owns cancellation of
 * sockets, callbacks and UI generations; this class never retries or connects.
 * Supply a monotonic clock such as SystemClock.elapsedRealtime on Android. */
internal class SonyConnectionAttempt(private val now:()->Long):AutoCloseable {
    enum class Stage(val budgetMs:Long) {
        WIFI(45_000), DISCOVERY(20_000), PTP(25_000), METADATA(15_000)
    }
    enum class State { ACTIVE, COMPLETED, CANCELED, TIMED_OUT }
    data class Snapshot(val stage:Stage,val state:State,val remainingMs:Long,val failedStage:Stage?)

    private val lock=Any()
    private val startedAt=now().also { require(it>=0) { "A monotonic elapsed clock is required" } }
    private var observedAt=startedAt
    private var stageAt=startedAt
    private var currentStage=Stage.WIFI
    private var currentState=State.ACTIVE
    private var failedStage:Stage?=null

    val stage:Stage get()=snapshot().stage
    val state:State get()=snapshot().state
    val remainingMs:Long get()=snapshot().remainingMs

    /** Re-entering the current phase never refreshes its deadline. */
    fun enter(next:Stage):Boolean = synchronized(lock) {
        val at=observeLocked()
        expireLocked(at)
        if(currentState!=State.ACTIVE) return@synchronized false
        if(next==currentStage) return@synchronized true
        val allowed=when(currentStage) {
            Stage.WIFI -> next==Stage.DISCOVERY
            Stage.DISCOVERY -> next==Stage.PTP || next==Stage.METADATA
            Stage.PTP -> next==Stage.METADATA
            Stage.METADATA -> false
        }
        if(!allowed) return@synchronized false
        currentStage=next;stageAt=at
        true
    }

    /** Success is valid only after initial metadata finishes before both limits. */
    fun complete():Boolean = synchronized(lock) {
        expireLocked(observeLocked())
        if(currentState!=State.ACTIVE || currentStage!=Stage.METADATA) return@synchronized false
        currentState=State.COMPLETED
        true
    }

    fun cancel():Boolean = synchronized(lock) {
        if(currentState!=State.ACTIVE) return@synchronized false
        currentState=State.CANCELED
        true
    }

    /** Evaluates the current phase rather than the phase of an earlier timer.
     * After completion/cancel it always returns null, even on late timer delivery. */
    fun timeout():Stage? = synchronized(lock) {
        expireLocked(observeLocked())
        failedStage
    }

    /** Atomic observation for a Handler: stop for terminal state; otherwise
     * schedule against remainingMs, or report failedStage when TIMED_OUT. */
    fun snapshot():Snapshot = synchronized(lock) {
        val at=observeLocked()
        expireLocked(at)
        Snapshot(currentStage,currentState,if(currentState==State.ACTIVE) remainingLocked(at) else 0,failedStage)
    }

    private fun observeLocked():Long {
        // A backwards injected clock cannot add budget or undo a terminal state.
        observedAt=maxOf(observedAt,now())
        return observedAt
    }
    private fun remainingLocked(at:Long)=minOf(TOTAL_MS-(at-startedAt),currentStage.budgetMs-(at-stageAt)).coerceAtLeast(0)
    private fun expireLocked(at:Long) {
        if(currentState==State.ACTIVE && (at-startedAt>=TOTAL_MS || at-stageAt>=currentStage.budgetMs)) {
            currentState=State.TIMED_OUT;failedStage=currentStage
        }
    }
    override fun close() { cancel() }
    companion object { const val TOTAL_MS=100_000L }
}
