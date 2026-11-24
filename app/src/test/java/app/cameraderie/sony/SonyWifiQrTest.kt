package app.cameraderie.sony

import org.junit.Assert.*
import org.junit.Test
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.QRCodeReader

class SonyWifiQrTest {
    private val text="WIFI:T:WPA;S:DIRECT-test:ILCE-6400;P:test1234;;"
    @Test fun standardCameraWifiFieldsAreParsedWithoutJoiningAnything() {
        val result=SonyWifiQr.parse(text)!!
        assertEquals("DIRECT-test:ILCE-6400",result.ssid);assertEquals("test1234",result.password)
        assertFalse(result.toString().contains("test1234"));assertFalse(result.toString().contains("DIRECT"))
    }
    @Test fun fieldOrderAndEscapedSeparatorsArePreserved() {
        val result=SonyWifiQr.parse("WIFI:P:test\\;1234;H:false;S:DIRECT-test;T:WPA2;;")!!
        assertEquals("test;1234",result.password)
    }
    @Test fun unsupportedAmbiguousAndUntrustedCodesNeverBecomeCredentials() {
        for(raw in listOf("https://example.com/", "0000SonyQR",text.replace("WPA","WEP"),text.replace("WPA","WPA2-EAP"),text.replace("DIRECT-test:","Home:"),text.replace("test1234","short"),text.replace("P:test1234;","P:test1234;P:other123;"),text.dropLast(1),text+"garbage",text.replace("S:DIRECT","I:secret;S:DIRECT"),text.replace("T:WPA;","T:WPA;H:true;"))) assertNull(SonyWifiQr.parse(raw))
    }
    @Test fun oversizedAndControlCharacterValuesAreRejected() {
        assertNull(SonyWifiQr.parse(text.replace("test1234","a".repeat(64))))
        assertNull(SonyWifiQr.parse(text.replace("DIRECT-test:","DIRECT-"+"a".repeat(40))))
        assertNull(SonyWifiQr.parse(text.replace("test1234","test\n1234")))
    }
    @Test fun actualQrEncoderAndDecoderRoundTripIntoCameraCredentials() {
        val matrix=QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,360,360)
        val pixels=IntArray(360*360) { i -> if(matrix[i%360,i/360]) -16777216 else -1 }
        val decoded=QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(360,360,pixels))),mapOf(DecodeHintType.PURE_BARCODE to true))
        assertEquals("test1234",SonyWifiQr.parse(decoded.text)!!.password)
    }
    @Test fun sonyW01BuildsTheArtifactConfirmedSsidWithoutRetainingMac() {
        val result=SonyWifiQr.parse("W01:S:Ab12;P:sample123;C:ILCE-6400;M:001122AABBCC;")!!
        assertEquals("DIRECT-Ab12:ILCE-6400",result.ssid);assertEquals("sample123",result.password)
        assertFalse(result.toString().contains("001122"));assertFalse(result.toString().contains("sample123"))
    }
    @Test fun sonyW01RejectsMissingDuplicateUnsafeOrUnvalidatedFields() {
        val sample="W01:S:Ab12;P:sample123;C:ILCE-6400;M:001122AABBCC;"
        for(bad in listOf(sample.replace("S:Ab12;",""),sample.replace("P:sample123;",""),sample.replace("M:001122AABBCC;",""),sample.replace("S:Ab12;","S:Ab12;S:Cd34;"),sample.replace("Ab12","Ab12/"),sample.replace("001122AABBCC","invalid"),sample.replace("sample123","short"),sample.dropLast(1),sample.replace("W01:","W02:"))) assertNull(SonyWifiQr.parse(bad))
    }
    @Test fun sonyW01CameraNamesAreNotRestrictedToA6400() {
        for(name in listOf("ILCE-7M3","ILCE-7M4","DSC-RX100M7","ZV-E1","My Camera")) {
            val raw="W01:S:Tst1;P:example123;C:$name;M:001122AABBCC;"
            val parsed=SonyWifiQr.parse(raw)!!
            assertEquals("DIRECT-Tst1:$name",parsed.ssid);assertEquals(name,parsed.cameraName)
        }
    }
    @Test fun genericModelSupportDoesNotAllowUnsafeOrOversizedSsidLabels() {
        for(name in listOf("","bad\nname","a".repeat(21))) assertNull(SonyWifiQr.parse("W01:S:Tst1;P:example123;C:$name;M:001122AABBCC;"))
    }
    @Test fun sonyW01ImageRoundTripDecodesIntoCameraNetwork() {
        val raw="W01:S:Cd34;P:sample456;C:ILCE-6400;M:AABBCCDDEEFF;"
        val matrix=QRCodeWriter().encode(raw,BarcodeFormat.QR_CODE,360,360)
        val pixels=IntArray(360*360) { i -> if(matrix[i%360,i/360]) -16777216 else -1 }
        val decoded=QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(360,360,pixels))),mapOf(DecodeHintType.PURE_BARCODE to true))
        assertEquals("DIRECT-Cd34:ILCE-6400",SonyWifiQr.parse(decoded.text)!!.ssid)
    }
}
