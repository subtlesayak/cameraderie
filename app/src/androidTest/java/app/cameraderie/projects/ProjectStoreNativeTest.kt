package app.cameraderie.projects

import android.database.sqlite.SQLiteDatabase
import app.cameraderie.NativeChecks
import app.cameraderie.gallery.media.history.ImportCompletionKind
import app.cameraderie.gallery.media.history.ImportHistoryDraft
import app.cameraderie.gallery.media.history.ImportHistoryEntry
import app.cameraderie.gallery.media.history.ImportHistoryReceipt
import app.cameraderie.gallery.media.history.ImportHistoryStage
import app.cameraderie.gallery.media.history.ImportProvenance
import app.cameraderie.gallery.media.history.SqliteImportHistoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class ProjectStoreNativeTest {
    @Test fun keepsVerifiedMultiCameraMediaAndNotesAcrossReopen() {
        val context = NativeChecks.instance.targetContext
        val db = "projects-test-${UUID.randomUUID()}.db"
        try {
            val photo = fixture(1, "ridge.JPG", "image/jpeg", 'a', "Sony a6400")
            val copy = fixture(2, "same-ridge.JPG", "image/jpeg", 'a', "Sony a6400")
            val video = fixture(3, "ride.MP4", "video/mp4", 'b', "Osmo Action 6")
            val audio = fixture(4, "sound.WAV", "audio/wav", 'c', null)
            val projectId = ProjectStore(context, db).use { store ->
                val id = store.create("Himalayan Diaries", "Ridge sequence")
                val other = store.create("Other project", "")
                val unfinished = fixture(5, "pending.JPG", "image/jpeg", 'd', null).copy(stage = ImportHistoryStage.VERIFIED)
                val noHash = fixture(6, "old.JPG", "image/jpeg", null, null)
                assertEquals(3, store.addMedia(id, listOf(photo, copy, video, audio, unfinished, noHash)))
                assertEquals(0, store.addMedia(id, listOf(video, photo)))
                assertEquals(1, store.addMedia(other, listOf(copy)))
                store.update(id, "Himalayan Diaries", "Use the wide take.")
                id
            }
            ProjectStore(context, db).use { store ->
                assertEquals("Use the wide take.", store.project(projectId)?.notes)
                assertEquals(3, store.project(projectId)?.mediaCount)
                assertEquals(listOf(photo, video, audio).map { ProjectMedia.from(it) }, store.media(projectId))
                store.removeMedia(projectId, video.draft.id)
            }
            ProjectStore(context, db).use { store ->
                assertEquals(listOf(photo.draft.id, audio.draft.id), store.media(projectId).map(ProjectMedia::importId))
                assertEquals(2, store.project(projectId)?.mediaCount)
                assertEquals(1, store.projects().single { it.name == "Other project" }.mediaCount)
            }
        } finally { context.deleteDatabase(db) }
    }

    @Test fun shootsKeepMixedMediaAndMovesAcrossReopen() {
        val context = NativeChecks.instance.targetContext
        val db = "project-shoots-test-${UUID.randomUUID()}.db"
        try {
            val photo = fixture(1, "Departure.JPG", "image/jpeg", 'a', "Sony a6400")
            val video = fixture(2, "MountainPass.MP4", "video/mp4", 'b', "Osmo Action 6")
            val clip = fixture(3, "Viewpoint.MP4", "video/mp4", 'c', "Insta360 X4")
            val audio = fixture(4, "Location.WAV", "audio/wav", 'd', null)
            val alternatePhoto = photo.copy(draft = photo.draft.copy(id = "same-photo"))
            val project: String
            val departure: String
            val pass: String
            ProjectStore(context, db).use { store ->
                project = store.create("Himalayan Diaries", "Across the ridge")
                departure = store.createShoot(project, "Day 1 · Departure", "Early start")
                pass = store.createShoot(project, "Day 2 · Mountain pass", "")
                val other = store.create("Other project", "")
                val otherShoot = store.createShoot(other, "Unit A", "")
                assertEquals(2, store.addMedia(project, listOf(photo, video)))
                assertEquals(3, store.addMedia(project, listOf(alternatePhoto, clip, audio), departure))
                assertEquals(0, store.addMedia(project, listOf(photo), pass))
                assertThrows(IllegalStateException::class.java) { store.moveMedia(project, photo.draft.id, otherShoot) }
                store.moveMedia(project, photo.draft.id, pass)
                store.moveMedia(project, video.draft.id, pass)
                store.updateShoot(project, pass, "Day 2 · Mountain pass", "Use the wide take.")
                store.moveShoot(project, pass, -1)
            }
            ProjectStore(context, db).use { store ->
                assertEquals(listOf(pass, departure), store.shoots(project).map(ProjectShoot::id))
                assertEquals(listOf(2, 2), store.shoots(project).map(ProjectShoot::mediaCount))
                assertEquals("Use the wide take.", store.shoots(project).first().notes)
                val moved = store.media(project).first()
                assertEquals(ProjectMedia.from(photo), moved.copy(shootId = null))
                assertEquals(pass, moved.shootId)
                assertEquals(4, store.project(project)?.mediaCount)
                store.moveMedia(project, photo.draft.id, null)
                assertNull(store.media(project).first().shootId)
                store.removeMedia(project, video.draft.id)
                assertEquals(0, store.shoots(project).first().mediaCount)
            }
        } finally { context.deleteDatabase(db) }
    }

    @Test fun growsVersionOneProjectsWithoutChangingTheirReferences() {
        val context = NativeChecks.instance.targetContext
        val db = "projects-v1-test-${UUID.randomUUID()}.db"
        try {
            val file = context.getDatabasePath(db)
            file.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { legacy ->
                legacy.execSQL("""CREATE TABLE projects (
                    id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, notes TEXT NOT NULL, updated_at INTEGER NOT NULL)""")
                legacy.execSQL("""CREATE TABLE project_media (
                    project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                    import_id TEXT NOT NULL, sha256 TEXT NOT NULL, name TEXT NOT NULL,
                    PRIMARY KEY(project_id, import_id), UNIQUE(project_id, sha256))""")
                legacy.execSQL("INSERT INTO projects VALUES ('existing', 'Earlier project', 'Saved notes', 1)")
                legacy.execSQL("INSERT INTO project_media VALUES ('existing', 'saved', ?, 'ridge.jpg')", arrayOf("a".repeat(64)))
                legacy.version = 1
            }
            ProjectStore(context, db).use { upgraded ->
                assertEquals(Project("existing", "Earlier project", "Saved notes", 1), upgraded.project("existing"))
                assertEquals(emptyList<ProjectShoot>(), upgraded.shoots("existing"))
                val original = ProjectMedia("saved", "a".repeat(64), "ridge.jpg")
                assertEquals(listOf(original), upgraded.media("existing"))
                val shoot = upgraded.createShoot("existing", "Day 1", "")
                upgraded.moveMedia("existing", "saved", shoot)
                assertEquals(listOf(original.copy(shootId = shoot)), upgraded.media("existing"))
            }
        } finally { context.deleteDatabase(db) }
    }

    @Test fun publishedExportRetainsItsShootAndSourceAcrossReopen() {
        val context = NativeChecks.instance.targetContext
        val projects = "project-export-${UUID.randomUUID()}.db"
        val history = "project-history-${UUID.randomUUID()}.db"
        try {
            val source = fixture(1, "Original.MP4", "video/mp4", 'a', "Osmo Action 6")
            val output = fixture(2, "Edit.MP4", "video/mp4", 'b', "Osmo Action 6")
            ProjectStore(context, projects).use { projectStore ->
                val project = projectStore.create("Malaysian Diaries", "")
                val shoot = projectStore.createShoot(project, "Day 2 · Mountain pass", "")
                val other = projectStore.createShoot(project, "Day 1 · Departure", "")
                projectStore.addMedia(project, listOf(source), shoot)
                val wrongReceipt = source.copy(receipt = checkNotNull(source.receipt).copy(sha256 = "c".repeat(64)))
                assertNull(projectStore.exportOrigin(project, wrongReceipt))
                val origin = checkNotNull(projectStore.exportOrigin(project, source))
                val exported = output.draft.copy(provenance = ImportProvenance.EDITED_FROM_ORIGINAL,
                    videoSource = source.draft.displayName, trimStartUs = 1_000, trimEndUs = 500_000, projectExport = origin)
                SqliteImportHistoryStore(context, history).use { journal ->
                    journal.begin(source.draft)
                    journal.verified(source.draft.id, checkNotNull(source.receipt))
                    journal.complete(source.draft.id, 2, ImportCompletionKind.OBSERVED_PUBLICATION)
                    journal.begin(exported)
                    journal.verified(exported.id, checkNotNull(output.receipt))
                    assertEquals(ImportHistoryStage.VERIFIED, journal.projectExports(project).single().stage)
                }
                projectStore.moveMedia(project, source.draft.id, other)
                SqliteImportHistoryStore(context, history).use { reopened ->
                    reopened.complete(exported.id, 3, ImportCompletionKind.RECOVERED_VERIFIED)
                    val saved = reopened.projectExports(project).single()
                    assertEquals(ImportHistoryStage.COMPLETE, saved.stage)
                    assertEquals(origin, saved.draft.projectExport)
                    assertEquals(shoot, saved.draft.projectExport?.shootId)
                    assertEquals(source.draft.id, saved.draft.projectExport?.sourceImportId)
                    assertEquals(source.receipt?.sha256, saved.draft.projectExport?.sourceSha256)
                    assertEquals(source.receipt, reopened.find(source.draft.id)?.receipt)
                    assertEquals(output.receipt, saved.receipt)
                    assertEquals(emptyList<ImportHistoryEntry>(), reopened.projectExports("another-project"))
                }
            }
        } finally {
            context.deleteDatabase(projects)
            context.deleteDatabase(history)
        }
    }

    private fun fixture(number: Int, name: String, mime: String, hash: Char?, camera: String?): ImportHistoryEntry {
        val draft = ImportHistoryDraft("media-$number", "content://media/external_primary/file/$number", name, 100, 1,
            ImportProvenance.DOCUMENT_BYTES, cameraModel = camera)
        val receipt = ImportHistoryReceipt(100, hash?.toString()?.repeat(64), mime, hash != null, displayName = name)
        return ImportHistoryEntry(draft, ImportHistoryStage.COMPLETE, receipt)
    }
}
