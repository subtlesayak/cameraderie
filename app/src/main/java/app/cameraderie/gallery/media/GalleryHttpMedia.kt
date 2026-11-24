package app.cameraderie.gallery.media

import app.cameraderie.gallery.protocol.GalleryMediaPage
import java.io.*
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** Selected-network adapter is injected; this class cannot resolve a host or use
 * the default route. Original transfers accept only exact unencoded bodies. */
class GalleryHttpMedia(private val open:(URI)->Request,private val valid:()->Boolean):AutoCloseable {
    private val scope=app.cameraderie.gallery.GalleryResourceScope(2)
    override fun close()=scope.close()
    private fun <T> request(uri:URI,block:(Request)->T):T {
        checkCurrent();val opened=open(uri)
        return scope.track(opened) { it.close() }.use { handle -> checkCurrent();block(handle.value) }
    }
    interface Request:AutoCloseable { val connection:HttpURLConnection }
    internal class RestartRequired : IOException("Camera could not confirm the saved download")
    internal fun checkCurrent() { if(Thread.currentThread().isInterrupted || !scope.active || !valid())throw InterruptedIOException("Camera media context ended") }

    internal data class ClipBlock(val bytes: ByteArray, val length: Long, val validator: String)

    /** Open one unlisted companion on the exact store and stem of a revalidated parent. */
    internal fun <T> companionFile(parent: GalleryMediaPage.Media, companion: GalleryCompanion,
        progress: (Long,Long) -> Unit, receive: (Long,(OutputStream) -> MediaImportTransaction.Copied) -> T): T? {
        require(GalleryCompanion.forParent(parent)==companion)
        val path=parent.key.path.substringBeforeLast('.')+"."+companion.extension
        return request(mediaUri(parent,path,"DCIM/")) { response ->
            val connection=response.connection
            setup(connection)
            if(connection.responseCode==404) return@request null
            val length=connection.contentLengthLong
            val encoding=connection.getHeaderField("Content-Encoding")
            if(connection.responseCode!=200 || connection.getHeaderField("Content-Range")!=null ||
                (encoding!=null && !encoding.equals("identity",true)) || length <= 0) {
                throw IOException("Camera companion did not offer an exact full length")
            }
            if (length > companion.maxBytes) throw GalleryCompanion.TooLarge(companion.extension)
            receive(length) { output ->
                checkCurrent()
                connection.inputStream.use { copy(it,output,length,length,progress) }
            }
        }
    }

