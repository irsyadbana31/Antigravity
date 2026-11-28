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

    @Test
    fun shouldHoldPersistentWakeLock_activeDuringTurnOrKeepAlivePref() {
        assertFalse(CoreServerService.shouldHoldPersistentWakeLock(agentTurnActive = false, keepAlivePref = false))
        assertTrue(CoreServerService.shouldHoldPersistentWakeLock(agentTurnActive = true, keepAlivePref = false))
        assertTrue(CoreServerService.shouldHoldPersistentWakeLock(agentTurnActive = false, keepAlivePref = true))
        assertTrue(CoreServerService.shouldHoldPersistentWakeLock(agentTurnActive = true, keepAlivePref = true))
        assertTrue(
            CoreServerService.shouldHoldPersistentWakeLock(
                agentTurnActive = false,
                keepAlivePref = false,
                performanceModeActive = true
            )
        )
    }

    @Test
    fun formatActiveTurnStatus_formatsShortAndLongDurations() {
        assertEquals("Agent working…", CoreServerService.formatActiveTurnStatus(0L, "Agent working"))
        assertEquals("Agent working…", CoreServerService.formatActiveTurnStatus(4L, "Agent working"))
        assertEquals("Agent working (12s)…", CoreServerService.formatActiveTurnStatus(12L, "Agent working"))
        assertEquals("Agent working (2m 5s)…", CoreServerService.formatActiveTurnStatus(125L, "Agent working"))
        assertEquals("Agent working…", CoreServerService.formatActiveTurnStatus(-3L, ""))
    }

    @Test
    fun shouldUseStartForegroundService_avoidsFgsTimerOnRunningControlUpdates() {
        // Pre-Oreo never uses startForegroundService
        assertFalse(CoreServerService.shouldUseStartForegroundService(24, false, CoreServerService.ACTION_START))
        // Stop never uses startForegroundService
        assertFalse(CoreServerService.shouldUseStartForegroundService(35, true, CoreServerService.ACTION_STOP))
        assertFalse(CoreServerService.shouldUseStartForegroundService(35, false, CoreServerService.ACTION_STOP))

        // When service is stopped, even control actions promote via FGS
        assertTrue(CoreServerService.shouldUseStartForegroundService(35, false, CoreServerService.ACTION_START))
        assertTrue(CoreServerService.shouldUseStartForegroundService(35, false, CoreServerService.ACTION_AGENT_TURN_STATE))
        assertTrue(CoreServerService.shouldUseStartForegroundService(35, false, CoreServerService.ACTION_SET_PERFORMANCE_MODE))

        // When service is already running, control actions use startService to avoid 5s FGS start timeout
        assertFalse(CoreServerService.shouldUseStartForegroundService(35, true, CoreServerService.ACTION_ENSURE_ALIVE))
        assertFalse(CoreServerService.shouldUseStartForegroundService(35, true, CoreServerService.ACTION_SET_KEEP_ALIVE))
        assertFalse(CoreServerService.shouldUseStartForegroundService(35, true, CoreServerService.ACTION_SET_PERFORMANCE_MODE))
        assertFalse(CoreServerService.shouldUseStartForegroundService(35, true, CoreServerService.ACTION_AGENT_TURN_STATE))
    }

    @Test
    fun isDaemonCmdline_matchesOnlyAgyDaemonProcess() {
        assertTrue(CoreServerService.isDaemonCmdline("/data/user/0/id.vern.antigravity.turbo/files/usr/bin/python3 /data/user/0/id.vern.antigravity.turbo/files/usr/bin/agy-daemon start"))
        assertFalse(CoreServerService.isDaemonCmdline("/data/user/0/id.vern.antigravity.turbo/files/usr/bin/bash -c sleep 60"))
        assertFalse(CoreServerService.isDaemonCmdline(""))
    }
}
