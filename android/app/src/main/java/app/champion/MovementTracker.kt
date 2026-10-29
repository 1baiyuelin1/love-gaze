package app.champion

/** The anchor is the last published position, not the previous GPS sample. */
class MovementTracker(
    initial: Moment?,
    private val distance: (Moment, Moment) -> Float,
    private val persist: (Moment, Boolean) -> Unit,
) {
    companion object { const val THRESHOLD_METERS = 500f }
    private var anchor = initial

    fun moved(sample: Moment): Boolean = anchor?.let { distance(it, sample) >= THRESHOLD_METERS } ?: false

    fun accept(sample: Moment): Boolean {
        val publish = moved(sample)
        if (anchor == null || publish) {
            // A first fix establishes a baseline silently. Advance only after persistence succeeds.
            persist(sample, publish)
            anchor = sample
        }
        return publish
    }
}
