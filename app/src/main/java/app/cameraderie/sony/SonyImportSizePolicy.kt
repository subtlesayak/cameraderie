package app.cameraderie.sony

import java.util.Locale
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

enum class SonyImportSize { ORIGINAL_JPEG, TWO_MEGAPIXEL_JPEG, VGA_JPEG, ORIGINAL_RAW }

/** Metadata describes a camera resource, never an invented URL or RAW conversion. */
object SonyImportSizePolicy {
    data class Resource(val id:String,val name:String,val mime:String,val bytes:Long,
                        val width:Int?=null,val height:Int?=null,val thumbnail:Boolean=false,val reduced:Boolean=false) {
        val mediaType get()=mime.substringBefore(';').trim().lowercase(Locale.ROOT)
        val jpeg get()=mediaType=="image/jpeg"
        val raw get()=mediaType in setOf("image/x-sony-arw","image/x-arw","image/arw") ||
            (mediaType=="application/octet-stream" && name.endsWith(".arw",true))
        val bounds get()=if(width!=null && height!=null && width>0 && height>0 && width<=65535 && height<=65535) Bounds(width,height) else null
    }
    data class Bounds(val width:Int,val height:Int) {
        init { require(width>0 && height>0) }
        val pixels get()=width.toLong()*height
    }
    sealed class Plan {
        /** Exact offered bytes; caller still verifies length/header/readback. */
        data class CameraFile(val resource:Resource):Plan()
        /** A generated JPEG, not a camera-original hash match or bandwidth saving. */
        data class LocalJpeg(val source:Resource,val size:SonyImportSize,val output:Bounds?):Plan()
        data class Unavailable(val reason:String):Plan()
    }
    fun choose(size:SonyImportSize,resources:List<Resource>):Plan {
        val usable=resources.filter { it.bytes>0 && !it.thumbnail }
        if(size==SonyImportSize.ORIGINAL_RAW) {
            return usable.filter { it.raw }.maxByOrNull { it.bytes }?.let { Plan.CameraFile(it) }
                ?: Plan.Unavailable("The camera has not offered an original ARW file")
        }
        val jpegs=usable.filter { it.jpeg }
        val originals=jpegs.filterNot { it.reduced }
        val source=(if(size==SonyImportSize.ORIGINAL_JPEG) originals else originals.ifEmpty { jpegs }).maxWithOrNull(compareBy<Resource> { it.bounds?.pixels ?: 0 }.thenBy { it.bytes })
            ?: return Plan.Unavailable("The camera has not offered a full-size JPEG")
        if(size==SonyImportSize.ORIGINAL_JPEG) return Plan.CameraFile(source)
        val sourceBounds=source.bounds
        if(sourceBounds==null) return Plan.LocalJpeg(source,size,null) // must decode verified JPEG bounds
        val target=outputBounds(size,sourceBounds)
        // Prefer a known camera-sized JPEG only when close to the requested
        // bounds and the source aspect ratio. Never substitute an arbitrary TN.
        val offered=jpegs.filter { candidate ->
            val b=candidate.bounds ?: return@filter false
            val maxSide=maxOf(b.width,b.height);val minSide=minOf(b.width,b.height)
            val fits=when(size) {
                SonyImportSize.TWO_MEGAPIXEL_JPEG -> b.pixels<=2_000_000
                SonyImportSize.VGA_JPEG -> maxSide<=640 && minSide<=480
                else -> false
            }
            val sourceRatio=sourceBounds.width.toDouble()/sourceBounds.height
            val ratio=b.width.toDouble()/b.height
            fits && b.pixels>=target.pixels*0.75 && kotlin.math.abs(ratio/sourceRatio-1)<=0.02 && b.pixels<=sourceBounds.pixels
        }.maxByOrNull { it.bounds!!.pixels }
        return if(offered!=null) Plan.CameraFile(offered) else Plan.LocalJpeg(source,size,target)
    }
    /** Dimensions must already reflect EXIF orientation; renderers never upscale. */
    fun outputBounds(size:SonyImportSize,source:Bounds):Bounds {
        val scale=when(size) {
            SonyImportSize.TWO_MEGAPIXEL_JPEG -> min(1.0,sqrt(2_000_000.0/source.pixels))
            SonyImportSize.VGA_JPEG -> {
                val maxW=if(source.width>=source.height) 640.0 else 480.0
                val maxH=if(source.width>=source.height) 480.0 else 640.0
                min(1.0,min(maxW/source.width,maxH/source.height))
            }
            else -> 1.0
        }
        return Bounds(maxOf(1,floor(source.width*scale).toInt()),maxOf(1,floor(source.height*scale).toInt()))
    }
}
