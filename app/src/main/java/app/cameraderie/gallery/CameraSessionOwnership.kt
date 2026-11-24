package app.cameraderie.gallery

/** Main-thread ownership. A lease is bound to one transition and cannot be forged by an address. */
class CameraSessionOwnership {
    enum class Owner { BRIDGE, GALLERY_TRANSITION, GALLERY, ZOOM_TRANSITION, ZOOM, RESTORING_BRIDGE }
    data class Evidence(val authorized: Boolean, val fresh: Boolean, val power: Int?, val cameraState: Int?, val commandPending: Boolean) {
        val awake get() = authorized && fresh && power == 0
        val idle get() = awake && cameraState in setOf(0,1) && !commandPending
    }
    class Lease internal constructor(internal val generation: Long, val bridgeWasActive: Boolean,
        internal var galleryPreflightPending: Boolean = false)
    var owner = Owner.BRIDGE; private set
    var failure: String? = null; private set
    private var generation = 0L
    private var current: Lease? = null
    val bridgeActionsAllowed get() = owner == Owner.BRIDGE
    fun enter(bridgeActive: Boolean, evidence: Evidence): Lease = enter(bridgeActive,evidence,false)
    fun enterZoom(bridgeActive: Boolean, evidence: Evidence): Lease = enter(bridgeActive,evidence,true)
    /** Reserve ownership while a gallery-only entry checks authorized camera status. */
    fun reserveStandaloneGallery(): Lease {
        check(owner == Owner.BRIDGE) { "Close the current camera session first" }
        return Lease(++generation, false, galleryPreflightPending = true).also {
            current = it; owner = Owner.GALLERY_TRANSITION; failure = null
        }
    }
    fun authorizeGalleryHandoff(lease: Lease, evidence: Evidence): Boolean {
        if (!isCurrent(lease) || owner != Owner.GALLERY_TRANSITION || !lease.galleryPreflightPending || !evidence.idle) return false
        lease.galleryPreflightPending = false
        return true
    }
    private fun enter(bridgeActive: Boolean, evidence: Evidence, zoom: Boolean): Lease {
        check(owner == Owner.BRIDGE) { "Close the current camera session first" }
        check(evidence.idle) { "Connect the camera and wait for fresh awake idle status" }
        return Lease(++generation,bridgeActive).also {
            current = it; owner = if(zoom) Owner.ZOOM_TRANSITION else Owner.GALLERY_TRANSITION; failure = null
        }
    }
    fun isCurrent(lease: Lease) = current === lease && lease.generation == generation
    fun connected(lease: Lease): Boolean {
        if (!isCurrent(lease) || lease.galleryPreflightPending || owner !in setOf(Owner.GALLERY_TRANSITION,Owner.ZOOM_TRANSITION)) return false
        owner = if(owner==Owner.ZOOM_TRANSITION) Owner.ZOOM else Owner.GALLERY; return true
    }
    /** Called only after transports, downloads, keepalives and network requests have been closed. */
    fun exit(lease: Lease): Boolean {
        if (!isCurrent(lease) || owner !in setOf(Owner.GALLERY_TRANSITION,Owner.GALLERY,Owner.ZOOM_TRANSITION,Owner.ZOOM)) return false
        failure = null
        if (lease.bridgeWasActive) { owner = Owner.RESTORING_BRIDGE; return true }
        current = null; owner = Owner.BRIDGE; return false
    }
    fun restored(lease: Lease, evidence: Evidence): Boolean {
        if (!isCurrent(lease) || owner != Owner.RESTORING_BRIDGE || !evidence.awake || evidence.cameraState !in setOf(0,1,3)) return false
        current = null; owner = Owner.BRIDGE; failure = null; return true
    }
    fun restorationFailed(lease: Lease, message: String): Boolean {
        if (!isCurrent(lease) || owner != Owner.RESTORING_BRIDGE) return false
        failure = message; return true
    }
    /** Explicit Stop always supersedes a pending restore and all callbacks from its old lease. */
    fun stop() { generation++; current = null; owner = Owner.BRIDGE; failure = null }
}
