package app.cameraderie.core

import app.gripbridge.core.DjiFirmwareInfo
import app.gripbridge.core.DjiProtocol
import app.gripbridge.core.DjiSession
import org.junit.Assert.*
import org.junit.Test

class DjiFirmwareTest {
    // Synthetic documented-format response, not a camera capture.
    private fun response(tail: ByteArray="SDK-V1.1\u0000Synthetic camera\u000001.02.03.04\u0000".toByteArray()): ByteArray {
        val head=ByteArray(18); "SYNTHETIC".toByteArray().copyInto(head,2); return head+tail
    }
    private class Rig {
        var time=100L
        var writeOk=true
        val sent=mutableListOf<DjiProtocol.Frame>()
        val s=DjiSession(123,ByteArray(6),{time},{b,done->sent+=DjiProtocol.decode(b)!!; done(writeOk)},{},{})
        fun auth() { s.begin(false,1234); s.receive(DjiProtocol.Frame(2,40,0,0x19,DjiProtocol.connection(0xFF55,ByteArray(6),2,0))) }
        fun status(power: Int=0) { val b=ByteArray(38); b[0]=1;b[1]=1;b[28]=power.toByte(); s.receive(DjiProtocol.Frame(0,42,0x1D,2,b)) }
        fun reply(seq: Int=sent.last().sequence,payload: ByteArray) {s.receive(DjiProtocol.Frame(0x20,seq,0,0,payload))}
    }
    @Test fun printableDocumentedFieldsAreSeparated() {
        val info=DjiFirmwareInfo.parse(response())!!
        assertEquals("SYNTHETIC",info.product); assertEquals("SDK-V1.1",info.sdk)
        assertEquals("01.02.03.04",info.firmware); assertEquals("Synthetic camera",info.reportedName)
    }
    @Test fun malformedRejectedAndOversizedResponsesAreNotVersionInfo() {
        assertNull(DjiFirmwareInfo.parse(ByteArray(17))); assertNull(DjiFirmwareInfo.parse(ByteArray(1001)))
        val error=response();error[0]=2;assertNull(DjiFirmwareInfo.parse(error))
    }
    @Test fun binaryOrUnknownLayoutsNeverInventVersions() {
        val info=DjiFirmwareInfo.parse(response(byteArrayOf(1,2,3,0xFF.toByte())))!!
        assertNull(info.sdk); assertNull(info.firmware); assertNull(info.reportedName)
        assertFalse(info.versionText.any { it.code < 32 || it.code > 126 })
    }
    @Test fun queryRequiresFreshAwakeAuthorization() {
        val r=Rig();r.s.queryFirmware();assertTrue(r.sent.isEmpty())
        r.auth();r.status(3);val before=r.sent.size;r.s.queryFirmware();assertEquals(before,r.sent.size)
        r.status();r.time+=3001;r.s.queryFirmware();assertEquals(before,r.sent.size)
    }
    @Test fun onlyMatchingQueryResponseUpdatesVersions() {
        val r=Rig();r.auth();r.status();r.s.queryFirmware();val q=r.sent.last()
        assertEquals(0,q.set);assertEquals(0,q.id);assertTrue(q.payload.isEmpty())
        r.reply(q.sequence+1,response());assertTrue(r.s.firmwareBusy);assertNull(r.s.firmwareInfo)
        r.reply(q.sequence,response());assertFalse(r.s.firmwareBusy);assertNotNull(r.s.firmwareInfo)
    }
    @Test fun timeoutNeverRetriesAndDisconnectClearsDiagnostics() {
        val r=Rig();r.auth();r.status();r.s.queryFirmware();val before=r.sent.size
        r.time+=6001;r.s.tick();assertFalse(r.s.firmwareBusy);assertEquals(before,r.sent.size)
        r.reply(payload=response());assertNull(r.s.firmwareInfo)
        r.status();r.s.queryFirmware();r.reply(payload=response());assertNotNull(r.s.firmwareInfo)
        r.s.disconnect();assertNull(r.s.firmwareInfo);assertFalse(r.s.firmwareBusy)
    }
    @Test fun rejectedQueryAndFailedWriteDoNotClaimKnownVersions() {
        val r=Rig();r.auth();r.status();r.s.queryFirmware();val error=response();error[0]=1;r.reply(payload=error)
        assertNull(r.s.firmwareInfo);assertTrue(r.s.firmwareResult.contains("rejected"))
        r.writeOk=false;r.s.queryFirmware();assertFalse(r.s.firmwareBusy);assertTrue(r.s.firmwareResult.contains("write failed"))
    }
    @Test fun capturedAction6VersionTextKeepsLegacyProductSeparateFromModel() {
        val head=ByteArray(18); "DJI-ACTION4".toByteArray().copyInto(head,2)
        val info=DjiFirmwareInfo.parse(head+"SDK-v1.1 DEBUG AC203-03.04.80.15".toByteArray()+ByteArray(48))!!
        assertEquals("DJI-ACTION4",info.product);assertEquals("SDK-v1.1",info.sdk);assertEquals("03.04.80.15",info.firmware)
    }
    @Test fun expiredVersionResponseWithoutPeriodicTickDoesNotUpdateInfo() {
        val r=Rig();r.auth();r.status();r.s.queryFirmware();r.time+=6001;r.reply(payload=response())
        assertNull(r.s.firmwareInfo);assertFalse(r.s.firmwareBusy)
    }
}
