package app.cameraderie.gallery.media

import app.cameraderie.gallery.protocol.GalleryMediaPage
import app.cameraderie.gallery.protocol.GalleryCameraEvidence.Store
import java.security.MessageDigest
import java.util.UUID

/** The journal stores work across sessions. A claim only belongs to a live worker. */
internal class GalleryDownloadQueue(private val journal: Journal) {
    enum class State {
        WAITING, RESUME, RESTART, REFRESH, REVIEW, COMPLETE, CANCELED;
        val pending get() = this != COMPLETE && this != CANCELED
    }

    data class Source(val identity: String, val name: String, val bytes: Long, val store: Store)
    data class Job(val id: String, val camera: String, val source: Source,
        val state: State = State.WAITING, val attempt: String? = null)
    class Claim internal constructor(val job: Job)
    interface Journal {
        fun read(): List<Job>
        fun write(jobs: List<Job>)
    }

    private var jobs = journal.read().also { rows ->
        require(rows.size <= MAX_FILES && rows.map(Job::id).distinct().size == rows.size)
    }
    private var active: Claim? = null
    val activeId get() = synchronized(this) { active?.job?.id }

    @Synchronized fun snapshot(camera: String? = null): List<Job> =
        jobs.filter { camera == null || it.camera == camera }

    @Synchronized fun enqueue(camera: String, sources: List<Source>): Int {
        require(sources.size in 1..MAX_FILES)
        return add(sources.map { camera to it })
    }

    @Synchronized fun restore(sources: List<Pair<String, Source>>): Int {
        require(sources.size <= MAX_FILES)
        val recorded = jobs.filter { it.state != State.CANCELED }.map { it.camera to it.source.identity }.toSet()
        return add(sources.filter { (camera, source) -> (camera to source.identity) !in recorded })
    }

    private fun add(sources: List<Pair<String, Source>>): Int {
        val pending = jobs.filter { it.state.pending }
        val existing = pending.map { it.camera to it.source.identity }.toSet()
        val added = sources.distinctBy { (camera, source) -> camera to source.identity }
            .filter { (camera, source) -> (camera to source.identity) !in existing }
        if (added.isEmpty()) return 0
        check(added.size <= MAX_FILES - pending.size) { "Download queue is full" }
        val retained = jobs.filter { it.state.pending || it.id == active?.job?.id }
        check(retained.size + added.size <= MAX_FILES) { "Wait for the current download to stop" }
        val history = jobs.filter { !it.state.pending && it.id != active?.job?.id }
            .takeLast(MAX_FILES - retained.size - added.size)
        val keep = (retained + history).map(Job::id).toSet()
        save(jobs.filter { it.id in keep } + added.map { (camera, source) -> Job(UUID.randomUUID().toString(), camera, source) })
        return added.size
    }

    @Synchronized fun claim(camera: String, eligible: (Job) -> Boolean = { true }): Claim? {
        if (active != null) return null
        val job = nextPending(camera, eligible) ?: return null
        return Claim(job).also { active = it }
    }

    @Synchronized fun canResumeHead(camera: String, jobId: String, eligible: (Job) -> Boolean = { true }): Boolean {
        if (active != null) return false
        val next = nextPending(camera, eligible) ?: return false
        return next.id == jobId && (next.state == State.WAITING || next.state == State.RESUME)
    }

    private fun nextPending(camera: String, eligible: (Job) -> Boolean) =
        jobs.firstOrNull { it.camera == camera && it.state.pending && eligible(it) }

    @Synchronized fun beginAttempt(claim: Claim): String? {
        if (active !== claim) return null
        val job = jobs.first { it.id == claim.job.id }
        if (!job.state.pending) return null
        val attempt = UUID.randomUUID().toString()
        replace(job.copy(state = State.RESUME, attempt = attempt))
        return attempt
    }

    /** Release an unverified job before reconnecting, keeping the journal and attempt intact. */
    @Synchronized fun release(claim: Claim): Boolean {
        if (active !== claim) return false
        active = null
        return true
    }

    @Synchronized fun finish(claim: Claim, result: State) {
        if (active !== claim) return
        try {
            val job = jobs.first { it.id == claim.job.id }
            if (job.state != State.CANCELED || result == State.COMPLETE) replace(job.copy(state = result))
        } finally {
            active = null
        }
    }

    @Synchronized fun cancel(ids: Set<String>) {
        save(jobs.map { if (it.id in ids && it.state.pending) it.copy(state = State.CANCELED) else it })
    }

    private fun replace(job: Job) = save(jobs.map { if (it.id == job.id) job else it })

    private fun save(next: List<Job>) {
        if (next != jobs) {
            journal.write(next)
            jobs = next
        }
    }

    companion object {
        const val MAX_FILES = 500
        fun camera(address: String): String = digest("GripBridge DJI camera", address.uppercase(java.util.Locale.ROOT))
        fun source(media: GalleryMediaPage.Media): Source = Source(
            digest(media.key.store.name, media.key.path, media.thumbnailPath, media.handle,
                media.size, media.type, media.durationSeconds), media.name, media.size, media.key.store)

        private fun digest(vararg values: Any?): String = MessageDigest.getInstance("SHA-256")
            .digest(values.joinToString("\u0000").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
