package app.cameraderie.gallery.media.history

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ImportHistoryGateTest {
    @Test fun historyRecoveryCannotCleanAnActiveImportsPendingFile() {
        val gate=ImportHistoryGate();val active=CountDownLatch(1);val release=CountDownLatch(1)
        val worker=Thread { gate.importing { active.countDown();check(release.await(5,TimeUnit.SECONDS)) } }
        worker.start()
        try { assertTrue(active.await(5,TimeUnit.SECONDS));assertFalse(gate.tryRecover { fail("recovery must not run") }) }
        finally { release.countDown();worker.join(5_000) }
        assertFalse(worker.isAlive);assertTrue(gate.tryRecover {})
    }
    @Test fun failedImportReleasesRecoveryGate() {
        val gate=ImportHistoryGate()
        try { gate.importing { throw IllegalStateException() };fail() } catch(_:IllegalStateException) {}
        assertTrue(gate.tryRecover {})
    }
    @Test fun failedRecoveryDoesNotBlockSubsequentImports() {
        val gate=ImportHistoryGate()
        try { gate.tryRecover { throw IllegalStateException() };fail() } catch(_:IllegalStateException) {}
        assertEquals(7,gate.importing { 7 })
    }

    @Test fun canceledImportWaitingForRecoveryNeverEntersItsCopyBlock() {
        val gate=ImportHistoryGate();val held=CountDownLatch(1);val release=CountDownLatch(1)
        val entered=java.util.concurrent.atomic.AtomicBoolean(false);val interrupted=java.util.concurrent.atomic.AtomicBoolean(false)
        val recovery=Thread { gate.tryRecover { held.countDown();check(release.await(5,TimeUnit.SECONDS)) } }
        val importer=Thread { try { gate.importing { entered.set(true) } } catch(_:InterruptedException) { interrupted.set(true) } }
        recovery.start()
        try {
            assertTrue(held.await(5,TimeUnit.SECONDS));importer.start();importer.interrupt();importer.join(1000)
            assertFalse(importer.isAlive);assertTrue(interrupted.get());assertFalse(entered.get())
        } finally { release.countDown();recovery.join(5000);importer.interrupt();importer.join(1000) }
        assertTrue(gate.tryRecover {})
    }

}
