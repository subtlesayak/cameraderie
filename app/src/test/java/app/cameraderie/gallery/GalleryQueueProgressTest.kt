package app.cameraderie.gallery

import app.cameraderie.gallery.media.GalleryDownloadQueue
import app.cameraderie.gallery.media.GalleryDownloadQueue.Job
import app.cameraderie.gallery.media.GalleryDownloadQueue.State
import app.cameraderie.gallery.media.GalleryQueueProgress
import app.cameraderie.gallery.protocol.GalleryCameraEvidence.Store
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryQueueProgressTest {
    private fun job(id: String, bytes: Long, state: State = State.WAITING) =
        Job(id, "camera", GalleryDownloadQueue.Source(id, "$id.mp4", bytes, Store.SD), state)

    @Test fun weightsFilesByBytesAndKeepsProgressBetweenFiles() {
        val first = job("a", 20)
        val second = job("b", 80)
        val progress = GalleryQueueProgress("camera", listOf(first, second))
        val history = job("older", 10_000, State.COMPLETE)

        assertEquals(0, progress.percent(listOf(history, first, second)))
        progress.record(first.id, 10)
        assertEquals(10, progress.percent(listOf(first, second)))
        val afterFirst = listOf(first.copy(state = State.COMPLETE), second)
        assertEquals(20, progress.percent(afterFirst))
        progress.record(second.id, 40)
        assertEquals(60, progress.percent(afterFirst))
        assertEquals(60, progress.percent(afterFirst.map {
            if (it.id == second.id) it.copy(state = State.RESUME) else it
        }))
        progress.record(second.id, second.source.bytes)
        assertEquals(99, progress.percent(afterFirst))
        assertEquals(100, progress.percent(afterFirst.map { it.copy(state = State.COMPLETE) }))
    }

    @Test fun canceledJobsLeaveTheTotalAndNewJobsAddedToARunCount() {
        val a = job("a", 50)
        val b = job("b", 150)
        val c = job("c", 50)
        val progress = GalleryQueueProgress("camera", listOf(a, b))
        progress.include(listOf(c))
        val jobs = listOf(a.copy(state = State.COMPLETE), b.copy(state = State.CANCELED), c)
        assertEquals(50, progress.percent(jobs))
    }
}
