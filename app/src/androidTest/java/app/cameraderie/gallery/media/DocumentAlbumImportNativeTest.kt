package app.cameraderie.gallery.media

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import app.cameraderie.NativeChecks
import app.cameraderie.gallery.media.history.ImportHistoryRepository
import app.cameraderie.gallery.media.history.LocalImportedMedia
import app.cameraderie.gallery.media.history.SqliteImportHistoryStore
import app.cameraderie.projects.ProjectStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class DocumentAlbumImportNativeTest {
    @Test fun verifiesAndReusesAudioTrackAndInsta360FilesFromLibrary() {
        val context = NativeChecks.instance.targetContext
        val resolver = context.contentResolver
        val id = System.nanoTime().toString()
        val historyName = "document-import-$id.db"
        val projectsName = "document-project-$id.db"
        val gpx = """
            <gpx version="1.1" creator="Cameraderie" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><trkseg>
                <trkpt lat="3.14" lon="101.69"><time>2026-01-02T03:04:05Z</time></trkpt>
                <trkpt lat="3.15" lon="101.70"><time>2026-01-02T03:04:06Z</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent().encodeToByteArray()
        val fixtures = mapOf(
            "Field-$id.WAV" to wave(),
            "Ride-$id.gpx" to gpx,
            "Video-$id.insv" to "Synthetic opaque video contents $id".encodeToByteArray(),
            "Photo-$id.insp" to "Synthetic opaque photo contents $id".encodeToByteArray(),
        )
        val sources = mutableListOf<Uri>()
        val local = LocalImportedMedia(context)
        try {
            SqliteImportHistoryStore(context, historyName).use { journal ->
                try {
                    for ((name, bytes) in fixtures) {
                        val input = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(MediaStore.MediaColumns.MIME_TYPE, checkNotNull(AlbumMediaFile.document(name)).mime)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Cameraderie-test-sources/")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, input))
                        sources.add(uri)
                        checkNotNull(resolver.openOutputStream(uri, "w")).use { it.write(bytes) }
                        assertEquals(1, resolver.update(uri, ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }, null, null))
                    }
                    val repository = ImportHistoryRepository(journal, local)
                    val importer = DocumentAlbumImport(context, repository)
                    assertEquals(DocumentAlbumImport.Summary(imported = fixtures.size), importer.run(sources, { false }) {})
                    val imported = repository.list().filter { it.draft.displayName in fixtures }
                    assertEquals(fixtures.keys, imported.map { it.draft.displayName }.toSet())
                    for (entry in imported) {
                        val name = entry.draft.displayName
                        val expected = fixtures.getValue(name)
                        val digest = MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") { "%02x".format(it) }
                        val receipt = checkNotNull(entry.receipt)
                        assertEquals(name, receipt.displayName)
                        assertEquals(checkNotNull(AlbumMediaFile.document(name)).mime, receipt.mime)
                        assertEquals(digest, receipt.sha256)
                        assertTrue(receipt.sourceHashMatched)
                        assertTrue(local.verify(entry) { false })
                        assertArrayEquals(expected, checkNotNull(resolver.openInputStream(Uri.parse(entry.draft.uri))).use { it.readBytes() })
                    }
                    assertEquals(Intent.ACTION_CHOOSER, local.share(imported.map { it.draft.uri }).action)
                    assertEquals(DocumentAlbumImport.Summary(duplicates = fixtures.size), importer.run(sources, { false }) {})
                    val project: String
                    val shoot: String
                    ProjectStore(context, projectsName).use { projects ->
                        project = projects.create("Mixed shoot", "")
                        shoot = projects.createShoot(project, "Day 2", "")
                        assertEquals(fixtures.size, projects.addMedia(project, imported, shoot))
                    }
                    ProjectStore(context, projectsName).use { projects ->
                        val items = projects.media(project)
                        assertEquals(fixtures.keys, items.map { it.name }.toSet())
                        assertTrue(items.all { it.shootId == shoot })
                    }
                } finally {
                    journal.completed().filter { it.draft.displayName in fixtures }.forEach { entry ->
                        resolver.delete(Uri.parse(entry.draft.uri), null, null)
                    }
                }
            }
        } finally {
            sources.forEach { resolver.delete(it, null, null) }
            context.deleteDatabase(historyName)
            context.deleteDatabase(projectsName)
        }
    }

    private fun wave(): ByteArray = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".encodeToByteArray())
        putInt(40)
        put("WAVEfmt ".encodeToByteArray())
        putInt(16)
        putShort(1)
        putShort(1)
        putInt(8000)
        putInt(16000)
        putShort(2)
        putShort(16)
        put("data".encodeToByteArray())
        putInt(4)
        putShort(0)
        putShort(123)
    }.array()
}
