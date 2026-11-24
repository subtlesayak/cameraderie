package app.cameraderie.gallery.protocol

import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Blocking, single-worker session. The Android adapter supplies network-bound IO.
 * All callbacks run on the worker. Ownership must be a thread-safe current-attempt
 * predicate, not a remembered Bluetooth address or mutable UI state. */
class GalleryDatalinkSession(
    private val wire: Wire,
    private val identity: GalleryIdentity,
    private val now: ()->Long,
    private val ownsCamera: ()->Boolean,
    private val listener: Listener,
    private val state: DumlDatalinkState=freshState(),
) : AutoCloseable {
    interface Wire: AutoCloseable {
        /** TCP7001 bootstrap using the same identity that was approved over BLE. */
        fun probe(pairingFrame: ByteArray)
        fun openUdp()
        fun send(packet: ByteArray)
        /** Must return within timeoutMs or throw; null means a receive timeout. */
        fun receive(timeoutMs: Int): ByteArray?
    }
    enum class Stage { REACHABILITY, REGISTRATION, PLAYBACK, STORAGE, READY }
    enum class PlaybackExit { NOT_NEEDED, SENT, CONFIRMED, FAILED, CANCELED }
    enum class Error { TIMEOUT, TRANSPORT, REJECTED, STORAGE_UNAVAILABLE, CAMERA_STATE_LOST, INTERNAL }
    enum class SocketStep { TCP_BIND, TCP_CONNECT, TCP_WRITE, UDP_NETWORK_BIND, UDP_LOCAL_BIND, UDP_CONNECT }
    enum class SocketReason { REFUSED, UNREACHABLE, TIMED_OUT, PERMISSION, OTHER_IO }
    class SocketFailure(val step: SocketStep,val reason: SocketReason): IOException()
    data class Diagnostic(val stage: Stage,val elapsedMs: Long,val attempt: Int,val error: Error?,val responseCode: Int?,
        val transport: String?=null,val socketStep: SocketStep?=null,val socketReason: SocketReason?=null)
    interface Listener {
        fun stageChanged(stage: Stage,attempt: Int)
        fun ready(storages: List<GalleryCameraEvidence.Storage>)
        fun frameReceived(frame: DumlFrame) {}
        fun mediaCommand(ticketId:Long,role:GalleryListBatch.Role,counter:Int,sequence:Int,elapsedMs:Long) {}
        fun mediaProgress(ticketId:Long,stores:List<GalleryListBatch.Progress>) {}
        fun mediaCommandPayload(ticketId:Long,sequence:Int,payload:ByteArray) {}
        fun mediaPagesFinished(ticketId:Long,result:GalleryListBatch.Result) {}
        fun mediaWriteFinished(ticketId:Long,result:MediaWriteResult) {}
        fun closed(playbackExit: PlaybackExit) {}
        fun failed(diagnostic: Diagnostic)
    }
    class MediaContext internal constructor(internal val owner:Any,stores:Map<GalleryCameraEvidence.Store,GalleryCameraEvidence.Storage>,revisions:Map<GalleryCameraEvidence.Store,Long>) {
        val stores:Map<GalleryCameraEvidence.Store,GalleryCameraEvidence.Storage> = java.util.Collections.unmodifiableMap(HashMap(stores))
        val revisions:Map<GalleryCameraEvidence.Store,Long> = java.util.Collections.unmodifiableMap(HashMap(revisions))
        override fun toString()="GalleryMediaContext(stores=${stores.keys},identity=redacted)"
    }
    class MediaTicket internal constructor(val id:Long,val cursors:Map<GalleryCameraEvidence.Store,Long>,
        val context:MediaContext,internal val favouritesOnly:Boolean=false) {
        internal val expectedStores get()=context.stores
        internal val expectedRevisions get()=context.revisions
        private val cancellation=AtomicBoolean(false)
        private val completed=AtomicReference<GalleryListBatch.Result?>(null)
        val result get()=completed.get()
        internal val cancelRequested get()=cancellation.get()
        fun cancel() { cancellation.set(true) }
        internal fun settle(value:GalleryListBatch.Result)=completed.compareAndSet(null,value)
    }
    enum class MediaWriteResult { ACKNOWLEDGED, REJECTED, UNCONFIRMED, STALE }
    private sealed interface MediaWrite {
        data class Favourite(val on:Boolean):MediaWrite
        data object Delete:MediaWrite
    }
    internal class MediaWriteTicket(val id:Long) {
        private val canceled=AtomicBoolean()
        val cancelRequested get()=canceled.get()
        fun cancel() { canceled.set(true) }
    }
    private data class PendingWrite(val ticket:MediaWriteTicket,val media:GalleryMediaPage.Media,
        val operation:MediaWrite,val context:MediaContext)
    private class MediaWriteAttempt(val write:PendingWrite,val sequence:Int,val deadline:Long,
        val commandSet:Int,val commandId:Int) {
        val preparing get()=commandSet==2 && commandId==0x0C
        var result:MediaWriteResult?=null

        fun accept(frame:DumlFrame) {
            if(result!=null || frame.sender!=1 || frame.receiver!=2 || frame.commandSet!=commandSet ||
                frame.commandId!=commandId || frame.sequence!=sequence || frame.flags and 0x80==0) return
            val payload=frame.payload
            val expectedResponseBytes=if(commandSet==0)2 else 1
            if(payload.size!=expectedResponseBytes)return
            result=if(payload.all { it==0.toByte() })MediaWriteResult.ACKNOWLEDGED else MediaWriteResult.REJECTED
        }
    }
    private val mediaLock=Any()
    private val mediaScope=Any()
    private val mediaIds=AtomicLong()
    private val mediaCounters=GalleryMediaCounters()
    private var pendingMedia:MediaTicket?=null
    private var pendingWrite:PendingWrite?=null
    private var activeWrite:MediaWriteAttempt?=null // worker only
    private var favouriteCounter=0L // worker only
    private var deleteCounter=0L // worker only
    private var mediaOutstanding=false
    private var mediaStores=emptyMap<GalleryCameraEvidence.Store,GalleryCameraEvidence.Storage>() // mediaLock
    private val mediaEpochs=GalleryCameraEvidence.Store.entries.associateWith { 0L }.toMutableMap() // mediaLock
    private var activeMedia:Pair<MediaTicket,GalleryListBatch>?=null // worker only
    private var lastMediaProgress:List<GalleryListBatch.Progress>?=null
    val mediaBusy get()=synchronized(mediaLock) { mediaOutstanding }
    internal val mediaCountersExhausted get() = mediaCounters.exhausted
    val mediaContext get()=synchronized(mediaLock) { if(ready)MediaContext(mediaScope,mediaStores,mediaEpochs) else null }
    fun currentMediaContext(context:MediaContext,store:GalleryCameraEvidence.Store):Boolean = synchronized(mediaLock) {
        ready && context.owner===mediaScope && context.stores[store]?.available==true &&
            context.stores[store]==mediaStores[store] && context.revisions[store]==mediaEpochs[store]
    }
    /** Enqueue only. Sequences, counters, sends and parsers stay on run()'s worker.
     * One outstanding request bounds the queue and rejects repeated button taps. */
    fun requestMediaPages(cursors:Map<GalleryCameraEvidence.Store,Long>,context:MediaContext?=null,
        favouritesOnly:Boolean=false):MediaTicket? {
        require(cursors.isNotEmpty() && cursors.size<=2 && cursors.values.all { it in 1..0xFFFFFFFFL })
        return synchronized(mediaLock) {
            // Older cursors must carry their original session/storage context.
            // Capturing today's fingerprint around yesterday's cursor is unsafe.
            if(!ready || mediaOutstanding || (context!=null && context.owner!==mediaScope) ||
                (context==null && cursors.any { (store,cursor) -> cursor!=store.newestCursor }))null
            else MediaTicket(mediaIds.incrementAndGet(),cursors.toMap(),context ?: MediaContext(mediaScope,mediaStores,mediaEpochs),favouritesOnly)
                .also { pendingMedia=it;mediaOutstanding=true }
        }
    }
    internal fun requestFavourite(media:GalleryMediaPage.Media,on:Boolean,context:MediaContext):MediaWriteTicket? =
        if(media.favourite==null)null else requestMediaWrite(media,MediaWrite.Favourite(on),context)
    internal fun requestDelete(media:GalleryMediaPage.Media,context:MediaContext):MediaWriteTicket? =
        if(!media.singleFileRecord || media.modifiedStamp==null)null else requestMediaWrite(media,MediaWrite.Delete,context)
    private fun requestMediaWrite(media:GalleryMediaPage.Media,operation:MediaWrite,context:MediaContext):MediaWriteTicket? =
        synchronized(mediaLock) {
            if(mediaOutstanding || !currentMediaContext(context,media.key.store)) null
            else MediaWriteTicket(mediaIds.incrementAndGet()).also {
                pendingWrite=PendingWrite(it,media,operation,context);mediaOutstanding=true
            }
        }
    private val canceled=AtomicBoolean(false)
    private val started=AtomicBoolean(false)
    private val exitRequested=AtomicBoolean(false)
    private val ended=AtomicBoolean(false)
    private var playbackRequested=false
    private val isReady=AtomicBoolean(false)
    private val evidence=GalleryCameraEvidence(now)
    private val transport=GalleryUdpDiagnostics()
    private var stage=Stage.REACHABILITY
    private var stageAt=0L
    private var attempt=0
    private var nextAck=0L
    private var nextPresence=Long.MAX_VALUE
    private var linkAccepted=false
    private val pendingSubscriptions=mutableSetOf<Int>()
    private var playbackSequence: Int?=null
    private var playbackRejected: Int?=null
    private var registrationRejected: Int?=null
    @Volatile var failure: Diagnostic?=null; private set
    val ready get()=isReady.get() && live()
    private class Aborted: RuntimeException()
    private class Failed(val kind: Error,val code: Int?=null): RuntimeException()
    private fun live()=!canceled.get() && !exitRequested.get() && ownsCamera()
    private fun checkLive() { if(!live()) throw Aborted() }
    private fun progress(value: Stage,n: Int) {
        checkLive()
        if(stage!=value || attempt==0) stageAt=now()
        stage=value;attempt=n;listener.stageChanged(value,n);checkLive()
    }
    private fun send(bytes: ByteArray) { checkLive();wire.send(bytes);transport.sent() }
    private fun request(receiver: Int,set: Int,id: Int,payload: ByteArray,flags: Int=0x40): Int {
        val packet=state.command(receiver,set,id,payload,flags)
        val seq=DumlDatagram.get16(packet,26) // 20-byte envelope + frame sequence at6.
        send(packet);return seq
    }
    /** Call on one background worker, exactly once. No network activity precedes run. */
    fun run() {
        check(started.compareAndSet(false,true)) { "Gallery session already started" }
        try {
            progress(Stage.REACHABILITY,1)
            val pairing=GalleryCommands.pairing(identity).encode()
            try { wire.probe(pairing) } finally { pairing.fill(0) }
            checkLive();wire.openUdp()
            var handshaken=false
            for(n in 1..20) {
                progress(Stage.REACHABILITY,n);send(state.handshake())
                val until=now()+350
                while(live() && now()<until) {
                    val packet=receive((until-now()).coerceAtMost(100).coerceAtLeast(1).toInt())
                    // receive() already validated the envelope, XOR and this
                    // attempt's session. The camera SYN reply carries its own
                    // channel/options; it need not echo our proposed base.
                    if(packet?.type==0) { handshaken=true;break }
                }
                if(handshaken) break
            }
            checkLive();if(!handshaken) throw Failed(Error.TIMEOUT)
            linkAccepted=true
            waitFor(2000) { state.peerChannel!=null }
            if(!state.synchronizeToPeer()) throw Failed(Error.TIMEOUT)
            register()
            enterPlayback()
            progress(Stage.STORAGE,1)
            waitFor(8000) { evidence.readiness in setOf(GalleryCameraEvidence.Readiness.READY,GalleryCameraEvidence.Readiness.UNAVAILABLE) }
            when(evidence.readiness) {
                GalleryCameraEvidence.Readiness.READY -> Unit
                GalleryCameraEvidence.Readiness.UNAVAILABLE -> throw Failed(Error.STORAGE_UNAVAILABLE)
                else -> throw Failed(Error.TIMEOUT)
            }
            var reportedStores=evidence.stores()
            synchronized(mediaLock) {
                mediaStores=reportedStores.associateBy { it.store }
                for(store in mediaStores.keys)mediaEpochs[store]=1L
            }
            progress(Stage.READY,1);isReady.set(true);listener.ready(reportedStores);checkLive()
            while(live()) {
                receive(100)
                if(!evidence.playbackFresh || evidence.playback!=true) throw Failed(Error.CAMERA_STATE_LOST)
                if(!evidence.storageFresh) throw Failed(Error.CAMERA_STATE_LOST)
                val stores=evidence.stores()
                if(stores.none { it.available }) throw Failed(Error.STORAGE_UNAVAILABLE)
                if(stores!=reportedStores) {
                    val before=reportedStores.associateBy { it.store };val after=stores.associateBy { it.store }
                    for(store in GalleryCameraEvidence.Store.entries)if(before[store]!=after[store])activeMedia?.second?.storageChanged(store)
                    synchronized(mediaLock) {
                        for(store in GalleryCameraEvidence.Store.entries)if(before[store]!=after[store])
                            mediaEpochs[store]=Math.addExact(mediaEpochs.getValue(store),1L)
                        mediaStores=after
                    }
                    reportedStores=stores;listener.ready(stores);checkLive()
                }
                advanceMedia(stores)
                advanceWrite()
            }
        } catch(_: Aborted) {
            // Explicit exit/Stop is not an error and cannot publish a late failure.
        } catch(failed: Failed) {
            report(failed.kind,failed.code)
        } catch(socket: SocketFailure) {
            report(Error.TRANSPORT,socket=socket)
        } catch(_: IOException) {
            report(Error.TRANSPORT)
        } catch(_: InterruptedException) {
            Thread.currentThread().interrupt();report(Error.TRANSPORT)
        } catch(_: RuntimeException) {
            report(Error.INTERNAL)
        } finally {
            isReady.set(false);cancelMedia()
            val exit=leavePlayback()
            canceled.set(true);pendingSubscriptions.clear();evidence.reset()
            try { wire.close() } catch(_: Exception) {}
            ended.set(true);listener.closed(exit)
        }
    }
    private fun register() {
        for(n in 1..3) {
            progress(Stage.REGISTRATION,n)
            pendingSubscriptions.clear();registrationRejected=null
            request(0x48,0,0x81,GalleryCommands.deviceInfo(),0x80)
            drain(400)
            request(0x28,0,0x88,GalleryCommands.presence());nextPresence=now()+1000
            drain(400)
            request(3,3,0xDA,GalleryCommands.initialize());drain(200)
            for((i,key) in listOf("cam_storage","cam_status").withIndex()) {
                pendingSubscriptions.add(request(0x28,0,0x99,GalleryCommands.subscription(key,0x69DF+(n-1)*2+i)))
            }
            // Matching nonempty receipts prove only registration. The pinned camera
            // flow requests playback next; requiring its status here can deadlock
            // firmware that begins camera pushes after that request. Actual fresh
            // playback and storage evidence remain separate mandatory gates below.
            if(waitFor(2500) { registrationRejected!=null || pendingSubscriptions.isEmpty() }) {
                registrationRejected?.let { throw Failed(Error.REJECTED,it) }
                return
            }
            drain(n*250L)
        }
        throw Failed(Error.TIMEOUT)
    }
    private fun enterPlayback() {
        if(evidence.playbackFresh && evidence.playback==true) return
        for(n in 1..3) {
            progress(Stage.PLAYBACK,n);playbackRejected=null
            playbackRequested=true
            playbackSequence=request(1,2,0x0C,GalleryCommands.playback(true))
            if(waitFor(1000) { (evidence.playbackFresh && evidence.playback==true) || playbackRejected!=null }) {
                playbackRejected?.let { throw Failed(Error.REJECTED,it) }
                return
            }
            drain(n*150L)
        }
        throw Failed(Error.TIMEOUT)
    }
    private fun waitFor(duration: Long,condition: ()->Boolean): Boolean {
        val end=now()+duration
        while(live() && now()<end) {
            if(condition()) return true
            receive((end-now()).coerceAtMost(100).coerceAtLeast(1).toInt())
        }
        checkLive();return condition()
    }
    private fun drain(duration: Long) { waitFor(duration) { false } }
    private fun maintain() {
        if(!linkAccepted) return
        if(now()>=nextAck) { send(state.acknowledge());nextAck=now()+100 }
        if(state.synchronized && now()>=nextPresence) {
            request(0x28,0,0x88,GalleryCommands.presence());nextPresence=now()+1000
        }
    }
    private fun receive(timeout: Int): DumlDatagram? {
        checkLive();maintain()
        val bytes=wire.receive(timeout) ?: return null
        transport.received(bytes,state.session,state.base)
        checkLive()
        val packet=state.receive(bytes) ?: return null
        if(packet.type==1 && linkAccepted) { send(state.acknowledge());nextAck=now()+100 }
        if(packet.type in setOf(1,2,3,5)) {
            for(frame in DumlDatagramFrames.decode(packet)) {
                checkLive()
                transport.frame(frame)
                if(frame.receiver!=2) continue
                val payload=frame.payload
                if(frame.sender==0x28 && frame.commandSet==0 && frame.commandId==0x99 &&
                    frame.flags in setOf(0x80,0xC0) && frame.sequence in pendingSubscriptions) {
                    if(payload.size==10) pendingSubscriptions.remove(frame.sequence)
                    else if(payload.size==1 && payload[0].toInt()!=0) registrationRejected=payload[0].toInt() and 255
                }
                evidence.accept(frame)
                if(frame.sender==1 && frame.commandSet==2 && frame.commandId==0x0C && frame.sequence==playbackSequence &&
                    frame.flags in setOf(0x80,0xC0) && payload.isNotEmpty() && payload[0].toInt()!=0) playbackRejected=payload[0].toInt() and 255
                if(frame.flags==0x40 && frame.commandSet==0 && frame.commandId==0x81 && state.synchronized)
                    send(state.response(frame,GalleryCommands.deviceInfo()))
                activeMedia?.second?.accept(frame)
                activeWrite?.accept(frame)
                listener.frameReceived(frame);checkLive()
            }
        }
        return packet
    }
    private fun advanceMedia(storages:List<GalleryCameraEvidence.Storage>) {
        checkLive()
        if(activeMedia==null) {
            val queued=synchronized(mediaLock) { pendingMedia.also { pendingMedia=null } } ?: return
            if(queued.cancelRequested) { completeMedia(queued,GalleryListBatch.failed(queued.cursors.keys,GalleryListBatch.Error.CANCELED));return }
            val counters=mediaCounters.allocate()
            if(counters==null) { completeMedia(queued,GalleryListBatch.failed(queued.cursors.keys,GalleryListBatch.Error.COUNTER_LIMIT));return }
            val began=now()
            val batch=GalleryListBatch(queued.cursors,counters,now,queued.favouritesOnly) { role,payload ->
                val sequence=request(1,0,0x26,payload)
                listener.mediaCommand(queued.id,role,payload[4].toInt() and 255,sequence,(now()-began).coerceAtLeast(0))
                listener.mediaCommandPayload(queued.id,sequence,payload)
                checkLive();sequence
            }
            val current=storages.associateBy { it.store }
            for(store in queued.cursors.keys) {
                val revision=synchronized(mediaLock) { mediaEpochs.getValue(store) }
                if(queued.expectedStores[store]!=current[store] || queued.expectedRevisions[store]!=revision)batch.storageChanged(store)
                else if(current[store]?.available!=true)batch.unavailable(store)
            }
            activeMedia=queued to batch;lastMediaProgress=null
        }
        val (ticket,batch)=checkNotNull(activeMedia)
        if(ticket.cancelRequested)batch.cancel() else batch.tick()
        checkLive()
        val progress=batch.progress()
        if(progress!=lastMediaProgress) {
            lastMediaProgress=progress;listener.mediaProgress(ticket.id,progress);checkLive()
        }
        batch.result?.let { completeMedia(ticket,it) }
    }
    private fun completeMedia(ticket:MediaTicket,value:GalleryListBatch.Result) {
        val result=if(ticket.cancelRequested)GalleryListBatch.failed(ticket.cursors.keys,GalleryListBatch.Error.CANCELED) else value
        activeMedia=null;lastMediaProgress=null
        synchronized(mediaLock) { mediaOutstanding=false }
        if(ticket.settle(result) && live())listener.mediaPagesFinished(ticket.id,result)
        checkLive()
    }
    private fun advanceWrite() {
        val attempt=activeWrite
        if(attempt!=null) {
            val write=attempt.write
            val current=!write.ticket.cancelRequested && currentMediaContext(write.context,write.media.key.store)
            if(current && attempt.preparing && attempt.result==MediaWriteResult.ACKNOWLEDGED) {
                activeWrite=when(val operation=write.operation) {
                    is MediaWrite.Favourite -> sendWrite(write,2,0xBF,GalleryCommands.favourite(write.media.handle,++favouriteCounter,operation.on))
                    MediaWrite.Delete -> sendWrite(write,0,0x28,GalleryCommands.deleteFile(write.media.handle,++deleteCounter))
                }
                return
            }
            val result=when {
                !current -> if(attempt.preparing || write.operation is MediaWrite.Favourite)
                    MediaWriteResult.STALE else attempt.result ?: MediaWriteResult.UNCONFIRMED
                attempt.preparing && (attempt.result==MediaWriteResult.REJECTED || now()>=attempt.deadline) -> MediaWriteResult.STALE
                attempt.result!=null -> attempt.result
                now()>=attempt.deadline -> MediaWriteResult.UNCONFIRMED
                else -> null
            }
            if(result!=null)completeWrite(write,result)
            return
        }
        val write=synchronized(mediaLock) { pendingWrite.also { pendingWrite=null } } ?: return
        if(write.ticket.cancelRequested || !currentMediaContext(write.context,write.media.key.store)) {
            completeWrite(write,MediaWriteResult.STALE)
            return
        }
        val sequence=request(1,2,0x0C,GalleryCommands.playback(true))
        activeWrite=MediaWriteAttempt(write,sequence,now()+1500,2,0x0C)
    }
    private fun sendWrite(write:PendingWrite,commandSet:Int,commandId:Int,payload:ByteArray):MediaWriteAttempt {
        val sequence=request(1,commandSet,commandId,payload)
        return MediaWriteAttempt(write,sequence,now()+2500,commandSet,commandId)
    }
    private fun completeWrite(write:PendingWrite,result:MediaWriteResult) {
        activeWrite=null
        synchronized(mediaLock) { mediaOutstanding=false }
        if(live())listener.mediaWriteFinished(write.ticket.id,result)
    }
    private fun cancelMedia() {
        val active=activeMedia;activeMedia=null;active?.second?.cancel()
        activeWrite=null
        active?.first?.let { it.settle(GalleryListBatch.failed(it.cursors.keys,GalleryListBatch.Error.CANCELED)) }
        val pending=synchronized(mediaLock) { mediaOutstanding=false;pendingWrite=null;mediaStores=emptyMap();mediaEpochs.keys.forEach { mediaEpochs[it]=0L };pendingMedia.also { pendingMedia=null } }
        pending?.let { it.settle(GalleryListBatch.failed(it.cursors.keys,GalleryListBatch.Error.CANCELED)) }
        lastMediaProgress=null
    }
    private fun report(kind: Error,code: Int?=null,socket: SocketFailure?=null) {
        if(!live()) return
        val diagnostic=Diagnostic(stage,(now()-stageAt).coerceAtLeast(0),attempt,kind,code,
            transport.summary()+" pending_subscriptions=${pendingSubscriptions.size} camera_status_fresh=${evidence.playbackFresh}",
            socket?.step,socket?.reason)
        failure=diagnostic;listener.failed(diagnostic)
    }
    /** Invalidates normal work immediately; only the worker may issue the bounded
     * playback-exit command. Never blocks main or mutates its sequence from main. */
    fun requestExit(): Boolean {
        exitRequested.set(true);isReady.set(false)
        return !ended.get()
    }
    private fun leavePlayback(): PlaybackExit {
        if(canceled.get()) return PlaybackExit.CANCELED
        if((!exitRequested.get() && !ownsCamera()) || !linkAccepted || !state.synchronized ||
            (!playbackRequested && evidence.playback!=true)) return PlaybackExit.NOT_NEEDED
        return try {
            if(canceled.get()) return PlaybackExit.CANCELED
            wire.send(state.command(1,2,0x0C,GalleryCommands.playback(false)))
            val until=now()+450
            while(!canceled.get() && now()<until) {
                val bytes=wire.receive((until-now()).coerceIn(1,100).toInt()) ?: continue
                val packet=state.receive(bytes) ?: continue
                if(canceled.get()) return PlaybackExit.CANCELED
                if(packet.type==1) wire.send(state.acknowledge())
                for(frame in DumlDatagramFrames.decode(packet)) {
                    val data=frame.payload
                    if(frame.sender==1 && frame.receiver==2 && frame.commandSet==2 && frame.commandId==0x80 &&
                        data.size>=13 && (data[3].toInt() and 0x40)==0) return PlaybackExit.CONFIRMED
                }
            }
            if(canceled.get()) PlaybackExit.CANCELED else PlaybackExit.SENT
        } catch(_: Exception) { if(canceled.get()) PlaybackExit.CANCELED else PlaybackExit.FAILED }
    }
    /** Closes the bound IO to unblock an in-flight connect/receive. */
    override fun close() { canceled.set(true);isReady.set(false);try { wire.close() } catch(_: Exception) {} }
    companion object {
        internal fun freshState(): DumlDatalinkState {
            val random=SecureRandom()
            return DumlDatalinkState(random.nextInt(0xEFFE)+0x1000,(random.nextInt(0xE000)+0x1000) and 0xFFF8)
        }
    }
}
