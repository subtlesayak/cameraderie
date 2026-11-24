package app.cameraderie.core

import org.junit.Assert.*
import org.junit.Test

class DjiSessionTest {
    @Test fun shutterResultReportsRejectionAcknowledgmentAndDisconnect() {
        val r = Rig()
        val results = mutableListOf<TargetDelivery>()
        r.s.capturePhoto { results.add(it) }
        assertEquals(TargetDelivery.State.FAILED, results.single().state)
        r.auth()
        r.status(mode = 5)

        r.s.capturePhoto { results.add(it) }
        val shutter = r.frames.last()
        r.s.receive(DjiProtocol.Frame(0x20, shutter.sequence + 1, 0, 0x11, byteArrayOf(0)))
        assertEquals(1, results.size)
        r.s.receive(DjiProtocol.Frame(0x20, shutter.sequence, 0, 0x11, byteArrayOf(0)))
        assertEquals(TargetDelivery.State.ACKNOWLEDGED, results.last().state)

        r.s.capturePhoto { results.add(it) }
        r.s.disconnect()
        assertEquals(TargetDelivery.State.UNKNOWN, results.last().state)
        assertEquals(3, results.size)
    }

    @Test fun recordingReportsObservedConfirmationAndDoesNotReplayOnLinkLoss() {
        val r = Rig()
        r.auth(); r.status()
        val results = mutableListOf<TargetDelivery>()
        r.s.record(true) { results += it }
        r.ack()
        assertTrue(results.isEmpty())
        r.status(true)
        assertEquals(TargetDelivery.State.ACKNOWLEDGED, results.single().state)

        r.s.record(false) { results += it }
        val interrupted = r.frames.last().sequence
        r.s.disconnect()
        assertEquals(TargetDelivery.State.UNKNOWN, results.last().state)
        r.auth(); r.status(false); r.ack(interrupted)
        assertEquals(2, results.size)
    }

