package app.champion

import org.junit.Assert.*
import org.junit.Test

class BackgroundRecoveryTest {
    private val wechat = "com.tencent.mm"

    @Test fun appRecordingDoesNotDependOnLocation() {
        val withoutLocation = Access(usage = true, location = false, background = false, notifications = true, locationEnabled = false, precise = false)
        assertTrue(withoutLocation.ready)
        assertFalse(withoutLocation.locationReady)
        assertFalse(withoutLocation.copy(usage = false).ready)
        assertFalse(withoutLocation.copy(notifications = false).ready)
    }

    @Test fun restartKeepsRealOpenTimeAndRecordsActualClose() {
        val received = mutableListOf<Moment>()
        fun collector() = ForegroundSession { kind, pkg, at, duration -> received += Moment(owner = "white", kind = kind, packageName = pkg, at = at, durationMs = duration) }
        val first = collector()
        first.resume(wechat, 1000)
        first.tick(4000)
        val restarted = collector()
        restarted.restore(first.snapshot())
        restarted.pause(wechat, 90000)
        restarted.resume("com.miui.home", 90020)
        assertEquals(listOf(Kind.OPEN, Kind.LEAVE), received.map { it.kind })
        assertEquals(89000L, received.last().durationMs)
        assertEquals(1, received.asReversed().count(Timeline()::accept))
    }

    @Test fun pauseSavedBeforeProcessExitDoesNotInflateDuration() {
        val endings = mutableListOf<Long>()
        val first = ForegroundSession { _, _, _, _ -> }
        first.resume(wechat, 1000)
        first.tick(3000)
        first.pause(wechat, 5000)
        val restarted = ForegroundSession { kind, _, _, duration -> if (kind == Kind.LEAVE) endings += duration }
        restarted.restore(first.snapshot())
        restarted.tick(60000)
        assertEquals(listOf(4000L), endings)
    }

    @Test fun realSessionsInBufferedSystemHistoryAreRecovered() {
        val events = mutableListOf<Moment>()
        val session = ForegroundSession { kind, pkg, at, duration -> events += Moment(owner = "champion", kind = kind, packageName = pkg, at = at, durationMs = duration) }
        session.resume(wechat, 1000)
        session.pause(wechat, 5000)
        session.resume("com.bbk.launcher2", 5100)
        session.resume("com.sankuai.meituan", 10000)
        session.pause("com.sankuai.meituan", 70000)
        session.resume("com.bbk.launcher2", 70100)
        session.tick(120000)
        assertEquals(listOf(4000L, 60000L), events.filter { it.kind == Kind.LEAVE }.map { it.durationMs })
        assertEquals(2, events.asReversed().count(Timeline()::accept))
    }

    @Test fun shutdownDoesNotInventClosingTime() {
        val events = mutableListOf<Kind>()
        val session = ForegroundSession { kind, _, _, _ -> events += kind }
        session.resume(wechat, 1000)
        session.tick(3000)
        session.discard()
        session.tick(90000)
        assertEquals(listOf(Kind.OPEN), events)
    }

    @Test fun shortInterruptionReplaysFromCheckpointInsteadOfNow() {
        val window = ReplayWindow.since(1000, 180000)
        assertEquals(1000L, window.from)
        assertTrue(window.keepSession)
        assertFalse(window.truncated)
    }

    @Test fun firstRunDoesNotImportActivityBeforeSetup() {
        val window = ReplayWindow.since(0, 100000)
        assertEquals(100000L, window.from)
        assertFalse(window.keepSession)
    }

    @Test fun oldHistoryAndClockChangesDoNotExtendUnknownSessions() {
        val old = ReplayWindow.since(1000, ReplayWindow.MAX_AGE + 5000)
        assertEquals(5000L, old.from)
        assertFalse(old.keepSession)
        assertTrue(old.truncated)
        val reversed = ReplayWindow.since(9000, 8000)
        assertEquals(8000L, reversed.from)
        assertFalse(reversed.keepSession)
    }
}
