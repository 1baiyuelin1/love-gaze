package app.champion

/** Shared by the foreground service and scheduled work; persisted across process restarts. */
data class SyncSchedule(
    var lastAttempt: Long = 0L,
    var retryAt: Long = 0L,
    var nextPollMs: Long = 15_000L,
    var failures: Int = 0,
) {
    fun due(now: Long): Boolean {
        if (lastAttempt == 0L || now < lastAttempt) return true
        return now >= if (retryAt > 0) retryAt else lastAttempt + nextPollMs
    }

    fun succeeded(nextPoll: Long) { nextPollMs = nextPoll; retryAt = 0L; failures = 0 }

    fun failed(now: Long, serverRetryAt: Long? = null) {
        failures = (failures + 1).coerceAtMost(6)
        retryAt = serverRetryAt ?: (now + (15_000L * (1L shl (failures - 1))).coerceAtMost(300_000L))
    }
}
