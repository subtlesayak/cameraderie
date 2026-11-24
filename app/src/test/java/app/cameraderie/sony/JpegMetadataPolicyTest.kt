package app.cameraderie.sony

import org.junit.Assert.*
import org.junit.Test

class JpegMetadataPolicyTest {
    @Test fun preservesCameraLensExposureAndCaptureTime() {
        val source=mapOf("Make" to "SONY","Model" to "ILCE-6400","LensModel" to "E 50mm F1.8 OSS","FNumber" to "4.5","ExposureTime" to "1/1250","PhotographicSensitivity" to "250","DateTimeOriginal" to "2026:10:31 19:45:00")
        val result=JpegMetadataPolicy.reduced(source,1732,1154)
        source.forEach { (tag,value) -> assertEquals(value,result[tag]) }
    }
    @Test fun normalizedPixelsDoNotInheritOldOrientationOrDimensions() {
        val result=JpegMetadataPolicy.reduced(mapOf("Orientation" to "6","ImageWidth" to "6000","ImageLength" to "4000","ColorSpace" to "65535"),1080,1616)
        assertEquals("1",result["Orientation"]);assertEquals("1080",result["ImageWidth"]);assertEquals("1616",result["PixelYDimension"]);assertEquals("1",result["ColorSpace"])
    }
    @Test fun doesNotInventMissingCameraFieldsOrCopyUnsafeOffsets() {
        val result=JpegMetadataPolicy.reduced(mapOf("MakerNote" to "offsets into old image","JPEGInterchangeFormat" to "1024"),640,480)
        assertNull(result["Make"]);assertNull(result["DateTimeOriginal"]);assertNull(result["MakerNote"]);assertNull(result["JPEGInterchangeFormat"])
    }
    @Test fun preservesSubsecondsAndKnownOffsetWithoutGuessing() {
        val result=JpegMetadataPolicy.reduced(mapOf("SubSecTimeOriginal" to "123","OffsetTimeOriginal" to "+05:30"),640,480)
        assertEquals("123",result["SubSecTimeOriginal"]);assertEquals("+05:30",result["OffsetTimeOriginal"])
    }
}
