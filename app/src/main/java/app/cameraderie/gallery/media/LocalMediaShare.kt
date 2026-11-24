package app.cameraderie.gallery.media

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore

/** Builds a chooser only; a person selects the receiving app and recipient. */
class LocalMediaShare(private val context:Context) {
    fun chooser(uris:List<Uri>,cancellation:android.os.CancellationSignal?=null):Intent {
        require(uris.isNotEmpty() && uris.size<=500)
        cancellation?.throwIfCanceled()
        val items=uris.map { uri ->
            cancellation?.throwIfCanceled()
            context.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.MIME_TYPE,MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.OWNER_PACKAGE_NAME),null,null,null,cancellation)!!.use { cursor ->
                check(cursor.moveToFirst()) { "Imported media is no longer available" }
                VerifiedMediaShare.Item(uri.toString(),cursor.getString(0).orEmpty(),cursor.getLong(1),cursor.getInt(2)!=0,cursor.getString(3)==context.packageName)
            }
        }
        cancellation?.throwIfCanceled()
        return chooserVerified(items)
    }

    internal fun chooserVerified(items:List<VerifiedMediaShare.Item>):Intent {
        val plan=VerifiedMediaShare.plan(items)
        val local=plan.uris.map { Uri.parse(it) }
        val send=Intent(if(plan.multiple) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type=plan.mime
            if(plan.multiple) putParcelableArrayListExtra(Intent.EXTRA_STREAM,ArrayList(local))
            else putExtra(Intent.EXTRA_STREAM,local.single())
            clipData=ClipData.newUri(context.contentResolver,"Cameraderie imports",local.first()).apply { local.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send,"Share imported media").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
