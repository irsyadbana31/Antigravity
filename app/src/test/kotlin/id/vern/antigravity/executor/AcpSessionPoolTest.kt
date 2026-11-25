package id.vern.antigravity.executor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AcpSessionPoolTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun buildCancelNotificationJson_matchesJsonRpcSpec() {
        val raw = AcpSessionPool.buildCancelNotificationJson("sess-abc-123")
        val obj = JSONObject(raw)
        assertEquals("2.0", obj.getString("jsonrpc"))
        assertEquals("session/cancel", obj.getString("method"))
        assertEquals("sess-abc-123", obj.getJSONObject("params").getString("sessionId"))
    }

    @Test
    fun hashFileContentOrStat_returnsZeroForMissingFile() {
        val missing = File(tempFolder.root, "does-not-exist.json")
        assertEquals(0L, AcpSessionPool.hashFileContentOrStat(missing))
    }

    @Test
    fun hashFileContentOrStat_ignoresMtimeTouchButDetectsContentChange() {
        val f = tempFolder.newFile("settings.json")
        f.writeText("""{"permissions":{"allow":["command(*)"]}}""", Charsets.UTF_8)

        val hash1 = AcpSessionPool.hashFileContentOrStat(f)

        // Touching mtime without changing content must keep fingerprint identical
        f.setLastModified(f.lastModified() + 60_000L)
        val hashAfterTouch = AcpSessionPool.hashFileContentOrStat(f)
        assertEquals(hash1, hashAfterTouch)

        // Changing content must invalidate fingerprint
        f.writeText("""{"permissions":{"allow":["command(*)","mcp(*)"]}}""", Charsets.UTF_8)
        val hashAfterEdit = AcpSessionPool.hashFileContentOrStat(f)
        assertNotEquals(hash1, hashAfterEdit)
    }
}
