package app.champion

import org.junit.Assert.*
import org.junit.Test

class ActivityTimelineTest {
    private val events = mutableListOf<Moment>()
    private val session = ForegroundSession { kind, pkg, at, duration ->
        events += Moment(owner = "white", kind = kind, packageName = pkg, at = at, durationMs = duration)
    }
    private val wechat = "com.tencent.mm"

    @Test fun launcherAndQuickSwitchesProduceNoBubbles() {
        session.resume("com.miui.home", 1000)
        session.tick(4000)
        session.resume(wechat, 5000)
        session.pause(wechat, 6000)
        session.resume("com.bbk.launcher2", 6000)
        session.tick(10000)
        assertTrue(events.isEmpty())
        assertEquals("", session.currentPackage)
    }

    @Test fun sameAppActivityTransitionsAreOneTimedSession() {
        session.resume(wechat, 1000)
        session.tick(3000)
        session.pause(wechat, 4000)
        session.resume(wechat, 4000)
        session.pause(wechat, 8000)
        session.resume("com.bbk.launcher2", 8100)
        assertEquals(listOf(Kind.OPEN, Kind.LEAVE), events.map { it.kind })
        assertEquals(7000L, events.last().durationMs)
        assertEquals(8000L, events.last().at)
        val timeline = Timeline()
        assertEquals(1, events.asReversed().count(timeline::accept))
    }

    @Test fun bufferedPauseDoesNotCountBackgroundAsForeground() {
        session.resume(wechat, 1000)
        session.pause(wechat, 4000)
        session.resume(wechat, 9000)
        session.tick(11000)
        assertEquals(listOf(Kind.OPEN, Kind.LEAVE, Kind.OPEN), events.map { it.kind })
        assertEquals(3000L, events[1].durationMs)
        assertEquals(9000L, events.last().at)
    }

    @Test fun longUseUpdatesAndLeaveShareOneBubble() {
        session.resume(wechat, 1000)
        session.tick(4000)
        session.tick(301000)
        session.leave(310000)
        val timeline = Timeline()
        val visible = events.asReversed().filter(timeline::accept)
        assertEquals(1, visible.size)
        assertEquals(Kind.LEAVE, visible.single().kind)
        assertEquals(309000L, visible.single().durationMs)
    }

    @Test fun legacyZeroSecondPairAndLauncherAreHiddenButLocationRemains() {
        val timeline = Timeline()
        val records = listOf(
            Moment(owner = "white", kind = Kind.LEAVE, packageName = wechat, at = 1000, durationMs = 0),
            Moment(owner = "white", kind = Kind.OPEN, packageName = wechat, at = 1000),
            Moment(owner = "white", kind = Kind.OPEN, packageName = "com.miui.home", at = 800),
            Moment(owner = "champion", kind = Kind.LOCATION, at = 700, place = "中国", latitude = 31.2, longitude = 121.5),
        )
        assertEquals(listOf(Kind.LOCATION), records.filter(timeline::accept).map { it.kind })
    }

    @Test fun twoPeopleAndRepeatVisitsRemainSeparate() {
        val timeline = Timeline()
        val records = listOf(
            Moment(owner = "white", kind = Kind.LEAVE, packageName = wechat, at = 9000, durationMs = 3000),
            Moment(owner = "white", kind = Kind.OPEN, packageName = wechat, at = 6000),
            Moment(owner = "champion", kind = Kind.LEAVE, packageName = wechat, at = 4000, durationMs = 3000),
            Moment(owner = "white", kind = Kind.LEAVE, packageName = wechat, at = 4000, durationMs = 3000),
            Moment(owner = "champion", kind = Kind.OPEN, packageName = wechat, at = 1000),
            Moment(owner = "white", kind = Kind.OPEN, packageName = wechat, at = 1000),
        )
        assertEquals(3, records.count(timeline::accept))
    }
}
