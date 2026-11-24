package app.cameraderie.core

/** Retained across link/radio resets so repeated user retries cannot exhaust Android's scan budget. */
class BleScanBackoff {
    private val starts = ArrayDeque<Long>()
    private var retryAt = 0L
    private var failures = 0

    fun delayMs(now: Long): Long {
        while (starts.isNotEmpty() && now - starts.first() >= WINDOW_MS) starts.removeFirst()
        val budgetAt = if (starts.size >= MAX_STARTS) starts.first() + WINDOW_MS else now
        return (maxOf(retryAt, budgetAt) - now).coerceAtLeast(0)
    }
    fun started(now: Long) {
        check(delayMs(now) == 0L)
        starts.addLast(now)
        retryAt = now + 1000
    }
    fun failed(now: Long) {
        failures = (failures + 1).coerceAtMost(6)
        retryAt = maxOf(retryAt, now + minOf(30000L, 1000L shl (failures - 1)))
    }
    fun receivedResult() { failures = 0 }

    companion object {
        // Leave one registration of headroom beneath the platform's five-in-30s limit.
        private const val MAX_STARTS = 4
        private const val WINDOW_MS = 30100L
    }
}
