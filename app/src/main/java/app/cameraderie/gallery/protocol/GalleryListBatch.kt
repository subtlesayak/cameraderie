package app.cameraderie.gallery.protocol

/** Read-only CompositePack queries on the owning datalink worker. The public
 * Osmosis reference supplies the SD -> trigger -> internal ordering. These
 * timings/fresh counters still require Action6 wire acceptance, not just fixtures.
 * No quiet period, command ACK or storage capacity establishes a complete page. */
class GalleryListBatch(
    cursors:Map<GalleryCameraEvidence.Store,Long>,
    private val counters:GalleryMediaCounters.Pair,
    private val clock:()->Long,
    private val favouritesOnly:Boolean=false,
    private val sender:(Role,ByteArray)->Int,
) {
    enum class Role { SD_QUERY, TRIGGER, INTERNAL_QUERY }
    enum class Error { TIMEOUT, REJECTED, INVALID_STREAM, INVALID_MANIFEST, STORAGE_CHANGED, UNAVAILABLE, COUNTER_LIMIT, CANCELED }
    data class Outcome(val page:GalleryMediaPage.Page?=null,val error:Error?=null,
        val responseCode:Int?=null,val role:Role?=null,val parserError:GalleryMediaPage.Error?=null,
        val acceptedReceipt:Boolean=false,val stream:GalleryManifestStream.Snapshot?=null,val receipts:Map<Role,Int> = emptyMap()) {
        internal val endedWithoutPageData get() = error == Error.TIMEOUT && acceptedReceipt &&
            stream?.let { it.ended && it.chunks == 0 && it.bytes == 0 } == true
    }
    data class Result(val stores:Map<GalleryCameraEvidence.Store,Outcome>) {
        val successful get()=stores.values.all { it.page!=null }
    }
    data class Progress(val store:GalleryCameraEvidence.Store,val counter:Int,val sent:Boolean,
        val chunks:Int,val bytes:Int,val finished:Boolean)
    private data class Slice(val store:GalleryCameraEvidence.Store,val cursor:Long,val stream:GalleryManifestStream,
        var sent:Boolean=false,var parsedBytes:Int=-1,var parserError:GalleryMediaPage.Error?=null,
        val receipts:MutableMap<Role,Int> = linkedMapOf()) {
        val receipt get()=receipts[if(store==GalleryCameraEvidence.Store.SD)Role.SD_QUERY else Role.INTERNAL_QUERY]==0
    }
    private data class Command(val role:Role,val store:GalleryCameraEvidence.Store?)
    private val slices=cursors.mapValues { (store,cursor) ->
        require(cursor in 1..0xFFFFFFFFL)
        Slice(store,cursor,GalleryManifestStream(store,counter(store)))
    }
    private val outcomes=linkedMapOf<GalleryCameraEvidence.Store,Outcome>()
    private val commands=mutableMapOf<Int,Command>()
    private var queryAt:Long?=null
    private var triggerAt:Long?=null
    private var internalSent=false
    init { require(cursors.isNotEmpty() && cursors.size<=2) }
    private fun counter(store:GalleryCameraEvidence.Store)=if(store==GalleryCameraEvidence.Store.SD)counters.sd else counters.internal
    val finished get()=outcomes.size==slices.size
    val result get()=if(finished)Result(outcomes.toMap()) else null
    fun progress()=slices.values.map { slice ->
        val snapshot=outcomes[slice.store]?.stream ?: slice.stream.snapshot
        Progress(slice.store,counter(slice.store),slice.sent,snapshot.chunks,snapshot.bytes,slice.store in outcomes)
    }
    /** Before first send, collectors already exist for both uniquely scoped counters.
     * Actual later sends are spaced from the preceding send, even after a stall. */
    fun tick() {
        if(finished)return
        val now=clock()
        if(queryAt==null) {
            queryAt=now
            val requested=slices[GalleryCameraEvidence.Store.SD]?.takeIf { it.store !in outcomes }
            requested?.sent=true
            send(Role.SD_QUERY,GalleryCameraEvidence.Store.SD,GalleryListQuery.page(counters.sd,
                GalleryCameraEvidence.Store.SD,requested?.cursor ?: GalleryCameraEvidence.Store.SD.newestCursor,
                favouritesOnly=favouritesOnly))
            queryAt=clock()
        } else {
            val elapsed=now-queryAt!!
            require(elapsed>=0)
            if(elapsed>=TIMEOUT_MS) {
                for(slice in slices.values)if(slice.store !in outcomes)finish(slice,Outcome(
                    error=if(slice.parserError==null)Error.TIMEOUT else Error.INVALID_MANIFEST,
                    parserError=slice.parserError,acceptedReceipt=slice.receipt,stream=slice.stream.snapshot))
                return
            }
            if(triggerAt==null && elapsed>=PRIMER_MS) {
                send(Role.TRIGGER,GalleryCameraEvidence.Store.SD,GalleryListQuery.trigger(counters.sd))
                triggerAt=clock()
            } else if(!internalSent && triggerAt!=null && now-triggerAt!!>=TARGET_GAP_MS) {
                internalSent=true
                val requested=slices[GalleryCameraEvidence.Store.INTERNAL]?.takeIf { it.store !in outcomes }
                requested?.sent=true
                send(Role.INTERNAL_QUERY,GalleryCameraEvidence.Store.INTERNAL,GalleryListQuery.page(counters.internal,
                    GalleryCameraEvidence.Store.INTERNAL,requested?.cursor ?: GalleryCameraEvidence.Store.INTERNAL.newestCursor,
                    favouritesOnly=favouritesOnly))
            }
        }
        for(slice in slices.values) {
            if(slice.store in outcomes || !slice.sent)continue
            val snapshot=slice.stream.snapshot
            if(snapshot.status==GalleryManifestStream.Status.FAILED) {
                finish(slice,Outcome(error=Error.INVALID_STREAM,acceptedReceipt=slice.receipt,stream=snapshot));continue
            }
            if(snapshot.status!=GalleryManifestStream.Status.COLLECTED || snapshot.bytes==slice.parsedBytes)continue
            slice.parsedBytes=snapshot.bytes
            val bytes=slice.stream.collectedBytes() ?: continue
            try {
                val page=GalleryMediaPage.decode(bytes,slice.store)
                if(favouritesOnly && page.entries.any { it.favourite!=true })
                    throw GalleryMediaPage.Invalid(GalleryMediaPage.Error.MALFORMED)
                finish(slice,Outcome(page=page,acceptedReceipt=slice.receipt,stream=snapshot))
            } catch(invalid:GalleryMediaPage.Invalid) {
                // End may arrive ahead of higher data chunks. A contiguous prefix
                // is not final until strict record/count/last-tag validation passes.
                slice.parserError=invalid.error
            } finally { bytes.fill(0) }
        }
    }
    private fun send(role:Role,store:GalleryCameraEvidence.Store,payload:ByteArray) {
        val sequence=sender(role,payload)
        require(sequence in 0..65535 && sequence !in commands)
        commands[sequence]=Command(role,store)
    }
    /** Caller validates framing, CRC, datalink identity and current ownership first. */
    fun accept(frame:DumlFrame) {
        if(finished || frame.sender!=1 || frame.receiver!=2 || frame.commandSet!=0)return
        if(frame.commandId==0x26 && frame.flags in setOf(0x80,0xC0) && frame.payload.isNotEmpty()) {
            val command=commands[frame.sequence] ?: return
            val slice=slices[command.store] ?: return
            val previous=outcomes[slice.store]
            val code=frame.payload[0].toInt() and 255
            slice.receipts[command.role]=code
            // Keep receipts through the batch's terminal boundary, including a
            // slice that parsed before the other store answered. A matching late
            // rejection invalidates that page; never resurrect changed storage.
            if(previous?.error in setOf(Error.STORAGE_CHANGED,Error.CANCELED,Error.UNAVAILABLE))finish(slice,previous!!)
            else if(code!=0)finish(slice,Outcome(error=Error.REJECTED,responseCode=code,role=command.role,
                stream=previous?.stream ?: slice.stream.snapshot))
            else if(previous!=null)finish(slice,previous)
        } else if(frame.commandId==0x27) {
            val counter=frame.payload.getOrNull(4)?.toInt()?.and(255) ?: return
            val slice=slices.values.singleOrNull { counter(it.store)==counter } ?: return
            if(slice.sent && slice.store !in outcomes)slice.stream.accept(frame)
        }
    }
    fun unavailable(store:GalleryCameraEvidence.Store)=fail(store,Error.UNAVAILABLE)
    fun storageChanged(store:GalleryCameraEvidence.Store)=fail(store,Error.STORAGE_CHANGED,replace=true)
    private fun fail(store:GalleryCameraEvidence.Store,error:Error,replace:Boolean=false) {
        val slice=slices[store] ?: return
        if(!replace && store in outcomes)return
        val snapshot=outcomes[store]?.stream ?: slice.stream.snapshot
        finish(slice,Outcome(error=error,stream=snapshot))
    }
    fun cancel() {
        for(slice in slices.values)if(slice.store !in outcomes)finish(slice,Outcome(error=Error.CANCELED,stream=slice.stream.snapshot))
    }
    private fun finish(slice:Slice,outcome:Outcome) {
        outcomes[slice.store]=outcome.copy(acceptedReceipt=slice.receipt,receipts=slice.receipts.toMap());slice.stream.cancel()
    }
    companion object {
        const val PRIMER_MS=1600L
        const val TARGET_GAP_MS=800L
        const val TIMEOUT_MS=12_000L
        fun failed(stores:Set<GalleryCameraEvidence.Store>,error:Error)=Result(stores.associateWith { Outcome(error=error) })
    }
}

/** Never wrap/reuse counters in a live session. Trigger belongs to the SD query
 * operation and echoes its counter; each next batch consumes a fresh pair. */
class GalleryMediaCounters(start:Int=1) {
    data class Pair(val sd:Int,val internal:Int) { init { require(sd in 1..254 && internal==sd+1) } }
    @Volatile private var next=start
    val exhausted get() = next > 254
    init { require(start in 1..255) }
    fun allocate():Pair? {
        if(exhausted)return null
        return Pair(next,next+1).also { next+=2 }
    }
}
