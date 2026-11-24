package app.cameraderie.gallery.media

import android.content.Context
import android.net.Uri
import app.cameraderie.gallery.media.history.ImportHistoryDraft
import app.cameraderie.gallery.media.history.ImportHistoryGate
import app.cameraderie.gallery.media.history.ImportHistoryRepository
import app.cameraderie.gallery.media.history.ImportProvenance
import app.cameraderie.gallery.protocol.GalleryMediaPage
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

internal enum class GalleryCompanion(val extension: String, val mime: String, val maxBytes: Long) {
    DNG("DNG", "image/x-adobe-dng", 512L * 1024 * 1024),
    WAV("WAV", "audio/wav", 8L * 1024 * 1024 * 1024);

    class TooLarge(extension: String) : IOException("The camera’s $extension companion exceeds the supported size.")

    fun verify(input:InputStream, length:Long) {
        when(this) {
            DNG -> verifyDng(input)
            WAV -> GalleryWave.verify(input,length)
        }
    }

    private fun verifyDng(input:InputStream) {
        val data=DataInputStream(input)
        val header=ByteArray(8).also(data::readFully)
        val order=when(String(header,0,2,Charsets.US_ASCII)) {
            "II" -> ByteOrder.LITTLE_ENDIAN
            "MM" -> ByteOrder.BIG_ENDIAN
            else -> error("DNG byte order is invalid")
        }
        val tiff=ByteBuffer.wrap(header).order(order)
        check(tiff.getShort(2).toInt()==42)
        var distance=(tiff.getInt(4).toLong() and 0xffffffffL)-8
        check(distance>=0)
        while(distance>0) {
            val skipped=data.skip(distance)
            distance-=if(skipped>0)skipped else { data.readByte();1L }
        }
        val field=ByteArray(12)
        data.readFully(field,0,2)
        val values=ByteBuffer.wrap(field).order(order)
        val count=values.getShort(0).toInt() and 0xffff
        repeat(count) {
            data.readFully(field)
            if((values.getShort(0).toInt() and 0xffff)==50706) {
                check(values.getShort(2).toInt()==1 && values.getInt(4)==4 && field[8].toInt()!=0)
                return
            }
        }
        error("DNG version is absent from the primary TIFF directory")
    }

    fun importToFolder(context:Context,parent:GalleryMediaPage.Media,cameraKey:String,cameraName:String?,
        http:GalleryHttpMedia,folder:Uri,cancelled:()->Boolean,progress:(Long,Long)->Unit,
        published:(String)->Unit):MediaImportTransaction.Result? {
        val file=AlbumMediaFile(parent.name.substringBeforeLast('.')+"."+extension,mime,video=false,raw=this==DNG)
        val association=MessageDigest.getInstance("SHA-256").digest(
            "$cameraKey\u0000${parent.key.store.name}\u0000${parent.key.path}\u0000${parent.handle}\u0000${parent.size}"
                .toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        val history=ImportHistoryRepository.get(context)
        history.reconcile(cancelled)
        return ImportHistoryGate.process.importing {
            val output=FolderImportOutput(context,folder)
            output.checkWritable()
            http.companionFile(parent,this,progress) { length,copy ->
                output.checkAvailableSpace(length)
                val item=MediaImportTransaction.Item(association,file.name,length)
                output.publish(file,item,history,cancelled,{ uri ->
                    checkNotNull(context.contentResolver.openOutputStream(uri,"w")).use(copy)
                },{ id,uri,name,tree ->
                    ImportHistoryDraft(id,uri,name,length,System.currentTimeMillis(),
                        ImportProvenance.OFFERED_CAMERA_BYTES,cameraModel=cameraName,offeredReduced=false,folderTree=tree,
                        companionFor=parent.name,companionParentReference=association)
                },validate={ uri ->
                    checkNotNull(context.contentResolver.openInputStream(uri)).use { verify(it,length) }
                },published={ published(it.toString()) })
            }
        }
    }

    companion object {
        fun forParent(media:GalleryMediaPage.Media):GalleryCompanion? =
            when(media.name.substringAfterLast('.').uppercase(Locale.ROOT)) {
                "JPG","JPEG" -> DNG.takeUnless { media.video }
                "MP4","MOV" -> WAV.takeIf { media.video }
                else -> null
            }
    }
}
