package app.cameraderie.gallery

import app.cameraderie.gallery.protocol.*
import org.junit.Assert.*
import org.junit.Test

class GalleryBleHandshakeTest {
    private class Store(var value: String?=null,var saves: Int=0,var success: Boolean=true): GalleryIdentity.Store {
        override fun read()=value
        override fun save(value: String): Boolean { saves++;if(success)this.value=value;return success }
    }
    private class Rig: GalleryBleHandshake.Listener {
        var time=0L
        val writes=mutableListOf<DumlFrame>()
        val phases=mutableListOf<GalleryBleHandshake.Phase>()
        val failures=mutableListOf<GalleryBleHandshake.Failure>()
        val ready=mutableListOf<CameraWifiCredentials>()
        var writable=true
        var onPhase: ((GalleryBleHandshake.Phase)->Unit)?=null
        val store=Store()
        val identity=GalleryIdentity.load(store) { "0123456789abcdef0123456789abcdef" }
        val engine=GalleryBleHandshake(identity,{time},{frame -> writes.add(frame);writable },this)
        override fun phaseChanged(phase: GalleryBleHandshake.Phase) { phases.add(phase);onPhase?.invoke(phase) }
        override fun credentialsReady(credentials: CameraWifiCredentials) { ready.add(credentials) }
        override fun failed(reason: GalleryBleHandshake.Failure) { failures.add(reason) }
        fun advance(ms: Long) { time+=ms;engine.tick() }
        fun start() { engine.start();advance(120) }
        fun last(set: Int,id: Int)=writes.last { it.commandSet==set && it.commandId==id }
        fun reply(to: DumlFrame,payload: ByteArray)=engine.accept(DumlFrame(to.receiver,to.sender,to.sequence,0xC0,to.commandSet,to.commandId,payload))
        fun paired() { start();reply(last(7,0x45),byteArrayOf(0,1));advance(900);advance(0) }
        fun creds(value: String): ByteArray = byteArrayOf(0,value.toByteArray().size.toByte())+value.toByteArray()
        fun complete() { paired();reply(last(7,7),creds("Camera-Test"));advance(500);reply(last(7,0x0E),creds("testpass123")) }
    }
    @Test fun stableIdentityIsDurableAndNotRotatedAcrossRetriesOrInstances() {
        val store=Store()
        val a=GalleryIdentity.load(store) { "a".repeat(32) }
        val b=GalleryIdentity.load(store) { error("Must reuse identity") }
        assertEquals(1,store.saves);assertArrayEquals(a.bytes(),b.bytes())
        assertFalse(a.toString().contains("a".repeat(32)))
    }
    @Test fun failedPersistenceOrCorruptIdentityFailsClosedWithoutRotating() {
        val bad=Store("not-an-identity")
        try { GalleryIdentity.load(bad);fail() } catch(_: IllegalStateException) {}
        assertEquals(0,bad.saves)
        try { GalleryIdentity.load(Store(success=false));fail() } catch(_: IllegalStateException) {}
    }
    @Test fun sessionWakePrecedesStableIdentityPairBy120msAndNoRspIsNotAuthorization() {
        val r=Rig();r.engine.start()
        assertEquals(1,r.writes.size)
        val wake=r.writes.single();assertEquals(0xF0,wake.receiver);assertEquals(0x40,wake.flags)
        assertArrayEquals(byteArrayOf(4,0),wake.payload)
        r.advance(119);assertEquals(1,r.writes.size)
        r.advance(1);val pair=r.last(7,0x45)
        assertEquals(GalleryBleHandshake.Phase.PAIRING,r.engine.phase)
        assertEquals(32,pair.payload[0].toInt());assertTrue(r.ready.isEmpty())
        assertArrayEquals(r.identity.bytes(),pair.payload.copyOfRange(1,33))
        // Action 6 reference token is protocol data, not the application name.
        assertArrayEquals(byteArrayOf(4,111,115,109,111),pair.payload.copyOfRange(33,pair.payload.size))
        assertEquals(38,pair.payload.size)
    }
    @Test fun pairingRetriesReuseIdentityHaveNewSequencesAndStopAfterThree() {
        val r=Rig();r.start();r.advance(2500);r.advance(3000)
        val pairs=r.writes.filter { it.commandSet==7 && it.commandId==0x45 }
        assertEquals(3,pairs.size);assertEquals(3,pairs.map { it.sequence }.toSet().size)
        pairs.forEach { assertArrayEquals(pairs[0].payload,it.payload) }
        r.advance(2500);assertEquals(listOf(GalleryBleHandshake.Failure.PAIRING_TIMEOUT),r.failures)
        val count=r.writes.size;r.advance(60000);assertEquals(count,r.writes.size)
    }
    @Test fun wrongSenderReceiverSequenceFlagsOrCommandCannotAdvancePairing() {
        val r=Rig();r.start();val p=r.last(7,0x45)
        for(frame in listOf(DumlFrame(1,2,p.sequence,0xC0,7,0x45,byteArrayOf(0,1)),
            DumlFrame(7,3,p.sequence,0xC0,7,0x45,byteArrayOf(0,1)),DumlFrame(7,2,p.sequence+1,0xC0,7,0x45,byteArrayOf(0,1)),
            DumlFrame(7,2,p.sequence,0x40,7,0x45,byteArrayOf(0,1)),DumlFrame(7,2,p.sequence,0xC0,7,7,byteArrayOf(0,1)))) r.engine.accept(frame)
        assertEquals(GalleryBleHandshake.Phase.PAIRING,r.engine.phase)
    }
    @Test fun cameraApprovalRequestIsAckedWithZeroNotEchoedAndOnlyOnceAdvances() {
        val r=Rig();r.start();r.reply(r.last(7,0x45),byteArrayOf(0,2))
        r.advance(3000);assertEquals(1,r.writes.count { it.commandId==0x45 })
        val request=DumlFrame(7,2,1234,0x40,7,0x46,byteArrayOf(1))
        r.engine.accept(request)
        val ack=r.writes.last();assertEquals(0xC0,ack.flags);assertEquals(1234,ack.sequence)
        assertArrayEquals(byteArrayOf(0),ack.payload)
        assertEquals(GalleryBleHandshake.Phase.WIFI_START,r.engine.phase)
        r.engine.accept(request);assertEquals(2,r.writes.count { it.commandId==0x46 })
        assertEquals(1,r.phases.count { it==GalleryBleHandshake.Phase.WIFI_START })
    }
    @Test fun unsolicitedApprovalCannotAuthorizeANewSession() {
        val r=Rig();r.engine.accept(DumlFrame(7,2,3,0x40,7,0x46,byteArrayOf(1)))
        r.engine.start();r.engine.accept(DumlFrame(7,2,3,0x40,7,0x46,byteArrayOf(1)))
        assertEquals(GalleryBleHandshake.Phase.SESSION_OPEN,r.engine.phase)
    }
    @Test fun approvalTimeoutAndDenialAreDistinctFromMissingPairResponse() {
        val a=Rig();a.start();a.reply(a.last(7,0x45),byteArrayOf(0,2));a.advance(60000)
        assertEquals(listOf(GalleryBleHandshake.Failure.APPROVAL_TIMEOUT),a.failures)
        val b=Rig();b.start();b.reply(b.last(7,0x45),byteArrayOf(0,2));b.engine.accept(DumlFrame(7,2,1,0x40,7,0x46,byteArrayOf(0)))
        assertEquals(listOf(GalleryBleHandshake.Failure.PAIRING_REJECTED),b.failures)
    }
    @Test fun malformedPairResponsesNeverAuthorize() {
        for(bytes in listOf(byteArrayOf(),byteArrayOf(1),byteArrayOf(1,1),byteArrayOf(0,1,0),byteArrayOf(0,0))) {
            val r=Rig();r.start();r.reply(r.last(7,0x45),bytes)
            assertEquals(GalleryBleHandshake.Phase.FAILED,r.engine.phase)
        }
    }
    @Test fun knownDeviceInfoRequestsGetDocumentedBodyUnknownRequestsGetNoFakeAck() {
        val r=Rig();r.start();val n=r.writes.size
        r.engine.accept(DumlFrame(0x48,2,9,0x40,0,0x81,byteArrayOf()))
        val reply=r.writes.last();assertEquals(0x48,reply.receiver);assertEquals(9,reply.sequence)
        assertEquals(62,reply.payload.size);assertArrayEquals(byteArrayOf(0,0x41,0x50,0x50),reply.payload.copyOf(4))
        r.engine.accept(DumlFrame(7,2,10,0x40,0x66,0x12,byteArrayOf(1)))
        assertEquals(n+1,r.writes.size)
    }
    @Test fun completeFlowRequiresBothMatchedCredentialsAndOnlyThenReportsReady() {
        val r=Rig();r.paired();assertTrue(r.ready.isEmpty())
        val ssid=r.last(7,7);r.reply(ssid,r.creds("Camera-Test"));assertTrue(r.ready.isEmpty())
        r.advance(499);assertFalse(r.writes.any { it.commandId==0x0E })
        r.advance(1);r.reply(r.last(7,0x0E),r.creds("testpass123"))
        assertEquals(1,r.ready.size);assertEquals(GalleryBleHandshake.Phase.READY,r.engine.phase)
        r.ready.single().use { name,pass -> assertEquals("Camera-Test",name);assertEquals("testpass123",pass) }
        assertFalse(r.ready.single().toString().contains("testpass"))
        r.reply(ssid,r.creds("Different"));assertEquals(1,r.ready.size)
    }
    @Test fun keepaliveContinuesAfterReadyAndCloseMakesAllCallbacksInert() {
        val r=Rig();r.complete();val n=r.writes.count { it.commandId==0x2B }
        r.advance(1000);assertEquals(n+1,r.writes.count { it.commandId==0x2B })
        assertArrayEquals(byteArrayOf(1,1),r.last(0,0x2B).payload)
        r.engine.close();val count=r.writes.size;r.advance(90000)
        r.engine.accept(DumlFrame(7,2,123,0x40,7,0x46,byteArrayOf(1)));r.engine.transportLost()
        assertEquals(count,r.writes.size);assertEquals(1,r.ready.size);assertTrue(r.failures.isEmpty())
    }
    @Test fun retryOnlyOutstandingCredentialWithoutRepeatingPairingOrWake() {
        val r=Rig();r.paired();val ssid=r.last(7,7);r.reply(ssid,r.creds("Camera-Test"));r.advance(500)
        r.advance(2500);r.advance(4000)
        assertEquals(3,r.writes.count { it.commandId==0x0E })
        assertEquals(1,r.writes.count { it.commandId==0x45 })
        assertEquals(1,r.writes.count { it.commandSet==0x53 })
        assertEquals(1,r.writes.count { it.commandSet==7 && it.commandId==7 })
        r.advance(4500);assertEquals(listOf(GalleryBleHandshake.Failure.CREDENTIAL_TIMEOUT),r.failures)
    }
    @Test fun localWriteFailureNeverEmitsCredentialsOrContinuesProtocol() {
        val r=Rig();r.writable=false;r.engine.start();r.advance(100000)
        assertEquals(listOf(GalleryBleHandshake.Failure.WRITE_FAILED),r.failures)
        assertEquals(1,r.writes.size);assertTrue(r.ready.isEmpty())
    }
    @Test fun credentialDecoderRejectsStatusLengthUtf8ControlsAndNonzeroTail() {
        val good=byteArrayOf(0,3,65,66,67)
        assertEquals("ABC",GalleryBleHandshake.parseCredentialReply(good+byteArrayOf(0,0)))
        for(bytes in listOf(byteArrayOf(0),byteArrayOf(0,0),byteArrayOf(2,3,65,66,67),byteArrayOf(0,4,65,66,67),byteArrayOf(0,2,0xC0.toByte(),0xAF.toByte()),byteArrayOf(0,1,0),good+byteArrayOf(1)))
            assertNull(GalleryBleHandshake.parseCredentialReply(bytes))
    }
    @Test fun invalidSsidOrWpa2PassphraseFailsBeforeNetworkUse() {
        val bad=Rig();bad.paired();bad.reply(bad.last(7,7),bad.creds("X".repeat(33)))
        assertEquals(listOf(GalleryBleHandshake.Failure.INVALID_CREDENTIALS),bad.failures)
        for(pass in listOf("short","z".repeat(64),"pāssword123")) {
            val r=Rig();r.paired();r.reply(r.last(7,7),r.creds("Camera-Test"));r.advance(500);r.reply(r.last(7,0x0E),r.creds(pass))
            assertEquals(listOf(GalleryBleHandshake.Failure.INVALID_CREDENTIALS),r.failures);assertTrue(r.ready.isEmpty())
        }
    }
    @Test fun cancelFromPhaseListenerCannotEmitReadyOrWriteAfterClose() {
        lateinit var engine: GalleryBleHandshake
        var writes=0;var ready=0
        engine=GalleryBleHandshake(GalleryIdentity.load(Store()) { "b".repeat(32) },{0},{writes++;true},object: GalleryBleHandshake.Listener {
            override fun phaseChanged(phase: GalleryBleHandshake.Phase) { if(phase==GalleryBleHandshake.Phase.SESSION_OPEN) engine.close() }
            override fun credentialsReady(credentials: CameraWifiCredentials) { ready++ }
            override fun failed(reason: GalleryBleHandshake.Failure) {}
        })
        engine.start();assertEquals(0,writes);assertEquals(0,ready);assertEquals(GalleryBleHandshake.Phase.CLOSED,engine.phase)
    }
    @Test fun cancelAtReadyNotificationPreventsCredentialPublication() {
        val r=Rig();r.onPhase={ if(it==GalleryBleHandshake.Phase.READY) r.engine.close() }
        r.complete();assertEquals(GalleryBleHandshake.Phase.CLOSED,r.engine.phase);assertTrue(r.ready.isEmpty())
    }
    @Test fun failedStageDiagnosticRetainsDurationAttemptCountAndCategoryWithoutSecrets() {
        val r=Rig();r.start();r.advance(2500);r.advance(3000);r.advance(2500)
        val d=r.engine.diagnostic
        assertEquals(GalleryBleHandshake.Phase.PAIRING,d.stage);assertEquals(8000L,d.elapsedMs);assertEquals(3,d.attemptCount)
        assertEquals(GalleryBleHandshake.Failure.PAIRING_TIMEOUT,d.error);assertNull(d.responseCode)
        assertFalse(d.toString().contains(String(r.identity.bytes())))
        val b=Rig();b.start();b.reply(b.last(7,0x45),byteArrayOf(0,3))
        assertEquals(3,b.engine.diagnostic.responseCode)
    }
    @Test fun cancellationDuringFailureNotificationDoesNotPublishObsoleteFailure() {
        val r=Rig();r.writable=false;r.onPhase={ if(it==GalleryBleHandshake.Phase.FAILED) r.engine.close() }
        r.engine.start();assertEquals(GalleryBleHandshake.Phase.CLOSED,r.engine.phase);assertTrue(r.failures.isEmpty())
    }
    @Test fun malformedCredentialPayloadNeverBecomesDiagnosticResponseCodeOrText() {
        val r=Rig();r.paired();r.reply(r.last(7,7),"private-network-value".toByteArray())
        assertNull(r.engine.diagnostic.responseCode)
        assertFalse(r.engine.diagnostic.toString().contains("private-network"))
    }
}
