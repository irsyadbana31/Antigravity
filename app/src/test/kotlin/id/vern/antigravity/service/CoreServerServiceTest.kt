package id.vern.antigravity.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreServerServiceTest {

    @Test
    fun buildDaemonEnvironment_setsLdLibraryPathAndGatewayVarsForStart() {
        val env = CoreServerService.buildDaemonEnvironment(
            filesPath = "/data/user/0/id.vern.antigravity.turbo/files",
            homePath = "/data/user/0/id.vern.antigravity.turbo/files/home",
            tmpPath = "/data/user/0/id.vern.antigravity.turbo/files/tmp",
            packageName = "id.vern.antigravity.turbo",
            gatewayPort = 45161,
            gatewayToken = "tok123"
        )

        assertEquals(
            "/data/user/0/id.vern.antigravity.turbo/files/usr/lib:/system/lib64",
            env["LD_LIBRARY_PATH"]
        )
        assertTrue(env["PATH"]!!.contains("/usr/bin"))
        assertTrue(env["PATH"]!!.contains("/engine/bin"))
        assertEquals("/data/user/0/id.vern.antigravity.turbo/files/home", env["HOME"])
        assertEquals("/data/user/0/id.vern.antigravity.turbo/files/usr", env["PREFIX"])
        assertEquals("/data/user/0/id.vern.antigravity.turbo/files/tmp", env["TMPDIR"])
        assertEquals("id.vern.antigravity.turbo", env["AGY_APPLICATION_ID"])
        assertEquals("id.vern.antigravity.turbo", env["APPLICATION_ID"])
        assertEquals("id.vern.antigravity.turbo", env["RISH_APPLICATION_ID"])
        assertEquals("45161", env["AGY_GATEWAY_PORT"])
        assertEquals("tok123", env["AGY_GATEWAY_TOKEN"])
    }

    @Test
    fun buildDaemonEnvironment_keepsLdLibraryPathAndOmitsOptionalGatewayVarsOnStop() {
        val envStop = CoreServerService.buildDaemonEnvironment(
            filesPath = "/data/user/0/id.vern.antigravity.turbo/files",
            homePath = "/data/user/0/id.vern.antigravity.turbo/files/home",
            tmpPath = "/data/user/0/id.vern.antigravity.turbo/files/tmp",
            packageName = "id.vern.antigravity.turbo",
            gatewayPort = null,
            gatewayToken = null
        )

        assertEquals(
            "/data/user/0/id.vern.antigravity.turbo/files/usr/lib:/system/lib64",
            envStop["LD_LIBRARY_PATH"]
        )
        assertEquals("/data/user/0/id.vern.antigravity.turbo/files/usr", envStop["PREFIX"])
        assertTrue(envStop["PATH"]!!.contains("/usr/bin"))
        assertEquals("/data/user/0/id.vern.antigravity.turbo/files/tmp", envStop["TMPDIR"])
        assertFalse(envStop.containsKey("AGY_GATEWAY_PORT"))
        assertFalse(envStop.containsKey("AGY_GATEWAY_TOKEN"))

        val envInvalid = CoreServerService.buildDaemonEnvironment(
            filesPath = "/data/user/0/id.vern.antigravity.turbo/files",
            homePath = "/data/user/0/id.vern.antigravity.turbo/files/home",
            tmpPath = "/data/user/0/id.vern.antigravity.turbo/files/tmp",
            packageName = "id.vern.antigravity.turbo",
            gatewayPort = 0,
            gatewayToken = "   "
        )
        assertFalse(envInvalid.containsKey("AGY_GATEWAY_PORT"))
        assertFalse(envInvalid.containsKey("AGY_GATEWAY_TOKEN"))
    }
}
