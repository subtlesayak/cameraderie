package app.cameraderie.sony

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Two workers, bounded queue/cache, recycled-view cancellation and session invalidation.
 * Cache is memory-only, and is cleared whenever the camera connection changes. */
internal class SonyPreviewLoader(maxPending:Int=24):Closeable {
    private val main=Handler(Looper.getMainLooper())
    private val pool=ThreadPoolExecutor(2,2,15,TimeUnit.SECONDS,ArrayBlockingQueue(maxPending.also { require(it in 1..64) }),
        { r -> Thread(r,"Sony-preview").apply { isDaemon=true } },ThreadPoolExecutor.AbortPolicy())
    private val cache=object:LruCache<String,Bitmap>(16*1024*1024) {
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }
    private val missing=linkedSetOf<String>()
    private val tasks=mutableSetOf<FutureTask<Unit>>()
    private var epoch=0
    private var closed=false
    fun cached(key:String):Bitmap?=cache.get(key)

    fun load(key:String,source:()->ByteArray?,maxDimension:Int=512,result:(Bitmap?)->Unit):Closeable {
        val token=epoch
        if(closed) return Closeable { }
        cache.get(key)?.let { result(it);return Closeable { } }
        if(key in missing) { result(null);return Closeable { } }
        lateinit var task:FutureTask<Unit>
        task=FutureTask {
            val bitmap=try { source()?.let { decode(it,maxDimension) } } catch(_:Exception) { null }
            main.post {
                tasks.remove(task)
                if(token==epoch && !closed && !task.isCancelled) {
                    if(bitmap!=null) cache.put(key,bitmap) else {
                        missing.add(key);if(missing.size>128) missing.remove(missing.first())
                    }
                    result(bitmap)
                }
            }
        }
        tasks.add(task)
        try { pool.execute(task) } catch(_:RejectedExecutionException) { tasks.remove(task);result(null) }
        return Closeable { task.cancel(true);pool.remove(task);tasks.remove(task) }
    }
    fun retry(key:String) { missing.remove(key) }
    fun reset() {
        epoch++;tasks.toList().forEach { it.cancel(true);pool.remove(it) };tasks.clear()
        cache.evictAll();missing.clear()
    }
    override fun close() { closed=true;reset();pool.shutdownNow() }

    private fun decode(bytes:ByteArray,maxDimension:Int):Bitmap? {
        require(maxDimension in 128..2048)
        if(bytes.size>SonyPreviewData.MAX_DETAIL_BYTES) return null
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        val width=bounds.outWidth;val height=bounds.outHeight
        if(width<=0 || height<=0 || width.toLong()*height>64_000_000L) return null
        var sample=1
        while(maxOf(width,height)/sample>maxDimension) sample*=2
        val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: return null
        val orientation=try { androidx.exifinterface.media.ExifInterface(bytes.inputStream()).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,1) } catch(_:Exception) { 1 }
        val matrix=android.graphics.Matrix()
        when(orientation) {
            2 -> matrix.setScale(-1f,1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f,-1f)
            5 -> { matrix.setRotate(90f);matrix.postScale(-1f,1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(270f);matrix.postScale(-1f,1f) }
            8 -> matrix.setRotate(270f)
        }
        if(matrix.isIdentity) return decoded
        val oriented=Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true)
        if(oriented!==decoded) decoded.recycle()
        return oriented
    }
}
