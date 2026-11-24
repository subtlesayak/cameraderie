package app.cameraderie.gallery

/** One handoff, opened only by an observed selected-camera ACL disconnection.
 * GATT close/request success and elapsed time alone cannot open this gate. */
class GalleryReleaseGate(private val now: ()->Long,private val timeoutMs: Long=6000,private val settleMs: Long=250) {
    enum class State { OBSERVING, WAITING, SETTLING, OPEN, TIMED_OUT, CANCELED }
    var state=State.OBSERVING;private set
    private var releasedAt: Long?=null
    private var deadline=Long.MAX_VALUE
    fun disconnected(): Boolean {
        if(state in setOf(State.OPEN,State.TIMED_OUT,State.CANCELED) || releasedAt!=null) return false
        releasedAt=now()
        if(state==State.WAITING) state=State.SETTLING
        return true
    }
    fun begin() {
        check(state==State.OBSERVING)
        deadline=now()+timeoutMs
        state=if(releasedAt==null) State.WAITING else State.SETTLING
        tick()
    }
    fun tick() {
        if(state !in setOf(State.WAITING,State.SETTLING)) return
        if(now()>=deadline) { state=State.TIMED_OUT;return }
        if(releasedAt?.let { now()-it>=settleMs }==true) state=State.OPEN
    }
    fun cancel() { state=State.CANCELED }
}
