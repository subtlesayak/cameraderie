package app.cameraderie.gallery.media.history

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** All durable writes happen on the import/recovery worker, before UI generation
 * checks. This is publication history, not resumable background camera transfer. */
class ImportHistoryRepository(
    val store:ImportHistoryStore,val local:LocalImportedMedia,
    private val gate:ImportHistoryGate=ImportHistoryGate.process,private val now:()->Long={System.currentTimeMillis()}
) {
    data class Recovery(val ran:Boolean,val recovered:Int=0,val interrupted:Int=0,val unresolved:Int=0,val legacyAdded:Int=0)
    fun begin(draft:ImportHistoryDraft)=store.begin(draft)
    fun verified(id:String,receipt:ImportHistoryReceipt)=store.verified(id,receipt)
    fun completed(id:String)=store.complete(id,now(),ImportCompletionKind.OBSERVED_PUBLICATION)
    fun interrupted(id:String)=store.interrupted(id)
    fun list(cancelled:()->Boolean={false}):List<ImportHistoryEntry> {
        val records=mutableListOf<ImportHistoryEntry>();var cursor:String?=null
        do {
            if(cancelled())throw java.io.InterruptedIOException()
            val page=store.completedAfterId(cursor)
            records.addAll(page)
            if(page.size<100)break
            val next=page.last().draft.id;check(next!=cursor);cursor=next
        } while(true)
        return records.sortedWith(compareByDescending<ImportHistoryEntry> { it.completedAt ?: it.draft.startedAt ?: 0L }.thenByDescending { it.draft.id })
    }
    fun completedProjectExports(projectId: String): List<ImportHistoryEntry> {
        var exports = store.projectExports(projectId)
        if (exports.any { it.stage == ImportHistoryStage.STAGING || it.stage == ImportHistoryStage.VERIFIED }) {
            reconcile()
            exports = store.projectExports(projectId)
        }
        return exports.filter { it.stage == ImportHistoryStage.COMPLETE }
    }

    fun reconcile(cancelled:()->Boolean={false}):Recovery {
        var recovered=0;var interrupted=0;var unresolved=0;var legacy=0
        val ran=gate.tryRecover {
            val recovery=ImportHistoryRecovery(store,object:ImportHistoryRecovery.Media {
                override fun state(uri:String)=local.state(uri)
                override fun verify(entry:ImportHistoryEntry)=local.verify(entry,cancelled)
                override fun removePending(uri:String)=local.removePending(uri)
                override fun recoverFolder(entry:ImportHistoryEntry)=local.recoverFolder(entry,cancelled)
            },now)
            var cursor:String?=null
            do {
                val page=recovery.run(afterId=cursor,cancelled=cancelled)
                recovered+=page.recovered;interrupted+=page.interrupted;unresolved+=page.unresolved
                if(page.cancelled) return@tryRecover
                if(page.reachedEnd)break
                check(page.nextCursor!=null && page.nextCursor!=cursor);cursor=page.nextCursor
            } while(!cancelled())
            if(cancelled())return@tryRecover
            // No active import can be present while the writer gate is held.
            // Orphans include death between MediaStore insertion and journal begin.
            val pending=mutableListOf<String>()
            local.pending({ pending.add(it.uri) },cancelled)
            for(uri in pending) {
                if(cancelled())return@tryRecover
                if(!local.removePending(uri))unresolved++
            }
            if(cancelled())return@tryRecover
            local.published({ item ->
                if(!store.containsUri(item.uri)) {
                    val zone=ZoneId.systemDefault();val clock=item.dateTaken?.let { Instant.ofEpochMilli(it).atZone(zone) }
                    val metadata=ImportHistoryReceipt(item.bytes,null,item.mime.ifBlank { "application/octet-stream" },false,item.width,item.height,
                        captureDate=clock?.toLocalDate()?.toString(),captureTime=clock?.toLocalTime()?.toString(),captureSource=if(clock==null)null else "MEDIASTORE_INDEX",indexingZone=if(clock==null)null else zone.id,displayName=item.name)
                    val draft=ImportHistoryDraft(UUID.randomUUID().toString(),item.uri,item.name.ifBlank { "Imported media" },item.bytes,null,ImportProvenance.LEGACY_UNKNOWN)
                    store.legacy(draft,metadata,item.dateAdded);legacy++
                }
            },cancelled)
        }
        return Recovery(ran,recovered,interrupted,unresolved,legacy)
    }
    companion object {
        @Volatile private var shared:ImportHistoryRepository?=null
        fun get(context:Context):ImportHistoryRepository=shared ?: synchronized(this) {
            shared ?: ImportHistoryRepository(SqliteImportHistoryStore(context.applicationContext),LocalImportedMedia(context.applicationContext)).also { shared=it }
        }
    }
}
