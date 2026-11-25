package id.vern.antigravity.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CustomizationCatalogueDelegateTest {

    @Test
    fun syncDirectoriesNewestWins_copiesNonEmptyOverZeroByteWhenMtimeMatches() {
        val root = Files.createTempDirectory("sync-test").toFile()
        try {
            val dirA = File(root, "direct").apply { mkdirs() }
            val dirB = File(root, "home").apply { mkdirs() }
            val sameMtime = 1_700_000_000_000L

            val aManifest = File(dirA, "import_manifest.json").apply {
                writeText("""{"imports":[{"name":"conductor"}]}""")
                setLastModified(sameMtime)
            }
            val bManifest = File(dirB, "import_manifest.json").apply {
                writeText("")
                setLastModified(sameMtime)
            }

            val aEmpty = File(dirA, "other.json").apply {
                writeText("")
                setLastModified(sameMtime)
            }
            val bFilled = File(dirB, "other.json").apply {
                writeText("""{"ok":true}""")
                setLastModified(sameMtime)
            }

            CustomizationCatalogueDelegate.syncDirectoriesNewestWins(dirA, dirB)

            assertEquals(aManifest.readText(), bManifest.readText())
            assertEquals(bFilled.readText(), aEmpty.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun syncDirectoriesNewestWins_prefersNewerMtimeInBothDirections() {
        val root = Files.createTempDirectory("sync-mtime-test").toFile()
        try {
            val dirA = File(root, "direct").apply { mkdirs() }
            val dirB = File(root, "home").apply { mkdirs() }

            val fileA = File(dirA, "mcp_config.json").apply {
                writeText("""{"v":1}""")
                setLastModified(1_700_000_001_000L)
            }
            val fileB = File(dirB, "mcp_config.json").apply {
                writeText("""{"v":2}""")
                setLastModified(1_700_000_005_000L)
            }

            CustomizationCatalogueDelegate.syncDirectoriesNewestWins(dirA, dirB)

            assertEquals("""{"v":2}""", fileA.readText())
            assertEquals("""{"v":2}""", fileB.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
