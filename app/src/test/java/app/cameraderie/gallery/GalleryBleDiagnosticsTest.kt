package app.cameraderie.gallery

import app.cameraderie.gallery.protocol.*
import org.junit.Assert.*
import org.junit.Test

class GalleryBleDiagnosticsTest {
    @Test fun boundedDiagnosticsRetainOnlyMetadataEvenForSecretBearingFrames() {
        val diagnostics=GalleryBleDiagnostics()
        repeat(50) { i ->
            diagnostics.notification(500,0x55,i==0)
            diagnostics.frame(DumlFrame(7,2,i,0xC0,7,i,"secret-network-password-and-identity".toByteArray()))
        }
        diagnostics.issued();diagnostics.completed(0)
        val summary=diagnostics.summary()
        assertTrue(summary.contains("rx=50 early=1"));assertTrue(summary.contains("frames=50 tx=1 callbacks=1 status=0"))
        assertFalse(summary.contains("secret"));assertFalse(summary.contains("7/2/192/7/49"))
    }
    @Test fun earlyDeviceInfoSurvivesArmingWithoutAuthorizingBeforeProtocolStart() {
        val inbox=GalleryBleInbox();val sent=mutableListOf<DumlFrame>()
        val identity=GalleryIdentity.load(object: GalleryIdentity.Store { override fun read()="a".repeat(32);override fun save(value:String)=true })
        val engine=GalleryBleHandshake(identity,{0},{sent.add(it);true},object: GalleryBleHandshake.Listener {
            override fun phaseChanged(phase:GalleryBleHandshake.Phase) {}
            override fun credentialsReady(credentials:CameraWifiCredentials) { fail("Early input cannot authorize Wi-Fi") }
            override fun failed(reason:GalleryBleHandshake.Failure) { fail(reason.name) }
        })
        assertTrue(inbox.offer(DumlFrame(7,2,1234,0x40,0,0x81,byteArrayOf())))
        assertTrue(sent.isEmpty());engine.start()
        while(true) engine.accept(inbox.next() ?: break)
        assertEquals(2,sent.size);val reply=sent.last()
        assertEquals(1234,reply.sequence);assertEquals(0xC0,reply.flags);assertEquals(0x81,reply.commandId)
        assertEquals(62,reply.payload.size);assertEquals(GalleryBleHandshake.Phase.SESSION_OPEN,engine.phase)
    }
    @Test fun earlyInboxHasABoundAndCanBeClearedBeforeAnotherAttempt() {
        val inbox=GalleryBleInbox(1);val frame=DumlFrame(7,2,1,0x40,0,0x81,byteArrayOf())
        assertTrue(inbox.offer(frame));assertFalse(inbox.offer(frame));inbox.clear();assertNull(inbox.next())
        assertTrue(inbox.offer(frame));assertSame(frame,inbox.next());assertNull(inbox.next())
    }
}
