package app.cameraderie.core

import org.junit.Assert.*
import org.junit.Test

class DjiModeTest {
    private class Rig {
        var time=100L; var deferred=false; var writeOk=true
        val sent=mutableListOf<DjiProtocol.Frame>(); val callbacks=mutableListOf<(Boolean)->Unit>()
        val s=DjiSession(123,ByteArray(6),{time},{b,done -> sent+=DjiProtocol.decode(b)!!;if(deferred) callbacks+=done else done(writeOk)},{},{})
        init { s.begin(false,1234);s.receive(DjiProtocol.Frame(2,40,0,0x19,DjiProtocol.connection(0xFF55,ByteArray(6),2,0)));status() }
        fun status(mode: Int=1,state: Int=1,power: Int=0,preview: Int=0) {
            val b=ByteArray(38);b[0]=mode.toByte();b[1]=state.toByte();b[28]=power.toByte();b[29]=preview.toByte()
            s.receive(DjiProtocol.Frame(0,42,0x1D,2,b))
        }
        fun ack(seq: Int=sent.last().sequence,result: Int=0) {s.receive(DjiProtocol.Frame(0x20,seq,0x1D,4,byteArrayOf(result.toByte())))}
    }
    @Test fun usesDocumentedModeCommandAndRequiresAckPlusActualMode() {
        val r=Rig();r.s.selectMode(5);val command=r.sent.last()
        assertEquals(0x1D,command.set);assertEquals(4,command.id)
        assertArrayEquals(hexBytes("55 FF 00 00 05 00 00 00 00"),command.payload)
        r.ack();assertTrue(r.s.busy);r.status(mode=5);assertFalse(r.s.busy)
        assertTrue(r.s.lastCommand.contains("confirmed Photo mode"))
    }
    @Test fun previewAndWrongAckNeverConfirmSelection() {
        val r=Rig();r.s.selectMode(5);val seq=r.sent.last().sequence
        r.status(mode=1,preview=5);r.ack(seq+1);assertTrue(r.s.busy)
        r.ack(seq);assertTrue(r.s.busy);r.status(mode=5);assertFalse(r.s.busy)
    }
    @Test fun preWriteStatusDoesNotCountAndDisconnectCancelsLateCallbacks() {
        val r=Rig();r.deferred=true;r.s.selectMode(5);val seq=r.sent.last().sequence
        r.status(mode=5);r.ack(seq);assertTrue(r.s.busy)
        r.callbacks.last()(true);assertTrue(r.s.busy);r.status(mode=5);assertFalse(r.s.busy)
        r.s.selectMode(1);val old=r.callbacks.last();r.s.disconnect();old(true)
        assertFalse(r.s.busy);assertEquals(DjiSession.Auth.DISCONNECTED,r.s.auth)
    }
    @Test fun staleStatusCannotConfirmEvenWithMatchingAck() {
        val r=Rig();r.s.selectMode(5);val seq=r.sent.last().sequence
        r.status(mode=5);r.time+=3001;r.ack(seq);assertTrue(r.s.busy)
        r.status(mode=5);assertFalse(r.s.busy)
    }
    @Test fun expiredAckIsRejectedWithoutWaitingForPeriodicTick() {
        val r=Rig();r.s.selectMode(5);val seq=r.sent.last().sequence;val count=r.sent.size
        r.status(mode=5);r.time+=6001;r.ack(seq)
        assertFalse(r.s.busy);assertTrue(r.s.lastCommand.contains("outcome unknown"));assertEquals(count,r.sent.size)
    }
    @Test fun selectionRequiresAuthorizedAwakeFreshIdleState() {
        val r=Rig();val count=r.sent.size
        r.status(state=3);r.s.selectMode(5);assertEquals(count,r.sent.size)
        r.status(state=5);r.s.selectMode(5);assertEquals(count,r.sent.size)
        r.status(power=3);r.s.selectMode(5);assertEquals(count,r.sent.size)
        r.status();r.time+=3001;r.s.selectMode(5);assertEquals(count,r.sent.size)
        r.s.disconnect();r.s.selectMode(5);assertEquals(count,r.sent.size)
    }
    @Test fun rejectsUnknownModesAndSameModeWithoutSending() {
        val r=Rig();val count=r.sent.size;r.s.selectMode(1);r.s.selectMode(10)
        assertEquals(count,r.sent.size)
        try {DjiProtocol.mode(0xFF55,10);fail("Unsupported mode encoded")}catch(_: IllegalArgumentException){}
    }
    @Test fun rejectsFailedWriteAndCameraErrorWithoutClaimingChangedMode() {
        val r=Rig();r.writeOk=false;r.s.selectMode(5);assertFalse(r.s.busy);assertTrue(r.s.lastCommand.contains("write failed"))
        r.writeOk=true;r.s.selectMode(5);r.ack(result=2);assertFalse(r.s.busy);assertTrue(r.s.lastCommand.contains("rejected"))
    }
    @Test fun modePendingBlocksConflictingRecordAndQsActions() {
        val r=Rig();r.s.selectMode(5);val count=r.sent.size
        r.s.record(true);r.s.nextMode();r.s.selectMode(5);assertEquals(count,r.sent.size)
    }
    @Test fun observedAction6AckAliasNeedsMatchingSequenceAndFreshTargetMode() {
        val r=Rig();r.s.selectMode(5);val seq=r.sent.last().sequence
        r.s.receive(DjiProtocol.Frame(0x20,seq+1,0x1D,3,byteArrayOf(0)));r.status(mode=5)
        assertTrue(r.s.busy)
        r.s.receive(DjiProtocol.Frame(0x20,seq,0x1D,3,byteArrayOf(0)));assertFalse(r.s.busy)
        assertTrue(r.s.lastCommand.contains("confirmed Photo mode"))
    }
    @Test fun rejectedOrMalformedAliasCannotConfirmEvenWhenModeStatusMatches() {
        val r=Rig();r.s.selectMode(5);val seq=r.sent.last().sequence;r.status(mode=5)
        r.s.receive(DjiProtocol.Frame(0x20,seq,0x1D,3,byteArrayOf(2)))
        assertTrue(r.s.lastCommand.contains("rejected"))
        r.s.selectMode(1);val next=r.sent.last().sequence
        r.s.receive(DjiProtocol.Frame(0x20,next,0x1D,3,ByteArray(4)))
        assertTrue(r.s.lastCommand.contains("rejected"))
    }
}
