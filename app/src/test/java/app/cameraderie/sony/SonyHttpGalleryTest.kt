package app.cameraderie.sony

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.URI

class SonyHttpGalleryTest {
    @Test fun pngKeepsAnImportableNameAndIsNotOfferedAsAJpegConversion() {
        val item=didl("""<item id="png"><dc:title>PHOTO.PNG</dc:title><res size="1000" protocolInfo="http-get:*:image/png:*">http://192.168.122.1/PHOTO.PNG</res></item>""")
        val parsed=SonyHttpGallery.parseBrowse(response(item),base,gateway).items.single()
        assertEquals("PHOTO.PNG",parsed.name);assertNotNull(parsed.url)
        assertEquals("image/png",SonyImportPlan.file(parsed.name).mime)
        assertEquals(parsed.name,parsed.importResources.single().name)
        assertTrue(SonyImportPlan.unchanged(parsed.name))
        assertTrue(SonyImportPlan.importable(parsed.name,parsed.size,parsed.importResources))
        assertTrue(SonyImportSizePolicy.choose(SonyImportSize.ORIGINAL_JPEG,parsed.importResources) is SonyImportSizePolicy.Plan.Unavailable)
    }
    @Test fun extensionlessSupportedVideosUseTheirOfferedMimeAndUnsupportedFormatsStayUnavailable() {
        for((mime,suffix) in listOf("video/mp4" to ".MP4","video/quicktime" to ".MOV","video/mp2t" to ".MTS")) {
            val item=didl("""<item id="clip"><dc:title>Clip</dc:title><res size="1000" protocolInfo="http-get:*:$mime:*">http://192.168.122.1/object</res></item>""")
            val parsed=SonyHttpGallery.parseBrowse(response(item),base,gateway).items.single()
            assertEquals("object$suffix",parsed.name);assertTrue(SonyImportPlan.file(parsed.name).video)
            assertEquals(parsed.name,parsed.importResources.single().name);assertNotNull(parsed.url)
        }
        val item=didl("""<item id="unsupported"><dc:title>Photo</dc:title><res size="1000" protocolInfo="http-get:*:image/heif:*">http://192.168.122.1/PHOTO.HEIF</res></item>""")
        val parsed=SonyHttpGallery.parseBrowse(response(item),base,gateway).items.single()
        assertNull(parsed.url);assertTrue(parsed.importResources.isEmpty())
    }
    @Test fun importVariantsRetainDimensionsAndKeepPreviewRolesSeparate() {
        val item=didl("""<item id="p"><dc:title>Photo.ARW</dc:title><res size="1000" resolution="120x160" protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN">http://192.168.122.1/thumb.JPG</res><res size="500000" resolution="1616x1080" protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_MED">http://192.168.122.1/small.JPG</res><res size="9000000" resolution="6000x4000" protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG">http://192.168.122.1/full.ARW</res><res size="24000000" protocolInfo="http-get:*:image/x-sony-arw:*">http://192.168.122.1/fullraw.ARW</res></item>""")
        val variants=SonyHttpGallery.parseBrowse(response(item),base,gateway).items.single().importResources
        assertEquals(4,variants.size)
        assertTrue(variants[0].thumbnail);assertTrue(variants[1].reduced)
        assertEquals(1616,variants[1].width)
        val original=SonyImportSizePolicy.choose(SonyImportSize.ORIGINAL_JPEG,variants) as SonyImportSizePolicy.Plan.CameraFile
        assertEquals("full.JPG",original.resource.name)
        assertEquals(500000L,(SonyImportSizePolicy.choose(SonyImportSize.TWO_MEGAPIXEL_JPEG,variants) as SonyImportSizePolicy.Plan.CameraFile).resource.bytes)
        assertEquals("fullraw.ARW",(SonyImportSizePolicy.choose(SonyImportSize.ORIGINAL_RAW,variants) as SonyImportSizePolicy.Plan.CameraFile).resource.name)
    }
    @Test fun retainsThumbnailSeparatelyFromRawOriginal() {
        val item=didl("""<item id="raw"><dc:title>Photo.ARW</dc:title><res size="1200" protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN">http://192.168.122.1/thumb.JPG</res><res size="8000000" protocolInfo="http-get:*:image/x-sony-arw:*">http://192.168.122.1/original.ARW</res></item>""")
        val parsed=SonyHttpGallery.parseBrowse(response(item),base,gateway).items.single()
        assertTrue(parsed.url!!.endsWith("original.ARW"));assertEquals(8000000L,parsed.size)
        assertTrue(parsed.previewUrl!!.endsWith("thumb.JPG"));assertEquals("original.ARW",parsed.name)
    }
    @Test fun thumbnailOnlyNeverBecomesDownloadableOriginal() {
        val item=didl("""<item id="one"><dc:title>Photo.ARW</dc:title><res size="1200" protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN">http://192.168.122.1/thumb.JPG</res></item>""")
        val parsed=SonyHttpGallery.parseBrowse(response(item),base,gateway).items.single()
        assertNull(parsed.url);assertEquals(-1L,parsed.size);assertNotNull(parsed.previewUrl)
    }
    @Test fun unknownLengthThumbnailStillHasBoundedReader() {
        val item=didl("""<item id="one"><dc:title>Photo</dc:title><res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN">http://192.168.122.1/thumb.JPG</res></item>""")
        assertNotNull(SonyHttpGallery.parseBrowse(response(item),base,gateway).items.single().previewUrl)
    }
    @Test fun rejectsOffCameraCredentialsAndOversizedPreviews() {
        for(url in listOf("http://example.com/thumb.JPG","http://192.168.122.2/thumb.JPG","http://u:p@192.168.122.1/thumb.JPG")) {
            val item=didl("""<item id="one"><dc:title>Photo</dc:title><res size="1200" protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN">$url</res></item>""")
            assertNull(SonyHttpGallery.parseBrowse(response(item),base,gateway).items.single().previewUrl)
        }
        val huge=didl("""<item id="one"><dc:title>Photo</dc:title><res size="9000000" protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN">http://192.168.122.1/thumb.JPG</res></item>""")
        assertNull(SonyHttpGallery.parseBrowse(response(huge),base,gateway).items.single().previewUrl)
    }
    @Test fun albumArtRemainsLocalAndDoesNotImplyOriginal() {
        val item=didl("""<item id="one"><dc:title>Photo</dc:title><albumArtURI>/thumb.JPG</albumArtURI></item>""")
        val parsed=SonyHttpGallery.parseBrowse(response(item),base,gateway).items.single()
        assertEquals("http://192.168.122.1:64321/thumb.JPG",parsed.previewUrl);assertNull(parsed.url)
        assertNull(SonyHttpGallery.parseBrowse(response(item.replace("/thumb.JPG","http://192.168.122.2/thumb.JPG")),base,gateway).items.single().previewUrl)
    }
    @Test fun prefersSmallThumbnailAndKeepsMediumOutOfOriginalCandidates() {
        val item=didl("""<item id="one"><dc:title>Photo</dc:title><res size="40000" protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_MED">http://192.168.122.1/medium.JPG</res><res size="1200" protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN">http://192.168.122.1/thumb.JPG</res><res size="9000000" protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG">http://192.168.122.1/original.JPG</res></item>""")
        val parsed=SonyHttpGallery.parseBrowse(response(item),base,gateway).items.single()
        assertTrue(parsed.previewUrl!!.endsWith("thumb.JPG"));assertTrue(parsed.url!!.endsWith("original.JPG"))
    }
    @Test fun rawOriginalWinsOverLargerJpegRepresentation() {
        val item=didl("""<item id="raw"><dc:title>Photo</dc:title><res size="9000" protocolInfo="http-get:*:image/jpeg:*">http://192.168.122.1/preview.JPG</res><res size="8000" protocolInfo="http-get:*:image/x-sony-arw:*">http://192.168.122.1/original.JPG</res></item>""")
        val parsed=SonyHttpGallery.parseBrowse(response(item),base,gateway).items.single()
        assertEquals(8000L,parsed.size);assertEquals("original.ARW",parsed.name)
        assertTrue(parsed.url!!.endsWith("original.JPG"))
    }
    @Test fun octetStreamArwWinsButJpegArwUrlDoesNotImplyRaw() {
        val item=didl("""<item id="raw"><dc:title>Photo.ARW</dc:title><res size="9000" protocolInfo="http-get:*:image/jpeg:*">http://192.168.122.1/converted.ARW</res><res size="8000" protocolInfo="http-get:*:application/octet-stream:*">http://192.168.122.1/original.ARW</res></item>""")
        val parsed=SonyHttpGallery.parseBrowse(response(item),base,gateway).items.single()
        assertEquals("original.ARW",parsed.name);assertEquals(8000L,parsed.size)
    }
    private val gateway = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 122, 1))
    private val base = URI("http://192.168.122.1:64321/description.xml")
    private fun description(manufacturer: String = "Sony Corporation", model: String = "ILCE-6400", url: String = "/upnp/control/ContentDirectory") =
        """<root xmlns="urn:schemas-upnp-org:device-1-0"><device><manufacturer>$manufacturer</manufacturer><modelName>$model</modelName><serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>$url</controlURL></service></serviceList></device></root>"""
    private fun response(didl: String, returned: Int = 1, total: Int = returned): String {
        val escaped = didl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><Result>$escaped</Result><NumberReturned>$returned</NumberReturned><TotalMatches>$total</TotalMatches></u:BrowseResponse></s:Body></s:Envelope>"""
    }
    private fun didl(items: String) = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/">$items</DIDL-Lite>"""
    private fun mustReject(action: () -> Unit) { try { action(); fail("Expected rejection") } catch (_: IOException) { } }

    @Test fun discoversOnlyVerifiedCameraAndResolvesItsControlUrl() {
        val found = SonyHttpGallery.parseDescription(description(), base, gateway)
        assertEquals("ILCE-6400", found.model)
        assertEquals("http://192.168.122.1:64321/upnp/control/ContentDirectory", found.control.toString())
        mustReject { SonyHttpGallery.parseDescription(description(model = "OTHER"), base, gateway) }
        mustReject { SonyHttpGallery.parseDescription(description(manufacturer = "Other"), base, gateway) }
        mustReject { SonyHttpGallery.parseDescription(description(url = "http://192.168.122.2/control"), base, gateway) }
    }

    @Test fun rejectsCloudCredentialsAndWrongGatewayWithoutResolvingDns() {
        for (url in listOf("https://192.168.122.1/file", "http://example.com/file", "http://user:pass@192.168.122.1/file", "http://192.168.122.2/file", "http://192.168.122.1/file#frag", "http://192.168.122.1:0/file")) {
            mustReject { SonyHttpGallery.localUri(URI(url), gateway) }
        }
    }

    @Test fun parsesFolderAndFullSizeFileWhileIgnoringThumbnail() {
        val page = didl("""<container id="folder-1"><dc:title>2026-11-07</dc:title></container><item id="item-1"><dc:title>DSC0001.JPG</dc:title><res size="123" protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN">http://192.168.122.1/thumb.JPG</res><res size="24000000" protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG">http://192.168.122.1/DSC0001.JPG</res></item>""")
        val parsed = SonyHttpGallery.parseBrowse(response(page, 2, 42), base, gateway)
        assertEquals(42, parsed.total)
        assertEquals(2, parsed.items.size)
        assertTrue(parsed.items[0].folder)
        assertEquals("folder-1", parsed.items[0].id)
        assertEquals(24000000L, parsed.items[1].size)
        assertEquals("http://192.168.122.1/DSC0001.JPG", parsed.items[1].url)
    }

    @Test fun jpegRepresentationNeverInheritsArwTitleOrArwUrl() {
        val item = didl("""<item id="1"><dc:title>DSC0001.ARW</dc:title><res size="1234567" protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG">http://192.168.122.1/DSC0001.ARW</res></item>""")
        val parsed = SonyHttpGallery.parseBrowse(response(item), base, gateway).items.single()
        assertEquals("DSC0001.JPG", parsed.name)
        assertNotNull(parsed.url)
    }

    @Test fun actualArwResourceKeepsRawFilenameAndOriginalLength() {
        val item = didl("""<item id="1"><dc:title>Photo</dc:title><res size="25000000" protocolInfo="http-get:*:image/x-sony-arw:*">http://192.168.122.1/DSC0002.ARW</res></item>""")
        val parsed = SonyHttpGallery.parseBrowse(response(item), base, gateway).items.single()
        assertEquals("DSC0002.ARW", parsed.name)
        assertEquals(25000000L, parsed.size)
    }

    @Test fun missingSizePreviewAndExternalResourcesAreNotDownloads() {
        val item = didl("""<item id="1"><dc:title>Only preview</dc:title><res protocolInfo="http-get:*:image/jpeg:*">http://192.168.122.1/unknown.JPG</res><res size="500" protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_SM">http://192.168.122.1/small.JPG</res><res size="20000000" protocolInfo="http-get:*:image/jpeg:*">http://198.51.100.1/original.JPG</res></item>""")
        val parsed = SonyHttpGallery.parseBrowse(response(item), base, gateway).items.single()
        assertEquals(-1L, parsed.size)
        assertNull(parsed.url)
    }

    @Test fun escapesContainerIdentifierAndRequestsPage() {
        val request = SonyHttpGallery.browseEnvelope("a&<b", 40, 40, "urn:schemas-upnp-org:service:ContentDirectory:1")
        assertTrue(request.contains("<ObjectID>a&amp;&lt;b</ObjectID>"))
        assertTrue(request.contains("<StartingIndex>40</StartingIndex>"))
        assertTrue(request.contains("<RequestedCount>40</RequestedCount>"))
        assertTrue(request.contains("<BrowseFlag>BrowseDirectChildren</BrowseFlag>"))
    }

    @Test fun rejectsDtdSoapFaultAndInconsistentReturnedCount() {
        mustReject { SonyHttpGallery.parseDescription("<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///private'>]><root>&x;</root>", base, gateway) }
        mustReject { SonyHttpGallery.parseBrowse("<Envelope><Fault>Denied</Fault></Envelope>", base, gateway) }
        mustReject { SonyHttpGallery.parseBrowse(response(didl(""), returned = 1), base, gateway) }
    }
    @Test fun genericUpnpModelNameCanUseExactSonyCameraModelNumber() {
        val xml=description(model="Wireless Digital Imaging Device").replace("</modelName>","</modelName><modelNumber>ILCE-6400</modelNumber>")
        assertEquals("ILCE-6400",SonyHttpGallery.parseDescription(xml,base,gateway).model)
        mustReject { SonyHttpGallery.parseDescription(xml.replace("Sony Corporation","Other"),base,gateway) }
        mustReject { SonyHttpGallery.parseDescription(xml.replace("Wireless Digital Imaging Device","ILCE-7M4"),base,gateway) }
        mustReject { SonyHttpGallery.parseDescription(xml.replace("modelNumber","friendlyName"),base,gateway) }
    }
    @Test fun descriptionDiagnosticsContainCountsWithoutPrivateDeviceMetadata() {
        val xml=description().replace("</modelName>","</modelName><friendlyName>private-device-name</friendlyName><serialNumber>private-serial</serialNumber>")
        val shape=SonyHttpGallery.descriptionShape(xml)
        assertEquals("sony_devices=1 model_names=1 model_numbers=0 content_services=1",shape)
        assertFalse(shape.contains("private"));assertFalse(shape.contains("192.168"))
    }
    @Test fun otherSonyModelsAreDiscoveredButKnownSelectedModelCannotBeSwapped() {
        assertEquals("ILCE-7M3",SonyHttpGallery.parseDescription(description(model="ILCE-7M3"),base,gateway,"ILCE-7M3").model)
        mustReject { SonyHttpGallery.parseDescription(description(model="ILCE-7M3"),base,gateway,"ILCE-6400") }
        mustReject { SonyHttpGallery.parseDescription(description(model="ILCE-7M3").replace("</modelName>","</modelName><modelNumber>ILCE-6400</modelNumber>"),base,gateway) }
    }
    @Test fun observedA6400SonyImagingDeviceProfileUsesMatchingCameraLabel() {
        // Sanitized synthetic XML follows code42's observed description fields.
        val xml=description(model="SonyImagingDevice").replace("<manufacturer>","<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType><friendlyName>ILCE-6400</friendlyName><manufacturer>")
        assertEquals("ILCE-6400",SonyHttpGallery.parseDescription(xml,base,gateway).model)
        mustReject { SonyHttpGallery.parseDescription(xml.replace("Sony Corporation","Other"),base,gateway) }
        mustReject { SonyHttpGallery.parseDescription(xml.replace("<friendlyName>ILCE-6400","<friendlyName>Other camera"),base,gateway,"ILCE-6400") }
        mustReject { SonyHttpGallery.parseDescription(xml.replace("SonyImagingDevice","GenericDevice"),base,gateway) }
        mustReject { SonyHttpGallery.parseDescription(xml.replace("MediaServer:1","MediaRenderer:1"),base,gateway) }
        mustReject { SonyHttpGallery.parseDescription(xml.replace("/upnp/control/ContentDirectory","http://192.168.122.2/control"),base,gateway) }
    }
}
