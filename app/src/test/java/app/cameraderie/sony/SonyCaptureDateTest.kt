package app.cameraderie.sony

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.URI

class SonyCaptureDateTest {
    private fun item(date:String):SonyHttpItem {
        val didl="""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/"><item id="one"><dc:title>PHOTO.JPG</dc:title>$date<res size="1000" protocolInfo="http-get:*:image/jpeg:*">http://192.168.122.1/PHOTO.JPG</res></item></DIDL-Lite>"""
        val response="<Envelope><Result>${didl.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")}</Result><NumberReturned>1</NumberReturned><TotalMatches>1</TotalMatches></Envelope>"
        return SonyHttpGallery.parseBrowse(response,URI("http://192.168.122.1/"),InetAddress.getByAddress(byteArrayOf(192.toByte(),168.toByte(),122,1))).items.single()
    }
    @Test fun retainsCameraDateAndTimeForShootGrouping() {
        val parsed=item("<dc:date>2026-10-31T19:45:00+05:30</dc:date>")
        assertEquals("2026-10-31T19:45:00+05:30",parsed.captureDate)
        assertNotNull(CameraCaptureTime.parse(parsed.captureDate)?.time)
    }
    @Test fun missingOrDateOnlyMetadataDoesNotInventImportTimeAsCapture() {
        assertNull(item("").captureDate)
        assertNull(CameraCaptureTime.parse(item("<dc:date>2026-10-31</dc:date>").captureDate)?.time)
    }
}
