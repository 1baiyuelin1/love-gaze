package app.champion

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ServerMigrationTest {
    private val token = "a".repeat(64)
    private fun code(value: JSONObject) = "champion1:" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toString().toByteArray(Charsets.UTF_8))
    private fun old() = JSONObject().put("provider", "cloudbase").put("environment", BuildConfig.LEGACY_CLOUDBASE_ENV)
        .put("function", "champion-sync").put("role", "white").put("token", token)
    private fun current() = JSONObject().put("provider", "aliyun").put("server", BuildConfig.SYNC_ORIGIN)
        .put("role", "white").put("token", token)

    @Test fun existingConnectionPreservesRoleAndCredentialOnUpgrade() {
        assumeTrue(BuildConfig.LEGACY_CLOUDBASE_ENV.isNotBlank())
        val pairing = Pairing.parse(code(old()))
        assertTrue(pairing.connected)
        assertEquals("white", pairing.role)
        assertEquals(token, pairing.token)
        assertEquals("aliyun", pairing.json().getString("provider"))
        assertEquals(BuildConfig.SYNC_ORIGIN, pairing.json().getString("server"))
    }

    @Test fun newConnectionRoundTripKeepsTheSameIdentity() {
        val original = Pairing.parse(code(current()))
        val restored = Pairing.parse(code(original.json()))
        assertEquals(original.role, restored.role)
        assertEquals(original.token, restored.token)
        assertEquals(original.partner, restored.partner)
    }

    @Test fun oppositeRoleAndUnrelatedOldEnvironmentAreRejected() {
        for (value in listOf(current().put("role", "champion"), old().put("environment", BuildConfig.LEGACY_CLOUDBASE_ENV + "-other"))) {
            try { Pairing.parse(code(value)); fail("Unexpected connection accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun newConnectionCannotRedirectCredentialsToAnotherServer() {
        val value = current().put("server", BuildConfig.SYNC_ORIGIN + "/other")
        try { Pairing.parse(code(value)); fail("Unexpected origin accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
