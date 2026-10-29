package app.champion

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import java.util.UUID

class UsageCollector(
    private val context: Context,
    private val pairing: Pairing,
    private val emit: (Moment) -> Unit,
) {
    private val prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)
    private val manager = checkNotNull(context.getSystemService(UsageStatsManager::class.java))
    private var cursor = prefs.getLong("cursor", 0L)
    private val session = ForegroundSession(::event)
    private val activity = ForegroundActivity()
    private val keyguard = checkNotNull(context.getSystemService(KeyguardManager::class.java))
    private val power = checkNotNull(context.getSystemService(PowerManager::class.java))
    private val homePackages = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
    ).map { it.activityInfo.packageName }.toSet()
    private val labels = mutableMapOf<String, String>()

    init {
        val now = System.currentTimeMillis()
        val window = ReplayWindow.since(cursor, now)
        if (window.keepSession) {
            session.restore(SessionSnapshot(prefs.getString("package", "").orEmpty(), prefs.getLong("started", 0),
                prefs.getLong("pause", -1).takeIf { it >= 0 }, prefs.getLong("lastUsage", 0), prefs.getBoolean("announced", false)))
            activity.packageName = prefs.getString("activePackage", session.currentPackage).orEmpty()
            activity.pauseAt = prefs.getLong("activePause", -1L).takeIf { it >= 0 }
        }
        if (window.truncated) emit(Moment(owner = pairing.role, kind = Kind.GAP, at = now))
        cursor = window.from
    }

    private fun label(pkg: String) = labels.getOrPut(pkg) {
        try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString().take(100)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            Apps.preset(pkg)?.name ?: pkg
        }
    }

    private fun event(kind: Kind, pkg: String, at: Long, duration: Long = 0) {
        val id = UUID.nameUUIDFromBytes("${pairing.role}:$kind:$pkg:$at".toByteArray(Charsets.UTF_8)).toString()
        emit(Moment(id = id, owner = pairing.role, at = at, kind = kind, packageName = pkg,
            appName = if (pkg.isBlank()) "" else label(pkg), durationMs = duration.coerceAtLeast(0)))
    }

    fun poll() {
        check(access(context).usage) { "使用情况访问已关闭，请在系统设置中恢复" }
        val now = System.currentTimeMillis()
        val window = ReplayWindow.since(cursor, now)
        if (!window.keepSession) {
            session.discard()
            activity.clear()
            emit(Moment(owner = pairing.role, kind = Kind.GAP, at = now))
            cursor = window.from
        }
        val events = manager.queryEvents(cursor, now)
            ?: throw IllegalStateException("系统暂未提供应用使用事件")
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.timeStamp < cursor || e.timeStamp >= now) continue
            val pkg = e.packageName.orEmpty()
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    session.resume(pkg, e.timeStamp)
                    activity.resume(pkg)
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> { session.pause(pkg, e.timeStamp); activity.pause(pkg, e.timeStamp) }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    session.leave(e.timeStamp)
                    activity.clear()
                }
                // Android says shutdown timestamps are not exact session end times.
                UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP -> { session.discard(); activity.clear() }
            }
        }
        // An Activity-to-Activity transition inside the same package is one session.
        session.tick(now)
        activity.tick(now)
        cursor = now
        checkpoint()
    }

    fun status(): DeviceStatus {
        val pkg = if (LiveState.foreground) context.packageName else activity.packageName
        return DeviceStatus.capture(power.isInteractive, keyguard.isKeyguardLocked, pkg,
            if (pkg.isBlank()) "" else label(pkg), pkg in homePackages, pkg == context.packageName)
    }

    private fun checkpoint() {
        val state = session.snapshot()
        prefs.edit().putLong("cursor", cursor).putString("package", state.packageName)
            .putLong("started", state.started).putLong("pause", state.pauseAt ?: -1L)
            .putLong("lastUsage", state.lastUsage).putBoolean("announced", state.announced)
            .putString("activePackage", activity.packageName).putLong("activePause", activity.pauseAt ?: -1L)
            .remove("locked").apply()
    }
}
