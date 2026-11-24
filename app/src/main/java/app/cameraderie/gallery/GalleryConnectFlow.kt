package app.cameraderie.gallery

import app.cameraderie.gallery.protocol.*
import java.util.concurrent.atomic.AtomicBoolean

/** Serialized connection owner. Native bindings run all methods/events on main;
 * the worker receives only Token.active, which is safe across threads. */
class GalleryConnectFlow(private val host: Host,private val now: ()->Long,private val changed: (State)->Unit) {
    enum class Stage { IDLE, CHECKING_CAMERA, BLE_CONNECTION, CAMERA_APPROVAL, CREDENTIALS, WIFI_JOINING, REACHABILITY, REGISTRATION, PLAYBACK, STORAGE, READY, RESTORING, CLOSED, STOPPED, FAILED }
    enum class Category { PRECONDITION, PERMISSION, UNSUPPORTED_MODEL, CAMERA_BUSY, CAMERA_ASLEEP, BLUETOOTH, IDENTITY, CREDENTIALS, WIFI_DISABLED, WIFI_UNAVAILABLE, NETWORK_LOST, ROUTE, TIMEOUT, REJECTED, STORAGE_UNAVAILABLE, STATE_LOST, INTERNAL }
    data class Failure(val stage: Stage,val category: Category,val elapsedMs: Long,val attempt: Int,val responseCode: Int?=null)
    data class State(val stage: Stage=Stage.IDLE,val attempt: Int=0,val failure: Failure?=null,val storages: List<GalleryCameraEvidence.Storage> = emptyList())
    class EntryFailure(val category: Category,val stage: Stage=Stage.BLE_CONNECTION): RuntimeException()
    interface Network: AutoCloseable
    class Token internal constructor(val lease: CameraSessionOwnership.Lease) {
        private val valid=AtomicBoolean(true)
        val active get()=valid.get()
        internal fun invalidate() { valid.set(false) }
    }
    interface Runtime {
        fun connectBle()
        fun joinWifi(credentials: CameraWifiCredentials)
        fun startSession(network: Network,identity: GalleryIdentity)
        /** Synchronously stop ordinary worker work and retain its bounded exit
         * responsibility before Token.active becomes false. Must not start IO. */
        fun prepareExit() {}
        /** Bounded optional playback exit; completion is serialized with events.
         * Stop may call close before completion. No new media work is permitted. */
        fun finish(done: ()->Unit) { done() }
        fun close()
    }
    interface Events {
        fun progress(stage: Stage,attempt: Int=1)
        fun credentials(credentials: CameraWifiCredentials,identity: GalleryIdentity)
        fun network(network: Network)
        fun bleDisconnected()
        fun ready(storages: List<GalleryCameraEvidence.Storage>)
        fun failed(category: Category,stage: Stage?=null,elapsedMs: Long?=null,attempt: Int?=null,responseCode: Int?=null)
    }
    interface Host {
        fun acquire(): CameraSessionOwnership.Lease
        fun current(lease: CameraSessionOwnership.Lease): Boolean
        fun connected(lease: CameraSessionOwnership.Lease): Boolean
        /** Called only after Runtime.close; true means bridge restore started. */
        fun release(lease: CameraSessionOwnership.Lease): Boolean
        fun retryRestore()
        /** Construct only; no IO before Runtime.connectBle. */
        fun runtime(token: Token,events: Events): Runtime
    }
    private class Attempt(val token: Token) {
        var runtime: Runtime?=null
        var identity: GalleryIdentity?=null
        var network: Network?=null
        var wifiStarted=false
        var sessionStarted=false
        var stageAt=0L
    }
    var state=State();private set
    private var current: Attempt?=null
    private var restoring: CameraSessionOwnership.Lease?=null
    private var closing: Attempt?=null
    private var restartAfterRestore=false
    private var exitFailure: Failure?=null
    private fun emit(value: State) { if(value!=state) { state=value;changed(value) } }
    private fun valid(a: Attempt): Boolean {
        if(current!==a || !a.token.active) return false
        if(host.current(a.token.lease)) return true
        current=null;dispose(a);emit(State(Stage.STOPPED));return false
    }
    fun start(): Boolean {
        if(current!=null || restoring!=null) return false
        exitFailure=null
        val lease=try { host.acquire() }
            catch(error: EntryFailure) { emit(State(Stage.FAILED,failure=Failure(error.stage,error.category,0,0)));return false }
            catch(_: RuntimeException) { emit(State(Stage.FAILED,failure=Failure(Stage.BLE_CONNECTION,Category.PRECONDITION,0,0)));return false }
        val a=Attempt(Token(lease));current=a;a.stageAt=now()
        if(!valid(a)) return false
        emit(State(if(lease.galleryPreflightPending) Stage.CHECKING_CAMERA else Stage.BLE_CONNECTION,1))
        if(!valid(a)) return false
        try {
            a.runtime=host.runtime(a.token,events(a))
            if(valid(a)) a.runtime!!.connectBle() else a.runtime!!.close()
        } catch(_: RuntimeException) { fail(a,Failure(state.stage,Category.INTERNAL,(now()-a.stageAt).coerceAtLeast(0),state.attempt)) }
        return valid(a)
    }
    private fun events(a: Attempt)=object: Events {
        override fun progress(stage: Stage,attempt: Int) {
            if(!valid(a)) return
            if(stage!=state.stage) a.stageAt=now()
            emit(State(stage,attempt.coerceAtLeast(1)))
        }
        override fun credentials(credentials: CameraWifiCredentials,identity: GalleryIdentity) {
            if(!valid(a) || a.token.lease.galleryPreflightPending || a.wifiStarted) return
            a.identity=identity;a.wifiStarted=true;progress(Stage.WIFI_JOINING)
            if(valid(a)) try { a.runtime!!.joinWifi(credentials) } catch(_: RuntimeException) { failed(Category.INTERNAL) }
        }
        override fun network(network: Network) {
            if(valid(a) && a.sessionStarted && a.network===network) return
            if(!valid(a) || !a.wifiStarted || a.sessionStarted || a.identity==null) {
                try { network.close() } catch(_: Exception) {};return
            }
            a.network=network;a.sessionStarted=true;progress(Stage.REACHABILITY)
            if(!valid(a)) { try { network.close() } catch(_: Exception) {};return }
            try { a.runtime!!.startSession(network,a.identity!!) } catch(_: RuntimeException) {
                try { network.close() } catch(_: Exception) {};failed(Category.INTERNAL)
            }
        }
        override fun bleDisconnected() {
            if(!valid(a)) return
            // Some cameras end BLE as part of the Wi-Fi handoff. Once both
            // credentials were validated, Wi-Fi/session deadlines decide the
            // outcome; a BLE drop is neither readiness nor an automatic failure.
            if(!a.wifiStarted) failed(Category.BLUETOOTH,Stage.BLE_CONNECTION)
        }
        override fun ready(storages: List<GalleryCameraEvidence.Storage>) {
            if(!valid(a) || !a.sessionStarted) return
            if(storages.none { it.available } || (state.stage!=Stage.READY && !host.connected(a.token.lease))) { failed(Category.STATE_LOST);return }
            emit(State(Stage.READY,1,storages=storages.map { it.copy() }))
        }
        override fun failed(category: Category,stage: Stage?,elapsedMs: Long?,attempt: Int?,responseCode: Int?) {
            if(!valid(a)) return
            fail(a,Failure(stage ?: state.stage,category,elapsedMs ?: (now()-a.stageAt).coerceAtLeast(0),attempt ?: state.attempt,responseCode))
        }
    }
    private fun fail(a: Attempt,failure: Failure) { if(valid(a)) finish(a,true,failure) }
    private fun finish(a: Attempt,restore: Boolean,failure: Failure?=null) {
        if(current!==a) return
        if(restore) {
            try { a.runtime?.prepareExit() } catch(_: RuntimeException) {}
            // A reentrant Stop supersedes restoration even during preparation.
            if(current!==a) return
        }
        current=null;exitFailure=failure;a.token.invalidate();a.identity=null
        if(!restore) { dispose(a);return }
        restoring=a.token.lease;closing=a
        emit(State(Stage.RESTORING,failure=failure))
        if(closing!==a || restoring!==a.token.lease) { dispose(a);return }
        try {
            a.runtime?.finish { cleanupFinished(a) } ?: cleanupFinished(a)
        } catch(_: RuntimeException) { cleanupFinished(a) }
    }
    private fun cleanupFinished(a: Attempt) {
        if(closing!==a) return
        closing=null;dispose(a)
        if(restoring!==a.token.lease) return
        val started=try { host.release(a.token.lease) } catch(_: RuntimeException) {
            restorationFailed(0,Category.INTERNAL);return
        }
        if(!started && restoring===a.token.lease) {
            restoring=null
            if(restartAfterRestore) { restartAfterRestore=false;start() }
            else emit(State(if(exitFailure==null) Stage.CLOSED else Stage.FAILED,failure=exitFailure))
        }
    }
    private fun dispose(a: Attempt) {
        a.token.invalidate();a.identity=null
        try { a.runtime?.close() } catch(_: RuntimeException) {}
        a.runtime=null
        try { a.network?.close() } catch(_: Exception) {}
        a.network=null
    }
    fun cancel() {
        restartAfterRestore=false
        val a=current
        if(a!=null) finish(a,true)
        else if(restoring==null) { exitFailure=null;emit(State(Stage.CLOSED)) }
    }
    /** User-selected retry. Required dependencies are reopened after fresh bridge status;
     * targeted BLE/registration retries occur within their still-valid transports. */
    fun retry() {
        if(current!=null) return
        if(restoring!=null) { restartAfterRestore=true;if(closing==null) host.retryRestore();return }
        start()
    }
    fun reconnectFromBeginning() {
        restartAfterRestore=true
        val a=current
        if(a!=null) { finish(a,true);return }
        if(restoring==null) { restartAfterRestore=false;start() }
        else if(a==null && closing==null) host.retryRestore()
    }
    fun restored() {
        if(restoring==null || closing!=null) return
        restoring=null
        if(restartAfterRestore) { restartAfterRestore=false;start() }
        else emit(State(if(exitFailure==null) Stage.CLOSED else Stage.FAILED,failure=exitFailure))
    }
    fun restorationFailed(elapsedMs: Long=30000,category: Category=Category.TIMEOUT) {
        if(restoring==null) return
        restartAfterRestore=false
        emit(State(Stage.FAILED,failure=Failure(Stage.RESTORING,category,elapsedMs.coerceAtLeast(0),1)))
    }
    /** Stop supersedes exit/retry and never releases a lease into automatic restoration. */
    fun stop() {
        restartAfterRestore=false;restoring=null
        closing?.let { closing=null;dispose(it) }
        current?.let { finish(it,false) }
        emit(State(Stage.STOPPED))
    }
}
