package app.cameraderie.core

import app.gripbridge.core.DjiProtocol
import app.gripbridge.core.DjiSession
import org.junit.Assert.*
import org.junit.Test

class AllModeCaptureTest {
    private class Rig {
        var time = 100L
        val frames = mutableListOf<DjiProtocol.Frame>()
        val session = DjiSession(1,ByteArray(6),{time},{bytes,done -> frames += DjiProtocol.decode(bytes)!!; done(true)},{},{})
        init {
            session.begin(true,1234)
            session.receive(DjiProtocol.Frame(2,7,0,0x19,DjiProtocol.connection(0xFF55,ByteArray(6),2,0)))
        }
        fun status(mode: Int, state: Int = 1, power: Int = 0, heat: Int = 0) {
            val b = ByteArray(38); b[0]=mode.toByte(); b[1]=state.toByte(); b[28]=power.toByte(); b[29]=mode.toByte(); b[30]=heat.toByte()
            session.receive(DjiProtocol.Frame(0,8,0x1D,2,b))
        }
    }
    @Test fun nonstandardAndFirmwareSpecificModesUseNativeShutterWithoutGuessedModeIds() {
        for(mode in listOf(0,2,5,10,0x28,0x34,0x56)) {
            val r=Rig();r.status(mode);val count=r.frames.size
            r.session.shutterByMode()
            assertEquals(count+1,r.frames.size)
            val frame=r.frames.last();assertEquals(0,frame.set);assertEquals(0x11,frame.id)
            assertArrayEquals(byteArrayOf(1,1,0,0),frame.payload)
            assertFalse(r.session.lastCommand.contains("confirmed"))
        }
    }
    @Test fun activeNonPhotoCaptureCanStopViaNativeShutter() {
        for(mode in listOf(0,2,10,0x28,0x34,0x56)) {
            val r=Rig();r.status(mode,3,heat=2);val before=r.frames.size
            r.session.shutterByMode();assertEquals(before+1,r.frames.size)
            assertArrayEquals(byteArrayOf(1,1,0,0),r.frames.last().payload)
        }
    }
    @Test fun photoProcessingAndPlaybackNeverReceiveAnotherShutter() {
        for(pair in listOf(5 to 3,0 to 2,2 to 5)) {
            val r=Rig();r.status(pair.first,pair.second);val before=r.frames.size
            r.session.shutterByMode();assertEquals(before,r.frames.size)
        }
    }
    @Test fun staleAsleepUnknownAndStreamingStatesAreBlocked() {
        for(mode in listOf(0x1A,0x23)) {
            val r=Rig();r.status(mode);val before=r.frames.size;r.session.shutterByMode();assertEquals(before,r.frames.size)
        }
        val r=Rig();var before=r.frames.size;r.session.shutterByMode();assertEquals(before,r.frames.size)
        r.status(2,power=3);r.session.shutterByMode();assertEquals(before,r.frames.size)
        r.status(2);r.time+=3001;before=r.frames.size;r.session.shutterByMode();assertEquals(before,r.frames.size)
    }
    @Test fun nativeModeCycleWorksFromEveryShootingModeAndDoesNotGuessCustomIds() {
        for(mode in listOf(0,1,2,5,10,0x28,0x34,0x56)) {
            val r=Rig();r.status(mode);r.session.cycleShootingMode()
            assertArrayEquals(byteArrayOf(2,1,0,0),r.frames.last().payload)
        }
    }
    @Test fun pendingCaptureBlocksDuplicateAndModeSwitchAndTimeoutNeverReplays() {
        val r=Rig();r.status(2);r.session.shutterByMode();val before=r.frames.size
        r.session.shutterByMode();r.session.cycleShootingMode();assertEquals(before,r.frames.size)
        r.time+=6001;r.session.tick();assertEquals(before,r.frames.size)
        assertTrue(r.session.lastCommand.contains("outcome unknown"))
    }
    @Test fun overheatBlocksNewCaptureButNormalVideoKeepsExplicitCommand() {
        val r=Rig();r.status(0,heat=2);val before=r.frames.size;r.session.shutterByMode();assertEquals(before,r.frames.size)
        val video=Rig();video.status(1);video.session.shutterByMode()
        assertEquals(0x1D,video.frames.last().set);assertEquals(3,video.frames.last().id)
    }
}
