package app.cameraderie.sony

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import app.cameraderie.gallery.media.AlbumMediaFile
import app.cameraderie.gallery.media.MediaImportTransaction
import app.cameraderie.gallery.media.history.ImportCaptureMetadata
import app.cameraderie.gallery.media.history.ImportHistoryDraft
import app.cameraderie.gallery.media.history.ImportHistoryReceipt
import app.cameraderie.gallery.media.history.ImportHistoryRepository

/** Imports into one local album. Pending records are never reported as saved media. */
internal class SonyMediaStore(private val context:Context,injectedHistory:ImportHistoryRepository?=null) {
    private val resolver get()=context.contentResolver
    private val history by lazy { injectedHistory ?: ImportHistoryRepository.get(context) }
    fun cleanupInterrupted(cancelled:()->Boolean={Thread.currentThread().isInterrupted}) { history.reconcile(cancelled) }
    @Suppress("DEPRECATION")
    private fun preserveCaptureTimestamp(uri:Uri,time:Long) {
        // Resolve only this newly-created, app-owned pending item, never a camera
        // source or arbitrary path. Direct access to owned media is public API.
        val columns=arrayOf(MediaStore.MediaColumns.DATA,MediaStore.MediaColumns.OWNER_PACKAGE_NAME,MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.RELATIVE_PATH)
        val path=resolver.query(uri,columns,null,null,null)!!.use {
            check(it.moveToFirst() && it.getString(1)==context.packageName && it.getInt(2)==1 && it.getString(3)==SonyImportPlan.ALBUM)
            checkNotNull(it.getString(0))
        }
        resolver.openFileDescriptor(uri,"rw")!!.use { descriptor ->
            val opened=android.system.Os.fstat(descriptor.fileDescriptor)
            val resolved=android.system.Os.stat(path)
            check(opened.st_dev==resolved.st_dev && opened.st_ino==resolved.st_ino) { "Import destination changed before capture-date preservation" }
            check(java.io.File(path).setLastModified(time)) { "Could not preserve the imported capture date" }
            val stamped=android.system.Os.fstat(descriptor.fileDescriptor)
            check(stamped.st_mtime==time/1000) { "Imported capture-date timestamp was not retained" }
        }
    }
    fun port(copy:(MediaImportTransaction.Item,Uri)->MediaImportTransaction.Copied,cancelled:()->Boolean,published:(MediaImportTransaction.Item,Uri)->Unit={_,_->},capture:(MediaImportTransaction.Item)->CameraCaptureTime?={null},
        historyDraft:((MediaImportTransaction.Item,Uri)->ImportHistoryDraft)?=null,
        documentFile:AlbumMediaFile?=null)=object:MediaImportTransaction.Port {
        private fun file(item:MediaImportTransaction.Item) = documentFile?.also { check(it.name==item.name) } ?: SonyImportPlan.file(item.name)
        private val album =
            if (documentFile != null && !documentFile.image && !documentFile.video) AlbumMediaFile.DOWNLOAD_DIRECTORY
            else SonyImportPlan.ALBUM

        private val pendingItems=mutableMapOf<String,MediaImportTransaction.Item>()
        private val historyIds=mutableMapOf<String,String>()
        private val receipts=mutableMapOf<String,ImportHistoryReceipt>()
        override fun createPending(item:MediaImportTransaction.Item):String {
            val plan=file(item)
            val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
            val collection = when {
                plan.video -> MediaStore.Video.Media.getContentUri(volume)
                plan.image -> MediaStore.Images.Media.getContentUri(volume)
                else -> MediaStore.Downloads.getContentUri(volume)
            }
            val filename = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? COLLATE NOCASE AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), filename,
                arrayOf(plan.name, album), null)!!.use {
                if (it.moveToFirst()) throw MediaImportTransaction.FilenameConflict()
            }
            val values=ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,plan.name);put(MediaStore.MediaColumns.MIME_TYPE,plan.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH,album);put(MediaStore.MediaColumns.IS_PENDING,1)
            }
            return (resolver.insert(collection,values) ?: error("Could not create pending import")).toString().also { pendingItems[it]=item }
        }
        @Suppress("DEPRECATION")
        private fun requireOriginalName(uri: Uri, name: String) {
            val columns = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.RELATIVE_PATH)
            resolver.query(uri, columns, null, null, null)!!.use {
                check(it.moveToFirst() && it.getString(2) == context.packageName && it.getInt(3) == 1 &&
                    it.getString(4) == album)
                val staged = java.io.File(checkNotNull(it.getString(1)))
                val stagedName = staged.name.replaceFirst(Regex("^\\.pending-[0-9]+-"), "")
                if (it.getString(0) != name || stagedName != name ||
                    (staged.name != name && java.io.File(staged.parentFile, name).exists())) {
                    throw MediaImportTransaction.FilenameConflict()
                }
            }
        }
        override fun copy(item:MediaImportTransaction.Item,destination:String):MediaImportTransaction.Copied {
            requireOriginalName(Uri.parse(destination), item.name)
            // createPending has already returned its URI to the transaction, so
            // failed journal insertion still receives guarded pending cleanup.
            historyDraft?.invoke(item,Uri.parse(destination))?.let { draft ->
                check(draft.expectedBytes==item.bytes && app.cameraderie.gallery.media.history.LocalMediaIdentity.key(draft.uri)==app.cameraderie.gallery.media.history.LocalMediaIdentity.key(destination))
                historyIds[destination]=draft.id;history.begin(draft)
            }
            return copy(item,Uri.parse(destination))
        }
        override fun verify(item:MediaImportTransaction.Item,destination:String,copied:MediaImportTransaction.Copied) {
            val uri=Uri.parse(destination);val plan=file(item)
            if(documentFile!=null) checkNotNull(copied.sha256)
            val checked=resolver.openInputStream(uri)!!.use {
                SonyOriginalVerification.verify(it,item.bytes,documentFile==null && plan.raw,cancelled,copied.sha256)
            }
            var width:Int?=null;var height:Int?=null;var actualMime=plan.mime
            if(documentFile==null && !plan.raw && !plan.video) {
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it,null,bounds) }
                check(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=128_000_000)
                width=bounds.outWidth;height=bounds.outHeight;actualMime=bounds.outMimeType ?: plan.mime
                var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>256) sample*=2
                val bitmap=resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize=sample }) }
                check(bitmap!=null) { "Imported image could not be decoded" };bitmap.recycle()
            } else if(documentFile==null && plan.video) {
                MediaMetadataRetriever().use { reader ->
                    reader.setDataSource(context,uri);check(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()!=null)
                    val w=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.takeIf { it>0 }
                    val h=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.takeIf { it>0 }
                    if(w!=null && h!=null) { width=w;height=h }
                    actualMime=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.takeIf { it.contains('/') } ?: plan.mime
                }
            }
            // Index the actual capture time when available, rather than import time.
            val metadata = ImportCaptureMetadata.read(capture(item),
                openImage = if (plan.image) ({ resolver.openInputStream(uri) }) else null)
            val captureTime = metadata.time
            val zone = java.time.ZoneId.systemDefault()
            captureTime?.epochMillis(zone)?.takeIf { it>=0 }?.let { time ->
                // MediaProvider rescans at publication. Without an EXIF UTC offset it
                // infers the capture timezone from mtime only when dates are close.
                // Preserve the destination timestamp; never rewrite original bytes.
                // Providers may expose different filesystem views of this URI. The
                // receipt still records the capture time when either indexing step fails.
                runCatching { preserveCaptureTimestamp(uri,time) }
                runCatching { resolver.update(uri,ContentValues().apply { put(MediaStore.Images.ImageColumns.DATE_TAKEN,time) },null,null) }
            }
            if(destination in historyIds) {
                val actualName=resolver.query(uri,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),null,null,null)!!.use { check(it.moveToFirst());it.getString(0) }
                receipts[destination] = metadata.describe(
                    ImportHistoryReceipt(checked.bytes, checked.sha256, actualMime, copied.sha256 != null,
                        width, height, displayName = actualName), zone)
            }
        }
        override fun afterVerification(item:MediaImportTransaction.Item,destination:String,copied:MediaImportTransaction.Copied) {
            historyIds[destination]?.let { history.verified(it,checkNotNull(receipts[destination])) }
        }
        @Suppress("DEPRECATION")
        override fun publish(destination:String) {
            check(!cancelled())
            val uri=Uri.parse(destination)
            val item=checkNotNull(pendingItems[destination])
            requireOriginalName(uri, item.name)
            val scope="${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?"
            check(resolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },scope,arrayOf(album,context.packageName))==1)
            val columns = arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA)
            resolver.query(uri, columns, null, null, null)!!.use {
                check(it.moveToFirst() && it.getInt(0) == 0 && it.getLong(1) == item.bytes &&
                    it.getString(2) == album && it.getString(3) == context.packageName)
                check(it.getString(4) == item.name && java.io.File(checkNotNull(it.getString(5))).name == item.name)
            }
        }
        override fun afterPublication(item:MediaImportTransaction.Item,destination:String) {
            val uri=Uri.parse(destination)
            published(checkNotNull(pendingItems.remove(destination)),uri)
            receipts.remove(destination)
            historyIds.remove(destination)?.let { history.completed(it) }
        }
        override fun cleanup(destination:String):MediaImportTransaction.Cleanup {
            pendingItems.remove(destination)
            receipts.remove(destination)
            val historyId=historyIds.remove(destination)
            val uri=Uri.parse(destination)
            // Publication may have committed even if its readback failed. Keep
            // the guard in the atomic delete, never query then delete blindly.
            val scope="${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?"
            // Some providers reject a guarded delete with no matching row. Never
            // retry without the guard: resolve retention/absence by readback.
            val deleted=try { resolver.delete(uri,scope,arrayOf(album,context.packageName)) } catch(_:SecurityException) { 0 }
            check(deleted in 0..1)
            if(deleted==1) { historyId?.let { runCatching { history.interrupted(it) } };return MediaImportTransaction.Cleanup.REMOVED }
            resolver.query(uri,arrayOf(MediaStore.MediaColumns._ID),null,null,null)!!.use {
                if(it.moveToFirst())return MediaImportTransaction.Cleanup.RETAINED
                historyId?.let { id -> runCatching { history.interrupted(id) } }
                return MediaImportTransaction.Cleanup.REMOVED
            }
        }
        override fun remove(destination:String) { check(cleanup(destination)==MediaImportTransaction.Cleanup.REMOVED) }
    }
}
