package id.vern.antigravity.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemGatewayServerTest {

    @Test
    fun normalizeCommandEndpoint_handlesDirectAndDualRoutes() {
        assertEquals("exec", SystemGatewayServer.normalizeCommandEndpoint("/exec"))
        assertEquals("exec", SystemGatewayServer.normalizeCommandEndpoint("/gateway/exec"))
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
    fun buildExecEnvironment_setsLdLibraryPathForShellAndOmitsForRish() {
        val shellEnv = SystemGatewayServer.buildExecEnvironment(
            filesPath = "/data/user/0/id.vern.antigravity.turbo/files",
            homePath = "/data/user/0/id.vern.antigravity.turbo/files/home",
            tmpPath = "/data/user/0/id.vern.antigravity.turbo/files/tmp",
            packageName = "id.vern.antigravity.turbo",
            useRish = false
        )
        assertEquals(
            "/data/user/0/id.vern.antigravity.turbo/files/usr/lib:/system/lib64",
            shellEnv["LD_LIBRARY_PATH"]
        )
        assertTrue(shellEnv["PATH"]!!.contains("/usr/bin"))
        assertEquals("/data/user/0/id.vern.antigravity.turbo/files/tmp", shellEnv["TMPDIR"])
        assertEquals("xterm-256color", shellEnv["TERM"])

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
