package app.cameraderie.gallery.media

/** Progress for the jobs in one download run, including files verified before a reconnect. */
internal class GalleryQueueProgress(val camera: String, pending: List<GalleryDownloadQueue.Job>) {
    private val ids = pending.mapTo(mutableSetOf()) { it.id }
    private var activeId: String? = null
    private var downloadedBytes = 0L

    fun includesPending(jobs: List<GalleryDownloadQueue.Job>) =
        jobs.any { it.id in ids && it.state.pending }

    fun include(pending: List<GalleryDownloadQueue.Job>) {
        ids.addAll(pending.map { it.id })
    }

    fun record(jobId: String, bytes: Long) {
        activeId = jobId
        downloadedBytes = bytes
    }

    fun percent(jobs: List<GalleryDownloadQueue.Job>): Int? {
        var total = 0.0
        var done = 0.0
        var verifying = false
        for (job in jobs) {
            if (job.id !in ids || job.state == GalleryDownloadQueue.State.CANCELED) continue
            val bytes = job.source.bytes
            total += bytes
            verifying = verifying || job.state.pending
            done += when {
                job.state == GalleryDownloadQueue.State.COMPLETE -> bytes
                job.id == activeId -> downloadedBytes.coerceIn(0L, bytes)
                else -> 0L
            }
        }
        if (total <= 0.0) return null
        return (100.0 * done / total).toInt().coerceIn(0, if (verifying) 99 else 100)
    }
}
