package app.champion

import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Clorastore 0.6 wire format; authenticated transport is implemented in SyncClient. */
class CloraCodec(token: String) {
    private val key = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(token.toCharArray(), "abcdefgh".toByteArray(Charsets.US_ASCII), 786, 128)).encoded, "AES")

    fun encode(document: JSONObject): ByteArray = cipher(Cipher.ENCRYPT_MODE)
        .doFinal(document.toString().toByteArray(Charsets.UTF_8))

    fun decode(bytes: ByteArray): JSONObject = JSONObject(String(cipher(Cipher.DECRYPT_MODE).doFinal(bytes), Charsets.UTF_8))

    private fun cipher(mode: Int) = Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(mode, key) }
}

data class CloraPlan(val head: CloraHead, val archive: JSONObject?)

data class CloraHead(
    val role: String,
    val installation: String,
    val archiveCount: Int = 0,
    val events: List<Moment> = emptyList(),
    val presence: Moment? = null,
) {
    fun json(): JSONObject = JSONObject().put("v", 1).put("role", role).put("installation", installation)
        .put("archiveCount", archiveCount).put("events", strings(events))
        .put("presence", presence?.json()?.toString().orEmpty())

    fun append(incoming: List<Moment>): CloraPlan {
        require(incoming.all { it.owner == role }) { "待上传记录身份不匹配" }
        val known = events.map { it.id }.toSet()
        val newEvents = incoming.filter { it.kind != Kind.PRESENCE && it.id !in known }.distinctBy { it.id }
        require(newEvents.size <= CAPACITY) { "单批记录过多" }
        val heartbeat = (incoming.filter { it.kind == Kind.PRESENCE } + listOfNotNull(presence)).maxByOrNull { it.at }
        if (events.size + newEvents.size <= CAPACITY) {
            return CloraPlan(copy(events = events + newEvents, presence = heartbeat), null)
        }
        val archive = JSONObject().put("v", 1).put("role", role).put("number", archiveCount + 1).put("events", strings(events))
        return CloraPlan(copy(archiveCount = archiveCount + 1, events = newEvents, presence = heartbeat), archive)
    }

    companion object {
        const val CAPACITY = 300
        private fun strings(events: List<Moment>) = JSONArray(events.map { it.json().toString() })

        fun events(document: JSONObject, role: String): List<Moment> {
            require(document.getInt("v") == 1 && document.getString("role") == role) { "云端记录身份不匹配" }
            val raw = document.getJSONArray("events")
            require(raw.length() <= CAPACITY) { "云端分段记录过多" }
            return (0 until raw.length()).map { Moment.parse(JSONObject(raw.getString(it))) }.also { moments ->
                require(moments.all { it.owner == role && it.kind != Kind.PRESENCE }) { "云端记录类型不正确" }
                require(moments.map { it.id }.toSet().size == moments.size) { "云端记录编号重复" }
            }
        }

        fun parse(document: JSONObject, role: String): CloraHead {
            val events = events(document, role)
            val count = document.getInt("archiveCount")
            require(count >= 0) { "云端历史分段异常" }
            val installation = document.getString("installation")
            java.util.UUID.fromString(installation)
            val presence = document.getString("presence").takeIf { it.isNotBlank() }?.let { Moment.parse(JSONObject(it)) }
            require(presence == null || (presence.owner == role && presence.kind == Kind.PRESENCE)) { "云端设备状态异常" }
            return CloraHead(role, installation, count, events, presence)
        }
    }
}
