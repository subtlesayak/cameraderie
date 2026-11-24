package app.cameraderie.sony

import org.junit.Assert.*
import org.junit.Test

class SonyConnectionAttemptTest {
    private class Clock(var value:Long=0) { fun now()=value }
    private fun attempt(clock:Clock)=SonyConnectionAttempt(clock::now)

    @Test fun stalledWifiExpiresAtExactBoundaryAndKeepsItsFailure() {
        val clock=Clock();val attempt=attempt(clock)
        clock.value=44_999;assertEquals(1L,attempt.remainingMs);assertNull(attempt.timeout())
        clock.value=45_000;assertEquals(SonyConnectionAttempt.Stage.WIFI,attempt.timeout())
        assertEquals(SonyConnectionAttempt.State.TIMED_OUT,attempt.state);assertEquals(0L,attempt.remainingMs)
        assertFalse(attempt.enter(SonyConnectionAttempt.Stage.DISCOVERY));assertFalse(attempt.complete())
        clock.value=0;assertEquals(SonyConnectionAttempt.Stage.WIFI,attempt.timeout())
    }

    @Test fun discoveryHasItsOwnDeadlineAfterNetworkApproval() {
        val clock=Clock();val attempt=attempt(clock)
        clock.value=10_000;assertTrue(attempt.enter(SonyConnectionAttempt.Stage.DISCOVERY))
        clock.value=29_999;assertEquals(1L,attempt.remainingMs)
        clock.value=30_000;assertEquals(SonyConnectionAttempt.Stage.DISCOVERY,attempt.timeout())
    }

    @Test fun sameStageCallbacksCannotRefreshABudget() {
        val clock=Clock();val attempt=attempt(clock)
        clock.value=44_000;assertTrue(attempt.enter(SonyConnectionAttempt.Stage.WIFI))
        assertEquals(1_000L,attempt.remainingMs)
        clock.value=45_000;assertFalse(attempt.enter(SonyConnectionAttempt.Stage.WIFI))
        assertEquals(SonyConnectionAttempt.Stage.WIFI,attempt.timeout())
    }

    @Test fun oldWifiTimerRechecksTheNewDiscoveryPhase() {
        val clock=Clock();val attempt=attempt(clock)
        clock.value=44_999;assertTrue(attempt.enter(SonyConnectionAttempt.Stage.DISCOVERY))
        clock.value=45_000;assertNull(attempt.timeout())
        val snapshot=attempt.snapshot()
        assertEquals(SonyConnectionAttempt.Stage.DISCOVERY,snapshot.stage)
        assertEquals(SonyConnectionAttempt.State.ACTIVE,snapshot.state)
        assertEquals(19_999L,snapshot.remainingMs)
    }

    @Test fun stagedProgressCannotExtendTheOverallHundredSecondCap() {
        val clock=Clock();val attempt=attempt(clock)
        clock.value=44_999;assertTrue(attempt.enter(SonyConnectionAttempt.Stage.DISCOVERY))
        clock.value=64_998;assertTrue(attempt.enter(SonyConnectionAttempt.Stage.PTP))
        clock.value=89_997;assertTrue(attempt.enter(SonyConnectionAttempt.Stage.METADATA))
        clock.value=99_999;assertEquals(1L,attempt.remainingMs)
        clock.value=100_000;assertEquals(SonyConnectionAttempt.Stage.METADATA,attempt.timeout())
    }

    @Test fun expectedHttpOpeningCanFinishWithoutPtp() {
        val clock=Clock();val attempt=attempt(clock)
        clock.value=2_000;assertTrue(attempt.enter(SonyConnectionAttempt.Stage.DISCOVERY))
        clock.value=8_000;assertTrue(attempt.enter(SonyConnectionAttempt.Stage.METADATA))
        clock.value=9_000;assertTrue(attempt.complete())
        assertEquals(SonyConnectionAttempt.State.COMPLETED,attempt.state)
        clock.value=1_000_000;assertNull(attempt.timeout());assertEquals(0L,attempt.remainingMs)
        assertFalse(attempt.enter(SonyConnectionAttempt.Stage.PTP));assertFalse(attempt.complete());assertFalse(attempt.cancel())
    }

