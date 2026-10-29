package app.champion

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import kotlin.math.abs

class MovementTrackerTest {
    private var saved: String? = null
    private val messages = mutableListOf<Moment>()
    // Latitude is a deterministic metre axis in this unit fixture; Android supplies WGS84 distance in production.
    private fun fix(meters: Int, accuracy: Double = 10.0) = Moment(owner = "white", kind = Kind.LOCATION,
        latitude = meters / 1000.0, longitude = 0.0, accuracy = accuracy, at = 1000L + meters)
    private fun tracker() = MovementTracker(saved?.let { Moment.parse(JSONObject(it)) },
        { a, b -> (abs(checkNotNull(a.latitude) - checkNotNull(b.latitude)) * 1000).toFloat() },
        { sample, publish -> saved = sample.json().toString(); if (publish) messages.add(sample) })

    @Test fun firstFixAndRepeatedReopensDoNotCreateMessages() {
        tracker().accept(fix(0))
        repeat(4) { tracker().accept(fix(0)) }
        assertNotNull(saved)
        assertTrue(messages.isEmpty())
    }

    @Test fun smallStepsAreComparedWithSavedAnchorNotPreviousSample() {
        val collector = tracker()
        listOf(0, 100, 240, 400, 499).forEach { collector.accept(fix(it)) }
        assertTrue(messages.isEmpty())
        assertTrue(collector.accept(fix(510)))
        assertEquals(1, messages.size)
    }

    @Test fun precisionImprovementAndManualRefreshDoNotBypassDistance() {
        val collector = tracker()
        collector.accept(fix(0, 140.0))
        collector.accept(fix(70, 5.0))
        repeat(3) { collector.accept(fix(70, 3.0)) }
        assertTrue(messages.isEmpty())
    }

    @Test fun publishedPositionSurvivesRestartAndAllowsRealReturnTrip() {
        val collector = tracker()
        collector.accept(fix(0))
        collector.accept(fix(700))
        val restarted = tracker()
        assertFalse(restarted.accept(fix(750)))
        assertTrue(restarted.accept(fix(0)))
        assertEquals(listOf(0.7, 0.0), messages.map { it.latitude })
    }

    @Test fun clearingVisibleHistoryDoesNotRemoveTheSeparateAnchor() {
        val collector = tracker()
        collector.accept(fix(0)); collector.accept(fix(600))
        messages.clear()
        tracker().accept(fix(650))
        assertTrue(messages.isEmpty())
        assertTrue(tracker().accept(fix(1200)))
    }

    @Test fun failedSaveCanRetryWithoutLosingTheMovement() {
        var fail = true
        val collector = MovementTracker(fix(0), { _, _ -> 600f }, { sample, publish ->
            if (fail) throw java.io.IOException("disk full")
            if (publish) messages.add(sample)
        })
        assertThrows(java.io.IOException::class.java) { collector.accept(fix(600)) }
        fail = false
        assertTrue(collector.accept(fix(600)))
        assertEquals(1, messages.size)
    }
}
