package app.champion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

class SharingService : Service() {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var location: LocationCollector? = null
    private var pairingState: Pairing? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "app.champion.RECONFIGURE") {
            location?.stop()
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            started = false
            LiveState.value.update { it.copy(syncError = "") }
        }
        try {
            if (started) {
                pairingState?.let { updateLocation(it, intent?.action == "app.champion.REFRESH_LOCATION") }
                return START_STICKY
            }
            val vault = PairingVault(this)
            val pairing = vault.read() ?: Pairing.local()
            if (!access(this).ready) return stopIncomplete()
            pairingState = pairing
            showForeground(pairing, access(this).locationReady)
            started = true
            LiveState.value.update { it.copy(running = true, collectionError = "") }
            val app = application as ChampionApplication
            app.health.record("持续记录服务已启动")
            BackgroundSupport.schedule(this)
            checkNotNull(getSystemService(NotificationManager::class.java)).cancel(4103)
            updateLocation(pairing)
            scope.launch(Dispatchers.IO) {
                app.health.previousExit(this@SharingService)
                var collectionFailed = false
                while (isActive) {
                    if (!access(this@SharingService).ready) {
                        LiveState.value.update { it.copy(collectionError = "应用使用情况或运行通知已关闭，请在设置中恢复") }
                        stopSelf()
                        break
                    }
                    try {
                        if (access(this@SharingService).locationReady != (location != null)) {
                            withContext(Dispatchers.Main.immediate) { updateLocation(pairing) }
                        }
                        app.engine.collect(pairing, location?.currentPlace)
                        LiveState.value.update { it.copy(collectionError = location?.error.orEmpty()) }
                        collectionFailed = false
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        android.util.Log.e("champion", "Local collection failed: ${e.javaClass.simpleName}")
                        LiveState.value.update { it.copy(collectionError = "采集暂时中断，请检查系统权限和存储空间") }
                        if (!collectionFailed) app.health.record("持续服务采集中断：${e.javaClass.simpleName}")
                        collectionFailed = true
                    }
                    delay(2_000)
                }
            }
            if (pairing.connected) scope.launch {
                while (isActive) {
                    try {
                        app.engine.sync(pairing, "持续服务")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        LiveState.value.update { it.copy(syncError = "无法保存同步状态，请检查手机存储空间") }
                        app.health.record("持续服务同步中断：${e.javaClass.simpleName}")
                        delay(300_000L)
                    }
                    delay(2_000L)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("champion", "Service start failed: ${e.javaClass.simpleName}")
            LiveState.value.update { it.copy(collectionError = "后台服务未启动，请回到 love gaze 检查系统权限") }
            (application as ChampionApplication).health.record("持续服务启动失败：${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun showForeground(pairing: Pairing, withLocation: Boolean) {
        val notifications = checkNotNull(getSystemService(NotificationManager::class.java))
        notifications.createNotificationChannel(NotificationChannel("sharing", "持续双向共享", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val settings = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).putExtra("open_settings", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = "${pairing.role} ↔ ${pairing.partner} · ${if (withLocation) "App 与位置自动记录" else "App 自动记录 · 位置待设置"}"
        val notification = Notification.Builder(this, "sharing")
            .setSmallIcon(R.drawable.ic_status).setContentTitle(if (pairing.connected) "love gaze · 日常共享中" else "love gaze · 本机记录中")
            .setContentText(text).setOngoing(true).setContentIntent(pending)
            .addAction(Notification.Action.Builder(null, "后台与权限设置", settings).build())
            .setCategory(Notification.CATEGORY_SERVICE).build()
        val special = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        startForeground(100, notification, special or if (withLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
    }

    private fun updateLocation(pairing: Pairing, force: Boolean = false) {
        try {
            val canLocate = access(this).locationReady
            if (canLocate && location == null) {
                showForeground(pairing, true)
                val collector = LocationCollector(this, pairing, scope, (application as ChampionApplication).store)
                location = collector
                collector.start()
            } else if (!canLocate) {
                location?.stop()
                location = null
                showForeground(pairing, false)
                LiveState.value.update { it.copy(locationMessage = "位置暂不可用；App 记录与同步继续运行") }
            } else location?.refresh(force)
        } catch (_: SecurityException) {
            location?.stop(); location = null
            LiveState.value.update { it.copy(locationMessage = "请检查位置权限；App 记录与同步继续运行") }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as ChampionApplication).health.record("已从最近任务移除，安排补记与收发")
        BackgroundSupport.schedule(this, taskRemoved = true)
        super.onTaskRemoved(rootIntent)
    }

    private fun stopIncomplete(): Int {
        LiveState.value.update { it.copy(running = false, collectionError = "请完成系统权限设置") }
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        location?.stop()
        scope.cancel()
        LiveState.value.update { it.copy(running = false) }
        (application as ChampionApplication).health.record("持续服务已结束，等待系统任务")
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        try {
            if (context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).getBoolean("started", false)
                && access(context).ready) {
                BackgroundSupport.schedule(context)
                context.startForegroundService(Intent(context, SharingService::class.java))
            }
        } catch (e: Exception) {
            android.util.Log.w("champion", "System declined background restart: ${e.javaClass.simpleName}")
        }
    }
}
