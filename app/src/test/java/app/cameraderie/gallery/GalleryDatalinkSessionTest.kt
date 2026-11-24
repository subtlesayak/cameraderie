package app.cameraderie.gallery

import app.cameraderie.gallery.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Synthetic network peer exercises the actual production session engine, not hardware. */
class GalleryDatalinkSessionTest {
    private class Rig: GalleryDatalinkSession.Listener {
        var time=0L;var owns=true
        var ackSubscriptions=true;var nonemptySubscriptionAck=true;var cameraStatus=true;var storagePush=true
        var statusHasStarted=false;var statusOnlyAfterPlayback=false;var statusPacketType=3;var statusPrefixBytes=12;var storageAvailable=true;var invalidStorage=false;var entersPlayback=true;var rejectPlayback: Int?=null
        var wrongReplySequence=false;var wrongSession=false;var handshakeReply=true;var handshakeReplyPayload:ByteArray?=null;var heartbeatEnabled=true;var probeFailure:IOException?=null
        var lostSubscriptionReceipts=0;var subscriptionRejection: Int?=null
        var closeWhenReady=true;var exitWhenReady=false;var exitStatus=true;var losePlaybackAfterReady=false
        var loseStorageAfterReady=false;var stopStorageAfterReady=false;var changeStoresAfterReady=false
        val exits=mutableListOf<GalleryDatalinkSession.PlaybackExit>()
        var playback=false;var readyAt=Long.MAX_VALUE
        var onStage: ((GalleryDatalinkSession.Stage)->Unit)?=null
        val frames=mutableListOf<DumlFrame>();val sent=mutableListOf<ByteArray>();val incoming=ArrayDeque<ByteArray>()
        val stages=mutableListOf<GalleryDatalinkSession.Stage>();val failures=mutableListOf<GalleryDatalinkSession.Diagnostic>()
        val registrationAttempts=mutableListOf<Int>()
        val stores=mutableListOf<List<GalleryCameraEvidence.Storage>>()
        var probeCount=0;var udpCount=0;var closeCount=0;var open=false;var probingFrame: DumlFrame?=null
        var nextStatus=0L
        var listHandler:((DumlFrame)->Unit)?=null
        var favouriteHandler:((DumlFrame)->Unit)?=null
        var deleteHandler:((DumlFrame)->Unit)?=null
        var writeResult:GalleryDatalinkSession.MediaWriteResult?=null
        var readyAction:(()->Unit)?=null
        var storagesAction:(()->Unit)?=null
        var mediaFinishedAction:((Long,GalleryListBatch.Result)->Unit)?=null
        var mediaCommandAction:((GalleryListBatch.Role)->Unit)?=null
        var removeSdDuringListing=false
        var changeSdCapacityBeforeSend=false
        val mediaResults=mutableListOf<Pair<Long,GalleryListBatch.Result>>()
        val mediaCommands=mutableListOf<Triple<GalleryListBatch.Role,Int,Long>>()
        val mediaProgress=mutableListOf<List<GalleryListBatch.Progress>>()
        fun media(counter:Int,bytes:ByteArray) {
            fun frame(type:Int,chunk:Int=0,data:ByteArray=byteArrayOf()) {
                val payload=ByteArray(10).also { it[0]=0x4A;it[1]=type.toByte();it[4]=counter.toByte();it[6]=chunk.toByte();it[7]=(chunk ushr 8).toByte() }+data
                push(DumlFrame(1,2,900+chunk,0,0,0x27,payload))
            }
            frame(4);bytes.asList().chunked(384).forEachIndexed { index,part -> frame(1,index,part.toByteArray()) };frame(3)
        }
        val identity=GalleryIdentity.load(object: GalleryIdentity.Store {
            override fun read()="0123456789abcdef0123456789abcdef"
            override fun save(value: String)=error("Should reuse identity")
        })
        private fun wrap(type: Int,body: ByteArray)=DumlDatagram(if(wrongSession) 0x1235 else 0x1234,0,type,body).encode()
        fun push(frame: DumlFrame) {
            val status=frame.commandSet==2 && frame.commandId in setOf(0x80,0xDC)
            incoming.add(wrap(if(status) statusPacketType else 3,ByteArray(if(status) statusPrefixBytes else 12)+frame.encode()))
        }
        fun heartbeat() {
            if(!heartbeatEnabled) return
            val b=ByteArray(26);b[1]=0x30;b[2]=0x10;b[10]=0x20;incoming.add(wrap(1,b))
        }
        fun status() {
            if(statusOnlyAfterPlayback && !playback && !statusHasStarted) return
            statusHasStarted=true
            if(cameraStatus) push(DumlFrame(1,2,9,0,2,0x80,ByteArray(13).also { if(playback)it[3]=0x40 }))
            if(storagePush) {
                val b=ByteArray(40);b[2]=2;b[5]=2
                if(storageAvailable) { b[6]=100;b[10]=if(invalidStorage) 101 else 50;b[24]=100;b[28]=50 }
                if(changeSdCapacityBeforeSend && readyAt!=Long.MAX_VALUE) { b[6]=120;b[10]=70 }
                if((changeStoresAfterReady || removeSdDuringListing) && readyAt!=Long.MAX_VALUE) { b[6]=0;b[10]=0 }
                push(DumlFrame(1,2,10,0,2,0xDC,b))
            }
        }
        val wire=object: GalleryDatalinkSession.Wire {
            override fun probe(pairingFrame: ByteArray) {
                probeCount++;probingFrame=DumlFrame.decode(pairingFrame)
                probeFailure?.let { throw it }
                time+=400
            }
            override fun openUdp() { udpCount++;open=true }
            override fun send(packet: ByteArray) {
                check(open);sent.add(packet.copyOf())
                val p=DumlDatagram.decode(packet,0x1234)!!
                if(p.type==0 && handshakeReply) { incoming.add(wrap(0,handshakeReplyPayload ?: p.payload));heartbeat() }
                if(p.type==5) {
                    val f=DumlFrame.decode(p.payload.copyOfRange(12,p.payload.size))!!;frames.add(f)
                    if(f.commandSet==0 && f.commandId==0x26)listHandler?.invoke(f)
                    if(f.commandSet==2 && f.commandId==0xBF)favouriteHandler?.invoke(f)
                    if(f.commandSet==0 && f.commandId==0x28)deleteHandler?.invoke(f)
                    if(f.commandSet==0 && f.commandId==0x99 && ackSubscriptions && lostSubscriptionReceipts--<=0) {
                        push(DumlFrame(0x28,2,if(wrongReplySequence)(f.sequence+1) and 65535 else f.sequence,0xC0,0,0x99,
                            subscriptionRejection?.let { byteArrayOf(it.toByte()) } ?: if(nonemptySubscriptionAck) ByteArray(10) else byteArrayOf()))
                    }
                    if(f.commandSet==2 && f.commandId==0x0C) {
                        push(DumlFrame(1,2,f.sequence,0xC0,2,0x0C,byteArrayOf((rejectPlayback ?: 0).toByte())))
                        if(rejectPlayback==null && entersPlayback) playback=f.payload.last().toInt()!=0
                        if(f.payload.last().toInt()!=0 || exitStatus) status()
                    }
                }
            }
            override fun receive(timeoutMs: Int): ByteArray? {
                time++
                check(time<90000) { "Synthetic session ran beyond bounded test deadline" }
                if(incoming.isNotEmpty()) return incoming.removeFirst()
                time+=timeoutMs
                if(time>=nextStatus) {
                    nextStatus=time+200
                    if(losePlaybackAfterReady && readyAt!=Long.MAX_VALUE && time>readyAt+1000) playback=false
                    if(readyAt!=Long.MAX_VALUE && time>readyAt+1000) {
                        if(loseStorageAfterReady) storageAvailable=false
                        if(stopStorageAfterReady) storagePush=false
                    }
                    heartbeat();status()
                }
                return if(incoming.isEmpty()) null else incoming.removeFirst()
            }
            override fun close() { if(open || closeCount==0) { closeCount++;open=false } }
        }
        val session=GalleryDatalinkSession(wire,identity,{time},{owns},this,DumlDatalinkState(0x1234,0x3000))
        override fun stageChanged(stage: GalleryDatalinkSession.Stage,attempt: Int) { stages.add(stage);if(stage==GalleryDatalinkSession.Stage.REGISTRATION)registrationAttempts.add(attempt);onStage?.invoke(stage) }
        override fun ready(storages: List<GalleryCameraEvidence.Storage>) {
            stores.add(storages);readyAt=time
            if(stores.size==1)readyAction?.invoke()
            storagesAction?.invoke()
            if(changeStoresAfterReady && stores.size>=2) session.close()
            if(exitWhenReady) { owns=false;session.requestExit() } else if(closeWhenReady) session.close()
        }
        override fun mediaCommand(ticketId:Long,role:GalleryListBatch.Role,counter:Int,sequence:Int,elapsedMs:Long) {
            mediaCommands.add(Triple(role,counter,elapsedMs));mediaCommandAction?.invoke(role)
        }
        override fun mediaProgress(ticketId:Long,stores:List<GalleryListBatch.Progress>) { mediaProgress.add(stores) }
        override fun mediaPagesFinished(ticketId:Long,result:GalleryListBatch.Result) { mediaResults.add(ticketId to result);mediaFinishedAction?.invoke(ticketId,result) }
        override fun mediaWriteFinished(ticketId:Long,result:GalleryDatalinkSession.MediaWriteResult) {
            writeResult=result;session.close()
        }
        override fun closed(playbackExit: GalleryDatalinkSession.PlaybackExit) { exits.add(playbackExit) }
        override fun failed(diagnostic: GalleryDatalinkSession.Diagnostic) { failures.add(diagnostic) }
    }
    @Test fun realEngineRegistersObservesPlaybackAndStorageThenReportsReady() {
        val r=Rig();r.session.run()
        assertEquals(1,r.probeCount);assertEquals(1,r.udpCount);assertEquals(1,r.closeCount)
        assertEquals(1,r.stores.size);assertTrue(r.failures.isEmpty())
        assertEquals(listOf(GalleryCameraEvidence.Store.SD,GalleryCameraEvidence.Store.INTERNAL),r.stores.single().map { it.store })
        assertTrue(r.readyAt>=2100)
        assertEquals(0x45,r.probingFrame!!.commandId)
        assertArrayEquals(r.identity.bytes(),r.probingFrame!!.payload.copyOfRange(1,33))
        assertArrayEquals(byteArrayOf(4,111,115,109,111),r.probingFrame!!.payload.copyOfRange(33,38))
        assertTrue(r.frames.any { it.receiver==0x48 && it.commandId==0x81 && it.flags==0x80 && it.payload.size==62 })
        assertEquals(2,r.frames.count { it.commandId==0x99 })
        assertTrue(r.sent.any { DumlDatagram.decode(it,0x1234)!!.type==4 })
    }
    @Test fun noRecordingResetOrDeletionCommandsExistInConnectionSequence() {
        val r=Rig();r.session.run()
        val allowed=setOf(0 to 0x81,0 to 0x88,3 to 0xDA,0 to 0x99,2 to 0x0C)
        assertTrue(r.frames.all { (it.commandSet to it.commandId) in allowed })
        assertFalse(r.frames.any { it.commandId==0x28 || it.commandId==0x19 || it.commandSet==0x1D })
    }
    @Test fun favouriteAcceptsOnlyProperReceiptAndNeverResendsAWrite() {
        val store=GalleryCameraEvidence.Store.INTERNAL
        val file=GalleryMediaPage.Media(GalleryMediaPage.Key(store,"DCIM/TEST/VIDEO.MP4"),
            "MISC/THM/TEST/VIDEO.scr",0x40104040L,100,3,1,false)
        for(code in listOf(null,0,0xD6)) {
            val rig=Rig();rig.closeWhenReady=false
            rig.readyAction={
                val context=rig.session.mediaContext!!
                assertNotNull(rig.session.requestFavourite(file,true,context))
                assertNull(rig.session.requestFavourite(file,true,context))
                assertNull(rig.session.requestMediaPages(mapOf(store to store.newestCursor),context))
            }
            rig.favouriteHandler={ frame ->
                assertEquals(1,frame.receiver)
                assertArrayEquals(byteArrayOf(1,1,0x40,0x40,0x10,0x40,1,0,0,0,0,1,0,0,0),frame.payload)
                rig.push(DumlFrame(1,2,frame.sequence,0x80,2,0xBF,byteArrayOf()))
                rig.push(DumlFrame(1,2,(frame.sequence+1) and 65535,0x80,2,0xBF,byteArrayOf(0)))
                if(code!=null)rig.push(DumlFrame(1,2,frame.sequence,0x80,2,0xBF,byteArrayOf(code.toByte())))
            }
            rig.session.run()
            assertEquals(1,rig.frames.count { it.commandSet==2 && it.commandId==0xBF })
            assertEquals(when(code) {
                null -> GalleryDatalinkSession.MediaWriteResult.UNCONFIRMED
                0 -> GalleryDatalinkSession.MediaWriteResult.ACKNOWLEDGED
                else -> GalleryDatalinkSession.MediaWriteResult.REJECTED
            },rig.writeResult)
        }
    }
    @Test fun favouriteDoesNotTransmitAfterStorageContextChangesOrIntentIsCanceled() {
        val store=GalleryCameraEvidence.Store.SD
        val file=GalleryMediaPage.Media(GalleryMediaPage.Key(store,"DCIM/TEST/VIDEO.MP4"),
            "MISC/THM/TEST/VIDEO.scr",0x100040L,100,3,1,true)
        for(cancel in listOf(false,true)) {
            val rig=Rig();rig.closeWhenReady=false
            rig.readyAction={
                val ticket=rig.session.requestFavourite(file,false,rig.session.mediaContext!!)!!
                if(cancel)ticket.cancel()
                else {
                    rig.removeSdDuringListing=true;rig.incoming.clear()
                    rig.cameraStatus=false;rig.status();rig.cameraStatus=true
                }
            }
            rig.session.run()
            assertEquals(GalleryDatalinkSession.MediaWriteResult.STALE,rig.writeResult)
            assertFalse(rig.frames.any { it.commandSet==2 && it.commandId==0xBF })
        }
    }
    @Test fun deleteRequiresCurrentUnambiguousRequestAndUsesOneCorrelatedReply() {
        val store=GalleryCameraEvidence.Store.INTERNAL
        val file=GalleryMediaPage.Media(GalleryMediaPage.Key(store,"DCIM/CLIPS/DJI_20261119102415_0274_D.MP4"),
            "MISC/THM/CLIPS/DJI_20261119102415_0274_D.scr",0x40104480L,100,3,1,false,1561409638L,true)
        for(reply in listOf(null,0,0xD6)) {
            val rig=Rig();rig.closeWhenReady=false
            rig.readyAction={
                val context=rig.session.mediaContext!!
                assertNull(rig.session.requestDelete(file.copy(singleFileRecord=false),context))
                assertNotNull(rig.session.requestDelete(file,context))
                assertNull(rig.session.requestDelete(file,context))
            }
            rig.deleteHandler={ frame ->
                assertEquals(1,frame.receiver)
                assertArrayEquals(byteArrayOf(1,0x80.toByte(),0x44,0x10,0x40,1,0,0,0,0,1,0,0,0,1,1,0,0),frame.payload)
                rig.push(DumlFrame(1,2,frame.sequence,0x80,0,0x28,byteArrayOf(0)))
                rig.push(DumlFrame(1,2,(frame.sequence+1) and 65535,0x80,0,0x28,byteArrayOf(0,0)))
                if(reply!=null)rig.push(DumlFrame(1,2,frame.sequence,0x80,0,0x28,byteArrayOf(0,reply.toByte())))
            }
            rig.session.run()
            assertEquals(1,rig.frames.count { it.commandSet==0 && it.commandId==0x28 })
            assertEquals(when(reply) {
                null -> GalleryDatalinkSession.MediaWriteResult.UNCONFIRMED
                0 -> GalleryDatalinkSession.MediaWriteResult.ACKNOWLEDGED
                else -> GalleryDatalinkSession.MediaWriteResult.REJECTED
            },rig.writeResult)
        }
    }
    @Test fun lackOfOwnershipPreventsEvenTheTcpProbe() {
        val r=Rig();r.owns=false;r.session.run()
        assertEquals(0,r.probeCount);assertEquals(0,r.udpCount);assertTrue(r.failures.isEmpty());assertTrue(r.stores.isEmpty())
    }
    @Test fun cancellationFromStageCallbackCannotSendSubsequentCommands() {
        val r=Rig();r.onStage={ if(it==GalleryDatalinkSession.Stage.REGISTRATION) r.session.close() }
        r.session.run();assertTrue(r.frames.isEmpty());assertTrue(r.failures.isEmpty());assertTrue(r.stores.isEmpty())
    }
    @Test fun registrationRequiresMatchedNonemptyReceipts() {
        for(configure in listOf<(Rig)->Unit>({it.ackSubscriptions=false},{it.nonemptySubscriptionAck=false},{it.wrongReplySequence=true})) {
            val r=Rig();configure(r);r.session.run()
            assertEquals(GalleryDatalinkSession.Stage.REGISTRATION,r.failures.single().stage)
            assertEquals(3,r.failures.single().attempt);assertEquals(GalleryDatalinkSession.Error.TIMEOUT,r.failures.single().error)
            assertTrue(r.stores.isEmpty());assertFalse(r.frames.any { it.commandId==0x0C })
        }
    }
    @Test fun playbackAckWithoutObservedModeDoesNotReportReady() {
        val r=Rig();r.entersPlayback=false;r.session.run()
        assertEquals(GalleryDatalinkSession.Stage.PLAYBACK,r.failures.single().stage)
        assertEquals(GalleryDatalinkSession.Error.TIMEOUT,r.failures.single().error)
        assertEquals(3,r.frames.count { it.commandSet==2 && it.commandId==0x0C && it.payload.last().toInt()==1 });assertTrue(r.stores.isEmpty())
    }
    @Test fun missingReceiptRetriesOnlyRegistrationWithoutRepeatingBleOrTcpBootstrap() {
        val r=Rig();r.lostSubscriptionReceipts=1;r.session.run()
        assertEquals(listOf(1,2),r.registrationAttempts)
        assertEquals(1,r.probeCount);assertEquals(1,r.udpCount);assertEquals(1,r.stores.size);assertTrue(r.failures.isEmpty())
    }
    @Test fun explicitSubscriptionRejectionIsNotARegistrationSuccess() {
        val r=Rig();r.subscriptionRejection=0xE0;r.session.run()
        val error=r.failures.single();assertEquals(GalleryDatalinkSession.Stage.REGISTRATION,error.stage)
        assertEquals(GalleryDatalinkSession.Error.REJECTED,error.error);assertEquals(0xE0,error.responseCode)
        assertTrue(r.stores.isEmpty())
    }
    @Test fun actualPlaybackRejectionHasItsOwnStageAndNumericResult() {
        val r=Rig();r.rejectPlayback=0xE0;r.session.run()
        val failure=r.failures.single();assertEquals(GalleryDatalinkSession.Stage.PLAYBACK,failure.stage)
        assertEquals(GalleryDatalinkSession.Error.REJECTED,failure.error);assertEquals(0xE0,failure.responseCode)
        assertEquals(1,r.frames.count { it.commandSet==2 && it.commandId==0x0C && it.payload.last().toInt()==1 })
    }
    @Test fun unavailableAndUnknownStorageCannotBecomeAnEmptySuccessfulLibrary() {
        val missing=Rig();missing.storageAvailable=false;missing.session.run()
        assertEquals(GalleryDatalinkSession.Error.STORAGE_UNAVAILABLE,missing.failures.single().error)
        for(configure in listOf<(Rig)->Unit>({it.storagePush=false},{it.invalidStorage=true})) {
            val r=Rig();configure(r);r.session.run()
            assertEquals(GalleryDatalinkSession.Stage.STORAGE,r.failures.single().stage)
            assertEquals(GalleryDatalinkSession.Error.TIMEOUT,r.failures.single().error);assertTrue(r.stores.isEmpty())
        }
    }
    @Test fun wrongSessionOrMissingSynReplyCannotCreateHandshakeEvidence() {
        for(configure in listOf<(Rig)->Unit>({it.wrongSession=true},{it.handshakeReply=false})) {
            val r=Rig();configure(r);r.session.run()
            assertEquals(GalleryDatalinkSession.Stage.REACHABILITY,r.failures.single().stage)
            assertEquals(20,r.failures.single().attempt);assertTrue(r.frames.isEmpty())
        }
    }
    @Test fun ioFailureIsReportedWithoutRawExceptionTextAndStillClosesResources() {
        val r=Rig();r.probeFailure=IOException("private network credentials and path");r.session.run()
        assertEquals(GalleryDatalinkSession.Error.TRANSPORT,r.failures.single().error)
        assertFalse(r.failures.single().toString().contains("private"));assertEquals(1,r.closeCount)
    }
    @Test fun bootstrapFailureReportsOnlySocketStepAndReasonAndNeverStartsUdp() {
        val r=Rig()
        val step=GalleryDatalinkSession.SocketStep.TCP_CONNECT
        val reason=GalleryDatalinkSession.SocketReason.REFUSED
        r.probeFailure=GalleryDatalinkSession.SocketFailure(step,reason)
        r.session.run()
        val diagnostic=r.failures.single()
        assertEquals(GalleryDatalinkSession.Stage.REACHABILITY,diagnostic.stage)
        assertEquals(step,diagnostic.socketStep);assertEquals(reason,diagnostic.socketReason)
        assertEquals(0,r.udpCount);assertEquals(1,r.closeCount)
        assertEquals(listOf(GalleryDatalinkSession.PlaybackExit.NOT_NEEDED),r.exits)
    }
    @Test fun losingPlaybackAfterReadyTerminatesTheSessionRatherThanClaimingItIsStillReady() {
        val r=Rig();r.closeWhenReady=false;r.losePlaybackAfterReady=true;r.session.run()
        assertEquals(1,r.stores.size);assertEquals(GalleryDatalinkSession.Error.CAMERA_STATE_LOST,r.failures.single().error)
        assertFalse(r.session.ready)
    }
    @Test fun unavailableStorageAfterReadyFailsInsteadOfRetainingInitialStores() {
        val r=Rig();r.closeWhenReady=false;r.loseStorageAfterReady=true;r.session.run()
        assertEquals(1,r.stores.size);assertEquals(GalleryDatalinkSession.Error.STORAGE_UNAVAILABLE,r.failures.single().error)
        assertFalse(r.session.ready)
    }
    @Test fun expiredStorageAfterReadyFailsEvenWhilePlaybackKeepsArriving() {
        val r=Rig();r.closeWhenReady=false;r.stopStorageAfterReady=true;r.session.run()
        assertEquals(1,r.stores.size);assertEquals(GalleryDatalinkSession.Error.CAMERA_STATE_LOST,r.failures.single().error)
        assertFalse(r.session.ready)
    }
    @Test fun changedAvailableStoresAreReportedWithoutReenteringPlayback() {
        val r=Rig();r.closeWhenReady=false;r.changeStoresAfterReady=true;r.session.run()
        assertEquals(2,r.stores.size);assertFalse(r.stores.last().first().available);assertTrue(r.stores.last().last().available)
        assertTrue(r.failures.isEmpty());assertEquals(1,r.frames.count { it.commandSet==2 && it.commandId==0x0C })
    }
    @Test fun connectionCannotBeReusedAfterCloseOrCompletion() {
        val r=Rig();r.session.run();val sent=r.sent.size
        try { r.session.run();fail() } catch(_: IllegalStateException) {}
        assertEquals(sent,r.sent.size)
    }
    @Test fun closingUnblocksAReceiveAndPreventsLateFailure() {
        val receiving=CountDownLatch(1);val unblock=CountDownLatch(1);val done=CountDownLatch(1)
        val closed=AtomicBoolean(false);var failures=0
        val wire=object: GalleryDatalinkSession.Wire {
            override fun probe(pairingFrame: ByteArray) {}
            override fun openUdp() {}
            override fun send(packet: ByteArray) {}
            override fun receive(timeoutMs: Int): ByteArray? { receiving.countDown();check(unblock.await(2,TimeUnit.SECONDS));throw IOException("closed") }
            override fun close() { closed.set(true);unblock.countDown() }
        }
        val identity=GalleryIdentity.load(object: GalleryIdentity.Store { override fun read()="a".repeat(32);override fun save(value: String)=true })
        val s=GalleryDatalinkSession(wire,identity,{System.nanoTime()/1_000_000},{true},object: GalleryDatalinkSession.Listener {
            override fun stageChanged(stage: GalleryDatalinkSession.Stage,attempt: Int) {}
            override fun ready(storages: List<GalleryCameraEvidence.Storage>) { fail() }
        override fun failed(diagnostic: GalleryDatalinkSession.Diagnostic) { failures++ }
        })
        val worker=thread { try { s.run() } finally { done.countDown() } }
        assertTrue(receiving.await(2,TimeUnit.SECONDS));s.close();assertTrue(done.await(2,TimeUnit.SECONDS));worker.join(1000)
        assertTrue(closed.get());assertEquals(0,failures)
    }
    @Test fun explicitExitAfterOwnershipQuarantineSendsOnlyPlaybackLeaveThenCloses() {
        val r=Rig();r.exitWhenReady=true;r.session.run()
        assertEquals(GalleryDatalinkSession.PlaybackExit.CONFIRMED,r.exits.single())
        assertArrayEquals(byteArrayOf(1,1,0,0),r.frames.last().payload)
        assertEquals(0x0C,r.frames.last().commandId);assertTrue(r.failures.isEmpty());assertEquals(1,r.closeCount)
    }
    @Test fun exitWithoutObservedPlaybackOffIsOnlySentAndBoundedNotConfirmed() {
        val r=Rig();r.exitWhenReady=true;r.exitStatus=false;r.cameraStatus=false
        // Disable status only after real readiness so setup still needs genuine evidence.
        r.cameraStatus=true;r.onStage={ if(it==GalleryDatalinkSession.Stage.READY) r.cameraStatus=false }
        r.session.run();assertEquals(GalleryDatalinkSession.PlaybackExit.SENT,r.exits.single())
        assertTrue(r.time-r.readyAt in 450..700);assertTrue(r.failures.isEmpty())
    }
    @Test fun immediateStopNeverSendsCleanupCommandsAfterCancellation() {
        val r=Rig();r.session.run()
        assertEquals(GalleryDatalinkSession.PlaybackExit.CANCELED,r.exits.single())
        assertEquals(1,r.frames.count {it.commandSet==2 && it.commandId==0x0C})
    }
    @Test fun validCameraSynDoesNotNeedToEchoTheProposedBase() {
        // Hardware code30: 20 valid matching-session SYNs, none echoing our base.
        // The pinned reference accepts pktType0; heartbeat still provides channel.
        for(payload in listOf(byteArrayOf(),byteArrayOf(0x78,0x56,0,0))) {
            val r=Rig();r.handshakeReplyPayload=payload;r.session.run()
            assertEquals(1,r.stores.size);assertTrue(r.failures.isEmpty())
            assertEquals(1,r.sent.count { DumlDatagram.decode(it,0x1234)!!.type==0 })
        }
    }
    @Test fun synAloneCannotRegisterWithoutAnObservedPeerHeartbeat() {
        val r=Rig();r.handshakeReplyPayload=byteArrayOf(0x78,0x56);r.heartbeatEnabled=false;r.session.run()
        assertTrue(r.stores.isEmpty());assertEquals(GalleryDatalinkSession.Stage.REACHABILITY,r.failures.single().stage)
        assertFalse(r.frames.any {it.commandId==0x81 || it.commandId==0x99})
    }
    @Test fun productionSessionFactoryMatchesCameraProfileRangesAndAlignment() {
        repeat(64) {
            val state=GalleryDatalinkSession.freshState()
            assertTrue(state.session in 0x1000 until 0xFFFE)
            assertTrue(state.base in 0x1000 until 0xF000);assertEquals(0,state.base and 7)
        }
    }
    @Test fun typeTwoStatusRegistersPlaybackAndStorageWithoutAssumingATwelveBytePrefix() {
        for(prefix in listOf(0,4,12)) {
            val r=Rig();r.statusPacketType=2;r.statusPrefixBytes=prefix;r.session.run()
            assertEquals(1,r.stores.size);assertTrue(r.failures.isEmpty())
        }
    }
    @Test fun exitConfirmationAlsoRecognizesTypeTwoPlaybackStatus() {
        val r=Rig();r.statusPacketType=2;r.statusPrefixBytes=4;r.exitWhenReady=true;r.session.run()
        assertEquals(GalleryDatalinkSession.PlaybackExit.CONFIRMED,r.exits.single());assertTrue(r.failures.isEmpty())
    }
    @Test fun registrationDoesNotWaitForStatusThatStartsOnlyAfterPlaybackRequest() {
        val r=Rig();r.statusOnlyAfterPlayback=true;r.session.run()
        assertEquals(listOf(1),r.registrationAttempts);assertEquals(1,r.stores.size)
        assertTrue(r.failures.isEmpty())
    }
    @Test fun registrationReceiptsWithoutAnyCameraStatusFailAtPlaybackAndNeverReady() {
        val r=Rig();r.cameraStatus=false;r.session.run()
        assertEquals(GalleryDatalinkSession.Stage.PLAYBACK,r.failures.single().stage)
        assertEquals(3,r.failures.single().attempt);assertTrue(r.stores.isEmpty())
        assertEquals(listOf(1),r.registrationAttempts)
    }
    @Test fun typeOneTelemetryAndWindowHeartbeatsShareAChannelWithoutLosingStatus() {
        val r=Rig();r.statusPacketType=1;r.statusPrefixBytes=4;r.statusOnlyAfterPlayback=true;r.exitWhenReady=true
        r.session.run()
        assertEquals(1,r.stores.size);assertTrue(r.failures.isEmpty())
        assertEquals(GalleryDatalinkSession.PlaybackExit.CONFIRMED,r.exits.single())
        assertTrue(r.sent.any { DumlDatagram.decode(it,0x1234)!!.type==4 })
    }
    private fun mediaFixture(name:String)=javaClass.classLoader!!.getResourceAsStream("gallery-fixtures/$name")!!.use { it.readBytes() }
    private fun replyWithFixtures(r:Rig) {
        r.listHandler={ frame ->
            r.push(DumlFrame(1,2,frame.sequence,0xC0,0,0x26,byteArrayOf(0)))
            if(frame.payload[1].toInt()==0) {
                val counter=frame.payload[4].toInt() and 255
                r.media(counter,mediaFixture(if(counter%2==1)"oa6_sd_3.bin" else "oa6_internal_2.bin"))
            }
        }
    }
    @Test fun pageRequestsUseTheLiveWorkerWhileAckPresenceAndTelemetryContinue() {
        val r=Rig();r.closeWhenReady=false;replyWithFixtures(r)
        var ticket:GalleryDatalinkSession.MediaTicket?=null
        r.readyAction={
            ticket=r.session.requestMediaPages(mapOf(GalleryCameraEvidence.Store.SD to 1L,GalleryCameraEvidence.Store.INTERNAL to 0x40000001L))
            assertNotNull(ticket);assertNull(r.session.requestMediaPages(mapOf(GalleryCameraEvidence.Store.SD to 1L)))
        }
        r.mediaFinishedAction={ _,_->r.session.requestExit() }
        r.session.run()
        assertTrue(r.failures.isEmpty());assertEquals(1,r.mediaResults.size)
        assertTrue(r.mediaResults.single().second.successful)
        assertSame(r.mediaResults.single().second,ticket!!.result)
        assertFalse(r.session.mediaBusy)
        assertEquals(listOf(1,1,2),r.mediaCommands.map { it.second })
        assertTrue(r.mediaCommands[1].third-r.mediaCommands[0].third>=1600)
        assertTrue(r.mediaCommands[2].third-r.mediaCommands[1].third>=800)
        assertTrue(r.mediaProgress.any { list -> list.any { it.bytes>0 } })
        assertTrue(r.frames.count { it.commandSet==0 && it.commandId==0x88 }>=3)
        assertTrue(r.sent.count { DumlDatagram.decode(it,0x1234)!!.type==4 }>10)
        assertEquals(GalleryDatalinkSession.PlaybackExit.CONFIRMED,r.exits.single())
        assertNull(r.session.requestMediaPages(mapOf(GalleryCameraEvidence.Store.SD to 1L)))
    }
    @Test fun aQueuedCanceledPageSendsNothingAndCompletesOnlyOnce() {
        val r=Rig();r.closeWhenReady=false
        r.readyAction={ r.session.requestMediaPages(mapOf(GalleryCameraEvidence.Store.SD to 1L))!!.cancel() }
        r.mediaFinishedAction={ _,_->r.session.requestExit() };r.session.run()
        assertTrue(r.mediaCommands.isEmpty());assertEquals(1,r.mediaResults.size)
        assertEquals(GalleryListBatch.Error.CANCELED,r.mediaResults.single().second.stores.getValue(GalleryCameraEvidence.Store.SD).error)
        assertTrue(r.failures.isEmpty())
    }
    @Test fun explicitExitDuringFirstListSendSuppressesAllLaterMediaCallbacksAndQueries() {
        val r=Rig();r.closeWhenReady=false;replyWithFixtures(r)
        var ticket:GalleryDatalinkSession.MediaTicket?=null
        r.readyAction={ ticket=r.session.requestMediaPages(mapOf(GalleryCameraEvidence.Store.SD to 1L,GalleryCameraEvidence.Store.INTERNAL to 0x40000001L)) }
        r.mediaCommandAction={ if(it==GalleryListBatch.Role.SD_QUERY)r.session.requestExit() };r.session.run()
        assertEquals(1,r.mediaCommands.size);assertTrue(r.mediaResults.isEmpty())
        assertTrue(r.failures.isEmpty());assertFalse(r.session.mediaBusy)
        assertTrue(ticket!!.result!!.stores.values.all { it.error==GalleryListBatch.Error.CANCELED })
        assertEquals(GalleryDatalinkSession.PlaybackExit.CONFIRMED,r.exits.single())
    }
    @Test fun listRejectionDoesNotTurnTheOtherStoreIntoAnEmptyOrFailedConnection() {
        val r=Rig();r.closeWhenReady=false
        r.listHandler={ frame ->
            val counter=frame.payload[4].toInt() and 255
            r.push(DumlFrame(1,2,frame.sequence,0xC0,0,0x26,byteArrayOf(if(counter==1 && frame.payload[1].toInt()==0)0xD8.toByte() else 0)))
            if(counter==2 && frame.payload[1].toInt()==0)r.media(2,mediaFixture("oa6_internal_2.bin"))
        }
        r.readyAction={ r.session.requestMediaPages(mapOf(GalleryCameraEvidence.Store.SD to 1L,GalleryCameraEvidence.Store.INTERNAL to 0x40000001L)) }
        r.mediaFinishedAction={ _,_->r.session.requestExit() };r.session.run()
        val result=r.mediaResults.single().second
        assertEquals(GalleryListBatch.Error.REJECTED,result.stores.getValue(GalleryCameraEvidence.Store.SD).error)
        assertNotNull(result.stores.getValue(GalleryCameraEvidence.Store.INTERNAL).page)
        assertTrue(r.failures.isEmpty())
    }
    @Test fun laterPageJobsUseFreshCountersAndRejectLatePriorCounterData() {
        val r=Rig();r.closeWhenReady=false;replyWithFixtures(r)
        var context:GalleryDatalinkSession.MediaContext?=null
        r.readyAction={ context=r.session.mediaContext;r.session.requestMediaPages(mapOf(GalleryCameraEvidence.Store.SD to 1L,GalleryCameraEvidence.Store.INTERNAL to 0x40000001L)) }
        r.mediaFinishedAction={ _,_->
            if(r.mediaResults.size==1) {
                r.media(1,byteArrayOf(99,98,97)) // Delayed old transfer, never associated with the next batch.
                assertNotNull(r.session.requestMediaPages(mapOf(GalleryCameraEvidence.Store.SD to 0x00100080L,GalleryCameraEvidence.Store.INTERNAL to 0x40100040L),context))
            } else r.session.requestExit()
        }
        r.session.run()
        assertEquals(2,r.mediaResults.size);assertTrue(r.mediaResults.all { it.second.successful })
        assertEquals(listOf(1,1,2,3,3,4),r.mediaCommands.map { it.second })
        assertEquals(2,r.mediaResults.map { it.first }.toSet().size);assertTrue(r.failures.isEmpty())
    }
    @Test fun changedStorageInvalidatesOnlyItsCurrentListResult() {
        val r=Rig();r.closeWhenReady=false;replyWithFixtures(r)
        r.readyAction={ r.session.requestMediaPages(mapOf(GalleryCameraEvidence.Store.SD to 1L,GalleryCameraEvidence.Store.INTERNAL to 0x40000001L)) }
        r.mediaCommandAction={ if(it==GalleryListBatch.Role.SD_QUERY)r.removeSdDuringListing=true }
        r.mediaFinishedAction={ _,_->r.session.requestExit() };r.session.run()
        val result=r.mediaResults.single().second
        assertEquals(GalleryListBatch.Error.STORAGE_CHANGED,result.stores.getValue(GalleryCameraEvidence.Store.SD).error)
        assertNull(result.stores.getValue(GalleryCameraEvidence.Store.SD).page)
        assertNotNull(result.stores.getValue(GalleryCameraEvidence.Store.INTERNAL).page)
        assertTrue(r.failures.isEmpty())
    }
    @Test fun pageQueueCannotStartAConnectionOrProbeBeforeReadiness() {
        val r=Rig()
        assertNull(r.session.requestMediaPages(mapOf(GalleryCameraEvidence.Store.SD to 1L)))
        assertEquals(0,r.probeCount);assertEquals(0,r.udpCount);assertTrue(r.sent.isEmpty());assertFalse(r.session.mediaBusy)
    }
    @Test fun storageChangeBetweenEnqueueAndFirstSendRejectsTheOldQueuedCursor() {
        val r=Rig();r.closeWhenReady=false;replyWithFixtures(r)
        r.readyAction={
            assertNotNull(r.session.requestMediaPages(mapOf(GalleryCameraEvidence.Store.SD to 0x00100080L),r.session.mediaContext))
            r.changeSdCapacityBeforeSend=true
            r.incoming.clear();r.cameraStatus=false;r.status();r.cameraStatus=true
            // Put changed storage first, before the worker's first job dequeue.
        }
        r.mediaFinishedAction={ _,_->r.session.requestExit() };r.session.run()
        val sd=r.mediaResults.single().second.stores.getValue(GalleryCameraEvidence.Store.SD)
        assertEquals(GalleryListBatch.Error.STORAGE_CHANGED,sd.error)
        assertTrue(r.mediaCommands.isEmpty());assertNull(sd.page);assertTrue(r.failures.isEmpty())
    }
    @Test fun staleUiCannotAttachAnOldCursorToNewStorageAfterItsChange() {
        val r=Rig();r.closeWhenReady=false;replyWithFixtures(r)
        var old:GalleryDatalinkSession.MediaContext?=null
        r.readyAction={
            old=r.session.mediaContext
            r.changeSdCapacityBeforeSend=true;r.incoming.clear();r.cameraStatus=false;r.status();r.cameraStatus=true
        }
        r.storagesAction={
            if(r.stores.size==2)assertNotNull(r.session.requestMediaPages(mapOf(GalleryCameraEvidence.Store.SD to 0x00100080L),old))
        }
        r.mediaFinishedAction={ _,_->r.session.requestExit() };r.session.run()
        assertEquals(GalleryListBatch.Error.STORAGE_CHANGED,r.mediaResults.single().second.stores.getValue(GalleryCameraEvidence.Store.SD).error)
        assertTrue(r.mediaCommands.isEmpty());assertTrue(r.failures.isEmpty())
    }
    @Test fun olderCursorsNeedImmutableContextFromTheSameLiveSession() {
        var old:GalleryDatalinkSession.MediaContext?=null
        val first=Rig();first.closeWhenReady=false
        first.readyAction={
            old=first.session.mediaContext;assertNotNull(old)
            try { (old!!.stores as MutableMap).clear();fail("Context must be immutable") } catch(_:UnsupportedOperationException) {}
            assertNull(first.session.requestMediaPages(mapOf(GalleryCameraEvidence.Store.SD to 0x00100080L)))
            first.session.requestExit()
        };first.session.run()
        val second=Rig();second.closeWhenReady=false
        second.readyAction={
            assertNull(second.session.requestMediaPages(mapOf(GalleryCameraEvidence.Store.SD to 1L),old))
            assertFalse(second.session.mediaBusy);second.session.requestExit()
        };second.session.run()
        assertTrue(first.mediaCommands.isEmpty());assertTrue(second.mediaCommands.isEmpty())
    }
    @Test fun observedRemoveAndReinsertDoesNotReviveAnOldStoreContext() {
        val r=Rig();r.closeWhenReady=false;replyWithFixtures(r)
        var original:GalleryDatalinkSession.MediaContext?=null
        r.readyAction={
            original=r.session.mediaContext
            r.removeSdDuringListing=true;r.incoming.clear();r.cameraStatus=false;r.status();r.cameraStatus=true
        }
        r.storagesAction={
            if(r.stores.size==2) {
                r.removeSdDuringListing=false;r.incoming.clear();r.cameraStatus=false;r.status();r.cameraStatus=true
            } else if(r.stores.size==3) {
                val fresh=r.session.mediaContext!!
                assertEquals(original!!.stores,fresh.stores)
                assertNotEquals(original!!.revisions[GalleryCameraEvidence.Store.SD],fresh.revisions[GalleryCameraEvidence.Store.SD])
                assertEquals(original!!.revisions[GalleryCameraEvidence.Store.INTERNAL],fresh.revisions[GalleryCameraEvidence.Store.INTERNAL])
                assertNotNull(r.session.requestMediaPages(mapOf(GalleryCameraEvidence.Store.SD to 0x00100080L),original))
            }
        }
        r.mediaFinishedAction={ _,_->
            if(r.mediaResults.size==1) {
                assertTrue(r.mediaCommands.isEmpty())
                assertNotNull(r.session.requestMediaPages(mapOf(GalleryCameraEvidence.Store.INTERNAL to 0x40100040L),original))
            } else r.session.requestExit()
        }
        r.session.run()
        assertEquals(2,r.mediaResults.size)
        assertEquals(GalleryListBatch.Error.STORAGE_CHANGED,r.mediaResults.first().second.stores.getValue(GalleryCameraEvidence.Store.SD).error)
        assertNotNull(r.mediaResults.last().second.stores.getValue(GalleryCameraEvidence.Store.INTERNAL).page)
        assertTrue(r.failures.isEmpty())
    }
}
