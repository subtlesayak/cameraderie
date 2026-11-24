package app.cameraderie.sony

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.URI
import java.io.ByteArrayInputStream

class SonyDetailPreviewTest {
    private val gateway=InetAddress.getByAddress(byteArrayOf(192.toByte(),168.toByte(),122,1))
    private fun item(resources:String):SonyHttpItem {
        val didl="""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/"><item id="photo"><dc:title>PHOTO.ARW</dc:title>$resources</item></DIDL-Lite>"""
        val escaped=didl.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        val response="""<Envelope><Result>$escaped</Result><NumberReturned>1</NumberReturned><TotalMatches>1</TotalMatches></Envelope>"""
        return SonyHttpGallery.parseBrowse(response,URI("http://192.168.122.1/"),gateway).items.single()
    }
    private fun resource(path:String,bytes:Long,mime:String,resolution:String,profile:String="*")="""<res size="$bytes" resolution="$resolution" protocolInfo="http-get:*:$mime:$profile">http://192.168.122.1/$path</res>"""
    @Test fun gridStaysSmallWhileDetailsUseLargerOfferedJpeg() {
        val parsed=item(resource("thumb.JPG",1200,"image/jpeg","120x160","JPEG_TN")+resource("full.JPG",8000000,"image/jpeg","4000x6000"))
        assertTrue(parsed.previewUrl!!.endsWith("thumb.JPG"));assertTrue(parsed.detailPreviewUrl!!.endsWith("full.JPG"))
        assertTrue(parsed.url!!.endsWith("full.JPG"));assertEquals(8000000L,parsed.size)
    }
    @Test fun fullRawIsNeverDecodedAsPreview() {
        val parsed=item(resource("thumb.JPG",1200,"image/jpeg","120x160","JPEG_TN")+resource("full.ARW",12000000,"image/x-sony-arw","4000x6000"))
        assertTrue(parsed.url!!.endsWith("full.ARW"));assertTrue(parsed.detailPreviewUrl!!.endsWith("thumb.JPG"))
    }
    @Test fun tooLargeOriginalFallsBackToMediumCameraPreview() {
        val parsed=item(resource("thumb.JPG",1200,"image/jpeg","120x160","JPEG_TN")+resource("medium.JPG",1200000,"image/jpeg","1200x1600","JPEG_MED")+resource("huge.JPG",40000000,"image/jpeg","4000x6000"))
        assertTrue(parsed.detailPreviewUrl!!.endsWith("medium.JPG"));assertTrue(parsed.url!!.endsWith("huge.JPG"))
    }
    @Test fun noImagePreviewDoesNotBorrowVideoOrRawOriginal() {
        val parsed=item(resource("full.ARW",12000000,"image/x-sony-arw","4000x6000"))
        assertNull(parsed.previewUrl);assertNull(parsed.detailPreviewUrl)
    }
    @Test fun largerReaderBudgetIsExplicitAndStillBounded() {
        val bytes=ByteArray(SonyPreviewData.MAX_BYTES+1).also { it[0]=0xff.toByte();it[1]=0xd8.toByte();it[2]=0xff.toByte() }
        assertEquals(bytes.size,SonyPreviewData.read(ByteArrayInputStream(bytes),bytes.size.toLong(),SonyPreviewData.MAX_DETAIL_BYTES).size)
        try { SonyPreviewData.read(ByteArrayInputStream(bytes),bytes.size.toLong());fail() } catch(_:java.io.IOException) { }
    }
}
