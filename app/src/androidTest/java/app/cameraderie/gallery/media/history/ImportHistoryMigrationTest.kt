package app.cameraderie.gallery.media.history

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.cameraderie.NativeChecks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ImportHistoryMigrationTest {
    @Test fun upgradesAnExistingJournalWithReceiptsAndPendingImports() {
        val context = NativeChecks.instance.targetContext
        val name = "import-migration-${UUID.randomUUID()}.db"
        val hash = "a".repeat(64)
        val folder = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCameraderie"
        val pendingId = "aaaaaaaa-0000-0000-0000-000000000001"
        val reservation = "urn:cameraderie:folder:$pendingId"
        val pendingFile = "$folder/document/primary%3ADCIM%2FCameraderie%2Ftest.MP4"
        val originalRows: List<List<String?>>
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                // The released v1 schema and serialized keys are fixed test fixtures.
                db.execSQL("CREATE TABLE imports (id TEXT PRIMARY KEY NOT NULL, uri TEXT UNIQUE NOT NULL, stage TEXT NOT NULL, started_at INTEGER, draft TEXT NOT NULL, receipt TEXT, completed_at INTEGER, completion_kind TEXT)")
                db.execSQL("CREATE INDEX imports_by_stage ON imports(stage,completed_at)")
                db.version = 1
                val receipt = JSONObject()
                    .put("v", 1).put("bytes", 24).put("sha", hash).put("mime", "video/mp4")
                    .put("sourceMatched", true).put("captureDate", "2026-11-24")
                db.insertVersionOne("complete", 1, "COMPLETE", 2, receipt, 5, "OBSERVED_PUBLICATION")
                db.insertVersionOne("legacy", 2, "COMPLETE", null, null, 8, "LEGACY_ROW_ADDED")
                db.insertVersionOne("staging", 3, "STAGING", 9)
                db.insertVersionOne("interrupted", 4, "INTERRUPTED", 10, receipt)
                db.insertVersionOne(pendingId, 5, "VERIFIED", 11,
                    JSONObject(receipt.toString()).put("verifiedFolderFile", pendingFile), folder = folder, uri = reservation)
                originalRows = snapshot(db)
            }

            SqliteImportHistoryStore(context, name).use { store ->
                assertEquals(listOf("legacy", "complete"), store.completed().map { it.draft.id })
                val saved = checkNotNull(store.find("complete"))
                assertEquals(hash, saved.receipt?.sha256)
                assertEquals("2026-11-24", saved.receipt?.captureDate)
                assertEquals(5L, saved.completedAt)
                assertEquals(ImportCompletionKind.LEGACY_ROW_ADDED, store.find("legacy")?.completionKind)
                assertNull(store.find("legacy")?.receipt)
                assertEquals(ImportHistoryStage.INTERRUPTED, store.find("interrupted")?.stage)
                assertEquals(listOf(ImportHistoryStage.VERIFIED, ImportHistoryStage.STAGING), store.unfinished().map { it.stage })
                val pending = checkNotNull(store.find(pendingId))
                assertEquals(reservation, pending.draft.uri)
                assertEquals(pendingFile, pending.receipt?.verifiedFolderFile)
            }

            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                assertEquals(2, db.version)
                assertEquals(originalRows, snapshot(db))
                val indexes = db.rawQuery("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='imports'", null)
                    .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                assertTrue(indexes.containsAll(setOf("imports_by_stage_and_id", "imports_by_completion")))
            }

            SqliteImportHistoryStore(context, name).use { store ->
                store.completeFolder(pendingId, pendingFile, 12, ImportCompletionKind.RECOVERED_VERIFIED)
                assertEquals(pendingFile, store.completed().first().draft.uri)
                assertTrue(store.containsUri(pendingFile))
                store.interrupted("staging")
                assertTrue(store.unfinished().isEmpty())
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun SQLiteDatabase.insertVersionOne(
        id: String, mediaId: Int, stage: String, started: Long?, receipt: JSONObject? = null,
        completed: Long? = null, kind: String? = null, folder: String? = null,
        uri: String = "content://media/external_primary/video/media/$mediaId",
    ) {
        val draft = JSONObject().put("v", 1).put("id", id).put("uri", uri)
            .put("name", "test.MP4").put("bytes", 24)
            .put("provenance", if (started == null) "LEGACY_UNKNOWN" else "DOCUMENT_BYTES")
            .putOpt("started", started).putOpt("folderTree", folder)
        val uriKey = if (folder == null) "content://media/external_primary/file/$mediaId" else uri
        execSQL("INSERT INTO imports VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(id, uriKey, stage, started, draft.toString(), receipt?.toString(), completed, kind))
    }

    private fun snapshot(db: SQLiteDatabase): List<List<String?>> = db.rawQuery("SELECT * FROM imports ORDER BY id", null)
        .use { cursor -> buildList { while (cursor.moveToNext()) add(List(cursor.columnCount, cursor::getString)) } }
}
