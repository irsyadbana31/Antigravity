package id.vern.antigravity.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SystemGatewayServerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun ensureGatewayToken_generatesAndReuses64CharHexToken() {
        val dir = tempFolder.newFolder("gw_files")
        val token1 = SystemGatewayServer.ensureGatewayToken(dir)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(token1))
        val tokenFile = File(dir, "logs/gateway.token")
        assertTrue(tokenFile.isFile)
        assertEquals(token1, tokenFile.readText(Charsets.UTF_8).trim())

        val token2 = SystemGatewayServer.ensureGatewayToken(dir)
        assertEquals(token1, token2)
    }

    @Test
    fun isAuthorizedRequest_validatesBearerAndXToken() {
        val secret = "a".repeat(64)
        assertTrue(SystemGatewayServer.isAuthorizedRequest(mapOf("x-agy-token" to secret), secret))
        assertTrue(SystemGatewayServer.isAuthorizedRequest(mapOf("authorization" to "Bearer $secret"), secret))
        assertTrue(SystemGatewayServer.isAuthorizedRequest(mapOf("authorization" to "bearer $secret"), secret))
        assertFalse(SystemGatewayServer.isAuthorizedRequest(mapOf("x-agy-token" to "b".repeat(64)), secret))
        assertFalse(SystemGatewayServer.isAuthorizedRequest(emptyMap(), secret))
        assertFalse(SystemGatewayServer.isAuthorizedRequest(mapOf("x-agy-token" to secret), ""))
    }

    @Test
    fun validateHostAndOriginHeaders_blocksBrowserAndRebindingRequests() {
        assertNull(SystemGatewayServer.validateHostAndOriginHeaders(mapOf("host" to "127.0.0.1:45161")))
        assertNull(SystemGatewayServer.validateHostAndOriginHeaders(mapOf("host" to "localhost:45161")))
        assertNull(SystemGatewayServer.validateHostAndOriginHeaders(mapOf("host" to "[::1]:45161")))
        assertNotNull(SystemGatewayServer.validateHostAndOriginHeaders(mapOf("origin" to "https://evil.example")))
        assertNotNull(SystemGatewayServer.validateHostAndOriginHeaders(mapOf("sec-fetch-site" to "cross-site")))
        assertNotNull(SystemGatewayServer.validateHostAndOriginHeaders(mapOf("host" to "evil.example:45161")))
    }

    @Test
    fun normalizeCommandEndpoint_handlesDirectAndDualRoutes() {
        assertEquals("exec", SystemGatewayServer.normalizeCommandEndpoint("/exec"))
        assertEquals("exec", SystemGatewayServer.normalizeCommandEndpoint("/gateway/exec"))
        assertEquals("status", SystemGatewayServer.normalizeCommandEndpoint("/status"))
        assertEquals("status", SystemGatewayServer.normalizeCommandEndpoint("/gateway/status"))
        assertEquals("notify", SystemGatewayServer.normalizeCommandEndpoint("/gateway/notify/"))
        assertEquals("exec", SystemGatewayServer.normalizeCommandEndpoint("/gateway/exec?debug=1"))
        assertEquals("toast", SystemGatewayServer.normalizeCommandEndpoint("/toast/"))
        assertEquals("", SystemGatewayServer.normalizeCommandEndpoint("/"))
    }

    @Test
    fun resolveExecCommand_supportsCmdAndCommandAlias() {
        assertEquals("echo hi", SystemGatewayServer.resolveExecCommand(JSONObject().put("cmd", "  echo hi  ")))
        assertEquals("echo alias", SystemGatewayServer.resolveExecCommand(JSONObject().put("command", "echo alias")))
        assertEquals(
            "primary",
            SystemGatewayServer.resolveExecCommand(JSONObject().put("cmd", "primary").put("command", "fallback"))
        )
        assertEquals("", SystemGatewayServer.resolveExecCommand(JSONObject()))
    }

    @Test
    fun resolveExecMode_validatesExplicitModeAndFallsBackToRishFlag() {
        assertEquals(
            SystemGatewayServer.ExecMode.SHELL,
            SystemGatewayServer.resolveExecMode(JSONObject())
        )
        assertEquals(
            SystemGatewayServer.ExecMode.SHELL,
            SystemGatewayServer.resolveExecMode(JSONObject().put("mode", "shell"))
        )
        assertEquals(
            SystemGatewayServer.ExecMode.RISH,
            SystemGatewayServer.resolveExecMode(JSONObject().put("mode", "RISH"))
        )
        assertEquals(
            SystemGatewayServer.ExecMode.RISH,
            SystemGatewayServer.resolveExecMode(JSONObject().put("rish", true))
        )
        assertNull(
            SystemGatewayServer.resolveExecMode(JSONObject().put("mode", "invalid_mode"))
        )
    }

    @Test
    fun buildExecEnvironment_setsLdLibraryPathForShellAndOmitsForRish() {
        val shellEnv = SystemGatewayServer.buildExecEnvironment(
            filesPath = "/data/user/0/id.vern.antigravity.turbo/files",
            homePath = "/data/user/0/id.vern.antigravity.turbo/files/home",
            tmpPath = "/data/user/0/id.vern.antigravity.turbo/files/tmp",
            packageName = "id.vern.antigravity.turbo",
            useRish = false,
            gatewayPort = 45162,
            gatewayToken = "tok123"
        )
        assertEquals(
            "/data/user/0/id.vern.antigravity.turbo/files/usr/lib:/system/lib64",
            shellEnv["LD_LIBRARY_PATH"]
        )
        assertTrue(shellEnv["PATH"]!!.contains("/usr/bin"))
        assertEquals("/data/user/0/id.vern.antigravity.turbo/files/tmp", shellEnv["TMPDIR"])
        assertEquals("xterm-256color", shellEnv["TERM"])
        assertEquals("45162", shellEnv["AGY_GATEWAY_PORT"])
        assertEquals("tok123", shellEnv["AGY_GATEWAY_TOKEN"])

        val rishEnv = SystemGatewayServer.buildExecEnvironment(
            filesPath = "/data/user/0/id.vern.antigravity.turbo/files",
            homePath = "/data/user/0/id.vern.antigravity.turbo/files/home",
            tmpPath = "/data/user/0/id.vern.antigravity.turbo/files/tmp",
            packageName = "id.vern.antigravity.turbo",
            useRish = true
        )
        assertFalse(rishEnv.containsKey("LD_LIBRARY_PATH"))
        assertEquals("id.vern.antigravity.turbo", rishEnv["RISH_APPLICATION_ID"])
    }
}
