package app.cameraderie.sony
import org.junit.Assert.*
import org.junit.Test
class SonySessionLifetimeTest {
    @Test fun sessionNeedsAnExplicitStartAndCanEndOnlyOnce() { val s=SonySessionLifetime();assertFalse(s.isCurrent("a"));assertTrue(s.begin("a"));assertTrue(s.end("a"));assertFalse(s.end("a")) }
    @Test fun staleStopDoesNotCloseANewerSession() { val s=SonySessionLifetime();s.begin("a");s.end("a");s.begin("b");assertFalse(s.end("a"));assertTrue(s.isCurrent("b")) }
    @Test fun aSecondSessionCannotSilentlyReplaceItsOwner() { val s=SonySessionLifetime();s.begin("a");assertFalse(s.begin("b"));assertTrue(s.isCurrent("a")) }
    @Test fun emptyIdsAreRejectedWithoutStartingAConnection() { try { SonySessionLifetime().begin(" ");fail() } catch(_:IllegalArgumentException) { } }
}
