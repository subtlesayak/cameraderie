package app.cameraderie.gallery.media.history

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.Closeable

/** Private journal. Separate stage columns support crash recovery without
 * treating merely-created or partially-copied destinations as imported files. */
class SqliteImportHistoryStore(context:Context,name:String="import_history.db"):ImportHistoryStore,Closeable {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE imports (id TEXT PRIMARY KEY NOT NULL, uri TEXT UNIQUE NOT NULL, stage TEXT NOT NULL, started_at INTEGER, draft TEXT NOT NULL, receipt TEXT, completed_at INTEGER, completion_kind TEXT, project_export_id TEXT)")
            createHistoryIndexes(db)
            createProjectExportIndex(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            check(oldVersion in 1..2 && newVersion == DATABASE_VERSION) {
                "Unsupported import-history migration from $oldVersion to $newVersion"
            }
            if (oldVersion == 1) {
                db.execSQL("DROP INDEX imports_by_stage")
                createHistoryIndexes(db)
            }
            db.execSQL("ALTER TABLE imports ADD COLUMN project_export_id TEXT")
            createProjectExportIndex(db)
        }
    }

    private fun createHistoryIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX imports_by_stage_and_id ON imports(stage, id)")
        db.execSQL("CREATE INDEX imports_by_completion ON imports(stage, COALESCE(completed_at, started_at, 0) DESC, id DESC)")
    }
    private fun createProjectExportIndex(db: SQLiteDatabase) {
        db.execSQL("""CREATE INDEX imports_by_project_export ON imports(
            project_export_id, stage, COALESCE(completed_at, started_at, 0) DESC, id DESC)
            WHERE project_export_id IS NOT NULL""")
    }

    private fun <T> transaction(block:(SQLiteDatabase)->T):T {
        val db=helper.writableDatabase;db.beginTransaction()
        try { val result=block(db);db.setTransactionSuccessful();return result } finally { db.endTransaction() }
    }
    override fun begin(draft:ImportHistoryDraft) {
        require(draft.provenance!=ImportProvenance.LEGACY_UNKNOWN)
        transaction { db ->
            check(db.insertOrThrow("imports",null,values(draft,ImportHistoryStage.STAGING,null,null,null))>=0)
        }
    }
    override fun verified(id:String,receipt:ImportHistoryReceipt) {
        require(receipt.sha256!=null)
        transaction { db ->
            val row=checkNotNull(find(db,id));check(receipt.bytes==row.draft.expectedBytes)
            if(row.stage==ImportHistoryStage.VERIFIED) check(row.receipt==receipt)
            else {
                check(row.stage==ImportHistoryStage.STAGING)
                check(db.update("imports",ContentValues().apply { put("stage",ImportHistoryStage.VERIFIED.name);put("receipt",encode(receipt).toString()) },"id=? AND stage=?",arrayOf(id,ImportHistoryStage.STAGING.name))==1)
            }
        }
    }
    override fun complete(id:String,observedAt:Long,kind:ImportCompletionKind) {
        require(observedAt>=0 && kind!=ImportCompletionKind.LEGACY_ROW_ADDED)
        transaction { db ->
            val row=checkNotNull(find(db,id))
            if(row.stage!=ImportHistoryStage.COMPLETE) {
                check(row.stage==ImportHistoryStage.VERIFIED && row.receipt?.sha256!=null)
                check(db.update("imports",ContentValues().apply { put("stage",ImportHistoryStage.COMPLETE.name);put("completed_at",observedAt);put("completion_kind",kind.name) },"id=? AND stage=?",arrayOf(id,ImportHistoryStage.VERIFIED.name))==1)
            }
        }
    }
    override fun completeFolder(id:String, uri:String, observedAt:Long, kind:ImportCompletionKind) {
        require(observedAt>=0 && kind!=ImportCompletionKind.LEGACY_ROW_ADDED)
        transaction { db ->
            val row=checkNotNull(find(db,id))
            check(LocalMediaIdentity.inFolder(uri,checkNotNull(row.draft.folderTree)))
            check(row.stage==ImportHistoryStage.VERIFIED && row.receipt?.sha256!=null)
            val published=row.draft.copy(uri=uri)
            check(db.update("imports",values(published,ImportHistoryStage.COMPLETE,row.receipt,observedAt,kind),
                "id=? AND stage=?",arrayOf(id,ImportHistoryStage.VERIFIED.name))==1)
        }
    }
    internal fun relink(entry: ImportHistoryEntry): Boolean = transaction { db ->
        val draft = entry.draft
        val receipt = checkNotNull(entry.receipt)
        check(entry.stage == ImportHistoryStage.COMPLETE && receipt.sha256 != null && receipt.bytes == draft.expectedBytes)
        check(LocalMediaIdentity.inFolder(draft.uri, checkNotNull(draft.folderTree)))
        val prior = find(db, draft.id)
        if (prior != null && (prior.stage != ImportHistoryStage.COMPLETE || prior.receipt?.sha256 != receipt.sha256)) {
            return@transaction false
        }
        val values = values(draft, entry.stage, receipt, entry.completedAt, entry.completionKind)
        if (prior == null) {
            db.insertWithOnConflict("imports", null, values, SQLiteDatabase.CONFLICT_IGNORE) >= 0
        } else {
            db.updateWithOnConflict("imports", values, "id=?", arrayOf(draft.id), SQLiteDatabase.CONFLICT_IGNORE) == 1
        }
    }

    override fun interrupted(id:String) {
        helper.writableDatabase.update("imports",ContentValues().apply { put("stage",ImportHistoryStage.INTERRUPTED.name) },
            "id=? AND stage IN (?,?)",arrayOf(id,ImportHistoryStage.STAGING.name,ImportHistoryStage.VERIFIED.name))
    }
    override fun unfinished(limit:Int,afterId:String?):List<ImportHistoryEntry> {
        require(limit in 1..500)
        val selection="stage IN (?,?)"+(if(afterId==null) "" else " AND id>?")
        val args=mutableListOf(ImportHistoryStage.STAGING.name,ImportHistoryStage.VERIFIED.name).apply { if(afterId!=null)add(afterId) }
        return helper.readableDatabase.query("imports",null,selection,args.toTypedArray(),null,null,"id ASC",limit.toString()).use(::rows)
    }
    override fun completed(offset:Int,limit:Int):List<ImportHistoryEntry> {
        require(offset>=0 && limit in 1..500)
        return helper.readableDatabase.query("imports",null,"stage=?",arrayOf(ImportHistoryStage.COMPLETE.name),null,null,"COALESCE(completed_at,started_at,0) DESC, id DESC","$offset,$limit").use(::rows)
    }
    override fun completedAfterId(afterId:String?,limit:Int):List<ImportHistoryEntry> {
        require(limit in 1..500)
        val selection="stage=?"+(if(afterId==null)"" else " AND id>?")
        val args=mutableListOf(ImportHistoryStage.COMPLETE.name).apply { if(afterId!=null)add(afterId) }
        return helper.readableDatabase.query("imports",null,selection,args.toTypedArray(),null,null,"id ASC",limit.toString()).use(::rows)
    }
    override fun projectExports(projectId: String): List<ImportHistoryEntry> =
        helper.readableDatabase.query("imports", null,
            "project_export_id=? AND stage IN ('STAGING','VERIFIED','COMPLETE')", arrayOf(projectId),
            null, null, "COALESCE(completed_at,started_at,0) DESC, id DESC").use(::rows)

    override fun countCompleted()=DatabaseUtils.longForQuery(helper.readableDatabase,"SELECT count(*) FROM imports WHERE stage=?",arrayOf(ImportHistoryStage.COMPLETE.name)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    override fun containsUri(uri:String)=helper.readableDatabase.query("imports",arrayOf("id"),"uri=?",arrayOf(LocalMediaIdentity.key(uri)),null,null,null,"1").use { it.moveToFirst() }
    override fun legacy(draft:ImportHistoryDraft,receipt:ImportHistoryReceipt?,rowAddedAt:Long?) {
        require(draft.provenance==ImportProvenance.LEGACY_UNKNOWN && draft.requestedQuality==null && draft.startedAt==null)
        require(receipt?.sha256==null && receipt?.sourceHashMatched!=true)
        require(receipt==null || receipt.bytes==draft.expectedBytes)
        require(rowAddedAt==null || rowAddedAt>=0)
        // A URI already journaled as STAGING/VERIFIED must never be promoted by
        // an unrelated discovery scan; INSERT IGNORE retains its original state.
        val inserted=helper.writableDatabase.insertWithOnConflict("imports",null,values(draft,ImportHistoryStage.COMPLETE,receipt,rowAddedAt,ImportCompletionKind.LEGACY_ROW_ADDED),SQLiteDatabase.CONFLICT_IGNORE)
        check(inserted>=0 || containsUri(draft.uri))
    }
    override fun find(id:String):ImportHistoryEntry?=find(helper.readableDatabase,id)
    private fun find(db:SQLiteDatabase,id:String):ImportHistoryEntry?=db.query("imports",null,"id=?",arrayOf(id),null,null,null,"1").use { if(it.moveToFirst()) row(it) else null }
    private fun rows(cursor:Cursor):List<ImportHistoryEntry> = buildList { while(cursor.moveToNext())add(row(cursor)) }
    private fun row(c:Cursor):ImportHistoryEntry {
        val draft=decodeDraft(JSONObject(c.getString(c.getColumnIndexOrThrow("draft"))))
        check(draft.id==c.getString(c.getColumnIndexOrThrow("id")) && LocalMediaIdentity.key(draft.uri)==c.getString(c.getColumnIndexOrThrow("uri")))
        val receipt=c.getColumnIndexOrThrow("receipt").let { if(c.isNull(it)) null else decodeReceipt(JSONObject(c.getString(it))) }
        val completed=c.getColumnIndexOrThrow("completed_at").let { if(c.isNull(it)) null else c.getLong(it) }
        val kind=c.getColumnIndexOrThrow("completion_kind").let { if(c.isNull(it)) null else ImportCompletionKind.valueOf(c.getString(it)) }
        return ImportHistoryEntry(draft,ImportHistoryStage.valueOf(c.getString(c.getColumnIndexOrThrow("stage"))),receipt,completed,kind)
    }
    private fun values(d:ImportHistoryDraft,stage:ImportHistoryStage,r:ImportHistoryReceipt?,completed:Long?,kind:ImportCompletionKind?)=ContentValues().apply {
        put("id",d.id);put("uri",LocalMediaIdentity.key(d.uri));put("stage",stage.name);put("draft",encode(d).toString())
        put("project_export_id", d.projectExport?.projectId)
        if(d.startedAt==null)putNull("started_at") else put("started_at",d.startedAt)
        if(r==null)putNull("receipt") else put("receipt",encode(r).toString())
        if(completed==null)putNull("completed_at") else put("completed_at",completed)
        if(kind==null)putNull("completion_kind") else put("completion_kind",kind.name)
    }
    internal fun encode(d:ImportHistoryDraft)=JSONObject().apply {
        put("v",1);put("id",d.id);put("uri",d.uri);put("name",d.displayName);put("bytes",d.expectedBytes);put("provenance",d.provenance.name)
        putOpt("videoSource",d.videoSource);putOpt("framePositionMs",d.framePositionMs)
        d.projectExport?.let { origin ->
            put("projectExport", JSONObject()
                .put("project", origin.projectId).putOpt("shoot", origin.shootId)
                .put("sourceId", origin.sourceImportId).put("sourceSha", origin.sourceSha256))
        }
        putOpt("trimStartUs",d.trimStartUs);putOpt("trimEndUs",d.trimEndUs)
        putOpt("companionFor",d.companionFor);putOpt("companionRef",d.companionParentReference)
        putOpt("folderTree",d.folderTree);putOpt("started",d.startedAt);putOpt("quality",d.requestedQuality);putOpt("model",d.cameraModel);putOpt("scope",d.sourceScope);putOpt("scopeKind",d.sourceScopeKind);putOpt("sourceRef",d.sourceItemReference);putOpt("reduced",d.offeredReduced)
    }
    internal fun decodeDraft(j:JSONObject):ImportHistoryDraft {
        check(j.getInt("v")==1)
        return ImportHistoryDraft(j.getString("id"),j.getString("uri"),j.getString("name"),j.getLong("bytes"),j.longOrNull("started"),ImportProvenance.valueOf(j.getString("provenance")),
            j.stringOrNull("quality"),j.stringOrNull("model"),j.stringOrNull("scope"),j.stringOrNull("scopeKind"),j.stringOrNull("sourceRef"),if(j.has("reduced") && !j.isNull("reduced")) j.getBoolean("reduced") else null,j.stringOrNull("folderTree"),
            j.stringOrNull("companionFor"),j.stringOrNull("companionRef"),
            j.stringOrNull("videoSource") ?: j.stringOrNull("frameSource"),j.longOrNull("framePositionMs"),
            j.longOrNull("trimStartUs"),j.longOrNull("trimEndUs"),
            j.optJSONObject("projectExport")?.let { origin ->
                ProjectExportOrigin(origin.getString("project"), origin.stringOrNull("shoot"),
                    origin.getString("sourceId"), origin.getString("sourceSha"))
            })
    }
    internal fun encode(r:ImportHistoryReceipt)=JSONObject().apply {
        put("v",1);put("bytes",r.bytes);putOpt("sha",r.sha256);put("mime",r.mime);put("sourceMatched",r.sourceHashMatched)
        putOpt("width",r.width);putOpt("height",r.height);putOpt("orientation",r.exifOrientation)
        putOpt("captureDate",r.captureDate);putOpt("captureTime",r.captureTime);putOpt("captureOffset",r.captureOffset);putOpt("captureSource",r.captureSource);putOpt("indexingZone",r.indexingZone)
        putOpt("actualName",r.displayName);putOpt("verifiedFolderFile",r.verifiedFolderFile)
    }
    internal fun decodeReceipt(j:JSONObject):ImportHistoryReceipt {
        check(j.getInt("v")==1)
        return ImportHistoryReceipt(j.getLong("bytes"),j.stringOrNull("sha"),j.getString("mime"),j.getBoolean("sourceMatched"),j.intOrNull("width"),j.intOrNull("height"),j.intOrNull("orientation"),
            j.stringOrNull("captureDate"),j.stringOrNull("captureTime"),j.stringOrNull("captureOffset"),j.stringOrNull("captureSource"),j.stringOrNull("indexingZone"),j.stringOrNull("actualName"),j.stringOrNull("verifiedFolderFile"))
    }
    private fun JSONObject.stringOrNull(key:String)=if(has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.longOrNull(key:String)=if(has(key) && !isNull(key)) getLong(key) else null
    private fun JSONObject.intOrNull(key:String)=if(has(key) && !isNull(key)) getInt(key) else null
    override fun close()=helper.close()

    private companion object {
        const val DATABASE_VERSION = 3
    }
}