    /** The manifest identifies the clip, while the proxy has its own extent and validator. */
    internal fun clipBlock(media: GalleryMediaPage.Media, start: Long, count: Int,
        expected: ClipBlock?=null,original:Boolean=false): ClipBlock {
        require(supportsProxy(media) && start >= 0 && count in 1..PROXY_BLOCK_SIZE && start <= Long.MAX_VALUE - count)
        if(original) require(media.size>0)
        val path = if(original) media.key.path else media.key.path.substringBeforeLast('.') + ".LRF"
        val end = start + count - 1
        return request(mediaUri(media,path,"DCIM/")) { response ->
            val connection = response.connection
            setup(connection)
            connection.setRequestProperty("Range","bytes=$start-$end")
            expected?.let { connection.setRequestProperty("If-Range",it.validator) }
            if(connection.responseCode != 206) throw IOException("Camera video range unavailable")
            val extent = parseRange(connection.singleHeader("Content-Range"))
                ?: throw IOException("Invalid camera video range")
            val validator = strongValidator(connection.singleHeader("ETag"))
                ?: throw IOException("Camera video has no stable validator")
            val encoding = connection.getHeaderField("Content-Encoding")
            if(extent.total <= start || extent.first != start || extent.last != minOf(end,extent.total-1) ||
                (encoding != null && !encoding.equals("identity",true)) ||
                (original && extent.total != media.size) ||
                (expected != null && (extent.total != expected.length || validator != expected.validator))) {
                throw IOException("Camera video changed or returned an invalid range")
            }
            val length = extent.last - start + 1
            if(connection.contentLengthLong >= 0 && connection.contentLengthLong != length)
                throw IOException("Camera video range length changed")
            val bytes = ByteArrayOutputStream(length.toInt()).use { buffer ->
                connection.inputStream.use { copy(it,buffer,length,length) }
                buffer.toByteArray()
            }
            ClipBlock(bytes,extent.total,validator)
        }
    }
    fun preview(media:GalleryMediaPage.Media):ByteArray? {
        checkCurrent()
        for(path in listOf(media.thumbnailPath,media.thumbnailPath.removeSuffix(".scr")+".thm")) {
            try {
                val bytes=request(uri(media,path)) { request ->
                    val c=request.connection;setup(c);checkCurrent()
                    if(c.responseCode==404)return@request null
                    require(c.getHeaderField("Content-Encoding").let { it==null || it.equals("identity",true) })
                    require(c.responseCode==200 && c.getHeaderField("Content-Range")==null)
                    val length=c.contentLengthLong;require(length<0 || length in 1..MAX_PREVIEW)
                    ByteArrayOutputStream().use { out -> c.inputStream.use { copy(it,out,length.takeIf { n -> n>=0 },MAX_PREVIEW) };out.toByteArray() }
                }
                if(bytes!=null)return bytes
            } catch(_:InterruptedIOException) { checkCurrent() }
            catch(_:IOException) { checkCurrent() }
            catch(_:IllegalArgumentException) { checkCurrent() }
        }
        return null
    }
    fun original(media:GalleryMediaPage.Media,output:OutputStream,progress:(Long,Long)->Unit={_,_->}):MediaImportTransaction.Copied {
        return originalWithValidator(media,output,{},progress)
    }
    internal fun originalWithValidator(media:GalleryMediaPage.Media,output:OutputStream,
        validator:(String?)->Unit,progress:(Long,Long)->Unit):MediaImportTransaction.Copied {
        checkCurrent();require(media.size>0)
        return request(uri(media,media.key.path)) { request ->
            val c=request.connection;setup(c);checkCurrent()
            require(c.responseCode==200 && c.getHeaderField("Content-Range")==null) { "Original response was not a full file" }
            require(c.getHeaderField("Content-Encoding").let { it==null || it.equals("identity",true) })
            val length=c.contentLengthLong;require(length<0 || length==media.size) { "Original length changed" }
            validator(strongValidator(c.singleHeader("ETag")))
            c.inputStream.use { copy(it,output,media.size,media.size,progress) }
        }
    }
    /** A conditional range must echo a strong validator. With no strong
     * validator, compare every saved byte in the same 206 that supplies the tail. */
    internal fun resume(media:GalleryMediaPage.Media,saved:Long,etag:String?,prefix:()->InputStream,
        output:OutputStream,validator:(String?)->Unit,progress:(Long,Long)->Unit):MediaImportTransaction.Copied {
        require(saved in 1 until media.size)
        require(etag==null || etag==strongValidator(etag))
        val start=if(etag!=null)saved else 0L
        return request(uri(media,media.key.path)) { request ->
            val c=request.connection;setup(c)
            c.setRequestProperty("Range","bytes=$start-${media.size-1}")
            if(etag!=null)c.setRequestProperty("If-Range",etag)
            checkCurrent()
            val code=c.responseCode
            if(code>=500)throw IOException("Camera range temporarily unavailable")
            val responseValidator=strongValidator(c.singleHeader("ETag"))
            val range=c.singleHeader("Content-Range")
            val length=c.contentLengthLong
            val encoding=c.getHeaderField("Content-Encoding")
            if(code!=206 || !matchesRange(range,start,media.size) ||
                (length>=0 && length!=media.size-start) || (encoding!=null && !encoding.equals("identity",true)) ||
                (etag!=null && responseValidator!=etag))throw RestartRequired()
            val digest=MessageDigest.getInstance("SHA-256")
            c.inputStream.use { input ->
                prefix().use { local ->
                    hashPrefix(local,if(start==0L)input else null,saved,digest,media.size,progress)
                }
                validator(responseValidator)
                copy(input,output,media.size,media.size,progress,saved,digest)
            }
        }
    }
    // HttpURLConnection can return only the last occurrence of a repeated header.
    private fun HttpURLConnection.singleHeader(name: String): String? {
        val occurrences = headerFields.entries.sumOf { (key, values) ->
            if (name.equals(key, ignoreCase = true)) values.size else 0
        }
        return if (occurrences > 1) null else getHeaderField(name)
    }

