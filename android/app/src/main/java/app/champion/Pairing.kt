package app.champion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
private fun decode(value: String) = Base64.getUrlDecoder().decode(value)

data class Pairing(val environment: String, val role: String, val token: String, val legacy: Boolean = false) {
    val partner get() = if (role == "white") "champion" else "white"
    val connected get() = environment.isNotBlank()
    fun json() = JSONObject().put("provider", "aliyun").put("server", BuildConfig.SYNC_ORIGIN)
        .put("role", role).put("token", token)
    companion object {
        fun local() = Pairing("", BuildConfig.DEVICE_ROLE, "")
        fun parse(text: String): Pairing {
            val obj = JSONObject(String(decode(text.trim().removePrefix("champion1:")), Charsets.UTF_8))
            val provider = obj.getString("provider")
            require(provider in listOf("cloudbase", "aliyun")) { "请使用 love gaze 连接码" }
            val environment = if (provider == "cloudbase") {
                // Upgrade keeps existing credentials and installation binding; requests use the new origin.
                require(BuildConfig.LEGACY_CLOUDBASE_ENV.isNotBlank() &&
                    obj.getString("environment") == BuildConfig.LEGACY_CLOUDBASE_ENV &&
                    obj.getString("function") == "champion-sync") { "旧连接配置不匹配" }
                obj.getString("environment")
            } else {
                require(obj.getString("server") == BuildConfig.SYNC_ORIGIN) { "服务器地址与安装包不匹配" }
                "aliyun"
            }
            val role = obj.getString("role")
            require(role in listOf("white", "champion")) { "身份配置不正确" }
            require(role == BuildConfig.DEVICE_ROLE) { "这份安装包属于 ${BuildConfig.DEVICE_ROLE}，请使用对应的连接码" }
            val token = obj.getString("token")
            require(token.matches(Regex("[a-f0-9]{64}"))) { "设备凭据不正确" }
            return Pairing(environment, role, token)
        }
    }
}

class PairingVault(context: Context) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val alias = "champion.connection.v1"
    val installation: String = prefs.getString("installation", null) ?: java.util.UUID.randomUUID().toString().also {
        check(prefs.edit().putString("installation", it).commit()) { "无法保存设备标识" }
    }

    private fun deviceKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun read(): Pairing? {
        val sealed = prefs.getString("sealed", null) ?: return null
        val bytes = decode(sealed)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deviceKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val decoded = String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        val document = JSONObject(String(decode(decoded.removePrefix("champion1:")), Charsets.UTF_8))
        if (document.optString("provider") == "clorabase") return Pairing.local().copy(legacy = true)
        return Pairing.parse(decoded)
    }

    fun save(pairing: Pairing) {
        check(read()?.connected != true) { "身份已固定。如需重新配对，请先处理云端绑定。" }
        val value = "champion1:" + encode(pairing.json().toString().toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deviceKey())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("sealed", encode(sealed)).commit()) { "保存连接失败" }
    }
}
