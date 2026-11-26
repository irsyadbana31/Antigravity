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

    @Test
    fun isAuxiliaryUid_detectsShellAndRootUidsOnly() {
        assertTrue(ShizukuConfig.isAuxiliaryUid(2000))
        assertTrue(ShizukuConfig.isAuxiliaryUid(0))
        assertFalse(ShizukuConfig.isAuxiliaryUid(10148))
        assertFalse(ShizukuConfig.isAuxiliaryUid(-1))
    }

    @Test
    fun rishOverlayScript_avoidsUnqualifiedTermuxCoreutils() {
        val rishFile = listOf(
            java.io.File("src/main/assets/overlay/bin/rish"),
            java.io.File("app/src/main/assets/overlay/bin/rish"),
        ).first { it.isFile }
        val content = rishFile.readText()
        assertTrue(content.contains("unset LD_PRELOAD LD_LIBRARY_PATH"))
        assertTrue(content.contains("exec /system/bin/app_process"))
        assertTrue(content.contains("/system/bin/chmod"))
        assertTrue(content.contains("TMPDIR="))
        assertTrue(content.contains("export TMPDIR"))

        val shellLines = buildList {
            var inPython = false
            for (line in content.lineSequence()) {
                val trimmed = line.trimStart()
                if (trimmed.startsWith("PYEOF")) {
                    inPython = false
                    continue
                }
                if (inPython) continue
                if (trimmed.contains("<<'PYEOF'")) {
                    inPython = true
                }
                if (!trimmed.startsWith("#")) {
                    add(line)
                }
            }
        }.joinToString("\n")

        assertFalse(Regex("""\b(dirname|sed|head|tr|env|mkdir|chmod)\b""").containsMatchIn(shellLines.replace("/system/bin/mkdir", "").replace("/system/bin/chmod", "")))
        assertFalse(content.contains("exec env"))
    }

    @Test
    fun agyShizukuOverlayScript_existsAndHasExpectedSubcommands() {
        val scriptFile = listOf(
            java.io.File("src/main/assets/overlay/bin/agy-shizuku"),
            java.io.File("app/src/main/assets/overlay/bin/agy-shizuku"),
        ).first { it.isFile }
        val content = scriptFile.readText()
        assertTrue(content.startsWith("#!/system/bin/sh"))
        assertTrue(content.contains("TMPDIR="))
        assertTrue(content.contains("export "))
        listOf("\"status\"", "\"diag\"", "\"anr\"", "\"logcat\"", "\"install\"", "\"pkg\"", "\"ui\"", "\"exec\"").forEach { sub ->
            assertTrue("Missing subcommand $sub", content.contains(sub))
        }
        listOf("\"top\"", "\"open\"", "\"dump\"", "\"find\"", "\"tap\"", "\"swipe\"", "\"scroll\"", "\"text\"", "\"key\"", "\"screen\"", "\"return\"").forEach { act ->
            assertTrue("Missing ui action $act", content.contains(act))
        }
        assertTrue(content.contains("--expect-pkg"))
        assertTrue(content.contains("--allow-self"))
        assertFalse(content.contains("exec env"))
    }
}
