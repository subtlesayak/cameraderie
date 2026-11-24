package app.cameraderie.sony

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException

/** Only validated JPEGs enter this path. Camera originals are never modified. */
internal object SonyJpegReducer {
    fun render(source:File,destination:File,size:SonyImportSize,cancelled:()->Boolean):SonyImportSizePolicy.Bounds {
        require(size==SonyImportSize.TWO_MEGAPIXEL_JPEG || size==SonyImportSize.VGA_JPEG)
        fun active() { if(cancelled()) throw java.io.InterruptedIOException("JPEG import cancelled") }
        active()
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(source.path,bounds)
        if(bounds.outMimeType!="image/jpeg" || bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outWidth.toLong()*bounds.outHeight>128_000_000) throw IOException("Source is not a supported JPEG")
        val orientation=ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)
        val swap=orientation in 5..8
        val normalized=SonyImportSizePolicy.Bounds(if(swap) bounds.outHeight else bounds.outWidth,if(swap) bounds.outWidth else bounds.outHeight)
        val target=SonyImportSizePolicy.outputBounds(size,normalized)
        var sample=1
        while(bounds.outWidth/(sample*2)>=minOf(target.width,target.height) && bounds.outHeight/(sample*2)>=minOf(target.width,target.height) && bounds.outWidth.toLong()/sample*(bounds.outHeight/sample)>8_000_000) sample*=2
        var bitmap:Bitmap?=null
        try {
            bitmap=BitmapFactory.decodeFile(source.path,BitmapFactory.Options().apply { inSampleSize=sample;inPreferredColorSpace=android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB) }) ?: throw IOException("JPEG decode failed")
            active()
            val matrix=Matrix().apply {
                when(orientation) {
                    2 -> setScale(-1f,1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f,-1f)
                    5 -> { setRotate(90f);postScale(-1f,1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(-90f);postScale(-1f,1f) }
                    8 -> setRotate(-90f)
                }
            }
            if(!matrix.isIdentity) {
                val oriented=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
                if(oriented!==bitmap) { bitmap.recycle();bitmap=oriented }
            }
            val requested=SonyImportSizePolicy.outputBounds(size,SonyImportSizePolicy.Bounds(bitmap.width,bitmap.height))
            require(requested.width>=2)
            val actual=SonyImportSizePolicy.Bounds(requested.width and -2,requested.height)
            val scaled=Bitmap.createScaledBitmap(bitmap,actual.width,actual.height,true)
            if(scaled!==bitmap) { bitmap.recycle();bitmap=scaled }
            active()
            SonyJpeg422.encode(bitmap,destination,cancelled)
            active()
            JpegExifPreserver.reduced(source,destination,actual.width,actual.height)
            active()
            return actual
        } finally { bitmap?.recycle() }
    }
}
