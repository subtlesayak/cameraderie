package app.cameraderie.core

import app.gripbridge.core.DjiProtocol
import app.gripbridge.core.DjiSession
import org.junit.Assert.*
import org.junit.Test

class DjiSleepTest {
    private class Rig {
        var time=100L;var deferred=false;var writeOk=true
        val sent=mutableListOf<DjiProtocol.Frame>();val callbacks=mutableListOf<(Boolean)->Unit>()
        val s=DjiSession(123,ByteArray(6),{time},{b,done->sent+=DjiProtocol.decode(b)!!;if(deferred)callbacks+=done else done(writeOk)},{},{})
        init{s.begin(false,1234);s.receive(DjiProtocol.Frame(2,40,0,0x19,DjiProtocol.connection(0xFF55,ByteArray(6),2,0)));status()}
        fun status(power: Int=0,state: Int=1){val b=ByteArray(38);b[0]=1;b[1]=state.toByte();b[28]=power.toByte();s.receive(DjiProtocol.Frame(0,42,0x1D,2,b))}
        fun ack(seq: Int=sent.last().sequence,result: Int=0){s.receive(DjiProtocol.Frame(0x20,seq,0,0x1A,byteArrayOf(result.toByte())))}
        fun ordinary(){s.requestStatus();s.queryFirmware();s.record(true);s.nextMode();s.capturePhoto();s.selectMode(5)}
    }
    @Test fun documentedSleepRequestNeedsAckAndPostWritePower3() {
        val r=Rig();r.s.sleepCamera();val q=r.sent.last()
        assertEquals(0,q.set);assertEquals(0x1A,q.id);assertArrayEquals(byteArrayOf(3),q.payload)
        assertTrue(r.s.trafficSuspended);r.ack();assertTrue(r.s.sleepPending)
        r.status(3);assertFalse(r.s.sleepPending);assertTrue(r.s.lastCommand.contains("confirmed asleep"))
        assertTrue(r.s.trafficSuspended)
    }
    @Test fun ordinaryTrafficPausesImmediatelyAndWhileAsleep() {
        val r=Rig();r.s.sleepCamera();val count=r.sent.size;r.ordinary();assertEquals(count,r.sent.size)
        r.ack();r.status(3);r.ordinary();assertEquals(count,r.sent.size)
    }
    @Test fun observedAutonomousSleepAlsoPausesTraffic() {
        val r=Rig();r.status(3);val count=r.sent.size;r.ordinary();assertEquals(count,r.sent.size)
        r.status();assertFalse(r.s.trafficSuspended);r.s.requestStatus();assertEquals(count+1,r.sent.size)
    }
    @Test fun requestRequiresIdleAwakeAuthorizedFreshCamera() {
        val r=Rig();val count=r.sent.size
        r.status(state=3);r.s.sleepCamera();assertEquals(count,r.sent.size)
        r.status(state=5);r.s.sleepCamera();assertEquals(count,r.sent.size)
        r.status();r.time+=3001;r.s.sleepCamera();assertEquals(count,r.sent.size)
        r.s.disconnect();r.s.sleepCamera();assertEquals(count,r.sent.size)
    }
    @Test fun wrongSequenceOrPreWriteSleepStatusNeverCompletes() {
        val r=Rig();r.deferred=true;r.s.sleepCamera();val seq=r.sent.last().sequence
        r.status(3);r.ack(seq+1);assertTrue(r.s.sleepPending)
        r.ack(seq);assertTrue(r.s.sleepPending);r.callbacks.last()(true);assertTrue(r.s.sleepPending)
        r.status(3);assertFalse(r.s.sleepPending)
    }
    @Test fun rejectedRequestRestoresTrafficOnlyIfActuallyAwake() {
        val r=Rig();r.s.sleepCamera();r.ack(result=2);assertFalse(r.s.sleepPending)
        assertFalse(r.s.trafficSuspended);assertTrue(r.s.lastCommand.contains("rejected"))
        r.s.sleepCamera();r.status(3);r.ack(result=2);assertTrue(r.s.trafficSuspended)
    }
    @Test fun acceptedButUnobservedSleepStaysUnconfirmedAndNoReplay() {
        val r=Rig();r.s.sleepCamera();r.ack();val count=r.sent.size
        r.time+=6001;r.s.tick();assertFalse(r.s.sleepPending);assertTrue(r.s.lastCommand.contains("not confirmed"))
        assertTrue(r.s.trafficSuspended);r.ordinary();assertEquals(count,r.sent.size)
        r.status();assertFalse(r.s.trafficSuspended)
    }
    @Test fun failedWriteAndLateCallbackCannotClaimSleep() {
        val r=Rig();r.writeOk=false;r.s.sleepCamera();assertFalse(r.s.sleepPending);assertTrue(r.s.trafficSuspended)
        r.status();assertFalse(r.s.trafficSuspended)
        r.writeOk=true;r.deferred=true;r.s.sleepCamera();val old=r.callbacks.last()
        r.s.disconnect();old(true);assertFalse(r.s.sleepPending);assertFalse(r.s.trafficSuspended)
    }
    @Test fun expiredSleepAckDoesNotConfirmEvenWithoutPeriodicTick() {
        val r=Rig();r.s.sleepCamera();val seq=r.sent.last().sequence;r.status(3)
        r.time+=6001;r.ack(seq);assertTrue(r.s.lastCommand.contains("not confirmed"));assertFalse(r.s.sleepPending)
    }
    @Test fun sleepDoesNotInterruptAnotherCameraCommand() {
        val r=Rig();r.s.selectMode(5);val count=r.sent.size;r.s.sleepCamera();assertEquals(count,r.sent.size)
        assertFalse(r.s.trafficSuspended)
    }
}