    private data class Range(val first: Long, val last: Long, val total: Long)
    private fun matchesRange(value:String?,start:Long,total:Long):Boolean =
        parseRange(value)==Range(start,total-1,total)

    private fun parseRange(value: String?): Range? {
        val groups = value?.trim()?.let(RANGE::matchEntire)?.groupValues ?: return null
        return Range(groups[1].toLongOrNull() ?: return null,
            groups[2].toLongOrNull() ?: return null,groups[3].toLongOrNull() ?: return null)
    }
    private fun hashPrefix(local:InputStream,remote:InputStream?,saved:Long,digest:MessageDigest,
        total:Long,progress:(Long,Long)->Unit) {
        val buffer=ByteArray(64*1024);val comparison=remote?.let { ByteArray(buffer.size) }
        var verified=0L
        while(verified<saved) {
            checkCurrent()
            val count=minOf(saved-verified,buffer.size.toLong()).toInt()
            if(!readPrefixChunk(local,buffer,count))throw RestartRequired()
            if(remote!=null && comparison!=null) {
                if(!readPrefixChunk(remote,comparison,count))throw EOFException("Incomplete verification response")
                for(index in 0 until count)if(buffer[index]!=comparison[index])throw RestartRequired()
            }
            checkCurrent();digest.update(buffer,0,count);verified+=count
            if(remote!=null)progress(verified,total)
        }
        if(local.read()!=-1)throw RestartRequired()
    }
    private fun readPrefixChunk(input:InputStream,buffer:ByteArray,count:Int):Boolean {
        var position=0
        while(position<count) {
            checkCurrent()
            val read=input.read(buffer,position,count-position)
            if(read<0)return false
            position+=read
        }
        return true
    }
    private fun setup(c:HttpURLConnection) {
        c.instanceFollowRedirects=false;c.useCaches=false;c.connectTimeout=5000;c.readTimeout=2000;c.requestMethod="GET"
        c.setRequestProperty("Connection","close");c.setRequestProperty("Accept-Encoding","identity")
        // Header checks happen after responseCode establishes the connection.
    }
    private fun copy(input:InputStream,output:OutputStream,expected:Long?,limit:Long,progress:(Long,Long)->Unit={_,_->},
        alreadyCopied:Long=0,digest:MessageDigest=MessageDigest.getInstance("SHA-256")):MediaImportTransaction.Copied {
        checkCurrent();val buffer=ByteArray(64*1024);var bytes=alreadyCopied
        try {
            while(true) {
                checkCurrent();val n=input.read(buffer);checkCurrent();if(n<0)break;if(n==0)continue
                require(n.toLong()<=limit-bytes) { "Camera file exceeded its validated size" }
                output.write(buffer,0,n);digest.update(buffer,0,n);bytes+=n;progress(bytes,expected ?: limit)
            }
            if(bytes==0L || (expected!=null && bytes!=expected))throw EOFException("Camera file was incomplete")
            checkCurrent();output.flush();return MediaImportTransaction.Copied(bytes,digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        } finally { buffer.fill(0) }
    }
    companion object {
        const val MAX_PREVIEW=3L*1024*1024
        internal const val PROXY_BLOCK_SIZE=512*1024
        private val RANGE=Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)",RegexOption.IGNORE_CASE)
        internal fun supportsProxy(media: GalleryMediaPage.Media): Boolean =
            media.video && media.name.substringAfterLast('.').uppercase(java.util.Locale.ROOT) in setOf("MP4","MOV")
        internal fun strongValidator(value:String?):String? = value?.takeIf {
            it.length in 2..256 && it.first()=='"' && it.last()=='"' &&
                it.substring(1,it.lastIndex).all { c -> c in '!'..'~' && c!='"' }
        }
        /** Only parser-supported relative camera paths. URI escapes spaces as %20. */
        fun uri(media:GalleryMediaPage.Media,path:String):URI =
            mediaUri(media,path,if(path==media.key.path) "DCIM/" else "MISC/")

        private fun mediaUri(media:GalleryMediaPage.Media,path:String,prefix:String):URI {
            require(path.startsWith(prefix) && path.length<=249 &&
                path.matches(Regex("[A-Za-z0-9_ ./-]+")) && path.split('/').none { it.isBlank() || it=="." || it==".." })
            return URI(null,null,"/v2","storage=${media.key.store.protocolId}&path=$path",null)
        }
    }
}
