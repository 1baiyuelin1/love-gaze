package app.champion

/** Foreground identity for the status card; it does not create chat events. */
class ForegroundActivity(var packageName: String = "", var pauseAt: Long? = null) {
    fun resume(pkg: String) { packageName = pkg; pauseAt = null }
    fun pause(pkg: String, at: Long) { if (pkg == packageName && pauseAt == null) pauseAt = at }
    fun clear() { packageName = ""; pauseAt = null }
    fun tick(now: Long) { if (pauseAt?.let { now - it >= 2_000L } == true) clear() }
}

data class DeviceStatus(val packageName: String = "", val appName: String = "", val text: String) {
    companion object {
        fun capture(interactive: Boolean, keyguard: Boolean, pkg: String, name: String, home: Boolean, ownApp: Boolean): DeviceStatus = when {
            keyguard -> DeviceStatus(text = "锁屏中")
            !interactive -> DeviceStatus(text = "息屏中")
            ownApp -> DeviceStatus(pkg, "love gaze", "正在看 love gaze")
            home -> DeviceStatus(text = "桌面停留")
            pkg == "com.android.systemui" || pkg == "android" -> DeviceStatus(text = "系统界面")
            pkg.isNotBlank() && name.isNotBlank() && name != pkg -> DeviceStatus(pkg, name, "正在用 $name")
            else -> DeviceStatus(text = "亮屏中")
        }
    }
}

data class PresenceLabel(val text: String, val hint: String, val fresh: Boolean)

object PresenceLabels {
    fun display(moment: Moment?, own: Boolean, running: Boolean, now: Long): PresenceLabel {
        if (own && !running) return PresenceLabel("记录未运行", "待恢复", false)
        if (moment == null) return PresenceLabel("等待状态", "连接中", false)
        val age = now - moment.at
        if (age < -30_000L || age > (if (own) 90_000L else 900_000L))
            return PresenceLabel("状态待更新", "待同步", false)
        val text = moment.status.takeIf { it in setOf("锁屏中", "息屏中", "桌面停留", "系统界面", "亮屏中", "正在看 love gaze") || it.startsWith("正在用 ") }
            ?: if (Apps.important(moment.packageName)) "正在用 ${Apps.preset(moment.packageName)?.name}" else "等待设备状态"
        val fresh = age <= 90_000L
        return PresenceLabel(text, if (own) "当前" else if (fresh) "已同步" else "上次状态", fresh)
    }
}
