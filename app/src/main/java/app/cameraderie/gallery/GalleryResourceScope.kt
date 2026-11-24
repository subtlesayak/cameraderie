package app.cameraderie.gallery

/** Cancellation barrier shared by sockets, HTTP requests and transfers.
 * Late resources are closed immediately; one cleanup failure never skips the rest. */
class GalleryResourceScope(private val maximum: Int=64): AutoCloseable {
    private val lock=Any()
    private var closed=false
    private val entries=linkedSetOf<Handle<*>>()
    private var failures=0
    val cleanupFailures get()=synchronized(lock) { failures }
    val active get()=synchronized(lock) { !closed }
    init { require(maximum>0) }
    inner class Handle<T> internal constructor(private val resource: T,private val cleanup: (T)->Unit): AutoCloseable {
        private var released=false
        val value: T get()=synchronized(lock) { check(!released && !closed) { "Gallery resource is closed" };resource }
        internal fun cleanupOnce() {
            val needed=synchronized(lock) { if(released) false else { released=true;entries.remove(this);true } }
            if(needed) try { cleanup(resource) } catch(_: Exception) { synchronized(lock) { failures++ } }
        }
        override fun close()=cleanupOnce()
    }
    fun <T> track(resource: T,cleanup: (T)->Unit): Handle<T> {
        val handle=Handle(resource,cleanup)
        val admitted=synchronized(lock) { if(closed || entries.size>=maximum) false else { entries.add(handle);true } }
        if(!admitted) { handle.cleanupOnce();throw IllegalStateException("Gallery resource scope unavailable") }
        return handle
    }
    override fun close() {
        val closing=synchronized(lock) { if(closed) return;closed=true;entries.toList().asReversed() }
        closing.forEach { it.cleanupOnce() }
    }
}
