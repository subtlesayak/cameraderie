package app.cameraderie.sony

import androidx.exifinterface.media.ExifInterface
import java.io.File

internal object JpegExifPreserver {
    fun reduced(source:File,destination:File,width:Int,height:Int) {
        val original=ExifInterface(source.path)
        val values=JpegMetadataPolicy.reduced(JpegMetadataPolicy.preserved.mapNotNull { tag -> original.getAttribute(tag)?.let { tag to it } }.toMap(),width,height)
        val output=ExifInterface(destination.path)
        values.forEach { (tag,value) -> output.setAttribute(tag,value) }
        output.saveAttributes()
        val readback=ExifInterface(destination.path)
        check(readback.getAttributeInt(ExifInterface.TAG_ORIENTATION,1)==1)
        check(readback.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH,0)==width && readback.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH,0)==height)
        // These fields must survive a successful metadata-preserving resize.
        for(tag in listOf("Make","Model","LensModel","DateTimeOriginal")) values[tag]?.let { check(readback.getAttribute(tag)==it) }
    }
}
