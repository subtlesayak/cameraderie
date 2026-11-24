package app.cameraderie.gallery.media

import android.content.Context
import app.cameraderie.gallery.media.history.ImportHistoryDraft
import app.cameraderie.gallery.media.history.ImportHistoryGate
import app.cameraderie.sony.SonyMediaStore
import java.io.File
import java.io.InterruptedIOException
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Publish a finished local derivative through the same verified album transaction as originals. */
internal object GalleryDerivedMedia {
    fun publish(context:Context,file:File,item:MediaImportTransaction.Item,cancelled:()->Boolean,
        draft:(String)->ImportHistoryDraft,published:(String)->Unit):MediaImportTransaction.Result {
        checkActive(cancelled)
        val port=SonyMediaStore(context).port(
            copy={ _,uri ->
                val digest=MessageDigest.getInstance("SHA-256")
                val size=checkNotNull(context.contentResolver.openOutputStream(uri,"w")).use { output ->
                    val hashing=DigestOutputStream(output,digest)
                    file.inputStream().use { input ->
                        val buffer=ByteArray(64*1024)
                        var count=0L
                        while(true) {
                            checkActive(cancelled)
                            val read=input.read(buffer)
                            if(read<0) break
                            hashing.write(buffer,0,read)
                            count+=read
                        }
                        count
                    }
                }
                MediaImportTransaction.Copied(size,digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
            },cancelled=cancelled,published={ _,uri -> published(uri.toString()) },
            historyDraft={ _,uri -> draft(uri.toString()) })
        return ImportHistoryGate.process.importing { MediaImportTransaction().run(listOf(item),port,cancelled) }
    }

    fun checkActive(cancelled:()->Boolean) {
        if(cancelled()) throw InterruptedIOException("Video export canceled")
    }
}
