package app.cameraderie.gallery

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class GalleryResourceScopeTest {
    @Test fun exitClosesAllResourcesInReverseOrderOnceEvenWhenOneFails() {
        val closed=mutableListOf<Int>();val scope=GalleryResourceScope()
        val a=scope.track(1) { closed.add(it) }
        scope.track(2) { closed.add(it);throw IllegalStateException("private transport details") }
        scope.track(3) { closed.add(it) }
        scope.close();scope.close();a.close()
        assertEquals(listOf(3,2,1),closed);assertEquals(1,scope.cleanupFailures)
        try { a.value;fail() } catch(_: IllegalStateException) {}
        assertFalse(scope.active)
    }
    @Test fun individuallyClosedHandlesDoNotExhaustBoundedSessionRegistry() {
        val scope=GalleryResourceScope(2);var closed=0
        repeat(1000) { scope.track(it) { closed++ }.close() }
        assertEquals(1000,closed);assertTrue(scope.active)
        scope.close();assertEquals(1000,closed)
    }
    @Test fun lateCallbacksCannotLeakResourcesIntoCanceledScope() {
        val scope=GalleryResourceScope();var closed=0;scope.close()
        try { scope.track("secret") { closed++ };fail() } catch(e: IllegalStateException) { assertFalse(e.message!!.contains("secret")) }
        assertEquals(1,closed)
    }
    @Test fun capacityLimitClosesRejectedResourceAndKeepsAcceptedOnesOwned() {
        val scope=GalleryResourceScope(1);val closed=mutableListOf<Int>()
        scope.track(1) { closed.add(it) }
        try { scope.track(2) { closed.add(it) };fail() } catch(_: IllegalStateException) {}
        assertEquals(listOf(2),closed);scope.close();assertEquals(listOf(2,1),closed)
    }
    @Test fun cancellationClosesAnInFlightBlockingOperationBeforeWorkerCanPublish() {
        val scope=GalleryResourceScope();val started=CountDownLatch(1);val canceled=CountDownLatch(1);val finished=CountDownLatch(1)
        val handle=scope.track(Any()) { canceled.countDown() }
        var published=false
        val worker=thread {
            started.countDown();check(canceled.await(2,TimeUnit.SECONDS))
            try { handle.value;published=true } catch(_: IllegalStateException) {} finally { finished.countDown() }
        }
        assertTrue(started.await(2,TimeUnit.SECONDS));scope.close()
        assertTrue(finished.await(2,TimeUnit.SECONDS));worker.join(1000);assertFalse(published)
    }
}
