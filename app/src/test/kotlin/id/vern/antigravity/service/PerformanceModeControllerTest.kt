package id.vern.antigravity.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceModeControllerTest {

    @Test
    fun buildShizukuApplyCommands_usesDynamicPackageNameNeverHardcodedOldPackage() {
        val cmds = PerformanceModeController.buildShizukuApplyCommands("id.vern.antigravity.turbo")
        val allCmdText = cmds.joinToString("\n") { it.second }

        assertTrue(allCmdText.contains("settings put global settings_enable_monitor_phantom_procs false"))
        assertTrue(allCmdText.contains("max_phantom_processes 2147483647"))
        assertTrue(allCmdText.contains("cmd deviceidle whitelist +id.vern.antigravity.turbo"))
        assertTrue(allCmdText.contains("cmd appops set id.vern.antigravity.turbo RUN_ANY_IN_BACKGROUND allow"))
        assertTrue(allCmdText.contains("am set-standby-bucket id.vern.antigravity.turbo active"))

        // Must never target the legacy upstream package without .turbo suffix
        assertFalse(allCmdText.contains("id.vern.antigravity "))
        assertFalse(allCmdText.endsWith("id.vern.antigravity"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildShizukuApplyCommands_rejectsShellInjectionPackageName() {
        PerformanceModeController.buildShizukuApplyCommands("id.vern.antigravity.turbo; rm -rf /")
    }

    @Test
    fun parseShizukuReadback_parsesRestrictedXos16OutputAccurately() {
        val sample = """
            ===PHANTOM_SETTING===
            null
            ===PHANTOM_MAX===
              max_phantom_processes=8
            ===DOZE===
            user,id.vern.antigravity.turbo,10148
            ===APPOPS===
            RUN_ANY_IN_BACKGROUND: allow
            ===BUCKET===
            5
        """.trimIndent()

        val parsed = PerformanceModeController.parseShizukuReadback(sample, "id.vern.antigravity.turbo")
        assertTrue(parsed.shizukuAvailable)
        assertEquals(null, parsed.phantomProcsDisabled)
        assertEquals(8, parsed.maxPhantomProcesses)
        assertFalse(parsed.phantomOk)
        assertEquals(true, parsed.dozeWhitelisted)
        assertEquals(true, parsed.runAnyInBackgroundAllowed)
        assertEquals(5, parsed.standbyBucket)
        assertTrue(parsed.standbyActive)
        assertEquals(3, parsed.satisfiedCount)
    }

    @Test
    fun parseShizukuReadback_parsesFullyTunedOutputAccurately() {
        val sample = """
            ===PHANTOM_SETTING===
            false
            ===PHANTOM_MAX===
              max_phantom_processes=2147483647
            ===DOZE===
            user,id.vern.antigravity.turbo,10148
            ===APPOPS===
            RUN_ANY_IN_BACKGROUND: allow
            ===BUCKET===
            5
        """.trimIndent()

        val parsed = PerformanceModeController.parseShizukuReadback(sample, "id.vern.antigravity.turbo")
        assertEquals(true, parsed.phantomProcsDisabled)
        assertEquals(2147483647, parsed.maxPhantomProcesses)
        assertTrue(parsed.phantomOk)
        assertEquals(4, parsed.satisfiedCount)
    }

    @Test
    fun evaluateThermalCoolingState_enforcesTriggerAndHysteresis() {
        // Normal temperature stays normal
        assertFalse(PerformanceModeController.evaluateThermalCoolingState(false, 38.0f, 0))
        assertFalse(PerformanceModeController.evaluateThermalCoolingState(false, 42.9f, 1))

        // Triggers at >= 43.0 C battery temp
        assertTrue(PerformanceModeController.evaluateThermalCoolingState(false, 43.0f, 0))
        assertTrue(PerformanceModeController.evaluateThermalCoolingState(false, 44.5f, 0))

        // Hysteresis: remains cooling down until <= 39.5 C
        assertTrue(PerformanceModeController.evaluateThermalCoolingState(true, 41.5f, 0))
        assertTrue(PerformanceModeController.evaluateThermalCoolingState(true, 39.6f, 0))
        assertFalse(PerformanceModeController.evaluateThermalCoolingState(true, 39.5f, 0))
        assertFalse(PerformanceModeController.evaluateThermalCoolingState(true, 37.0f, 1))

        // Triggers immediately on Android Thermal HAL >= SEVERE (3) regardless of battery temp
        assertTrue(PerformanceModeController.evaluateThermalCoolingState(false, 37.5f, 3))
        // Stays cooling if thermal HAL is still MODERATE (2) even if battery temp dropped
        assertTrue(PerformanceModeController.evaluateThermalCoolingState(true, 38.0f, 2))
    }
}
