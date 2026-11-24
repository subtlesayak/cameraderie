package app.cameraderie.gallery.media

import app.cameraderie.gallery.protocol.*

/** Actor-owned catalogue. Only strict, completed protocol pages can commit rows.
 * Connection readiness, an ACK and a partial manifest never establish completion. */
class GalleryMediaCatalog {
    enum class Phase { IDLE, DEFERRED, LOADING, COMPLETE, FAILED, CHANGED, CANCELED, UNAVAILABLE }
    class Located internal constructor(val media:GalleryMediaPage.Media,val requestCursor:Long,
        val context:GalleryDatalinkSession.MediaContext,val revision:Long) {
        override fun toString()="LocatedMedia(store=${media.key.store},identity=redacted)"
    }
    data class StoreState(val store:GalleryCameraEvidence.Store,val phase:Phase,val entries:List<Located>,val error:String?)
    data class Snapshot(val generation:Long,val revision:Long,val started:Boolean,val stores:List<StoreState>,
        val stoppedForDownloads:Boolean) {
        val entries get()=stores.flatMap { it.entries }
        val complete get()=started && stores.any { it.phase==Phase.COMPLETE } && stores.all { it.phase in setOf(Phase.COMPLETE,Phase.UNAVAILABLE) }
        val downloadsAvailable get()=complete || (stoppedForDownloads && entries.isNotEmpty())
        val loading get()=started && stores.any { it.phase in setOf(Phase.LOADING,Phase.IDLE,Phase.DEFERRED) }
    }
    class Request internal constructor(val id:Long,val generation:Long,val context:GalleryDatalinkSession.MediaContext,
        val cursors:Map<GalleryCameraEvidence.Store,Long>)
    private class Store(val storage:GalleryCameraEvidence.Storage,val epoch:Long,val revision:Long) {
        val pages=GalleryPagination(storage.store)
        val entries=linkedMapOf<GalleryMediaPage.Key,Located>()
        var phase=if(storage.available)Phase.IDLE else Phase.UNAVAILABLE
        var error:String?=null
        var retriedInitialSd=false
        var deferredInitialSd=false
        var overlapRetryCursor:Long?=null
        var overlapRetries=0
    }
    private var context:GalleryDatalinkSession.MediaContext?=null
    private val stores=linkedMapOf<GalleryCameraEvidence.Store,Store>()
    private var generation=0L
    private var revision=0L
    private var ids=0L
    private var active:Request?=null
    private var started=false
    private var stoppedForDownloads=false
    val snapshot get()=Snapshot(generation,revision,started,
        stores.map { (id,s) -> StoreState(id,s.phase,s.entries.values.toList(),s.error) },stoppedForDownloads)
    /** Observation is passive; new/changed storage never starts network work. */
    fun observe(next:GalleryDatalinkSession.MediaContext) {
        val previous=context
        if(previous!=null && previous.owner!==next.owner) { generation++;active=null;started=false;stoppedForDownloads=false;stores.clear() }
        context=next
        var changed=false
        for(id in GalleryCameraEvidence.Store.entries) {
            val value=next.stores[id];val old=stores[id]
            if(value==null) { if(stores.remove(id)!=null)changed=true;continue }
            val epoch=next.revisions.getValue(id)
            if(old==null || old.storage!=value || old.epoch!=epoch) {
                stores[id]=Store(value,epoch,++revision).also {
                    if(started && value.available) { it.phase=Phase.CHANGED;it.error="Storage changed. Refresh this library." }
                };changed=true
            }
        }
        if(changed)revision++
    }
    fun start(next:GalleryDatalinkSession.MediaContext) {
        generation++;active=null;started=false;stoppedForDownloads=false;stores.clear();context=null;observe(next);started=true;revision++
    }
    fun nextRequest():Request? {
        if(!started || active!=null)return null
        val ctx=context ?: return null
        val next=stores.filterValues { it.phase==Phase.IDLE }.ifEmpty { stores.filterValues { it.phase==Phase.DEFERRED } }
        val wanted=next.mapValues { it.value.overlapRetryCursor ?: it.value.pages.cursor }
        if(wanted.isEmpty())return null
        return Request(++ids,generation,ctx,wanted.toMap()).also { request ->
            active=request;wanted.keys.forEach { stores.getValue(it).phase=Phase.LOADING };revision++
        }
    }
    private fun sameStore(a:GalleryDatalinkSession.MediaContext,b:GalleryDatalinkSession.MediaContext,id:GalleryCameraEvidence.Store)=
        a.owner===b.owner && a.stores[id]==b.stores[id] && a.revisions[id]==b.revisions[id]
    fun apply(request:Request,result:GalleryListBatch.Result):Boolean {
        if(active!==request || request.generation!=generation)return false
        active=null
        val current=context ?: return false
        for(id in request.cursors.keys) {
            val store=stores[id] ?: continue
            if(!sameStore(request.context,current,id) || store.phase!=Phase.LOADING)continue
            val outcome=result.stores[id]
            if(outcome?.page==null || outcome.error!=null) {
                // Public reference re-asks once because SD mounting can lag the
                // initial query. A real collector/fresh counter is still required.
                val stream=outcome?.stream
                if(id==GalleryCameraEvidence.Store.SD && store.entries.isEmpty() &&
                    request.cursors[id]==id.newestCursor && outcome?.error==GalleryListBatch.Error.TIMEOUT &&
                    stream?.chunks==0 && stream.bytes==0) {
                    if(!store.retriedInitialSd) {
                        store.retriedInitialSd=true;store.phase=Phase.IDLE;store.error="Retrying SD listing";continue
                    }
                    // A live refresh after internal pagination recovered a previously silent SD store.
                    // Keep a single deferred probe and continue indexing the other store first.
                    val internal=stores[GalleryCameraEvidence.Store.INTERNAL]
                    if(!store.deferredInitialSd && outcome.acceptedReceipt && stream.ended &&
                        internal?.phase in setOf(Phase.IDLE,Phase.LOADING)) {
                        store.deferredInitialSd=true;store.phase=Phase.DEFERRED;store.error=null;continue
                    }
                }
                if(id==GalleryCameraEvidence.Store.SD && store.overlapRetries < MAX_SD_OVERLAP_RETRIES &&
                    outcome?.endedWithoutPageData==true) {
                    val retry=store.pages.overlapRetryCursor(store.overlapRetryCursor)
                    if(retry!=null) {
                        store.overlapRetries++
                        store.overlapRetryCursor=retry;store.phase=Phase.IDLE;store.error="Retrying the SD page from a known file";continue
                    }
                }
                store.phase=if(outcome?.error==GalleryListBatch.Error.CANCELED)Phase.CANCELED else Phase.FAILED
                store.error=if(stream?.ended==true && stream.chunks==0 && stream.bytes==0) "NO_PAGE_DATA"
                    else outcome?.error?.name ?: "MISSING_RESPONSE"
                continue
            }
            try {
                val added=store.pages.accept(outcome.page,requireBoundaryOverlap=store.overlapRetryCursor!=null)
                for(media in added)store.entries[media.key]=Located(media,request.cursors.getValue(id),request.context,store.revision)
                store.overlapRetryCursor=null
                store.overlapRetries=0
                store.phase=if(store.pages.finished)Phase.COMPLETE else Phase.IDLE;store.error=null
            } catch(invalid:GalleryPagination.Invalid) {
                if(invalid.error==GalleryPagination.Error.CHANGED_MEDIA) { store.entries.clear();store.phase=Phase.CHANGED }
                else store.phase=Phase.FAILED
                store.error=invalid.error.name
            }
        }
        revision++;return true
    }
    internal fun stopForDownloads(): Boolean {
        val current = snapshot
        if (!current.started || current.downloadsAvailable || current.entries.isEmpty()) return false
        active = null
        stoppedForDownloads = true
        cancelOpenStores()
        return true
    }

