package app.cameraderie.gallery.media

import app.cameraderie.gallery.protocol.GalleryMediaPage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** A queued original is staged privately. Only the shared MediaStore transaction
 * can publish it. The queue owns the job ID and revalidates its source before IO. */
internal class GalleryPartialDownloads(private val root:File) {
    class NoSpace : IOException("Phone storage is full")
    private data class Receipt(val bytes:Long,val hash:String,val etag:String?,val complete:Boolean)
    class Staged internal constructor(private val path:File,private val received:MediaImportTransaction.Copied,
        val savedBytes:Long,val rangeStart:Long?) {
        fun copyTo(output:OutputStream,cancelled:()->Boolean):MediaImportTransaction.Copied {
            path.inputStream().use { input ->
                val buffer=ByteArray(64*1024)
                var remaining=received.bytes
                while(remaining>0) {
                    if(cancelled())throw InterruptedIOException()
                    val n=input.read(buffer,0,minOf(remaining,buffer.size.toLong()).toInt())
                    if(n<0)throw IOException("Saved download is incomplete")
                    if(cancelled())throw InterruptedIOException()
                    output.write(buffer,0,n);remaining-=n
                }
                if(input.read()!=-1)throw IOException("Saved download length changed")
            }
            return received
        }
    }

    fun prepare(job:GalleryDownloadQueue.Job,media:GalleryMediaPage.Media,http:GalleryHttpMedia,
        cancelled:()->Boolean,progress:(Long,Long)->Unit):Staged {
        check(job.source==GalleryDownloadQueue.source(media))
        if(cancelled())throw InterruptedIOException()
        val path=file(job.id,"part");val metadata=file(job.id,"receipt")
        check(root.isDirectory || root.mkdirs()) { "Could not create private download storage" }
        val saved=path.length()
        if(saved>media.size)throw GalleryHttpMedia.RestartRequired()
        val receipt=readReceipt(metadata)?.takeIf { it.bytes==saved && saved>0 && it.hash==hash(path,cancelled) }
        // Staging and pending publication share phone storage, so reserve one
        // full output copy plus any bytes still needed in the private staging file.
        val space=root.usableSpace
        if(space<media.size || space-media.size<media.size-saved)throw NoSpace()
        if(saved==media.size) {
            if(receipt?.complete!=true)throw GalleryHttpMedia.RestartRequired()
            return Staged(path,MediaImportTransaction.Copied(receipt.bytes,receipt.hash),saved,null)
        }
        var validatedEtag:String?=null
        var result:MediaImportTransaction.Copied?=null
        var prefixSynced=false
        try {
            FileOutputStream(path,saved>0).use { out ->
                val rememberValidator:(String?)->Unit={ validatedEtag=it }
                try {
                    val copied=if(saved==0L) http.originalWithValidator(media,out,rememberValidator,progress)
                    else http.resume(media,saved,receipt?.etag,path::inputStream,out,rememberValidator,progress)
                    out.fd.sync()
                    result=copied
                } catch(error:Exception) {
                    prefixSynced=runCatching { out.fd.sync() }.isSuccess
                    throw error
                }
            }
        } finally {
            val received=result
            if(received!=null && path.length()==received.bytes) {
                writeReceipt(metadata,Receipt(received.bytes,checkNotNull(received.sha256),validatedEtag,true))
            } else if(prefixSynced && validatedEtag!=null && path.length() in (saved+1) until media.size) {
                // The transfer can still be recovered without a validator if
                // the phone stops before this optional checkpoint is written.
                runCatching { writeReceipt(metadata,Receipt(path.length(),hash(path){ false },validatedEtag,false)) }
            }
        }
        return Staged(path,checkNotNull(result),saved,if(saved==0L)null else if(receipt?.etag!=null)saved else 0L)
    }

    fun discard(id:String) {
        for(extension in listOf("part","receipt","receipt.tmp")) {
            val remove=file(id,extension)
            if(remove.exists() && !remove.delete())throw IOException("Could not clear the private download")
        }
    }
    fun removeUnused(pending:Set<String>) {
        val ids=root.listFiles().orEmpty().mapNotNull { path ->
            path.name.substringBefore('.').takeIf { id -> runCatching { UUID.fromString(id).toString()==id }.getOrDefault(false) }
        }.toSet()
        for(id in ids-pending)runCatching { discard(id) }
    }
    private fun file(id:String,extension:String):File {
        require(UUID.fromString(id).toString()==id)
        return File(root,"$id.$extension")
    }
    private fun readReceipt(path:File):Receipt? {
        if(path.length() !in 1..512)return null
        return runCatching {
            val values=path.readText(Charsets.UTF_8).split('\n');require(values.size==4)
            val hash=values[1];require(hash.matches(Regex("[0-9a-f]{64}")))
            val tag=values[2].takeIf { it.isNotEmpty() }
            require(tag==null || GalleryHttpMedia.strongValidator(tag)==tag)
            Receipt(values[0].toLong(),hash,tag,values[3].toBooleanStrict())
        }.getOrNull()
    }
    private fun writeReceipt(path:File,receipt:Receipt) {
        val tmp=File(root,path.name+".tmp")
        FileOutputStream(tmp).use { stream ->
            stream.write("${receipt.bytes}\n${receipt.hash}\n${receipt.etag.orEmpty()}\n${receipt.complete}".toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Files.move(tmp.toPath(),path.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
    }
    private fun hash(path:File,cancelled:()->Boolean):String {
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024)
        path.inputStream().use { input ->
            while(true) {
                if(cancelled())throw InterruptedIOException()
                val n=input.read(buffer)
                if(n<0)break
                digest.update(buffer,0,n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
