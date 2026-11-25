package app.cameraderie.gallery.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import app.cameraderie.gallery.media.history.*
import app.cameraderie.sony.CameraCaptureTime
import app.cameraderie.sony.SonyOriginalVerification
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID

/** Writes a named temporary document, verifies its bytes, then publishes by rename. */
internal class FolderImportOutput(private val context: Context, private val tree: Uri) {
    private val resolver get() = context.contentResolver
    class NoSpace : IOException("Free space in the selected folder and try again.")
    data class FileInfo(val uri: Uri, val name: String, val mime: String, val size: Long?, val flags: Int, val modified: Long?)
    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)

    init { LocalMediaIdentity.requireFolderTree(tree.toString()) }

    fun checkWritable() {
        check(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission })
        val info = checkNotNull(info(resolver,root))
        check(info.mime == Document.MIME_TYPE_DIR && info.flags and Document.FLAG_DIR_SUPPORTS_CREATE != 0)
    }

    fun checkAvailableSpace(bytes: Long) {
        if (tree.authority != "com.android.externalstorage.documents") return
        val volumeId = rootId.substringBefore(':')
        val directory = context.getSystemService(StorageManager::class.java)?.storageVolumes
            ?.firstOrNull { volume ->
                if (volumeId == "primary") volume.isPrimary else volumeId.equals(volume.uuid, ignoreCase = true)
            }?.directory ?: return
        val available = runCatching { StatFs(directory.path).availableBytes }.getOrNull() ?: return
        if (available < bytes) throw NoSpace()
    }

    fun publish(file: AlbumMediaFile, copied: MediaImportTransaction.Copied, sourceId: String,
        history: ImportHistoryRepository, cancelled: () -> Boolean, metadata: ImportCaptureMetadata,
        write: (Uri) -> Unit): MediaImportTransaction.Result {
        val item=MediaImportTransaction.Item(sourceId,file.name,copied.bytes)
        return publish(file,item,history,cancelled,{ uri -> write(uri); copied },
            draftFor={ id, uri, name, folder -> ImportHistoryDraft(id,uri,name,item.bytes,System.currentTimeMillis(),
                ImportProvenance.DOCUMENT_BYTES, cameraModel=metadata.cameraModel,sourceScopeKind="ANDROID_DOCUMENT",
                sourceItemReference=sourceId,folderTree=folder) }, documentMetadata=metadata)
    }

    fun publish(file: AlbumMediaFile, item: MediaImportTransaction.Item, history: ImportHistoryRepository,
        cancelled: () -> Boolean, copy: (Uri) -> MediaImportTransaction.Copied,
        draftFor: (String,String,String,String) -> ImportHistoryDraft, validate: (Uri) -> Unit = {},
        cameraTime: CameraCaptureTime? = null,
        documentMetadata: ImportCaptureMetadata? = null,
        published: (Uri) -> Unit = {}): MediaImportTransaction.Result {
        checkWritable()
        val id = UUID.randomUUID().toString()
        val pendingPrefix = pendingPrefix(id)
        val finalName = file.name
        val draft = draftFor(id,LocalMediaIdentity.folderReservation(id),finalName,tree.toString())
        check(draft.expectedBytes==item.bytes && draft.folderTree==tree.toString())
        var phase=MediaImportTransaction.Phase.COPY
        var reserved=false
        return try {
            if(cancelled()) throw InterruptedIOException()
            history.begin(draft)
            reserved=true
            // Android can redact metadata on reads of media documents. Verify a generic temporary
            // document before renaming it into the requested media type.
            val pending=checkNotNull(DocumentsContract.createDocument(resolver,root,"application/octet-stream",pendingPrefix+"part"))
            check(LocalMediaIdentity.inFolder(pending.toString(),tree.toString()))
            val pendingInfo=checkNotNull(info(resolver,pending))
            check(pendingInfo.name.startsWith(pendingPrefix) && pendingInfo.flags and Document.FLAG_SUPPORTS_RENAME!=0)
            val copied=copy(pending)
            phase=MediaImportTransaction.Phase.VERIFY
            val hash=checkNotNull(copied.sha256)
            check(copied.bytes==item.bytes)
            val stagedIdentity=verifyPending(pending,item.bytes,hash,cancelled,
                file.mime in setOf("image/x-sony-arw", "image/x-adobe-dng") && draft.provenance==ImportProvenance.OFFERED_CAMERA_BYTES)
            val verifiedFile=stagedIdentity?.takeIf { file.mime=="image/jpeg" && hasLocationMetadata(pending)==true }
            validate(pending)
            if(cancelled()) throw InterruptedIOException()
            val duplicate=if(draft.provenance!=ImportProvenance.DOCUMENT_BYTES) {
                history.list(cancelled).firstOrNull { prior ->
                    prior.draft.folderTree==draft.folderTree && prior.draft.requestedQuality==draft.requestedQuality &&
                        prior.draft.provenance==draft.provenance &&
                        prior.draft.companionParentReference==draft.companionParentReference && prior.receipt?.sha256==hash &&
                        runCatching { history.local.verify(prior,cancelled) }.getOrDefault(false)
                }
            } else null
            if(cancelled()) throw InterruptedIOException()
            if(duplicate!=null) {
                discardPending(id)
                history.interrupted(id)
                val followUp=runCatching { published(Uri.parse(duplicate.draft.uri)) }.isFailure
                return MediaImportTransaction.Result(listOf(item.id),followUpFailed=followUp)
            }
            val metadata = documentMetadata ?: ImportCaptureMetadata.read(cameraTime,
                openImage = if (file.mime.startsWith("image/")) ({ resolver.openInputStream(pending) }) else null)
            val receipt = metadata.describe(ImportHistoryReceipt(item.bytes, hash, file.mime, true,
                displayName = finalName, verifiedFolderFile = verifiedFile))
            history.verified(id, receipt)
            if(cancelled()) throw InterruptedIOException()
            if (children().any { it.name.equals(finalName, ignoreCase = true) }) {
                throw MediaImportTransaction.FilenameConflict()
            }
            phase=MediaImportTransaction.Phase.PUBLISH
            val finalUri=checkNotNull(DocumentsContract.renameDocument(resolver,pending,finalName))
            val saved=checkNotNull(info(resolver,finalUri))
            check(saved.name == finalName && matchesPublished(saved, receipt, cancelled)) {
                "Published file could not be verified"
            }
            history.store.completeFolder(id,finalUri.toString(),System.currentTimeMillis(),ImportCompletionKind.OBSERVED_PUBLICATION)
            val followUp=runCatching { published(finalUri) }.isFailure
            MediaImportTransaction.Result(listOf(item.id),followUpFailed=followUp)
        } catch (error: Exception) {
            val canClean=reserved && phase!=MediaImportTransaction.Phase.PUBLISH
            val clean=canClean && runCatching {
                discardPending(id)
                history.interrupted(id)
            }.isSuccess
            MediaImportTransaction.Result(emptyList(),cancelled(),phase.takeUnless { cancelled() },
                cleanupFailed=canClean && !clean,reconciliationRequired=reserved && !clean,
                filenameConflict=error is MediaImportTransaction.FilenameConflict)
        }
    }

    fun recover(entry:ImportHistoryEntry,cancelled:()->Boolean):ImportHistoryRecovery.FolderRecovery {
        checkWritable()
        val receipt=entry.receipt
        val documents=children()
        val pending=documents.any { it.name.startsWith(pendingPrefix(entry.draft.id)) }
        val published=documents.singleOrNull { it.name==entry.draft.displayName }
        if(cancelled()) throw InterruptedIOException()
        if(!pending && published!=null && entry.stage==ImportHistoryStage.VERIFIED && receipt?.sha256!=null) {
            check(matchesPublished(published,receipt,cancelled)) { "Published file could not be verified" }
            return ImportHistoryRecovery.FolderRecovery(publishedUri=published.uri.toString())
        }
        discardPending(entry.draft.id)
        return ImportHistoryRecovery.FolderRecovery(removed=true)
    }

    private fun discardPending(id:String) {
        val matches=children().filter { it.name.startsWith(pendingPrefix(id)) }
        check(matches.size<=1)
        for(candidate in matches) {
            check(info(resolver,candidate.uri)?.name==candidate.name)
            check(candidate.mime!=Document.MIME_TYPE_DIR)
            check(DocumentsContract.deleteDocument(resolver,candidate.uri))
        }
    }

    private fun children():List<FileInfo> {
        val path=DocumentsContract.buildChildDocumentsUriUsingTree(tree,rootId)
        return (resolver.query(path,columns,null,null,null) ?: throw IOException("Destination folder unavailable")).use { cursor ->
            buildList {
                while(cursor.moveToNext()) {
                    val uri=DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0))
                    add(read(cursor,uri))
                }
            }
        }
    }

    private fun verifyPending(uri:Uri,bytes:Long,hash:String,cancelled:()->Boolean,raw:Boolean):String? {
        check(LocalMediaIdentity.inFolder(uri.toString(),tree.toString()))
        return ParcelFileDescriptor.AutoCloseInputStream(checkNotNull(resolver.openFileDescriptor(uri,"r"))).use { input ->
            val before = fileIdentity(input,bytes)
            SonyOriginalVerification.verify(input,bytes,raw,cancelled,hash)
            val after = fileIdentity(input,bytes)
            check(before == null || before == after) { "File changed during verification" }
            after
        }
    }

    fun matchesPublished(file:FileInfo,receipt:ImportHistoryReceipt,cancelled:()->Boolean):Boolean {
        val hash = receipt.sha256 ?: return false
        val uri = file.uri
        if(file.size != receipt.bytes || !LocalMediaIdentity.inFolder(uri.toString(),tree.toString())) return false
        val matches = runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(checkNotNull(resolver.openFileDescriptor(uri,"r"))).use { input ->
                val before = fileIdentity(input,receipt.bytes)
                val readback = SonyOriginalVerification.verify(input,receipt.bytes,false,cancelled)
                val after = fileIdentity(input,receipt.bytes)
                check(before == null || before == after) { "File changed during verification" }
                // A provider may redact GPS metadata after a JPEG rename. Only
                // the same filesystem object can carry forward pre-rename proof.
                readback.sha256 == hash || (before != null && before == receipt.verifiedFolderFile && hasLocationMetadata(uri)==false)
            }
        }.getOrDefault(false)
        if(cancelled()) return false
        if(matches) return true
        // Older receipts lack the filesystem proof. Try the provider's exact
        // view when Android already grants access to the original bytes.
        return runCatching {
            val media = MediaStore.getMediaUri(context,uri) ?: return false
            val original = MediaStore.setRequireOriginal(media)
            checkNotNull(resolver.openInputStream(original)).use {
                SonyOriginalVerification.verify(it,receipt.bytes,false,cancelled,hash)
            }
        }.isSuccess && !cancelled()
    }

    private fun hasLocationMetadata(uri:Uri):Boolean? = runCatching {
        checkNotNull(resolver.openInputStream(uri)).use { androidx.exifinterface.media.ExifInterface(it).latLong != null }
    }.getOrNull()

    private fun fileIdentity(input:ParcelFileDescriptor.AutoCloseInputStream,bytes:Long):String? = runCatching {
        val stat = Os.fstat(input.fd)
        if(!OsConstants.S_ISREG(stat.st_mode) || stat.st_ino <= 0 || stat.st_size != bytes) null
        else "${stat.st_dev}:${stat.st_ino}:${stat.st_mtim.tv_sec}:${stat.st_mtim.tv_nsec}"
    }.getOrNull()

    companion object {
        private val columns=arrayOf(Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,Document.COLUMN_FLAGS,Document.COLUMN_LAST_MODIFIED)
        private fun pendingPrefix(id:String)="Cameraderie-$id-pending."
        private fun read(cursor:android.database.Cursor,uri:Uri)=FileInfo(uri,cursor.getString(1),cursor.getString(2),
            cursor.getLong(3).takeUnless { cursor.isNull(3) },cursor.getInt(4),cursor.getLong(5).takeIf { it>0 })
        fun info(resolver:ContentResolver,uri:Uri,cancellation:CancellationSignal?=null):FileInfo? {
            return (resolver.query(uri,columns,null,null,null,cancellation) ?: throw IOException("Document provider unavailable")).use {
                if(it.moveToFirst()) read(it,uri) else null
            }
        }
    }
}
