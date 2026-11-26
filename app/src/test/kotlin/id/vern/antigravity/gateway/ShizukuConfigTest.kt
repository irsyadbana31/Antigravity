package id.vern.antigravity.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShizukuConfigTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun defaultSettings_areDisabledAndOptIn() {
        val defaults = ShizukuSettings()
        assertFalse(defaults.enabled)
        assertEquals(ShizukuBackend.AUTO, defaults.backend)
        assertFalse(defaults.fallbackToShell)
    }

    @Test
    fun backendFromId_normalizesKnownAndUnknownValues() {
        assertEquals(ShizukuBackend.AUTO, ShizukuBackend.fromId(null))
        assertEquals(ShizukuBackend.AUTO, ShizukuBackend.fromId(""))
        assertEquals(ShizukuBackend.AUTO, ShizukuBackend.fromId("unknown"))
        assertEquals(ShizukuBackend.BINDER, ShizukuBackend.fromId("binder"))
        assertEquals(ShizukuBackend.BINDER, ShizukuBackend.fromId("  BINDER "))
        assertEquals(ShizukuBackend.RISH, ShizukuBackend.fromId("Rish"))
    }

    @Test
    fun formatAndParseConf_roundTripsAndHandlesMalformedLines() {
        val settings = ShizukuSettings(
            enabled = true,
            backend = ShizukuBackend.BINDER,
            fallbackToShell = true
        )
        val formatted = ShizukuConfig.formatConf(settings)
        assertTrue(formatted.contains("SHIZUKU_ENABLED=1"))
        assertTrue(formatted.contains("SHIZUKU_BACKEND=binder"))
        assertTrue(formatted.contains("SHIZUKU_FALLBACK_SHELL=1"))

        val parsed = ShizukuConfig.parseConf(formatted)
        assertEquals(settings, parsed)

        val malformed = """
            # comment line
            BAD_LINE_WITHOUT_EQUALS
            SHIZUKU_ENABLED=yes
            SHIZUKU_BACKEND="RISH"
            SHIZUKU_FALLBACK_SHELL=invalid
        """.trimIndent()
        val parsedMalformed = ShizukuConfig.parseConf(malformed)
        assertTrue(parsedMalformed.enabled)
        assertEquals(ShizukuBackend.RISH, parsedMalformed.backend)
        assertFalse(parsedMalformed.fallbackToShell)
    }

    @Test
    fun readAndWriteFilesDir_defaultsDisabledWhenMissing() {
        val filesDir = tempFolder.newFolder("files")
        val initial = ShizukuConfig.readFromFilesDir(filesDir)
        assertFalse(initial.enabled)
        assertEquals(ShizukuBackend.AUTO, initial.backend)
        assertFalse(initial.fallbackToShell)

        val updated = ShizukuSettings(
            enabled = true,
            backend = ShizukuBackend.RISH,
            fallbackToShell = true
        )
        ShizukuConfig.writeToFilesDir(filesDir, updated)
        val confFile = File(filesDir, ShizukuConfig.CONF_RELATIVE_PATH)
        assertTrue(confFile.isFile)
        assertEquals(updated, ShizukuConfig.readFromFilesDir(filesDir))

        val disabled = updated.copy(enabled = false, fallbackToShell = false)
        ShizukuConfig.writeToFilesDir(filesDir, disabled)
        assertEquals(disabled, ShizukuConfig.readFromFilesDir(filesDir))
    }
}
