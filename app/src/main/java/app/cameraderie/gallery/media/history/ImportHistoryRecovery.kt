package app.cameraderie.gallery.media.history

/** Publication recovery never promotes a staging row or treats a provider error
 * as deletion. The Android adapter supplies scoped, readback-verified observations. */
class ImportHistoryRecovery(private val store:ImportHistoryStore,private val media:Media,private val now:()->Long) {
    enum class LocalState { ABSENT, OWNED_PENDING, OWNED_PUBLISHED, UNAVAILABLE }
    interface Media {
        fun state(uri:String):LocalState
        fun verify(entry:ImportHistoryEntry):Boolean
        fun removePending(uri:String):Boolean
        fun recoverFolder(entry:ImportHistoryEntry):FolderRecovery = FolderRecovery()
    }
    data class FolderRecovery(val publishedUri:String?=null,val removed:Boolean=false)
    data class Result(val recovered:Int,val interrupted:Int,val unresolved:Int,val cancelled:Boolean,val nextCursor:String?=null,val reachedEnd:Boolean=false)
    fun run(limit:Int=100,afterId:String?=null,cancelled:()->Boolean={false}):Result {
        require(limit in 1..500)
        var recovered=0;var interrupted=0;var unresolved=0;var cursor=afterId
        val page=store.unfinished(limit,afterId)
        for(entry in page) {
            if(cancelled()) return Result(recovered,interrupted,unresolved,true,cursor)
            try {
                if (entry.draft.folderTree!=null) {
                    val outcome=media.recoverFolder(entry)
                    if(cancelled()) return Result(recovered,interrupted,unresolved,true,cursor)
                    when {
                        outcome.publishedUri!=null -> {
                            store.completeFolder(entry.draft.id,outcome.publishedUri,now(),ImportCompletionKind.RECOVERED_VERIFIED)
                            recovered++
                        }
                        outcome.removed -> { store.interrupted(entry.draft.id);interrupted++ }
                        else -> unresolved++
                    }
                    cursor=entry.draft.id
                    continue
                }
                val local=media.state(entry.draft.uri)
                if(cancelled()) return Result(recovered,interrupted,unresolved,true,cursor)
                when(local) {
                    LocalState.ABSENT -> { store.interrupted(entry.draft.id);interrupted++ }
                    LocalState.OWNED_PENDING -> {
                        if(media.removePending(entry.draft.uri)) { store.interrupted(entry.draft.id);interrupted++ }
                        else unresolved++
                    }
                    LocalState.OWNED_PUBLISHED -> {
                        val matches=entry.stage==ImportHistoryStage.VERIFIED && entry.receipt?.sha256!=null && media.verify(entry)
                        if(cancelled()) return Result(recovered,interrupted,unresolved,true,cursor)
                        if(matches) {
                            store.complete(entry.draft.id,now(),ImportCompletionKind.RECOVERED_VERIFIED);recovered++
                        } else { store.interrupted(entry.draft.id);interrupted++ }
                    }
                    LocalState.UNAVAILABLE -> unresolved++
                }
            } catch(_:Exception) {
                if(cancelled()) return Result(recovered,interrupted,unresolved,true,cursor)
                unresolved++
            }
            cursor=entry.draft.id
        }
        return Result(recovered,interrupted,unresolved,false,cursor,page.size<limit)
    }
}
