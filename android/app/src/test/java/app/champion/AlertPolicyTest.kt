package app.champion

import org.junit.Assert.*
import org.junit.Test

class AlertPolicyTest {
    private val now = 2_000_000_000L
    private fun open(pkg: String = "com.tencent.mm", owner: String = "champion", at: Long = now - 300_000) =
        Moment(owner = owner, at = at, kind = Kind.OPEN, packageName = pkg)

    @Test fun allFourAppsNotifyInEitherDirection() {
        for (owner in listOf("white", "champion")) {
            val events = AlertPolicy.packages.map { open(it, owner) }
            assertEquals(4, AlertPolicy.select(events, owner, now - 600_000, now).size)
        }
    }

    @Test fun ownActivityAndNonPriorityAppsNeverNotify() {
        val events = listOf(open(owner = "white"), open("com.jingdong.app.mall"), open("com.android.launcher"))
        assertTrue(AlertPolicy.select(events, "champion", 0, now).isEmpty())
    }

    @Test fun DurationUpdatesAndLeavesDoNotRepeatTheOpenAlert() {
        val events = listOf(Kind.LEAVE, Kind.USAGE, Kind.PRESENCE, Kind.LOCATION).map { open().copy(kind = it) }
        assertTrue(AlertPolicy.select(events, "champion", 0, now).isEmpty())
    }

    @Test fun oldHistoryBeforeEnablingAndFutureClockSkewDoNotFlood() {
        val events = listOf(open(at = now - 1_800_001), open(at = now + 120_001), open(at = now - 500_000))
        assertTrue(AlertPolicy.select(events, "champion", now - 400_000, now).isEmpty())
    }

    @Test fun repeatedOpensOfSameAppShowOnlyLatestWithinBatch() {
        val old = open(at = now - 300_000)
        val newest = open(at = now - 100_000)
        assertEquals(listOf(newest), AlertPolicy.select(listOf(old, newest, newest), "champion", 0, now))
    }

    @Test fun normalBackgroundDelayStillNotifiesAndKeepsActualTime() {
        val event = open(at = now - 600_000)
        assertEquals(event, AlertPolicy.select(listOf(event), "champion", 0, now).single())
    }
}
