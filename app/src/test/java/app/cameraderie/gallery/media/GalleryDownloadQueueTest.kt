package app.cameraderie.gallery.media

import app.cameraderie.gallery.protocol.GalleryCameraEvidence.Store
import app.cameraderie.gallery.protocol.GalleryMediaPage
import org.junit.Assert.*
import org.junit.Test

class GalleryDownloadQueueTest {
    private class Journal : GalleryDownloadQueue.Journal {
        private var rows = emptyList<GalleryDownloadQueue.Job>()
        var rejectWrite = false
        override fun read() = rows.toList()
        override fun write(jobs: List<GalleryDownloadQueue.Job>) {
            check(!rejectWrite)
            rows = jobs.toList()
        }
    }

    private fun source(number: Int) = GalleryDownloadQueue.Source("identity-$number", "IMG$number.JPG", 100, Store.SD)

    @Test fun duplicateSelectionsKeepFifoAndSeparateCameras() {
        val queue = GalleryDownloadQueue(Journal())
        assertEquals(2, queue.enqueue("a", listOf(source(2), source(1), source(2))))
        assertEquals(0, queue.enqueue("a", listOf(source(2))))
        assertEquals(1, queue.enqueue("b", listOf(source(2))))
        assertNull(queue.claim("other"))
        val first = checkNotNull(queue.claim("a"))
        assertEquals(source(2), first.job.source)
        assertNull(queue.claim("b"))
        queue.finish(first, GalleryDownloadQueue.State.COMPLETE)
        assertEquals(source(1), queue.claim("a")?.job?.source)
    }

    @Test fun aPartialListingCanRunAndResumeOnlyLocatedJobs() {
        val queue = GalleryDownloadQueue(Journal())
        queue.enqueue("a", listOf(source(1), source(2), source(3)))
        val (first, second, third) = queue.snapshot("a")
        val located: (GalleryDownloadQueue.Job) -> Boolean = { it.id != first.id }

        val running = checkNotNull(queue.claim("a", located))
        assertEquals(second.id, running.job.id)
        queue.finish(running, GalleryDownloadQueue.State.RESUME)
        assertFalse(queue.canResumeHead("a", second.id))
        assertTrue(queue.canResumeHead("a", second.id, located))
        queue.finish(checkNotNull(queue.claim("a", located)), GalleryDownloadQueue.State.COMPLETE)
        assertEquals(third.id, queue.claim("a", located)?.job?.id)
        assertEquals(GalleryDownloadQueue.State.WAITING, queue.snapshot("a").first().state)
    }

    @Test fun interruptedAttemptIsRecoveredWithSameJobButNeverAClaim() {
        val journal = Journal()
        val first = GalleryDownloadQueue(journal)
        first.enqueue("a", listOf(source(1), source(2)))
        val original = checkNotNull(first.claim("a"))
        val attempt = checkNotNull(first.beginAttempt(original))
        assertNull(first.claim("a"))
        val reopened = GalleryDownloadQueue(journal)
        val retry = checkNotNull(reopened.claim("a"))
        assertEquals(original.job.id, retry.job.id)
        assertEquals(attempt, retry.job.attempt)
        assertEquals(GalleryDownloadQueue.State.RESUME, retry.job.state)
        reopened.finish(retry, GalleryDownloadQueue.State.COMPLETE)
        assertEquals(source(2), reopened.claim("a")?.job?.source)
    }

    @Test fun releasingAnUnverifiedHeadForRolloverPreservesItsSavedAttemptAndOrder() {
        val journal = Journal()
        val queue = GalleryDownloadQueue(journal)
        queue.enqueue("a", listOf(source(1), source(2)))
        val first = checkNotNull(queue.claim("a"))
        val attempt = checkNotNull(queue.beginAttempt(first))
        queue.finish(first, GalleryDownloadQueue.State.RESUME)
        val beforeRollover = checkNotNull(queue.claim("a"))
        journal.rejectWrite = true

        assertTrue(queue.release(beforeRollover))
        val afterRollover = checkNotNull(queue.claim("a"))
        assertEquals(beforeRollover.job, afterRollover.job)
        assertEquals(attempt, afterRollover.job.attempt)
        assertFalse(queue.release(beforeRollover))
        assertNull(queue.claim("a"))
        assertEquals(source(2), queue.snapshot("a")[1].source)
    }

    @Test fun notificationResumeRequiresTheSameIdleHeadAndNeverRestartsIt() {
        val queue = GalleryDownloadQueue(Journal())
        queue.enqueue("a", listOf(source(1), source(2)))
        val (first, second) = queue.snapshot("a")
        assertTrue(queue.canResumeHead("a", first.id))
        assertFalse(queue.canResumeHead("a", second.id))
        assertFalse(queue.canResumeHead("b", first.id))
        val transfer = checkNotNull(queue.claim("a"))
        assertFalse(queue.canResumeHead("a", first.id))
        queue.finish(transfer, GalleryDownloadQueue.State.RESUME)
        assertTrue(queue.canResumeHead("a", first.id))
        for (blocked in listOf(GalleryDownloadQueue.State.RESTART, GalleryDownloadQueue.State.REFRESH, GalleryDownloadQueue.State.REVIEW)) {
            queue.finish(checkNotNull(queue.claim("a")), blocked)
            assertFalse(queue.canResumeHead("a", first.id))
            assertFalse(queue.canResumeHead("a", second.id))
        }
        queue.cancel(setOf(first.id))
        assertFalse(queue.canResumeHead("a", first.id))
        assertTrue(queue.canResumeHead("a", second.id))
    }

