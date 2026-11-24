package app.cameraderie.sony

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.File

/** Full-range JPEG YCbCr, horizontally shared chroma, using Android's YUY2 encoder. */
internal object SonyJpeg422 {
    fun encode(bitmap:Bitmap,destination:File,cancelled:()->Boolean) {
        val width=bitmap.width;val height=bitmap.height
        require(width>=2 && width%2==0 && width.toLong()*height<=2_000_000)
        val yuy2=ByteArray(width*height*2);val row=IntArray(width)
        fun y(p:Int):Int { val r=p ushr 16 and 255;val g=p ushr 8 and 255;val b=p and 255;return ((19595*r+38470*g+7471*b+32768) shr 16).coerceIn(0,255) }
        fun cb(p:Int):Int { val r=p ushr 16 and 255;val g=p ushr 8 and 255;val b=p and 255;return ((-11059*r-21709*g+32768*b+8421376) shr 16).coerceIn(0,255) }
        fun cr(p:Int):Int { val r=p ushr 16 and 255;val g=p ushr 8 and 255;val b=p and 255;return ((32768*r-27439*g-5329*b+8421376) shr 16).coerceIn(0,255) }
        for(line in 0 until height) {
            if(cancelled()) throw java.io.InterruptedIOException("JPEG encoding cancelled")
            bitmap.getPixels(row,0,width,0,line,width,1)
            var out=line*width*2
            for(x in 0 until width step 2) {
                val first=row[x];val second=row[x+1]
                yuy2[out++]=y(first).toByte();yuy2[out++]=((cb(first)+cb(second)+1)/2).toByte()
                yuy2[out++]=y(second).toByte();yuy2[out++]=((cr(first)+cr(second)+1)/2).toByte()
            }
        }
        destination.outputStream().use { output ->
            check(YuvImage(yuy2,ImageFormat.YUY2,width,height,intArrayOf(width*2)).compressToJpeg(Rect(0,0,width,height),95,output))
        }
        check(destination.inputStream().use { JpegSampling.read(it).is422 }) { "Encoder did not produce 4:2:2 JPEG" }
    }
}