    fun cancel() {
        generation++;active=null;stoppedForDownloads=false
        cancelOpenStores()
    }

    private fun cancelOpenStores() {
        stores.values.filter { it.phase in setOf(Phase.IDLE,Phase.LOADING,Phase.DEFERRED) }.forEach { it.phase=Phase.CANCELED;it.error="CANCELED" }
        revision++
    }
    fun find(key:GalleryMediaPage.Key)=stores[key.store]?.entries?.get(key)
    fun current(item:Located):Boolean {
        val ctx=context ?: return false
        return find(item.media.key)===item && sameStore(item.context,ctx,item.media.key.store)
    }
    internal fun validationFallbacks(item: Located): List<Long> {
        if (item.media.key.store != GalleryCameraEvidence.Store.SD || !current(item)) return emptyList()
        return stores.getValue(item.media.key.store).entries.values.asSequence()
            .map { it.media.handle }
            .filter { it > item.media.handle && it != item.requestCursor }
            .distinct().sorted().take(2).toList()
    }

    internal fun favouriteTarget(item:Located):Boolean = snapshot.complete && current(item) &&
        item.media.favourite!=null && stores.values.sumOf { store ->
            store.entries.values.count { it.media.handle==item.media.handle }
        }==1

    internal fun updateFavourite(item:Located,value:Boolean?):Boolean {
        if(!current(item))return false
        if(item.media.favourite!=value) {
            stores.getValue(item.media.key.store).entries[item.media.key]=
                Located(item.media.copy(favourite=value),item.requestCursor,item.context,item.revision)
            revision++
        }
        return true
    }

    /** Keep the unfiltered cursor and context for imports and writes. Only an exact,
     * complete filtered result may change which files are marked in that catalogue. */
    internal fun reconcileFavourites(filtered:Snapshot,from:GalleryDatalinkSession.MediaContext):Set<GalleryCameraEvidence.Store> {
        val current=context ?: return emptySet()
        if(!snapshot.complete)return emptySet()
        val verified=mutableSetOf<GalleryCameraEvidence.Store>()
        for(result in filtered.stores) {
            if(result.phase!=Phase.COMPLETE || !sameStore(from,current,result.store))continue
            val store=stores[result.store] ?: continue
            val marked=result.entries.associateBy { it.media.key }
            if(marked.any { (key,row) -> row.media.favourite!=true ||
                    store.entries[key]?.media?.sameFile(row.media)!=true })continue
            val updates=store.entries.values.toList()
            for(item in updates)updateFavourite(item,item.media.key in marked)
            verified.add(result.store)
        }
        return verified
    }
    fun close() { cancel();context=null;stores.clear();started=false;revision++ }

    private companion object { const val MAX_SD_OVERLAP_RETRIES = 3 }
}
