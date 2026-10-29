package app.champion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ConnectionRejected(message: String) : IOException(message)
class SyncRateLimited(val retryAt: Long, message: String) : IOException(message)
data class SyncResult(val nextPollMs: Long, val received: List<Moment>)

/** The existing two device credentials now connect directly to the dedicated server. */
class SyncClient(private val pairing: Pairing, private val installation: String) {
    private val root = BuildConfig.SYNC_ORIGIN

    private suspend fun request(path: String, body: JSONObject): JSONObject = suspendCancellableCoroutine { continuation ->
        val connection = URL(root + path).openConnection() as HttpURLConnection
        continuation.invokeOnCancellation {
            // onStopJob runs on the main thread; closing a socket must not block its callback.
            Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { connection.disconnect() })
        }
        if (!continuation.isActive) return@suspendCancellableCoroutine
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("x-device-id", installation)
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= 5_000_000) { "同步响应过大" }
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            }.orEmpty()
            val result = try { JSONObject(text) } catch (_: Exception) {
                throw IOException("同步服务返回了无法读取的响应（$status）")
            }
            val code = result.optString("code", result.optString("error")).uppercase()
            if (status == 429 || code.contains("QUOTA") || code.contains("RESOURCE_EXHAUSTED") || code.contains("LIMIT_EXCEEDED")) {
                throw SyncRateLimited(System.currentTimeMillis() + 3_600_000,
                    "同步服务暂时限流，本机记录已保留，稍后重试")
            }
            if (status == 401 || code.contains("TOKEN_EXPIRED") || code.contains("UNAUTHENTICATED")) {
                throw ConnectionRejected("设备连接校验失败，请检查对应身份的连接码")
            }
            if (status == 403 || code.contains("EXCEED_AUTHORITY") || code.contains("ACTION_FORBIDDEN")) {
                throw ConnectionRejected("服务器未允许当前设备访问，请检查连接配置")
            }
            if (status !in 200..299 || (!result.has("ok") && result.has("code") && code !in listOf("", "0", "SUCCESS"))) {
                throw IOException("同步请求失败（$status）")
            }
            continuation.resume(result)
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        } finally { connection.disconnect() }
    }

    private suspend fun call(body: JSONObject): JSONObject {
        currentCoroutineContext().ensureActive()
        body.put("role", pairing.role).put("token", pairing.token).put("installation", installation)
        val result = request("/v1/sync", body)
        require(result.opt("ok") is Boolean) { "云端同步函数返回格式不正确" }
        if (!result.optBoolean("ok")) {
            val code = result.optString("code")
            val message = result.optString("message", "同步暂不可用，本机记录已保留")
            if (code == "WAIT") throw SyncRateLimited(System.currentTimeMillis() +
                result.optLong("retryAfterMs", 300_000).coerceIn(1_000, 600_000), message)
            if (code in listOf("CONNECTION_REJECTED", "SETUP_REQUIRED", "CLOUD_RESET")) throw ConnectionRejected(message)
            throw IOException(message)
        }
        return result
    }

    suspend fun claim() = withContext(Dispatchers.IO) {
        val result = call(JSONObject().put("action", "claim"))
        require(result.getString("role") == pairing.role) { "云端身份不匹配" }
    }

    suspend fun sync(store: EventStore, appearance: AppearanceStore): SyncResult = withContext(Dispatchers.IO) {
        store.prepareServer(pairing.role)
        val pending = store.pending()
        val previousHead = store.cloudProgress("headSeen")
        val previousArchive = store.cloudProgress("archiveCursor")
        val avatar = appearance.pendingAvatar()
        val body = JSONObject().put("action", "sync").put("events", JSONArray(pending.map { it.json() }))
            .put("headSeen", previousHead).put("archiveCursor", previousArchive).put("foreground", LiveState.foreground)
            .put("partnerAvatarRevision", appearance.partnerAvatarRevision())
        if (avatar != null) body.put("avatar", avatar)
        val result = call(body)
        currentCoroutineContext().ensureActive()
        val rawIds = result.getJSONArray("acknowledged")
        val ids = (0 until rawIds.length()).map { rawIds.getString(it) }
        require(ids.size == pending.size && ids.toSet() == pending.map { it.id }.toSet()) { "上传确认记录不匹配" }
        val presenceUpTo = if (result.isNull("presenceUpTo")) null else result.getLong("presenceUpTo")
        require(presenceUpTo == pending.firstOrNull { it.kind == Kind.PRESENCE }?.at) { "状态确认时间不匹配" }
        val partner = result.getJSONObject("partner")
        val raw = partner.getJSONArray("events")
        require(raw.length() <= 900) { "接收记录过多" }
        val moments = (0 until raw.length()).map { Moment.parse(raw.getJSONObject(it)) }.toMutableList()
        require(moments.all { it.owner == pairing.partner && it.kind != Kind.PRESENCE }) { "接收记录身份不匹配" }
        if (!partner.isNull("presence")) {
            val presence = Moment.parse(partner.getJSONObject("presence"))
            require(presence.owner == pairing.partner && presence.kind == Kind.PRESENCE) { "设备状态身份不匹配" }
            moments.add(presence)
        }
        val headSeen = partner.getLong("headSeen")
        val archiveCursor = partner.getLong("archiveCursor")
        require(headSeen >= previousHead && archiveCursor >= previousArchive && archiveCursor <= partner.getLong("archiveCount")) {
            "云端接收进度不正确"
        }
        val nextPollMs = result.getLong("nextPollMs")
        require(nextPollMs in 2_000L..60_000L) { "同步频率配置不正确" }
        if (!partner.isNull("avatar")) appearance.receiveAvatar(partner.getJSONObject("avatar"))
        if (avatar != null && !result.isNull("avatarAcknowledged")) {
            require(result.getString("avatarAcknowledged") == avatar.getString("revision")) { "头像上传确认不匹配" }
            appearance.acknowledgeAvatar(avatar.getString("revision"))
        }
        val fresh = store.commitCloudSync(ids, presenceUpTo, moments, headSeen, archiveCursor)
        SyncResult(if (store.hasPendingActivity()) 2_000L else nextPollMs, fresh)
    }
}
