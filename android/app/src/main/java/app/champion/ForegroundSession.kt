package app.champion

data class SessionSnapshot(val packageName: String = "", val started: Long = 0L, val pauseAt: Long? = null,
    val lastUsage: Long = 0L, val announced: Boolean = false)

/** Event timestamps are supplied by Android; short Activity transitions are not sessions. */
class ForegroundSession(private val emit: (Kind, String, Long, Long) -> Unit) {
    companion object { const val MIN_DURATION = 2_000L }
    var currentPackage = ""
        private set
    private var started = 0L
    private var pauseAt: Long? = null
    private var lastUsage = 0L
    private var announced = false

    fun snapshot() = SessionSnapshot(currentPackage, started, pauseAt, lastUsage, announced)

    fun restore(value: SessionSnapshot) {
        discard()
        if (!Apps.important(value.packageName) || value.started < 0) return
        currentPackage = value.packageName
        started = value.started
        pauseAt = value.pauseAt
        lastUsage = value.lastUsage
        announced = value.announced
    }

    fun discard() {
        currentPackage = ""; started = 0L; pauseAt = null; lastUsage = 0L; announced = false
    }

    fun resume(pkg: String, at: Long) {
        val important = pkg.takeIf(Apps::important).orEmpty()
        if (important != currentPackage || pauseAt?.let { at - it >= MIN_DURATION } == true) {
            leave(pauseAt ?: at)
            currentPackage = important
            started = at
            lastUsage = at
        }
        pauseAt = null
    }

    fun pause(pkg: String, at: Long) {
        if (pkg == currentPackage && pkg.isNotBlank() && pauseAt == null) pauseAt = at
    }

    fun leave(at: Long) {
        val end = pauseAt ?: at
        if (currentPackage.isNotBlank() && end - started >= MIN_DURATION) {
            announce()
            emit(Kind.LEAVE, currentPackage, end, end - started)
        }
        discard()
    }

    fun tick(now: Long) {
        val paused = pauseAt
        if (paused != null && now - paused >= MIN_DURATION) { leave(paused); return }
        if (currentPackage.isBlank() || paused != null) return
        if (now - started >= MIN_DURATION) announce()
        if (announced && now - lastUsage >= 300_000L) {
            emit(Kind.USAGE, currentPackage, now, now - started)
            lastUsage = now
        }
    }

    private fun announce() {
        if (!announced) {
            emit(Kind.OPEN, currentPackage, started, 0)
            announced = true
        }
    }
}
