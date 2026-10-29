package app.champion

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class BackgroundStatus(val batteryUnrestricted: Boolean, val systemRestricted: Boolean, val recoveryScheduled: Boolean,
    val standbyBucket: Int = -1) {
    val standbyLabel get() = when (standbyBucket) {
        10 -> "活跃"
        20 -> "工作集"
        30 -> "常用"
        40 -> "很少使用"
        45 -> "受限"
        50 -> "尚未使用"
        else -> "系统未提供"
    }
}

object BackgroundSupport {
    private const val PERIODIC_JOB = 4101
    private const val REMOVED_JOB = 4102

    fun enabled(context: Context) = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).getBoolean("started", false)

    fun status(context: Context) = BackgroundStatus(
        checkNotNull(context.getSystemService(PowerManager::class.java)).isIgnoringBatteryOptimizations(context.packageName),
        checkNotNull(context.getSystemService(ActivityManager::class.java)).isBackgroundRestricted,
        checkNotNull(context.getSystemService(JobScheduler::class.java)).getPendingJob(PERIODIC_JOB) != null,
        checkNotNull(context.getSystemService(UsageStatsManager::class.java)).appStandbyBucket,
    )

    fun schedule(context: Context, taskRemoved: Boolean = false) {
        if (!enabled(context)) return
        val scheduler = checkNotNull(context.getSystemService(JobScheduler::class.java))
        val component = ComponentName(context, RecoveryJob::class.java)
        if (scheduler.getPendingJob(PERIODIC_JOB) == null) {
            val result = scheduler.schedule(JobInfo.Builder(PERIODIC_JOB, component)
                .setPersisted(true).setPeriodic(15 * 60_000L).build())
            if (result == JobScheduler.RESULT_FAILURE) {
                LiveState.value.update { it.copy(backgroundMessage = "系统未接受自动恢复任务，请检查后台限制") }
            }
        }
        if (taskRemoved && scheduler.schedule(JobInfo.Builder(REMOVED_JOB, component)
                .setMinimumLatency(5_000L).build()) == JobScheduler.RESULT_FAILURE) {
            (context.applicationContext as ChampionApplication).health.record("系统未接受移除后的收发任务")
        }
    }

    fun batteryIntent(context: Context): Intent =
        if (status(context).batteryUnrestricted) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /** Vendor screens differ by OS version. Missing screens fall back to app settings. */
    fun autostartIntents(context: Context): List<Intent> {
        val names = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi" -> listOf("com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity")
            "vivo", "iqoo" -> listOf("com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
            else -> emptyList()
        }
        return names.map { Intent().setComponent(ComponentName.unflattenFromString(it)) } +
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }

    fun needsTap(context: Context) {
        val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel("recovery", "记录恢复提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val pending = PendingIntent.getActivity(context, 41, Intent(context, MainActivity::class.java).putExtra("open_settings", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(4103, Notification.Builder(context, "recovery").setSmallIcon(R.drawable.ic_status)
            .setContentTitle("love gaze 需要恢复后台运行")
            .setContentText("点此检查省电限制，恢复记录与同步")
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).build())
    }
}

class RecoveryJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val work = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        if (!BackgroundSupport.enabled(this) || !access(this).ready) return false
        if (work.containsKey(params.jobId)) return true
        val app = application as ChampionApplication
        work[params.jobId] = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.health.previousExit(this@RecoveryJob)
                    val state = BackgroundSupport.status(this@RecoveryJob)
                    app.health.record("系统任务开始补记与收发（待机${state.standbyLabel}，电池豁免=${state.batteryUnrestricted}）")
                }
                val pairing = withContext(Dispatchers.IO) { PairingVault(this@RecoveryJob).read() ?: Pairing.local() }
                try {
                    withContext(Dispatchers.IO) { app.engine.collect(pairing) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    app.health.record("系统任务补记失败：${e.javaClass.simpleName}")
                    LiveState.value.update { it.copy(collectionError = "补记暂未完成，等待系统提供使用记录") }
                }
                // Sync is independent of foreground-service restart permission and collection success.
                val synced = try { withTimeout(90_000L) { app.engine.sync(pairing, "系统任务") } }
                catch (_: TimeoutCancellationException) {
                    app.health.record("系统任务收发超时，保留待传记录")
                    LiveState.value.update { it.copy(syncError = "收发超时，本机记录已保留，稍后重试") }
                    false
                }
                if (!synced && LiveState.value.value.syncError.isBlank()) app.health.record("系统任务：尚未到下次同步时间，或未配置连接")
                if (BackgroundSupport.enabled(this@RecoveryJob) && access(this@RecoveryJob).ready && !LiveState.value.value.running) {
                    try { startForegroundService(Intent(this@RecoveryJob, SharingService::class.java)) }
                    catch (e: IllegalStateException) { restartDenied(app, e) }
                    catch (e: SecurityException) { restartDenied(app, e) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { app.health.record("系统任务中断：${e.javaClass.simpleName}") }
            finally {
                if (work[params.jobId] === currentCoroutineContext()[Job]) {
                    work.remove(params.jobId)
                    jobFinished(params, false)
                }
            }
        }
        // Keep the JobScheduler execution window and wake lock until collection and HTTP finish.
        return true
    }

    private fun restartDenied(app: ChampionApplication, error: Exception) {
        app.health.record("持续服务重启被系统拒绝（${error.javaClass.simpleName}），保留定时收发")
        BackgroundSupport.needsTap(this)
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work.remove(params.jobId)?.cancel()
        (application as ChampionApplication).health.record("系统提前结束收发任务${if (Build.VERSION.SDK_INT >= 31) "，原因 ${params.stopReason}" else ""}")
        return false
    }

    override fun onDestroy() {
        work.clear()
        scope.cancel()
        super.onDestroy()
    }
}
