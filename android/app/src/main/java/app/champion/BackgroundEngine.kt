package app.champion

import android.os.BatteryManager
import android.os.PowerManager
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One collector and one sync lane per process, including overlapping JobService callbacks. */
class BackgroundEngine(private val app: ChampionApplication) {
    private var collectionPair: Pairing? = null
    private var usage: UsageCollector? = null
    private var heartbeat = 0L
    private var lastStatus: DeviceStatus? = null
    private var pruned = 0L
    private val syncLock = Mutex()
    private var syncPair: Pairing? = null
    private var client: SyncClient? = null
    private val prefs = app.getSharedPreferences("aliyun_sync_schedule", Context.MODE_PRIVATE)
    private val schedule = SyncSchedule(prefs.getLong("attempt", 0L), prefs.getLong("retry", 0L),
        prefs.getLong("nextPoll", 15_000L), prefs.getInt("failures", 0))
    private var lastSyncSummary = 0L

    @Synchronized fun collect(pairing: Pairing, place: String? = null) {
        if (!BackgroundSupport.enabled(app) || !access(app).ready) return
        if (collectionPair != pairing) {
            usage = UsageCollector(app, pairing, app.store::append)
            collectionPair = pairing
            heartbeat = 0L
            lastStatus = null
        }
        val collector = checkNotNull(usage)
        collector.poll()
        val now = System.currentTimeMillis()
        app.health.collected(now)
        val status = collector.status()
        if (status != lastStatus || now - heartbeat >= 30_000L) {
            app.store.append(Moment(owner = pairing.role, kind = Kind.PRESENCE, packageName = status.packageName,
                appName = status.appName, place = place.orEmpty(),
                battery = checkNotNull(app.getSystemService(BatteryManager::class.java))
                    .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).let { if (it in 0..100) it else -1 },
                status = status.text))
            heartbeat = now
            lastStatus = status
        }
        if (now - pruned > 3_600_000L) { app.store.prune(); pruned = now }
    }

    suspend fun sync(pairing: Pairing, source: String): Boolean = withContext(Dispatchers.IO) {
        syncLock.withLock {
            if (!BackgroundSupport.enabled(app) || !access(app).ready || !pairing.connected) return@withLock false
            val now = System.currentTimeMillis()
            if (!schedule.due(now)) return@withLock false
            schedule.lastAttempt = now
            persistSchedule()
            val wake = checkNotNull(app.getSystemService(PowerManager::class.java))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "love-gaze:sync")
            try {
                // Hold only during one network exchange, never between polls or indefinitely.
                wake.acquire(60_000L)
                if (syncPair != pairing) {
                    client = SyncClient(pairing, PairingVault(app).installation)
                    syncPair = pairing
                }
                val result = checkNotNull(client).sync(app.store, app.appearance)
                currentCoroutineContext().ensureActive()
                schedule.succeeded(result.nextPollMs)
                persistSchedule()
                app.health.synced(System.currentTimeMillis())
                if (now - lastSyncSummary >= 300_000L || lastSyncSummary == 0L) {
                    app.health.record("$source：收发完成，收到 ${result.received.count { it.kind != Kind.PRESENCE }} 条动态")
                    lastSyncSummary = now
                }
                try { PartnerNotifications(app, pairing.partner).show(result.received) }
                catch (e: Exception) { app.health.record("动态已接收，通知未显示：${e.javaClass.simpleName}") }
                true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                schedule.failed(System.currentTimeMillis(), (e as? SyncRateLimited)?.retryAt)
                persistSchedule()
                val message = when (e) {
                    is ConnectionRejected, is SyncRateLimited -> e.message.orEmpty()
                    is java.io.IOException -> "暂时连不上同步服务，本机记录已保留，稍后重试"
                    else -> "同步数据异常，本机记录已保留"
                }
                LiveState.value.update { it.copy(syncError = message) }
                app.health.record("$source：$message（${e.javaClass.simpleName}）")
                false
            } finally { if (wake.isHeld) wake.release() }
        }
    }

    private fun persistSchedule() {
        check(prefs.edit().putLong("attempt", schedule.lastAttempt).putLong("retry", schedule.retryAt)
            .putLong("nextPoll", schedule.nextPollMs).putInt("failures", schedule.failures).commit()) { "无法保存同步进度" }
    }
}
