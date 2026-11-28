package id.vern.antigravity.gateway

import id.vern.antigravity.service.WakeLockHolder
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
        assertEquals(
            SystemGatewayServer.ExecMode.SHIZUKU,
            SystemGatewayServer.resolveExecMode(JSONObject().put("mode", "shizuku"))
        )
        assertEquals(
            SystemGatewayServer.ExecMode.SHIZUKU,
            SystemGatewayServer.resolveExecMode(JSONObject().put("mode", "SHIZUKU"))
        )
        assertEquals(
            SystemGatewayServer.ExecMode.SHIZUKU,
            SystemGatewayServer.resolveExecMode(JSONObject().put("shizuku", true))
        )
        assertEquals(
            SystemGatewayServer.ExecMode.RISH,
            SystemGatewayServer.resolveExecMode(JSONObject().put("shizuku", false).put("rish", true))
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
        assertEquals("0", shellEnv["AGY_SHIZUKU_ENABLED"])
        assertEquals("auto", shellEnv["AGY_SHIZUKU_BACKEND"])

        val rishEnv = SystemGatewayServer.buildExecEnvironment(
            filesPath = "/data/user/0/id.vern.antigravity.turbo/files",
            homePath = "/data/user/0/id.vern.antigravity.turbo/files/home",
            tmpPath = "/data/user/0/id.vern.antigravity.turbo/files/tmp",
            packageName = "id.vern.antigravity.turbo",
            useRish = true,
            shizukuEnabled = true,
            shizukuBackend = "binder"
        )
        assertFalse(rishEnv.containsKey("LD_LIBRARY_PATH"))
        assertEquals("id.vern.antigravity.turbo", rishEnv["RISH_APPLICATION_ID"])
        assertEquals("1", rishEnv["AGY_SHIZUKU_ENABLED"])
        assertEquals("binder", rishEnv["AGY_SHIZUKU_BACKEND"])
    }

    @Test
    fun validatePackageName_acceptsValidAndRejectsShellInjection() {
        assertEquals(
            "id.vern.antigravity.turbo",
            SystemGatewayServer.validatePackageName(" id.vern.antigravity.turbo ")
        )
        assertEquals("com.android.settings", SystemGatewayServer.validatePackageName("com.android.settings"))
        assertNull(SystemGatewayServer.validatePackageName(""))
        assertNull(SystemGatewayServer.validatePackageName("singleword"))
        assertNull(SystemGatewayServer.validatePackageName("com.example; rm -rf /"))
        assertNull(SystemGatewayServer.validatePackageName("com.example && id"))
        assertNull(SystemGatewayServer.validatePackageName("../etc/passwd"))
        assertNull(SystemGatewayServer.validatePackageName("123.invalid.pkg"))
    }

    @Test
    fun parseShizukuDiagOutput_extractsStructuredMetrics() {
        val sampleRaw = """
            ===PKG===
            versionCode=17 minSdk=26 targetSdk=28
            versionName=1.2.5-turbo
            lastUpdateTime=2026-11-26 17:15:00
            ===PID===
            24019
            ===BATT===
            level: 78
            voltage: 4120
            temperature: 345
            status: 2
            ===PROCS===
            1142
            ===MEM===
            TOTAL PSS: 125301 TOTAL RSS: 223524 TOTAL SWAP PSS: 21299
            ===GFX===
            Total frames rendered: 9933
            Janky frames: 9 (0.09%)
            50th percentile: 10ms
            90th percentile: 13ms
            95th percentile: 14ms
            99th percentile: 19ms
            ===ANR===
            2026-11-26 15:20:07 data_app_anr (compressed text, 28903 bytes)
        """.trimIndent()

        val diag = SystemGatewayServer.parseShizukuDiagOutput("id.vern.antigravity.turbo", sampleRaw)
        assertEquals("id.vern.antigravity.turbo", diag.getString("package"))
        assertEquals("1.2.5-turbo", diag.getString("version_name"))
        assertEquals(17, diag.getInt("version_code"))
        assertEquals("2026-11-26 17:15:00", diag.getString("last_update_time"))
        assertEquals(24019, diag.getInt("pid"))
        assertEquals(1142, diag.getInt("total_processes"))

        val batt = diag.getJSONObject("battery")
        assertEquals(78, batt.getInt("level"))
        assertEquals(34.5, batt.getDouble("temperature_c"), 0.01)
        assertEquals(4120, batt.getInt("voltage_mv"))
        assertEquals(2, batt.getInt("status"))

        val mem = diag.getJSONObject("meminfo")
        assertEquals(125301, mem.getInt("total_pss_kb"))
        assertEquals(223524, mem.getInt("total_rss_kb"))

        val gfx = diag.getJSONObject("gfxinfo")
        assertEquals(9933, gfx.getInt("total_frames"))
        assertEquals(9, gfx.getInt("janky_frames"))
        assertEquals(0.09, gfx.getDouble("janky_percent"), 0.001)
        assertEquals(10, gfx.getInt("p50_ms"))
        assertEquals(13, gfx.getInt("p90_ms"))
        assertEquals(14, gfx.getInt("p95_ms"))
        assertEquals(19, gfx.getInt("p99_ms"))

        val anrs = diag.getJSONArray("recent_anrs")
        assertEquals(1, anrs.length())
        assertTrue(anrs.getString(0).contains("2026-11-26 15:20:07"))
    }

    @Test
    fun parseShizukuDiagOutput_parsesBackgroundHealthSection() {
        val sampleWithBg = """
            ===PKG===
            versionCode=20 minSdk=26 targetSdk=28
            versionName=1.2.8-turbo
            ===PID===
            5539
            ===BG===
            doze_whitelist=true
            standby_bucket=5
            fgs_active=true
            cgroup_frozen=0
            wakelock_active=true
        """.trimIndent()

        val diag = SystemGatewayServer.parseShizukuDiagOutput("id.vern.antigravity.turbo", sampleWithBg)
        val bg = diag.getJSONObject("background")
        assertTrue(bg.getBoolean("doze_whitelist"))
        assertEquals(5, bg.getInt("standby_bucket"))
        assertTrue(bg.getBoolean("fgs_active"))
        assertEquals(0, bg.getInt("cgroup_frozen"))
        assertTrue(bg.getBoolean("wakelock_active"))
    }

    @Test
    fun buildShizukuDiagCommand_checksWakeLocksBySectionAndUidNotHardcoded15Lines() {
        val cmd = SystemGatewayServer.buildShizukuDiagCommand("id.vern.antigravity.turbo")
        assertTrue(cmd.contains("sed -n '/Wake Locks:/,/^\$/p'"))
        assertTrue(cmd.contains("uid=\${UID_BG}"))
        assertTrue(cmd.contains("id.vern.antigravity.turbo"))
        assertFalse(cmd.contains("grep -A 15 'Wake Locks:'"))
    }

    @Test
    fun wakeLockHolder_resolvesPackageSpecificTagsForTurboFlavor() {
        assertEquals(
            "id.vern.antigravity.turbo:wakelock",
            WakeLockHolder.resolveLockTag("id.vern.antigravity.turbo", WakeLockHolder.LOCK_TAG)
        )
        assertEquals(
            "id.vern.antigravity.turbo:exec_wakelock",
            WakeLockHolder.resolveLockTag("id.vern.antigravity.turbo", "id.vern.antigravity:exec_wakelock")
        )
        assertEquals(
            "id.vern.antigravity:wakelock",
            WakeLockHolder.resolveLockTag("id.vern.antigravity", WakeLockHolder.LOCK_TAG)
        )
        assertEquals(
            "custom:tag",
            WakeLockHolder.resolveLockTag("id.vern.antigravity.turbo", "custom:tag")
        )
        assertEquals(
            "id.vern.antigravity.turbo:wifilock",
            WakeLockHolder.resolveWifiLockTag("id.vern.antigravity.turbo")
        )
    }
}
