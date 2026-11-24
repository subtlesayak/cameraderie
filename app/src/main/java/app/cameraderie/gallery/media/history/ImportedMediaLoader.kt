package app.cameraderie.gallery.media.history

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Two bounded local-thumbnail workers; recycling cancels the provider request. */
internal class ImportedMediaLoader(context:Context,private val local:LocalImportedMedia):Closeable {
    enum class Availability { UNKNOWN, AVAILABLE, UNAVAILABLE }
    data class Result(val availability:Availability,val item:LocalImportedMedia.Snapshot?,val bitmap:Bitmap?)
    private val resolver=context.applicationContext.contentResolver
    private val main=Handler(Looper.getMainLooper())
    private val closed=AtomicBoolean(false)
    private val jobs=mutableSetOf<CancellationSignal>()
    private val workers=ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(24))
    private val cache=object:LruCache<String,Bitmap>(8*1024) {
        override fun sizeOf(key:String,value:Bitmap)=((value.allocationByteCount+1023)/1024).coerceAtLeast(1)
    }
    fun load(entry:ImportHistoryEntry,edge:Int=384,done:(Result)->Unit):Closeable {
        require(edge in 64..1536)
        val active=AtomicBoolean(true);val signal=CancellationSignal()
        val future=synchronized(jobs) {
        if(closed.get())return Closeable {}
        jobs.add(signal)
        try { workers.submit {
            try {
            if(closed.get() || !active.get())return@submit
            val checked=runCatching { local.available(entry.draft.uri,signal) }
            val item=checked.getOrNull()
            var bitmap:Bitmap?=null
            if(item!=null && active.get() && !closed.get()) {
                val key="${LocalMediaIdentity.key(item.uri)}:${item.bytes}:${item.dateModified}:$edge"
                bitmap=synchronized(cache) { cache.get(key) }
                if(bitmap==null)bitmap=runCatching {
                    if(edge>384 && item.mime.startsWith("image/")) {
                        try { decodeOriginal(item.uri,edge,signal) }
                        catch(error:Exception) {
                            signal.throwIfCanceled()
                            if(closed.get() || !active.get())throw error
                            resolver.loadThumbnail(Uri.parse(item.uri),Size(edge,edge),signal)
                        }
                    } else resolver.loadThumbnail(Uri.parse(item.uri),Size(edge,edge),signal)
                }.getOrNull()?.also {
                    synchronized(cache) { if(active.get() && !closed.get())cache.put(key,it) }
                }
            }
            val result=Result(if(checked.isFailure)Availability.UNKNOWN else if(item==null)Availability.UNAVAILABLE else Availability.AVAILABLE,item,bitmap)
            main.post { if(active.get() && !closed.get())done(result) }
            } finally { synchronized(jobs) { jobs.remove(signal) } }
        } } catch(_:RejectedExecutionException) {
            jobs.remove(signal)
            main.post { if(active.get() && !closed.get())done(Result(Availability.UNKNOWN,null,null)) };null
        }
        }
        return Closeable { active.set(false);runCatching { signal.cancel() };future?.cancel(true);synchronized(jobs) { jobs.remove(signal) };workers.purge() }
    }
    /** Decode the actual saved image for details. Thumbnail providers may return
     * a small embedded preview regardless of the requested EXTRA_SIZE. ImageDecoder
     * already applies EXIF orientation; never rotate again from journal metadata. */
    private fun decodeOriginal(value:String,edge:Int,signal:CancellationSignal):Bitmap {
        signal.throwIfCanceled()
        val source=ImageDecoder.createSource(java.util.concurrent.Callable {
            signal.throwIfCanceled()
            resolver.openAssetFileDescriptor(Uri.parse(value),"r",signal) ?: throw java.io.IOException("Saved image unavailable")
        })
        return ImageDecoder.decodeBitmap(source) { decoder,info,_ ->
            signal.throwIfCanceled();decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            val width=info.size.width;val height=info.size.height;require(width>0 && height>0)
            val scale=minOf(1.0,edge.toDouble()/maxOf(width,height))
            decoder.setTargetSize(kotlin.math.round(width*scale).toInt().coerceAtLeast(1),kotlin.math.round(height*scale).toInt().coerceAtLeast(1))
        }.also { bitmap ->
            if(signal.isCanceled) { bitmap.recycle();signal.throwIfCanceled() }
        }
    }
    override fun close() {
        val pending=synchronized(jobs) { closed.set(true);jobs.toList().also { jobs.clear() } }
        pending.forEach { runCatching { it.cancel() } };workers.shutdownNow();main.removeCallbacksAndMessages(null);synchronized(cache) { cache.evictAll() }
    }
}
