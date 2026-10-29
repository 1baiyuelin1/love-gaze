package app.champion

data class ReplayWindow(val from: Long, val keepSession: Boolean, val truncated: Boolean) {
    companion object {
        const val MAX_AGE = 24 * 60 * 60_000L
        fun since(cursor: Long, now: Long): ReplayWindow = when {
            cursor <= 0 -> ReplayWindow(now, false, false)
            cursor > now -> ReplayWindow(now, false, true)
            cursor < now - MAX_AGE -> ReplayWindow(now - MAX_AGE, false, true)
            else -> ReplayWindow(cursor, true, false)
        }
    }
}
