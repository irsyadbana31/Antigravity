package id.vern.antigravity.service

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PermissionConfigServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun deprecatedGrantAndActionChecks_matchUnsandboxedOnly() {
        assertTrue(PermissionConfigService.isDeprecatedGrant("unsandboxed(*)"))
        assertTrue(PermissionConfigService.isDeprecatedGrant("  UNSANDBOXED(git) "))
        assertFalse(PermissionConfigService.isDeprecatedGrant("command(*)"))
        assertTrue(PermissionConfigService.isDeprecatedAction("unsandboxed"))
        assertFalse(PermissionConfigService.isDeprecatedAction("mcp"))
    }

    @Test
    fun syncSettingsFile_stripsLegacyUnsandboxedAndSkipsNoOpWrites() {
        val file = tempFolder.newFile("settings.json")
        file.writeText(
            """
            {
              "permissions": {
                "allow": ["unsandboxed(*)", "custom_tool(*)"]
              }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )

        val wroteFirst = PermissionConfigService.syncSettingsFile(file, PermissionGrantsState())
        assertTrue(wroteFirst)

        val parsed = JSONObject(file.readText(Charsets.UTF_8))
        val allowArr = parsed.getJSONObject("permissions").getJSONArray("allow")
        val allows = (0 until allowArr.length()).map { allowArr.getString(it) }
        assertFalse(allows.any { it.startsWith("unsandboxed(") })
        assertTrue(allows.contains("custom_tool(*)"))
        assertTrue(allows.contains("mcp(*)"))

        // Second sync with identical state must skip disk write
        val wroteSecond = PermissionConfigService.syncSettingsFile(file, PermissionGrantsState())
        assertFalse(wroteSecond)
    }
}
