package app.cameraderie.projects

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.cameraderie.gallery.media.history.ImportHistoryEntry
import app.cameraderie.gallery.media.history.ImportHistoryStage
import app.cameraderie.gallery.media.history.ProjectExportOrigin
import java.io.Closeable
import java.util.UUID

internal data class Project(val id: String, val name: String, val notes: String, val mediaCount: Int)

internal data class ProjectShoot(val id: String, val name: String, val notes: String, val mediaCount: Int)

internal data class LibraryShoot(
    val id: String,
    val label: String,
    val importId: String?,
    val sha256: String?,
)

internal data class ProjectMedia(val importId: String, val sha256: String, val name: String, val shootId: String? = null) {
    companion object {
        fun from(entry: ImportHistoryEntry): ProjectMedia? {
            val receipt = entry.receipt ?: return null
            val hash = receipt.sha256 ?: return null
            if (entry.stage != ImportHistoryStage.COMPLETE || receipt.bytes != entry.draft.expectedBytes) return null
            return ProjectMedia(entry.draft.id, hash, receipt.displayName ?: entry.draft.displayName)
        }
    }
}

/** A project references completed receipts. The Library keeps ownership of the media bytes. */
internal class ProjectStore(context: Context, name: String = "projects.db") : Closeable {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, 2) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE projects (
                id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, notes TEXT NOT NULL, updated_at INTEGER NOT NULL
            )""")
            db.execSQL("""CREATE TABLE project_media (
                project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                import_id TEXT NOT NULL, sha256 TEXT NOT NULL, name TEXT NOT NULL,
                PRIMARY KEY(project_id, import_id), UNIQUE(project_id, sha256)
            )""")
            createShoots(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            check(oldVersion == 1 && newVersion == 2)
            createShoots(db)
        }

        private fun createShoots(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE project_shoots (
                project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                id TEXT NOT NULL, name TEXT NOT NULL, notes TEXT NOT NULL, position INTEGER NOT NULL,
                PRIMARY KEY(project_id, id)
            )""")
            db.execSQL("""CREATE TABLE shoot_media (
                project_id TEXT NOT NULL, import_id TEXT NOT NULL, shoot_id TEXT NOT NULL,
                PRIMARY KEY(project_id, import_id),
                FOREIGN KEY(project_id, import_id) REFERENCES project_media(project_id, import_id) ON DELETE CASCADE,
                FOREIGN KEY(project_id, shoot_id) REFERENCES project_shoots(project_id, id) ON DELETE CASCADE
            )""")
            db.execSQL("CREATE INDEX shoot_media_by_shoot ON shoot_media(project_id, shoot_id)")
        }
    }

    fun create(name: String, notes: String): String {
        val id = UUID.randomUUID().toString()
        helper.writableDatabase.insertOrThrow("projects", null, projectValues(name, notes).apply { put("id", id) })
        return id
    }

    fun update(id: String, name: String, notes: String) {
        check(helper.writableDatabase.update("projects", projectValues(name, notes), "id=?", arrayOf(id)) == 1)
    }

    fun projects(): List<Project> = queryProjects(null)

    fun project(id: String): Project? = queryProjects(id).singleOrNull()

    private fun queryProjects(id: String?): List<Project> = helper.readableDatabase.rawQuery(
        """SELECT p.id, p.name, p.notes, (SELECT COUNT(*) FROM project_media m WHERE m.project_id=p.id)
            FROM projects p ${if (id == null) "" else "WHERE p.id=?"} ORDER BY p.updated_at DESC, p.id""",
        id?.let { arrayOf(it) },
    ).use { readRows(it) { Project(getString(0), getString(1), getString(2), getInt(3)) } }

    fun shoots(id: String): List<ProjectShoot> = helper.readableDatabase.rawQuery(
        """SELECT s.id, s.name, s.notes, (SELECT COUNT(*) FROM shoot_media m
            WHERE m.project_id=s.project_id AND m.shoot_id=s.id)
            FROM project_shoots s WHERE s.project_id=? ORDER BY s.position, s.id""",
        arrayOf(id),
    ).use { readRows(it) { ProjectShoot(getString(0), getString(1), getString(2), getInt(3)) } }

    fun createShoot(projectId: String, name: String, notes: String): String {
        val id = UUID.randomUUID().toString()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val position = db.rawQuery(
                "SELECT COALESCE(MAX(position), -1) + 1 FROM project_shoots WHERE project_id=?", arrayOf(projectId),
            ).use { check(it.moveToFirst()); it.getLong(0) }
            val values = namedValues(name, notes).apply {
                put("project_id", projectId)
                put("id", id)
                put("position", position)
            }
            db.insertOrThrow("project_shoots", null, values)
            touch(db, projectId)
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }

    fun updateShoot(projectId: String, shootId: String, name: String, notes: String) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            check(db.update("project_shoots", namedValues(name, notes), "project_id=? AND id=?",
                arrayOf(projectId, shootId)) == 1)
            touch(db, projectId)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun moveShoot(projectId: String, shootId: String, offset: Int) {
        require(offset == -1 || offset == 1)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val order = db.rawQuery("SELECT id, position FROM project_shoots WHERE project_id=? ORDER BY position, id",
                arrayOf(projectId)).use { readRows(it) { getString(0) to getLong(1) } }
            val index = order.indexOfFirst { it.first == shootId }
            check(index >= 0)
            order.getOrNull(index + offset)?.let { (neighbour, position) ->
                db.execSQL("""UPDATE project_shoots SET position=CASE WHEN id=? THEN ? ELSE ? END
                    WHERE project_id=? AND id IN (?,?)""",
                    arrayOf(shootId, position, order[index].second, projectId, shootId, neighbour))
                touch(db, projectId)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun media(id: String): List<ProjectMedia> = helper.readableDatabase.rawQuery(
        """SELECT m.import_id, m.sha256, m.name, s.shoot_id FROM project_media m
            LEFT JOIN shoot_media s ON s.project_id=m.project_id AND s.import_id=m.import_id
            WHERE m.project_id=? ORDER BY m.rowid""",
        arrayOf(id),
    ).use { readRows(it) { ProjectMedia(getString(0), getString(1), getString(2), getString(3)) } }

    fun libraryShoots(): List<LibraryShoot> = helper.readableDatabase.rawQuery(
        """SELECT s.id, p.name, s.name, m.import_id, m.sha256 FROM projects p
            JOIN project_shoots s ON s.project_id=p.id
            LEFT JOIN shoot_media sm ON sm.project_id=s.project_id AND sm.shoot_id=s.id
            LEFT JOIN project_media m ON m.project_id=sm.project_id AND m.import_id=sm.import_id
            ORDER BY p.name COLLATE NOCASE, p.id, s.position, s.id""",
        null,
    ).use { cursor ->
        readRows(cursor) { LibraryShoot(getString(0), getString(1) + " · " + getString(2), getString(3), getString(4)) }
    }

    fun exportOrigin(projectId: String, source: ImportHistoryEntry): ProjectExportOrigin? {
        val media = ProjectMedia.from(source) ?: return null
        return helper.readableDatabase.rawQuery(
            """SELECT s.shoot_id FROM project_media m
                LEFT JOIN shoot_media s ON s.project_id=m.project_id AND s.import_id=m.import_id
                WHERE m.project_id=? AND m.import_id=? AND m.sha256=?""",
            arrayOf(projectId, media.importId, media.sha256),
        ).use {
            if (it.moveToFirst()) ProjectExportOrigin(projectId, it.getString(0), media.importId, media.sha256) else null
        }
    }

    fun addMedia(id: String, entries: List<ImportHistoryEntry>, shootId: String? = null): Int {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            check(db.rawQuery("SELECT 1 FROM projects WHERE id=?", arrayOf(id)).use { it.moveToFirst() })
            if (shootId != null) requireShoot(db, id, shootId)
            var added = 0
            for (entry in entries) {
                val item = ProjectMedia.from(entry) ?: continue
                val values = ContentValues().apply {
                    put("project_id", id)
                    put("import_id", item.importId)
                    put("sha256", item.sha256)
                    put("name", item.name)
                }
                val inserted = db.insertWithOnConflict("project_media", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
                if (shootId == null) {
                    if (inserted) added++
                } else {
                    // An existing unassigned reference can join this shoot. Existing shoot
                    // assignments only change through the explicit move action.
                    val importId = if (inserted) item.importId else db.rawQuery(
                        "SELECT import_id FROM project_media WHERE project_id=? AND sha256=?",
                        arrayOf(id, item.sha256),
                    ).use { if (it.moveToFirst()) it.getString(0) else null }
                    if (importId != null && assignShoot(db, id, importId, shootId, SQLiteDatabase.CONFLICT_IGNORE)) added++
                }
            }
            if (added > 0) touch(db, id)
            db.setTransactionSuccessful()
            return added
        } finally { db.endTransaction() }
    }

    fun moveMedia(projectId: String, importId: String, shootId: String?) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            check(db.rawQuery("SELECT 1 FROM project_media WHERE project_id=? AND import_id=?",
                arrayOf(projectId, importId)).use { it.moveToFirst() })
            if (shootId == null) {
                db.delete("shoot_media", "project_id=? AND import_id=?", arrayOf(projectId, importId))
            } else {
                requireShoot(db, projectId, shootId)
                check(assignShoot(db, projectId, importId, shootId, SQLiteDatabase.CONFLICT_REPLACE))
            }
            touch(db, projectId)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun requireShoot(db: SQLiteDatabase, projectId: String, shootId: String) {
        check(db.rawQuery("SELECT 1 FROM project_shoots WHERE project_id=? AND id=?",
            arrayOf(projectId, shootId)).use { it.moveToFirst() })
    }

    private fun assignShoot(db: SQLiteDatabase, projectId: String, importId: String, shootId: String, conflict: Int): Boolean {
        val values = ContentValues().apply {
            put("project_id", projectId)
            put("import_id", importId)
            put("shoot_id", shootId)
        }
        return db.insertWithOnConflict("shoot_media", null, values, conflict) != -1L
    }

    fun removeMedia(id: String, importId: String) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            if (db.delete("project_media", "project_id=? AND import_id=?", arrayOf(id, importId)) > 0) touch(db, id)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun touch(db: SQLiteDatabase, id: String) {
        val values = ContentValues().apply { put("updated_at", System.currentTimeMillis()) }
        db.update("projects", values, "id=?", arrayOf(id))
    }

    private fun projectValues(name: String, notes: String) =
        namedValues(name, notes).apply { put("updated_at", System.currentTimeMillis()) }

    private fun namedValues(name: String, notes: String): ContentValues {
        require(name.trim().length in 1..MAX_NAME && notes.length <= MAX_NOTES)
        return ContentValues().apply {
            put("name", name.trim())
            put("notes", notes)
        }
    }

    override fun close() = helper.close()

    companion object {
        const val MAX_NAME = 120
        const val MAX_NOTES = 8_000
        private inline fun <T> readRows(cursor: Cursor, read: Cursor.() -> T): List<T> = buildList {
            while (cursor.moveToNext()) add(cursor.read())
        }
    }
}
