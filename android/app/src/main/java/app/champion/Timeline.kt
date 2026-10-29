package app.champion

/** Consumes newest first. A foreground session has one bubble, even across sync batches. */
class Timeline {
    private val sessions = hashSetOf<String>()

    fun accept(moment: Moment): Boolean {
        if (moment.kind == Kind.PRESENCE || moment.kind == Kind.LOCK) return false
        if (moment.kind !in listOf(Kind.OPEN, Kind.USAGE, Kind.LEAVE)) return true
        if (!Apps.important(moment.packageName)) return false
        val start = if (moment.kind == Kind.OPEN) moment.at else moment.at - moment.durationMs
        val key = "${moment.owner}:${moment.packageName}:$start"
        if (!sessions.add(key)) return false
        // Also consumes the key for an old zero-second leave, hiding its matching open.
        return moment.kind == Kind.OPEN || moment.durationMs >= ForegroundSession.MIN_DURATION
    }
}
