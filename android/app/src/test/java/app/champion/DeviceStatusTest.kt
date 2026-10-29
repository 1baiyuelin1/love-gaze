package app.champion

import org.junit.Assert.*
import org.junit.Test

class DeviceStatusTest {
    private val now = 2_000_000L
    private fun presence(status: String, age: Long = 0) = Moment(owner = "champion", kind = Kind.PRESENCE,
        status = status, at = now - age, packageName = "com.tencent.mm", appName = "微信")

    @Test fun lockedStateOverridesOldForegroundAppAndUnlockedScreenShowsApp() {
        assertEquals("锁屏中", DeviceStatus.capture(true, true, "com.tencent.mm", "微信", false, false).text)
        assertEquals("锁屏中", DeviceStatus.capture(false, true, "com.tencent.mm", "微信", false, false).text)
        assertEquals("正在用 微信", DeviceStatus.capture(true, false, "com.tencent.mm", "微信", false, false).text)
    }

    @Test fun screenOffDesktopAndOwnAppHaveConcreteStates() {
        assertEquals("息屏中", DeviceStatus.capture(false, false, "", "", false, false).text)
        assertEquals("桌面停留", DeviceStatus.capture(true, false, "com.miui.home", "桌面", true, false).text)
        assertEquals("正在看 love gaze", DeviceStatus.capture(true, false, "app.champion.white", "", false, true).text)
        assertEquals("亮屏中", DeviceStatus.capture(true, false, "", "", false, false).text)
    }

    @Test fun unlistedAppCanHaveAStatusWithoutCreatingAChatBubble() {
        val status = DeviceStatus.capture(true, false, "test.launchable", "阅读软件", false, false)
        assertEquals("正在用 阅读软件", status.text)
        assertFalse(Timeline().accept(Moment(owner = "white", kind = Kind.OPEN, packageName = status.packageName)))
    }

    @Test fun oldLockEventsAreHiddenButLocationAndAppBubblesRemain() {
        val timeline = Timeline()
        assertFalse(timeline.accept(Moment(owner = "white", kind = Kind.LOCK)))
        assertFalse(timeline.accept(Moment(owner = "champion", kind = Kind.LOCK)))
        assertTrue(timeline.accept(Moment(owner = "champion", kind = Kind.LOCATION)))
        assertTrue(timeline.accept(Moment(owner = "white", kind = Kind.OPEN, packageName = "com.tencent.mm")))
    }

    @Test fun pauseFromPreviousAppDoesNotEraseNewForegroundActivity() {
        val active = ForegroundActivity()
        active.resume("com.tencent.mm")
        active.resume("com.miui.home")
        active.pause("com.tencent.mm", 100)
        active.tick(3000)
        assertEquals("com.miui.home", active.packageName)
        active.pause("com.miui.home", 4000)
        active.resume("com.miui.home")
        active.tick(6000)
        assertEquals("com.miui.home", active.packageName)
    }

    @Test fun pausedAppExpiresAndScreenOffClearsForegroundIdentity() {
        val active = ForegroundActivity("com.tencent.mm")
        active.pause("com.tencent.mm", 1000)
        active.tick(3000)
        assertEquals("", active.packageName)
        active.resume("com.tencent.mm"); active.clear()
        assertEquals("", active.packageName)
    }

    @Test fun remoteLockRemainsVisibleDuringNormalSyncDelayWithoutPretendingToBeLive() {
        val label = PresenceLabels.display(presence("锁屏中", 600_000), false, true, now)
        assertEquals("锁屏中", label.text)
        assertEquals("上次状态", label.hint)
        assertFalse(label.fresh)
    }

    @Test fun staleStoppedAndFutureStatesCannotAppearCurrent() {
        assertEquals("状态待更新", PresenceLabels.display(presence("锁屏中", 900_001), false, true, now).text)
        assertEquals("状态待更新", PresenceLabels.display(presence("锁屏中", -60_000), false, true, now).text)
        assertEquals("记录未运行", PresenceLabels.display(presence("桌面停留"), true, false, now).text)
        assertEquals("当前", PresenceLabels.display(presence("桌面停留"), true, true, now).hint)
    }

    @Test fun olderPartnerVersionDoesNotInventAnUnlockedState() {
        val old = presence("设备采集运行中").copy(packageName = "", appName = "")
        assertEquals("等待设备状态", PresenceLabels.display(old, false, true, now).text)
    }
}
