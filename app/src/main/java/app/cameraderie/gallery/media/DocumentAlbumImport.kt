package app.cameraderie.gallery.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.cameraderie.gallery.media.history.ImportHistoryDraft
import app.cameraderie.gallery.media.history.ImportHistoryGate
import app.cameraderie.gallery.media.history.ImportProvenance
import app.cameraderie.gallery.media.history.ImportHistoryRepository
import app.cameraderie.sony.SonyMediaStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

internal class DocumentAlbumImport(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val history = ImportHistoryRepository.get(app)
    private val scratch = File(app.noBackupFilesDir, "document-imports")

    data class Summary(
        val imported: Int = 0,
        val duplicates: Int = 0,
        val unsupported: Int = 0,
        val filenameConflicts: Int = 0,
        val failed: Int = 0,
        val needsReview: Boolean = false,
        val cancelled: Boolean = false
    )

    private data class Document(val name: String, val size: Long?)

    fun run(uris: List<Uri>, cancelled: () -> Boolean, destinationTree: Uri? = null, progress: (Int) -> Unit): Summary {
        require(uris.size in 1..MAX_SELECTION)
        check(scratch.isDirectory || scratch.mkdirs())
        // One document worker owns this directory, including cleanup after process death.
        scratch.listFiles().orEmpty().filter { it.name.startsWith("document-") }.forEach { it.delete() }
        val folder = destinationTree?.let { FolderImportOutput(app,it).apply { checkWritable() } }
        history.reconcile(cancelled)
        val saved = history.list(cancelled).filter { it.receipt?.sha256 != null && it.draft.folderTree == destinationTree?.toString() }.groupBy { it.receipt?.sha256 }
        val newHashes = mutableSetOf<String>()
        var summary = Summary()

        for ((index, uri) in uris.withIndex()) {
            if (cancelled()) break
            progress(index + 1)
            try {
                val document = metadata(uri)
                val file = AlbumMediaFile.document(document.name)
                if (file == null) {
                    summary = summary.copy(unsupported = summary.unsupported + 1)
                    continue
                }
                document.size?.let { expected ->
                    val free = scratch.usableSpace
                    if (free < expected || (folder == null && free - expected < expected)) throw IOException("Insufficient space for import")
                }

                val staged = File.createTempFile("document-", ".part", scratch)
                try {
                    val copied = checkNotNull(resolver.openInputStream(uri)).use { input ->
                        staged.outputStream().use { output -> stage(input, output, document.size, cancelled) }
                    }
                    val current = metadata(uri)
                    check(current.name == document.name && (current.size == null || current.size == copied.bytes))
                    val hash = checkNotNull(copied.sha256)
                    val duplicate = hash in newHashes || saved[hash].orEmpty().any { entry ->
                        runCatching { history.local.verify(entry, cancelled) }.getOrDefault(false)
                    }
                    if (duplicate) {
                        summary = summary.copy(duplicates = summary.duplicates + 1)
                        continue
                    }
                    val result = if (folder != null) {
                        val sourceId = sha256().digest(uri.toString().toByteArray()).hex()
                        ImportHistoryGate.process.importing {
                            folder.publish(file,copied,sourceId,history,cancelled) { destination ->
                                checkNotNull(resolver.openOutputStream(destination,"w")).use { output ->
                                    staged.inputStream().use { input -> transfer(input,output,copied.bytes,cancelled) }
                                }
                            }
                        }
                    } else {
                        check(scratch.usableSpace >= copied.bytes)
                        publish(uri, file, staged, copied, cancelled)
                    }
                    if (result.completed.isNotEmpty()) {
                        newHashes.add(hash)
                        summary = summary.copy(imported = summary.imported + 1,
                            needsReview = summary.needsReview || result.followUpFailed)
                    } else if (!result.cancelled) {
                        summary = summary.copy(failed = summary.failed + if (result.filenameConflict) 0 else 1,
                            filenameConflicts = summary.filenameConflicts + if (result.filenameConflict) 1 else 0,
                            needsReview = summary.needsReview || result.cleanupFailed || result.reconciliationRequired)
                    }
                } finally {
                    staged.delete()
                }
            } catch (_: Exception) {
                if (cancelled()) break
                summary = summary.copy(failed = summary.failed + 1)
            }
        }
        return summary.copy(cancelled = cancelled())
    }

    private fun metadata(uri: Uri): Document {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT)
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return checkNotNull(resolver.query(uri, columns, null, null, null)).use { cursor ->
            check(cursor.moveToFirst())
            val name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else null
            Document(checkNotNull(name), size?.takeIf { it >= 0 })
        }
    }

    private fun publish(source: Uri, file: AlbumMediaFile, staged: File, copied: MediaImportTransaction.Copied,
        cancelled: () -> Boolean): MediaImportTransaction.Result {
        val sourceId = sha256().digest(source.toString().toByteArray()).hex()
        val item = MediaImportTransaction.Item(sourceId, file.name, copied.bytes)
        val port = SonyMediaStore(app).port(
            copy = { _, destination ->
                checkNotNull(resolver.openOutputStream(destination, "w")).use { output ->
                    staged.inputStream().use { input -> transfer(input, output, copied.bytes, cancelled) }
                }
                copied
            },
            cancelled = cancelled,
            historyDraft = { _, destination ->
                ImportHistoryDraft(UUID.randomUUID().toString(), destination.toString(), item.name, item.bytes,
                    System.currentTimeMillis(), ImportProvenance.DOCUMENT_BYTES, sourceScopeKind = "ANDROID_DOCUMENT",
                    sourceItemReference = sourceId)
            },
            documentFile = file
        )
        return ImportHistoryGate.process.importing { MediaImportTransaction().run(listOf(item), port, cancelled) }
    }

    companion object {
        const val MAX_SELECTION = 500

        private fun sha256() = MessageDigest.getInstance("SHA-256")
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

        private fun stage(input: InputStream, output: OutputStream, expected: Long?, cancelled: () -> Boolean): MediaImportTransaction.Copied {
            val digest = sha256()
            val bytes = transfer(input, output, expected, cancelled, digest)
            return MediaImportTransaction.Copied(bytes, digest.digest().hex())
        }

        private fun transfer(input: InputStream, output: OutputStream, expected: Long?, cancelled: () -> Boolean,
            digest: MessageDigest? = null): Long {
            val buffer = ByteArray(64 * 1024)
            var bytes = 0L
            while (true) {
                if (cancelled()) throw InterruptedIOException()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (cancelled()) throw InterruptedIOException()
                if (expected != null && read.toLong() > expected - bytes) throw IOException("Document size changed")
                output.write(buffer, 0, read)
                digest?.update(buffer, 0, read)
                bytes += read
            }
            if (bytes == 0L || (expected != null && bytes != expected)) throw IOException("Document size could not be verified")
            return bytes
        }
    }
}
