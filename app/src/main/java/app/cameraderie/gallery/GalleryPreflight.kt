package app.cameraderie.gallery

/** The bounded status-only R-SDK connection cannot turn missing status into idle. */
internal object GalleryPreflight {
    enum class Outcome { WAITING, READY, LINK_LOST, TIMEOUT, UNSUPPORTED, ASLEEP, BUSY }

    fun evaluate(evidence: CameraSessionOwnership.Evidence, profile: Int?, linkOpen: Boolean,
        now: Long, deadline: Long): Outcome = when {
        !linkOpen -> Outcome.LINK_LOST
        now >= deadline -> Outcome.TIMEOUT
        !evidence.authorized -> Outcome.WAITING
        profile != 0xFF55 -> Outcome.UNSUPPORTED
        !evidence.fresh -> Outcome.WAITING
        evidence.power != 0 -> Outcome.ASLEEP
        !evidence.idle -> Outcome.BUSY
        else -> Outcome.READY
    }
}
