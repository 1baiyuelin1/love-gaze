package app.champion

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Only operational timestamps and outcomes; no app history, coordinates or credentials. */
class BackgroundHealth(context: Context) {
    private val prefs = context.getSharedPreferences("background_health", Context.MODE_PRIVATE)
    private var lastCollection = prefs.getLong("collection", 0L)
    private val format = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    init {
        LiveState.value.update { it.copy(lastCollection = lastCollection, lastSync = prefs.getLong("sync", 0L),
            backgroundLog = prefs.getString("log", "").orEmpty()) }
    }

    @Synchronized fun record(message: String, at: Long = System.currentTimeMillis()) {
        val line = "${format.format(Instant.ofEpochMilli(at))}  $message"
        val log = (listOf(line) + prefs.getString("log", "").orEmpty().lines().filter(String::isNotBlank)).take(12).joinToString("\n")
        prefs.edit().putString("log", log).apply()
        LiveState.value.update { it.copy(backgroundLog = log) }
    }

    @Synchronized fun collected(now: Long) {
        LiveState.value.update { it.copy(lastCollection = now) }
        if (now < lastCollection || now - lastCollection >= 30_000L) {
            lastCollection = now
            prefs.edit().putLong("collection", now).apply()
        }
    }

    fun synced(now: Long) {
        prefs.edit().putLong("sync", now).apply()
        LiveState.value.update { it.copy(lastSync = now, syncError = "") }
    }

    fun previousExit(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val exit = try {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, 1)?.firstOrNull()
        } catch (_: Exception) { null } ?: return
        if (exit.timestamp <= prefs.getLong("exit", 0L)) return
        val reason = when (exit.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "系统内存不足"
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "进程崩溃"
            ApplicationExitInfo.REASON_ANR -> "应用无响应"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "用户操作或系统清理（系统未区分具体操作）"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "系统权限变更"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "系统资源限制"
            else -> "系统退出原因 ${exit.reason}"
        }
        record("上次进程结束：$reason", exit.timestamp)
        prefs.edit().putLong("exit", exit.timestamp).apply()
    }
}