    private class Rig {
        var time = 100L
        val frames = mutableListOf<DjiProtocol.Frame>()
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        val logs = mutableListOf<String>()
        var autoWrite = true
        val s = DjiSession(0x12345678,ByteArray(6),{time},{ bytes,done -> frames += DjiProtocol.decode(bytes)!!; if(autoWrite) done(true) else callbacks += done },logs::add,{})
        fun auth() { s.begin(true,1234); s.receive(DjiProtocol.Frame(2,77,0,0x19,DjiProtocol.connection(0xFF55,ByteArray(6),2,0))) }
        fun status(recording: Boolean = false, mode: Int = 1, power: Int = 0) {
            val b = ByteArray(38); b[0] = mode.toByte(); b[1] = if(recording) 3 else 1
            b[28] = power.toByte()
            s.receive(DjiProtocol.Frame(0,78,0x1D,2,b))
        }
        fun ack(seq: Int = frames.last().sequence, error: Boolean = false) { s.receive(DjiProtocol.Frame(0x20,seq,0x1D,3,byteArrayOf(if(error) 2 else 0,0,0,0,0))) }
    }
    @Test fun immediateAckIsNotAuthorization() {
        val r = Rig(); r.s.begin(true,1234)
        r.s.receive(DjiProtocol.Frame(0x20,r.frames.last().sequence,0,0x19,DjiProtocol.authReply(0xFF55)))
        assertEquals(DjiSession.Auth.WAITING_CAMERA,r.s.auth)
    }
    @Test fun authorizeOnlyAfterCameraRequestAndReplyWrite() {
        val r = Rig(); r.autoWrite = false; r.auth()
        assertEquals(DjiSession.Auth.REPLYING,r.s.auth)
        assertEquals(77,r.frames.last().sequence)
        r.callbacks.last()(true)
        assertEquals(DjiSession.Auth.AUTHORIZED,r.s.auth)
        assertEquals(Recording.UNKNOWN,r.s.recording)
    }
    @Test fun unknownRejectsToggleAndNoBlindReplay() {
        val r = Rig(); r.auth(); val before = r.frames.size; r.s.toggle(); assertEquals(before,r.frames.size)
        r.s.disconnect(); r.auth(); assertEquals(Recording.UNKNOWN,r.s.recording)
    }
    @Test fun successRequiresAckAndStatus() {
        val r = Rig(); r.auth(); r.status(); r.s.record(true)
        r.ack(); assertTrue(r.s.busy)
        r.status(true); assertFalse(r.s.busy); assertTrue(r.s.lastCommand.contains("Camera confirmed"))
    }
    @Test fun statusBeforeAckCanConfirmButWrongSequenceCannot() {
        val r = Rig(); r.auth(); r.status(); r.s.record(true)
        val seq = r.frames.last().sequence
        r.status(true); assertTrue(r.s.busy)
        r.ack(seq+1); assertTrue(r.s.busy)
        r.ack(seq); assertFalse(r.s.busy)
    }
    @Test fun duplicatePressDuringCommandDoesNotSendAgain() {
        val r = Rig(); r.auth(); r.status(); r.s.toggle(); val count = r.frames.size
        r.s.toggle(); assertEquals(count,r.frames.size)
    }
    @Test fun timeoutOutcomeUnknownAndNoAutomaticRetry() {
        val r = Rig(); r.auth(); r.status(); r.s.toggle(); val count = r.frames.size
        r.time += 6001; r.s.tick()
        assertEquals(Recording.UNKNOWN,r.s.recording); assertFalse(r.s.busy); assertEquals(count,r.frames.size)
    }
    @Test fun uiReadinessExpiresWithoutWaitingForPeriodicTick() {
        val r = Rig(); assertFalse(r.s.hasFreshStatus)
        r.auth(); assertFalse(r.s.hasFreshStatus)
        r.status(); assertTrue(r.s.hasFreshStatus)
        r.time += 3000; assertTrue(r.s.hasFreshStatus)
        r.time++; assertFalse(r.s.hasFreshStatus)
        r.status(mode=5); assertTrue(r.s.hasFreshStatus)
        r.s.disconnect(); assertFalse(r.s.hasFreshStatus)
    }
    @Test fun sleepingCameraNeverReceivesARecordingCommand() {
        val r = Rig(); r.auth()
        val b = ByteArray(38); b[0] = 1; b[1] = 1; b[28] = 3
        r.s.receive(DjiProtocol.Frame(0,78,0x1D,2,b))
        assertTrue(r.s.hasFreshStatus); assertEquals(3,r.s.camera?.power)
        assertEquals(Recording.UNKNOWN,r.s.recording)
        val count = r.frames.size; r.s.toggle(); r.s.record(true)
        assertEquals(count,r.frames.size)
    }
    @Test fun routineRecordingRequiresTheSameAuthorizedSession() {
        val r = Rig()
        val beforeAuthorization = r.s.prepareRoutineRecording(true)
        r.auth()
        r.status()
        val beforeReconnect = r.s.prepareRoutineRecording(true)
        r.s.disconnect()
        r.auth()
        r.status()
        val count = r.frames.size
        val results = mutableListOf<TargetDelivery>()

        beforeAuthorization { results += it }
        beforeReconnect { results += it }
        assertEquals(count, r.frames.size)
        assertEquals(listOf(TargetDelivery.State.FAILED, TargetDelivery.State.FAILED), results.map { it.state })

        val fresh = r.s.prepareRoutineRecording(true)
        fresh { results += it }
        assertEquals(count + 1, r.frames.size)
        r.ack()
        r.status(true)
        assertEquals(TargetDelivery.State.ACKNOWLEDGED, results.last().state)
    }
    @Test fun sleepInvalidatesPendingAndPreparedRoutineRecording() {
        val r = Rig()
        r.auth()
        r.status()
        val results = mutableListOf<TargetDelivery>()
        val delayed = r.s.prepareRoutineRecording(true)

        r.s.record(true) { results += it }
        val interrupted = r.frames.last().sequence
        r.status(power = 3)
        assertFalse(r.s.busy)
        val preparedWhileAsleep = r.s.prepareRoutineRecording(true)
        r.status(recording = true)
        r.ack(interrupted)
        assertEquals(TargetDelivery.State.UNKNOWN, results.single().state)

        val count = r.frames.size
        delayed { results += it }
        preparedWhileAsleep { results += it }
        assertEquals(count, r.frames.size)
        assertEquals(listOf(TargetDelivery.State.FAILED, TargetDelivery.State.FAILED), results.drop(1).map { it.state })

        val fresh = r.s.prepareRoutineRecording(false)
        fresh { results += it }
        assertEquals(count + 1, r.frames.size)
        r.ack()
        r.status()
        assertEquals(TargetDelivery.State.ACKNOWLEDGED, results.last().state)
    }
    @Test fun staleStatusBlocksNewRecord() {
        val r = Rig(); r.auth(); r.status(); val count = r.frames.size
        r.time += 3001; r.s.record(true)
        assertEquals(Recording.UNKNOWN,r.s.recording); assertEquals(count,r.frames.size)
    }
    @Test fun cameraRejectsPairingAndNoRetryLoop() {
        val r = Rig(); r.s.begin(true,1234)
        r.s.receive(DjiProtocol.Frame(2,77,0,0x19,DjiProtocol.connection(0xFF55,ByteArray(6),2,1)))
        assertEquals(DjiSession.Auth.FAILED,r.s.auth); assertEquals(1,r.frames.size)
    }
    @Test fun wrongCameraModelRejected() {
        val r = Rig(); r.s.begin(true,1234)
        r.s.receive(DjiProtocol.Frame(2,77,0,0x19,DjiProtocol.connection(0xFF44,ByteArray(6),2,0)))
        assertEquals(DjiSession.Auth.FAILED,r.s.auth)
    }
    @Test fun transportFailureIsNotSuccess() {
        val r = Rig(); r.auth(); r.status(); r.autoWrite = false; r.s.record(true)
        r.callbacks.last()(false)
        assertFalse(r.s.busy); assertEquals(Recording.UNKNOWN,r.s.recording)
    }
    @Test fun staleTransportCallbackCannotAuthorizeNewSession() {
        val r = Rig(); r.autoWrite = false; r.auth(); val done = r.callbacks.last()
        r.s.disconnect(); done(true); assertEquals(DjiSession.Auth.DISCONNECTED,r.s.auth)
    }
    @Test fun oldWriteAndAckCannotConfirmRecordingAfterReconnect() {
        val r = Rig()
        r.auth()
        r.status()
        r.autoWrite = false
        r.s.record(true)
        val oldSequence = r.frames.last().sequence
        val oldCompletion = r.callbacks.last()

        r.s.disconnect()
        r.autoWrite = true
        r.auth()
        r.status()
        val recoveredCount = r.frames.size
        assertFalse(r.s.busy)
        oldCompletion(true)
        r.ack(oldSequence)
        assertEquals(recoveredCount, r.frames.size)
        assertEquals(Recording.IDLE, r.s.recording)

        r.s.record(true)
        val newSequence = r.frames.last().sequence
        assertNotEquals(oldSequence, newSequence)
        r.status(true)
        r.ack(oldSequence)
        assertTrue(r.s.busy)
        r.ack(newSequence)
        assertFalse(r.s.busy)
        assertTrue(r.s.lastCommand.contains("Camera confirmed"))
    }
    @Test fun pairingTimeoutFails() {
        val r = Rig(); r.s.begin(true,1234); r.time += 45001; r.s.tick(); assertEquals(DjiSession.Auth.FAILED,r.s.auth)
    }
    @Test fun idempotentStartAndStopDoNotEmitCommand() {
        val r = Rig(); r.auth(); r.status(); val count = r.frames.size
        r.s.record(false); assertEquals(count,r.frames.size)
        r.status(true); r.s.record(true); assertEquals(count,r.frames.size)
    }
    @Test fun deniedCommandDoesNotShowConfirmation() {
        val r = Rig(); r.auth(); r.status(); r.s.record(true); r.ack(error=true)
        assertFalse(r.s.busy); assertFalse(r.s.lastCommand.contains("confirmed"))
    }
    @Test fun oneByteOfficialRecordAckAccepted() {
        val r = Rig(); r.auth(); r.status(); r.s.record(true)
        r.s.receive(DjiProtocol.Frame(0x20,r.frames.last().sequence,0x1D,3,byteArrayOf(0)))
        r.status(true); assertFalse(r.s.busy); assertTrue(r.s.lastCommand.contains("confirmed"))
    }
    @Test fun queuedButNotWrittenStatusCannotConfirmCommand() {
        val r = Rig(); r.auth(); r.status(); r.autoWrite = false; r.s.record(true)
        val seq = r.frames.last().sequence
        r.status(true); r.ack(seq)
        assertTrue(r.s.busy)
        r.callbacks.last()(true)
        assertTrue(r.s.busy)
        r.status(true); assertFalse(r.s.busy)
    }
    @Test fun lateRecordAckCannotConfirmExpiredOrStaleObservation() {
        val r=Rig();r.auth();r.status();r.s.record(true);val seq=r.frames.last().sequence;r.status(true)
        r.time+=3001;r.ack(seq);assertTrue(r.s.busy)
        r.status(true);assertFalse(r.s.busy)
        r.s.record(false);val stop=r.frames.last().sequence;r.status(false)
        r.time+=6001;r.ack(stop);assertFalse(r.s.busy);assertTrue(r.s.lastCommand.contains("outcome unknown"))
    }
}
