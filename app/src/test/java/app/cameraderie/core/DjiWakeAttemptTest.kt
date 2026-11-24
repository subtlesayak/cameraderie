package app.cameraderie.core

import org.junit.Assert.*
import org.junit.Test

class DjiWakeAttemptTest {
    private var time = 1000L
    private var reconnects = 0
    private val messages = mutableListOf<String>()
    private val wake = DjiWakeAttempt({time},{reconnects++},messages::add)
    @Test fun preservesExistingLinkDuringAdvertisementAndGracePeriod() {
        wake.begin(); assertEquals(0,reconnects)
        wake.advertised(true); time += 2999; wake.tick(); assertEquals(0,reconnects)
        time++; wake.tick(); assertEquals(1,reconnects)
        repeat(10) { time++; wake.tick() }; assertEquals(1,reconnects)
    }
    @Test fun advertisingCompletionAndSleepingOrStaleStatusNeverConfirmWake() {
        wake.begin(); wake.observeStatus(true,true,true)
        assertEquals(DjiWakeAttempt.State.ADVERTISING,wake.state)
        wake.advertised(true)
        wake.observeStatus(true,true,false); wake.observeStatus(true,false,true); wake.observeStatus(false,true,true)
        assertEquals(DjiWakeAttempt.State.WAITING_RESPONSE,wake.state)
        wake.observeStatus(true,true,true); assertEquals(DjiWakeAttempt.State.CONFIRMED,wake.state)
        time += 40000; wake.tick(); assertEquals(0,reconnects)
    }
    @Test fun peerDisconnectDuringAdvertisementDefersReconnectUntilSignalEnds() {
        wake.begin(); wake.disconnected(); assertEquals(0,reconnects)
        wake.advertised(true); assertEquals(0,reconnects)
        wake.savedCameraAdvertising(); assertEquals(1,reconnects)
        wake.disconnected(); wake.advertised(true); assertEquals(1,reconnects)
    }
    @Test fun peerDisconnectAfterAdvertisementReconnectsOnce() {
        wake.begin(); wake.advertised(true); wake.disconnected(); wake.disconnected()
        assertEquals(1,reconnects)
    }
    @Test fun timeoutFailsWithoutEndlessRetries() {
        wake.begin(); wake.advertised(true); time += 30000; wake.tick()
        assertEquals(DjiWakeAttempt.State.FAILED,wake.state); assertFalse(wake.active)
        repeat(10) { wake.tick() }; assertEquals(0,reconnects)
        assertTrue(messages.last().contains("Wake not confirmed"))
    }
    @Test fun cancellationIgnoresLateCompletionAndStatus() {
        wake.begin(); wake.cancel(); wake.advertised(true); wake.observeStatus(true,true,true)
        time += 40000; wake.tick(); assertEquals(DjiWakeAttempt.State.IDLE,wake.state); assertEquals(0,reconnects)
    }
    @Test fun advertiserFailureAndGattFailureRemainFailure() {
        wake.begin(); wake.advertised(false); assertEquals(DjiWakeAttempt.State.FAILED,wake.state)
        wake.begin(); wake.advertised(true); wake.fail("GATT failed")
        wake.observeStatus(true,true,true); assertEquals(DjiWakeAttempt.State.FAILED,wake.state)
    }
    @Test fun repeatedTapCannotRestartAnActiveAttempt() {
        wake.begin()
        try { wake.begin(); fail("Duplicate attempt allowed") } catch(_: IllegalStateException) {}
        assertEquals(DjiWakeAttempt.State.ADVERTISING,wake.state)
    }
    @Test fun connectableSavedAdvertisementStartsOneDirectReconnectAfterBroadcast() {
        wake.begin(); wake.savedCameraAdvertising()
        assertEquals(0,reconnects)
        wake.advertised(true); wake.savedCameraAdvertising()
        assertEquals(1,reconnects); assertEquals(DjiWakeAttempt.State.RECONNECTING,wake.state)
        wake.savedCameraAdvertising(); wake.advertised(true); wake.disconnected()
        assertEquals(1,reconnects)
        wake.observeStatus(true,true,false)
        assertEquals(DjiWakeAttempt.State.RECONNECTING,wake.state)
        wake.observeStatus(true,true,true)
        assertEquals(DjiWakeAttempt.State.CONFIRMED,wake.state)
    }
    @Test fun directReconnectStillHasBoundedDeadlineAndCancelIgnoresScanResult() {
        wake.begin(); wake.advertised(true); wake.savedCameraAdvertising(); time += 30000; wake.tick()
        assertEquals(DjiWakeAttempt.State.FAILED,wake.state)
        wake.begin(); wake.cancel(); wake.savedCameraAdvertising()
        assertEquals(1,reconnects); assertEquals(DjiWakeAttempt.State.IDLE,wake.state)
    }
    @Test fun freshAuthorizedAwakeStatusCanConfirmWakeDuringAnActiveBroadcast() {
        wake.begin()
        assertFalse(wake.observeStatus(true,true,true))
        wake.advertisingStarted()
        assertFalse(wake.observeStatus(true,false,true))
        assertFalse(wake.observeStatus(false,true,true))
        assertTrue(wake.observeStatus(true,true,true))
        wake.advertised(true); wake.savedCameraAdvertising(); wake.disconnected()
        assertEquals(DjiWakeAttempt.State.CONFIRMED,wake.state)
        assertEquals(0,reconnects)
    }
}
