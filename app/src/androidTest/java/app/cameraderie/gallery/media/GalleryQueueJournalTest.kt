package app.cameraderie.gallery.media

import app.cameraderie.NativeChecks
import app.cameraderie.gallery.media.history.*
import app.cameraderie.gallery.protocol.GalleryCameraEvidence.Store
import app.cameraderie.sony.SonyImportPlan
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class GalleryQueueJournalTest {
    @Test fun publishedAttemptRecoversByReceiptAndUnfinishedPendingAttemptIsRemoved() {
        val context = NativeChecks.instance.targetContext
        val database = "queue-reconcile-${UUID.randomUUID()}.db"
        val store = SqliteImportHistoryStore(context, database)
        val repository = ImportHistoryRepository(store, LocalImportedMedia(context))
        val resolver = context.contentResolver
        val created = mutableListOf<Uri>()
        val photo = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).let { bitmap ->
            try { ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }.toByteArray() }
            finally { bitmap.recycle() }
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(photo).joinToString("") { "%02x".format(it.toInt() and 255) }
        try {
            fun createAttempt(): Pair<Uri, String> {
                val id = UUID.randomUUID().toString()
                val name = "GripBridge-queue-test-$id.jpg"
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, SonyImportPlan.ALBUM)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })).also { created.add(it) }
                store.begin(ImportHistoryDraft(id, uri.toString(), name, photo.size.toLong(), 1, ImportProvenance.OFFERED_CAMERA_BYTES))
                checkNotNull(resolver.openOutputStream(uri, "w")).use { it.write(photo) }
                return uri to id
            }
            val (published, publishedId) = createAttempt()
            store.verified(publishedId, ImportHistoryReceipt(photo.size.toLong(), sha, "image/jpeg", true))
            assertEquals(1, resolver.update(published, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            assertEquals(GalleryOriginalImporter.PriorAttempt.COMPLETE, GalleryOriginalImporter.priorAttempt(repository, publishedId) { false })
            assertEquals(ImportCompletionKind.RECOVERED_VERIFIED, store.find(publishedId)?.completionKind)

            val (pending, pendingId) = createAttempt()
            assertEquals(GalleryOriginalImporter.PriorAttempt.RETRY, GalleryOriginalImporter.priorAttempt(repository, pendingId) { false })
            created.remove(pending)
            assertEquals(ImportHistoryStage.INTERRUPTED, store.find(pendingId)?.stage)
            assertEquals(ImportHistoryRecovery.LocalState.ABSENT, repository.local.state(pending.toString()))

            val (unknown, unknownId) = createAttempt()
            assertEquals(1, resolver.update(unknown, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            assertEquals(GalleryOriginalImporter.PriorAttempt.REVIEW, GalleryOriginalImporter.priorAttempt(repository, unknownId) { false })
            assertEquals(ImportHistoryRecovery.LocalState.OWNED_PUBLISHED, repository.local.state(unknown.toString()))
        } finally {
            try {
                for (uri in created) {
                    if (repository.local.snapshot(uri.toString())?.owned == true) resolver.delete(uri, null, null)
                }
            } finally {
                store.close()
                context.deleteDatabase(database)
            }
        }
    }

    @Test fun attemptAndCancellationSurviveIndependentJournalInstances() {
        val context = NativeChecks.instance.targetContext
        val file = File(context.cacheDir, "queue-${UUID.randomUUID()}.json")
        try {
            val queue = GalleryDownloadQueue(GalleryQueueJournal(file))
            assertTrue(queue.snapshot().isEmpty())
            val camera = GalleryDownloadQueue.camera("01:23:45:67:89:AB")
            val sources = listOf(GalleryDownloadQueue.Source("a", "DJI_0001.MP4", 2048, Store.SD),
                GalleryDownloadQueue.Source("b", "DJI_0002.JPG", 1024, Store.INTERNAL))
            queue.enqueue(camera, sources)
            val claim = checkNotNull(queue.claim(camera))
            val attempt = queue.beginAttempt(claim)
            val reopened = GalleryDownloadQueue(GalleryQueueJournal(file))
            assertEquals(sources, reopened.snapshot(camera).map { it.source })
            assertEquals(attempt, reopened.claim(camera)?.job?.attempt)
            assertFalse(file.readText().contains("01:23:45:67:89:AB"))
            reopened.cancel(reopened.snapshot(camera).map { it.id }.toSet())
            assertNull(GalleryDownloadQueue(GalleryQueueJournal(file)).claim(camera))
        } finally {
            android.util.AtomicFile(file).delete()
        }
    }
}
