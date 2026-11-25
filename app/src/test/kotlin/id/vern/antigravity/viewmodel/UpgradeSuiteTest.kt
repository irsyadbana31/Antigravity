package id.vern.antigravity.viewmodel

import id.vern.antigravity.ui.components.MarkdownElement
import id.vern.antigravity.ui.components.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpgradeSuiteTest {

    @Test
    fun conversationHistory_preservesCliSessionIdAcrossSerialization() {
        val original = listOf(
            ConversationSummary(
                id = "conv-1",
                title = "Gateway Auth",
                timestamp = 1795614000000L,
                messageCount = 4,
                isPinned = true,
                projectPath = "/data/home/repos/Antigravity",
                cliSessionId = "cli-uuid-999",
                isCustomTitle = true
            ),
            ConversationSummary(
                id = "conv-2",
                title = "Fresh Chat",
                timestamp = 1795614100000L,
                messageCount = 1,
                isPinned = false,
                projectPath = null,
                cliSessionId = null,
                isCustomTitle = false
            )
        )

        val json = ConversationPersistence.serializeHistoryJson(original)
        val restored = ConversationPersistence.parseHistoryJson(json)
        assertEquals(2, restored.size)
        assertEquals("cli-uuid-999", restored[0].cliSessionId)
        assertTrue(restored[0].isPinned)
        assertTrue(restored[0].isCustomTitle)
        assertNull(restored[1].cliSessionId)
        assertFalse(restored[1].isCustomTitle)
    }

    @Test
    fun parseDaemonTasksJson_readsNestedTriggerAndLastRunStatus() {
        val raw = """
            [
              {
                "id": "task_1",
                "name": "Nightly Check",
                "command": "",
                "prompt": "Summarize git status",
                "enabled": true,
                "last_run_status": "success",
                "trigger": {
                  "type": "cron",
                  "cron": "0 3 * * *",
                  "interval_sec": 300
                }
              },
              {
                "id": "task_2",
                "name": "Legacy Schema",
                "command": "echo ok",
                "trigger_type": "interval",
                "interval": 60,
                "last_status": "idle",
                "enabled": false
              }
            ]
        """.trimIndent()

        val parsed = AntigravityViewModel.parseDaemonTasksJson(raw)
        assertEquals(2, parsed.size)

        assertEquals("task_1", parsed[0].id)
        assertEquals("[AI] Summarize git status", parsed[0].command)
        assertEquals("cron", parsed[0].triggerType)
        assertEquals("0 3 * * *", parsed[0].cron)
        assertEquals(300, parsed[0].intervalSec)
        assertEquals("success", parsed[0].lastStatus)

        assertEquals("task_2", parsed[1].id)
        assertEquals("echo ok", parsed[1].command)
        assertEquals("interval", parsed[1].triggerType)
        assertEquals(60, parsed[1].intervalSec)
        assertEquals("idle", parsed[1].lastStatus)
        assertEquals(false, parsed[1].enabled)
    }

    @Test
    fun parseMarkdown_supportsGfmTablesAndTaskListsInKotlinFallback() {
        val md = """
            Status checklist:
            - [ ] Pending item
            - [x] Done item
            
            | Komponen | Hasil | Skor |
            | :--- | :---: | ---: |
            | Auth \| Token | PASS | 100 |
            | Stream | FAST | 99 |
        """.trimIndent()

        val elements = parseMarkdown(md)
        assertTrue(elements.any { it is MarkdownElement.TaskItem && !it.checked && it.text == "Pending item" })
        assertTrue(elements.any { it is MarkdownElement.TaskItem && it.checked && it.text == "Done item" })

        val table = elements.filterIsInstance<MarkdownElement.Table>().first()
        assertEquals(listOf("Komponen", "Hasil", "Skor"), table.headers)
        assertEquals(listOf('L', 'C', 'R'), table.alignments)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Auth | Token", "PASS", "100"), table.rows[0])
        assertEquals(listOf("Stream", "FAST", "99"), table.rows[1])
    }
}
