package app.cameraderie.gallery.media.history

import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import app.cameraderie.gallery.media.LocalMediaShare
import app.cameraderie.gallery.media.FolderImportOutput
import app.cameraderie.gallery.media.VerifiedMediaShare
import app.cameraderie.sony.SonyImportPlan
import app.cameraderie.sony.SonyOriginalVerification
import java.io.IOException

/** The history screen can inspect/open local imports without camera ownership,
 * storage permissions for unrelated media, or any radio/network connection. */
class LocalImportedMedia(context:Context,private val album:String=SonyImportPlan.ALBUM) {
    private val app=context.applicationContext
    private val resolver get()=app.contentResolver
    data class Snapshot(val uri:String,val name:String,val mime:String,val bytes:Long,
        val owned:Boolean,val inAlbum:Boolean,val pending:Boolean,val width:Int?,val height:Int?,
        val dateTaken:Long?,val dateAdded:Long?,val dateModified:Long?) {
        val published get()=owned && inAlbum && !pending && bytes>0
    }
    private val columns=arrayOf(MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.OWNER_PACKAGE_NAME,MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.WIDTH,MediaStore.MediaColumns.HEIGHT,
        MediaStore.Images.ImageColumns.DATE_TAKEN,MediaStore.MediaColumns.DATE_ADDED,MediaStore.MediaColumns.DATE_MODIFIED)
    private fun long(c:Cursor,column:String):Long? {
        val index=c.getColumnIndexOrThrow(column)
        return if(c.isNull(index))null else c.getLong(index).takeIf { it>0 }
    }
    private fun read(c:Cursor,uri:String):Snapshot {
        fun text(column:String)=c.getString(c.getColumnIndexOrThrow(column)).orEmpty()
        val w=long(c,MediaStore.MediaColumns.WIDTH)?.takeIf { it<=Int.MAX_VALUE }?.toInt()
        val h=long(c,MediaStore.MediaColumns.HEIGHT)?.takeIf { it<=Int.MAX_VALUE }?.toInt()
        return Snapshot(uri,text(MediaStore.MediaColumns.DISPLAY_NAME),text(MediaStore.MediaColumns.MIME_TYPE),c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)),
            text(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)==app.packageName,text(MediaStore.MediaColumns.RELATIVE_PATH)==album,
            c.getInt(c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING))!=0,if(w!=null && h!=null)w else null,if(w!=null && h!=null)h else null,
            long(c,MediaStore.Images.ImageColumns.DATE_TAKEN),long(c,MediaStore.MediaColumns.DATE_ADDED)?.let { Math.multiplyExact(it,1000L) },long(c,MediaStore.MediaColumns.DATE_MODIFIED))
    }
    fun snapshot(value:String,cancellation:android.os.CancellationSignal?=null):Snapshot? {
        LocalMediaIdentity.key(value)
        cancellation?.throwIfCanceled()
        if (LocalMediaIdentity.isFolderReservation(value)) return null
        if (LocalMediaIdentity.isFolderDocument(value)) {
            val item = FolderImportOutput.info(resolver,Uri.parse(value),cancellation) ?: return null
            return Snapshot(value,item.name,item.mime,item.size ?: 0,true,true,false,null,null,null,null,item.modified)
        }
        val args=Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE) }
        return (resolver.query(Uri.parse(value),columns,args,cancellation) ?: throw IOException("Local media provider unavailable")).use {
            if(it.moveToFirst())read(it,value) else null
        }
    }
    fun state(value:String):ImportHistoryRecovery.LocalState {
        val item=snapshot(value) ?: return ImportHistoryRecovery.LocalState.ABSENT
        return when {
            !item.owned || !item.inAlbum -> ImportHistoryRecovery.LocalState.UNAVAILABLE
            item.pending -> ImportHistoryRecovery.LocalState.OWNED_PENDING
            item.published -> ImportHistoryRecovery.LocalState.OWNED_PUBLISHED
            else -> ImportHistoryRecovery.LocalState.UNAVAILABLE
        }
    }
    fun verify(entry:ImportHistoryEntry,cancelled:()->Boolean):Boolean {
        val receipt=entry.receipt ?: return false;val hash=receipt.sha256 ?: return false
        if(entry.draft.folderTree!=null) {
            val file=FolderImportOutput.info(resolver,Uri.parse(entry.draft.uri)) ?: return false
            if(receipt.bytes!=entry.draft.expectedBytes) return false
            return FolderImportOutput(app,Uri.parse(entry.draft.folderTree)).matchesPublished(file,receipt,cancelled)
        }
        val current=snapshot(entry.draft.uri) ?: return false
        if(!current.published || current.bytes!=receipt.bytes || receipt.bytes!=entry.draft.expectedBytes)return false
        val input=resolver.openInputStream(Uri.parse(entry.draft.uri)) ?: return false
        input.use { SonyOriginalVerification.verify(it,receipt.bytes,false,cancelled,hash) }
        return true
    }
    internal fun recoverFolder(entry:ImportHistoryEntry,cancelled:()->Boolean):ImportHistoryRecovery.FolderRecovery =
        FolderImportOutput(app,Uri.parse(checkNotNull(entry.draft.folderTree))).recover(entry,cancelled)

    fun removePending(value:String):Boolean {
        check(!LocalMediaIdentity.isFolderDocument(value) && !LocalMediaIdentity.isFolderReservation(value))
        LocalMediaIdentity.key(value)
        val where="${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?"
        // A no-match guard may throw on newer providers. Successful readback
        // still must establish absence; a published/unknown row stays retained.
        val deleted=try { resolver.delete(Uri.parse(value),where,arrayOf(album,app.packageName)) } catch(_:SecurityException) { 0 }
        check(deleted in 0..1)
        return deleted==1 || snapshot(value)==null
    }
    private fun query(pending:Boolean,block:(Snapshot)->Unit,cancelled:()->Boolean) {
        val collection=MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val args=Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION,"${MediaStore.MediaColumns.IS_PENDING}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,arrayOf(if(pending)"1" else "0",album,app.packageName))
        }
        (resolver.query(collection,columns,args,null) ?: throw IOException("Local media provider unavailable")).use { cursor ->
            while(cursor.moveToNext()) {
                if(cancelled())throw java.io.InterruptedIOException()
                val id=cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));check(id>0)
                val item=read(cursor,ContentUris.withAppendedId(collection,id).toString())
                if(item.owned && item.inAlbum && item.pending==pending)block(item)
            }
        }
    }
    fun pending(block:(Snapshot)->Unit,cancelled:()->Boolean={false})=query(true,block,cancelled)
    fun published(block:(Snapshot)->Unit,cancelled:()->Boolean={false})=query(false,{ if(it.published)block(it) },cancelled)
    fun available(value:String,cancellation:android.os.CancellationSignal?=null):Snapshot? {
        val item=snapshot(value,cancellation)?.takeIf { it.published } ?: return null
        cancellation?.throwIfCanceled()
        val readable=resolver.openAssetFileDescriptor(Uri.parse(value),"r",cancellation)?.use { asset ->
            cancellation?.throwIfCanceled();asset.createInputStream().use { it.read()>=0 }
        } ?: false
        return item.takeIf { readable }
    }
    fun open(value:String,cancellation:android.os.CancellationSignal?=null):Intent {
        cancellation?.throwIfCanceled()
        val item=checkNotNull(available(value,cancellation)) { "Imported file is unavailable" };val uri=Uri.parse(value)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri,item.mime.ifBlank { "application/octet-stream" }).apply {
            clipData=ClipData.newUri(resolver,"Cameraderie import",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    fun share(values:List<String>,cancellation:android.os.CancellationSignal?=null):Intent {
        require(values.isNotEmpty() && values.size<=500)
        val items=values.map {
            cancellation?.throwIfCanceled()
            val item=checkNotNull(available(it,cancellation)) { "Imported file is unavailable" }
            VerifiedMediaShare.Item(item.uri,item.mime,item.bytes,item.pending,item.owned)
        }
        return LocalMediaShare(app).chooserVerified(items)
    }
}