    @Test fun ptpFallbackHasItsOwnBudgetAndMetadataCanThenFinish() {
        val clock=Clock();val attempt=attempt(clock)
        clock.value=1_000;assertTrue(attempt.enter(SonyConnectionAttempt.Stage.DISCOVERY))
        clock.value=7_000;assertTrue(attempt.enter(SonyConnectionAttempt.Stage.PTP))
        clock.value=31_999;assertEquals(1L,attempt.remainingMs)
        assertTrue(attempt.enter(SonyConnectionAttempt.Stage.METADATA))
        clock.value=32_000;assertTrue(attempt.complete());assertNull(attempt.timeout())
    }

    @Test fun stalledPtpAndMetadataIdentifyTheirActualFailedStage() {
        val clock=Clock();val ptp=attempt(clock)
        assertTrue(ptp.enter(SonyConnectionAttempt.Stage.DISCOVERY));assertTrue(ptp.enter(SonyConnectionAttempt.Stage.PTP))
        clock.value=25_000;assertEquals(SonyConnectionAttempt.Stage.PTP,ptp.timeout())
        clock.value=0;val metadata=attempt(clock)
        assertTrue(metadata.enter(SonyConnectionAttempt.Stage.DISCOVERY));assertTrue(metadata.enter(SonyConnectionAttempt.Stage.METADATA))
        clock.value=15_000;assertEquals(SonyConnectionAttempt.Stage.METADATA,metadata.timeout());assertFalse(metadata.complete())
    }

    @Test fun cancelAndCloseAreTerminalAndLateTimersDoNothing() {
        val clock=Clock();val attempt=attempt(clock)
        assertTrue(attempt.cancel());attempt.close();assertFalse(attempt.cancel())
        clock.value=100_000;assertNull(attempt.timeout());assertEquals(SonyConnectionAttempt.State.CANCELED,attempt.state)
        assertFalse(attempt.enter(SonyConnectionAttempt.Stage.DISCOVERY));assertFalse(attempt.complete())
        val another=attempt(Clock());another.close();assertEquals(SonyConnectionAttempt.State.CANCELED,another.state)
    }

    @Test fun backwardsClockDoesNotAddTimeOrUndoExpiration() {
        val clock=Clock();val attempt=attempt(clock)
        clock.value=10_000;assertEquals(35_000L,attempt.remainingMs)
        clock.value=5_000;assertEquals(35_000L,attempt.remainingMs)
        clock.value=45_000;assertEquals(SonyConnectionAttempt.Stage.WIFI,attempt.timeout())
        clock.value=1;assertEquals(SonyConnectionAttempt.State.TIMED_OUT,attempt.state)
    }

    @Test fun invalidSkippedOrBackwardsTransitionsDoNotMoveDeadlines() {
        val clock=Clock();val attempt=attempt(clock)
        assertFalse(attempt.complete());assertFalse(attempt.enter(SonyConnectionAttempt.Stage.PTP));assertFalse(attempt.enter(SonyConnectionAttempt.Stage.METADATA))
        clock.value=1_000;assertTrue(attempt.enter(SonyConnectionAttempt.Stage.DISCOVERY))
        assertFalse(attempt.enter(SonyConnectionAttempt.Stage.WIFI))
        clock.value=2_000;assertTrue(attempt.enter(SonyConnectionAttempt.Stage.METADATA))
        assertFalse(attempt.enter(SonyConnectionAttempt.Stage.PTP));assertEquals(15_000L,attempt.remainingMs)
    }

    @Test fun largeMonotonicTimestampsDoNotOverflowDeadlineArithmetic() {
        val clock=Clock(Long.MAX_VALUE-200_000);val attempt=attempt(clock)
        clock.value+=44_999;assertEquals(1L,attempt.remainingMs)
        clock.value++;assertEquals(SonyConnectionAttempt.Stage.WIFI,attempt.timeout())
    }
}
