package id.vern.antigravity.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CoreBinaryPatcherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun buildPythonPatchEnv_setsLdLibraryPathAndPrefix() {
        val filesDir = tempFolder.newFolder("files")
        val prefix = File(filesDir, "usr")
        val env = CoreBinaryPatcher.buildPythonPatchEnv(prefix)

        assertEquals("${File(prefix, "lib").absolutePath}:/system/lib64", env["LD_LIBRARY_PATH"])
        assertEquals(prefix.absolutePath, env["PREFIX"])
        assertEquals("${File(prefix, "bin").absolutePath}:/system/bin", env["PATH"])
        assertEquals(File(filesDir, "tmp").absolutePath, env["TMPDIR"])
    }

    @Test
    fun markAsPatched_recordsMatchingSizeAndMtimeMarker() {
        val binDir = tempFolder.newFolder("engine_bin")
        val bin = File(binDir, "agy").apply { writeBytes(ByteArray(32) { 1 }) }

        assertFalse(CoreBinaryPatcher.isAlreadyPatched(bin))
        CoreBinaryPatcher.markAsPatched(bin)
        assertTrue(CoreBinaryPatcher.isAlreadyPatched(bin))
    }

    @Test
    fun termuxShebangRewriter_rewritesPrefixAndPreservesEnvFlags() {
        val prefix = "/data/user/0/id.vern.antigravity.turbo/files/usr"
        assertEquals(
            "$prefix/bin/unzip",
            TermuxShebangRewriter.rewritePrefixPath("/data/data/com.termux/files/usr/bin/unzip", prefix)
        )
        assertEquals(
            "$prefix/share/termux-keyring/grimler.gpg",
            TermuxShebangRewriter.rewritePrefixPath("/data/user/0/com.termux/files/usr/share/termux-keyring/grimler.gpg", prefix)
        )

        val dir = tempFolder.newFolder("bin_scripts")
        val regular = File(dir, "tool.py").apply {
            writeText("#!/usr/bin/env python3\nprint('ok')\n", Charsets.UTF_8)
        }
        val withFlags = File(dir, "flagged.py").apply {
            writeText("#!/usr/bin/env -S python3 -u\nprint('ok')\n", Charsets.UTF_8)
        }

        assertTrue(TermuxShebangRewriter.rewriteFile(regular, prefix))
        assertTrue(TermuxShebangRewriter.rewriteFile(withFlags, prefix))

        assertEquals("#!$prefix/bin/python3\nprint('ok')\n", regular.readText(Charsets.UTF_8))
        assertEquals("#!$prefix/bin/env -S python3 -u\nprint('ok')\n", withFlags.readText(Charsets.UTF_8))
    }
}