    @Test fun cancelDoesNotDropPriorPublicationOrAnotherCamera() {
        val queue = GalleryDownloadQueue(Journal())
        queue.enqueue("a", listOf(source(1), source(2)))
        queue.enqueue("b", listOf(source(3)))
        val active = checkNotNull(queue.claim("a"))
        assertNotNull(queue.beginAttempt(active))
        queue.cancel(queue.snapshot("a").map { it.id }.toSet())
        assertNull(queue.beginAttempt(active))
        queue.finish(active, GalleryDownloadQueue.State.COMPLETE)
        assertEquals(listOf(GalleryDownloadQueue.State.COMPLETE, GalleryDownloadQueue.State.CANCELED), queue.snapshot("a").map { it.state })
        assertNull(queue.claim("a"))
        assertEquals(source(3), queue.claim("b")?.job?.source)
    }

    @Test fun cancelingABlockedJobKeepsOtherWorkAndAdvancesTheQueue() {
        val journal=Journal();val queue=GalleryDownloadQueue(journal)
        queue.enqueue("a",listOf(source(1),source(2)))
        queue.enqueue("b",listOf(source(3)))
        val blocked=checkNotNull(queue.claim("a"))
        queue.finish(blocked,GalleryDownloadQueue.State.REFRESH)
        queue.cancel(setOf(blocked.job.id))
        val reopened=GalleryDownloadQueue(journal)
        assertEquals(GalleryDownloadQueue.State.CANCELED,reopened.snapshot("a").first().state)
        assertEquals(GalleryDownloadQueue.State.WAITING,reopened.snapshot("b").single().state)
        assertEquals(source(2),reopened.claim("a")?.job?.source)
    }
    @Test fun staleReleaseCannotCompleteCurrentClaim() {
        val queue = GalleryDownloadQueue(Journal())
        queue.enqueue("a", listOf(source(1)))
        val first = checkNotNull(queue.claim("a"))
        queue.finish(first, GalleryDownloadQueue.State.REFRESH)
        val second = checkNotNull(queue.claim("a"))
        queue.finish(first, GalleryDownloadQueue.State.COMPLETE)
        assertNull(queue.claim("a"))
        assertEquals(GalleryDownloadQueue.State.REFRESH, queue.snapshot().single().state)
        queue.finish(second, GalleryDownloadQueue.State.COMPLETE)
        assertNull(queue.claim("a"))
    }

    @Test fun cancellationConfirmingAnEarlierListPreservesNewWork() {
        val queue = GalleryDownloadQueue(Journal())
        queue.enqueue("a", listOf(source(1)))
        val confirmed = queue.snapshot().map { it.id }.toSet()
        queue.enqueue("a", listOf(source(2)))
        queue.cancel(confirmed)
        assertEquals(source(2), queue.claim("a")?.job?.source)
    }

    @Test fun unpersistedAttemptCannotBeUsedAndFailedReleaseLeavesWork() {
        val journal = Journal()
        val queue = GalleryDownloadQueue(journal)
        queue.enqueue("a", listOf(source(1)))
        val item = checkNotNull(queue.claim("a"))
        journal.rejectWrite = true
        assertTrue(runCatching { queue.beginAttempt(item) }.isFailure)
        assertTrue(runCatching { queue.finish(item, GalleryDownloadQueue.State.COMPLETE) }.isFailure)
        assertNull(queue.snapshot().single().attempt)
        assertEquals(item.job.id, queue.claim("a")?.job?.id)
    }

    @Test fun onlyFinishedRowsArePrunedToMakeRoom() {
        val queue = GalleryDownloadQueue(Journal())
        queue.enqueue("a", (1..GalleryDownloadQueue.MAX_FILES).map(::source))
        assertTrue(runCatching { queue.enqueue("b", listOf(source(501))) }.isFailure)
        queue.finish(checkNotNull(queue.claim("a")), GalleryDownloadQueue.State.COMPLETE)
        assertEquals(1, queue.enqueue("b", listOf(source(501))))
        assertEquals(GalleryDownloadQueue.MAX_FILES, queue.snapshot().size)
        assertEquals(source(2), queue.claim("a")?.job?.source)
    }

    @Test fun newSessionIdentityRequiresSameStoragePathHandleSizeAndType() {
        val photo = GalleryMediaPage.Media(GalleryMediaPage.Key(Store.SD, "DCIM/100MEDIA/IMG1.JPG"),
            "MISC/100MEDIA/IMG1.scr", 4, 100, 1, null)
        val expected = GalleryDownloadQueue.source(photo)
        assertEquals(expected, GalleryDownloadQueue.source(photo.copy()))
        assertNotEquals(expected, GalleryDownloadQueue.source(photo.copy(key = photo.key.copy(store = Store.INTERNAL))))
        assertNotEquals(expected, GalleryDownloadQueue.source(photo.copy(key = photo.key.copy(path = "DCIM/101MEDIA/IMG1.JPG"))))
        assertNotEquals(expected, GalleryDownloadQueue.source(photo.copy(handle = 5)))
        assertNotEquals(expected, GalleryDownloadQueue.source(photo.copy(size = 101)))
        assertNotEquals(expected, GalleryDownloadQueue.source(photo.copy(type = 3)))
    }
}
