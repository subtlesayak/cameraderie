package app.cameraderie.sony

/** Preserve useful camera/capture metadata while making transformed geometry truthful. */
object JpegMetadataPolicy {
    val preserved=listOf("Make","Model","DateTime","DateTimeOriginal","DateTimeDigitized",
        "SubSecTime","SubSecTimeOriginal","SubSecTimeDigitized","OffsetTime","OffsetTimeOriginal","OffsetTimeDigitized",
        "ExposureTime","FNumber","PhotographicSensitivity","ISOSpeedRatings","SensitivityType","ExposureBiasValue",
        "ExposureProgram","ExposureMode","MeteringMode","Flash","FocalLength","FocalLengthIn35mmFilm",
        "WhiteBalance","LightSource","DigitalZoomRatio","SceneCaptureType","LensMake","LensModel","LensSpecification")
    fun reduced(source:Map<String,String>,width:Int,height:Int):Map<String,String> {
        require(width>0 && height>0)
        val result=linkedMapOf<String,String>()
        for(tag in preserved) source[tag]?.takeIf { it.isNotBlank() && it.length<=4096 }?.let { result[tag]=it }
        result["Orientation"]="1";result["ImageWidth"]=width.toString();result["ImageLength"]=height.toString()
        result["PixelXDimension"]=width.toString();result["PixelYDimension"]=height.toString();result["ColorSpace"]="1"
        return result
    }
}
