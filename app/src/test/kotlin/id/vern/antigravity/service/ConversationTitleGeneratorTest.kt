package id.vern.antigravity.service

import id.vern.antigravity.executor.EngineExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTitleGeneratorTest {

    @Test
    fun extractGenerateContentText_skipsThoughtPartsAndReadsText() {
        val raw = """
            {
              "response": {
                "candidates": [
                  {
                    "content": {
                      "role": "model",
                      "parts": [
                        { "thought": true, "text": "Thinking about options..." },
                        { "thoughtSignature": "abc123" },
                        { "text": "Perbandingan Arsitektur AGY Turbo" }
                      ]
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        assertEquals(
            "Perbandingan Arsitektur AGY Turbo",
            CloudCodeQuotaService.extractGenerateContentText(raw)
        )

        val thoughtOnly = """
            {"response":{"candidates":[{"content":{"parts":[{"thought":true,"text":"Internal"}]}}]}}
        """.trimIndent()
        assertNull(CloudCodeQuotaService.extractGenerateContentText(thoughtOnly))
        assertNull(CloudCodeQuotaService.extractGenerateContentText("not-json"))
    }

    @Test
    fun pickLightweightModelId_prefersFlashLiteAndLowEffortOverHigh() {
        val models = listOf(
            CliModel("gemini-4-neon-high", "Gemini 4 Neon", "Gemini 4 Neon (High)", "Google", "High"),
            CliModel("gemini-3.8-flash-high", "Gemini 3.8 Flash", "Gemini 3.8 Flash (High)", "Google", "High"),
            CliModel("gemini-3.8-flash-low", "Gemini 3.8 Flash", "Gemini 3.8 Flash (Low)", "Google", "Low"),
            CliModel("gemini-4-carbon-low", "Gemini 4 Carbon", "Gemini 4 Carbon (Low)", "Google", "Low")
        )
        assertEquals(
            "gemini-3.8-flash-low",
            ConversationTitleGenerator.pickLightweightModelId(models, "gemini-pro-orchestrator-neon")
        )

        val withFlashLite = models + CliModel(
            "gemini-2.5-flash-lite-low",
            "Gemini 2.5 Flash Lite",
            "Gemini 2.5 Flash Lite (Low)",
            "Google",
            "Low"
        )
        assertEquals(
            "gemini-2.5-flash-lite-low",
            ConversationTitleGenerator.pickLightweightModelId(withFlashLite, "gemini-pro-orchestrator-neon")
        )

        val carbonOnly = listOf(
            CliModel("gemini-4-neon-high", "Gemini 4 Neon", "Gemini 4 Neon (High)", "Google", "High"),
            CliModel("gemini-4-carbon-low", "Gemini 4 Carbon", "Gemini 4 Carbon (Low)", "Google", "Low")
        )
        assertEquals(
            "gemini-4-carbon-low",
            ConversationTitleGenerator.pickLightweightModelId(carbonOnly, "gemini-pro-orchestrator-neon")
        )
    }

    @Test
    fun buildImmediateFallbackTitle_cleansUrlsAndConversationalFillers() {
        val indoFiller = ConversationTitleGenerator.buildImmediateFallbackTitle(
            "oiya tolong perbaiki judul conversation biar kayak gemini"
        )
        assertFalse(indoFiller.lowercase().startsWith("oiya"))
        assertFalse(indoFiller.lowercase().startsWith("tolong"))
        assertTrue(indoFiller.startsWith("Perbaiki"))

        val withUrlAndText = ConversationTitleGenerator.buildImmediateFallbackTitle(
            "https://github.com/werdio325-png/antigravity-android coba bandingkan arsitektur repo ini"
        )
        assertFalse(withUrlAndText.contains("http"))
        assertTrue(withUrlAndText.startsWith("Bandingkan"))

        val bareGithubUrl = ConversationTitleGenerator.buildImmediateFallbackTitle(
            "https://github.com/werdio325-png/antigravity-android"
        )
        assertEquals("Werdio325-png/antigravity-android", bareGithubUrl)
    }

    @Test
    fun isSubstantiveForEarlyTitle_rejectsBareGreetingsAndAcceptsRealTopics() {
        assertFalse(ConversationTitleGenerator.isSubstantiveForEarlyTitle("halo"))
        assertFalse(ConversationTitleGenerator.isSubstantiveForEarlyTitle("tes"))
        assertFalse(ConversationTitleGenerator.isSubstantiveForEarlyTitle("oiya tolong"))
        assertFalse(ConversationTitleGenerator.isSubstantiveForEarlyTitle("udah install"))
        assertTrue(
            ConversationTitleGenerator.isSubstantiveForEarlyTitle(
                "oiya buat judul conversation biar relevan kayak Gemini"
            )
        )
        assertTrue(
            ConversationTitleGenerator.isSubstantiveForEarlyTitle(
                "https://github.com/werdio325-png/antigravity-android bandingkan"
            )
        )
    }

    @Test
    fun looksLikeLegacyFallbackTitle_matchesRawUrlAndTruncatedFirstPromptOnly() {
        val rawUrlMsg = "https://github.com/werdio325-png/antigravity-android coba bandingkan"
        assertTrue(
            ConversationTitleGenerator.looksLikeLegacyFallbackTitle(
                "https://github.com/werdio325-png/antig…",
                rawUrlMsg
            )
        )

        val longPrompt = "diatas adalah panduan cek usage untuk akun pro dan ultra"
        assertTrue(
            ConversationTitleGenerator.looksLikeLegacyFallbackTitle(
                "diatas adalah panduan cek usage…",
                longPrompt
            )
        )
        assertTrue(ConversationTitleGenerator.looksLikeLegacyFallbackTitle("New Conversation", longPrompt))

        assertFalse(
            ConversationTitleGenerator.looksLikeLegacyFallbackTitle(
                "Panduan Cek Kuota Akun",
                longPrompt
            )
        )
    }

    @Test
    fun sanitizeGeneratedTitle_stripsMarkdownAndRejectsErrors() {
        assertEquals(
            "Perbaikan Auto-Title Gemini",
            ConversationTitleGenerator.sanitizeGeneratedTitle("**Title: \"Perbaikan Auto-Title Gemini.\"**")
        )
        assertEquals(
            "Debugging Android Daemon",
            ConversationTitleGenerator.sanitizeGeneratedTitle("Sure, here is a title: Debugging Android Daemon")
        )
        assertNull(ConversationTitleGenerator.sanitizeGeneratedTitle("Error: unauthorized request"))
        assertNull(ConversationTitleGenerator.sanitizeGeneratedTitle("I cannot fulfill this request"))
        assertNull(ConversationTitleGenerator.sanitizeGeneratedTitle("New Conversation"))
    }

    @Test
    fun buildQuickPrintArgs_placesPrintPromptLast() {
        val prompt = "Create a short title"
        val args = EngineExecutor.buildQuickPrintArgs(
            prompt = prompt,
            cliModelId = "gemini-3.8-flash-low",
            timeoutMs = 20_000L
        )
        assertEquals(listOf("-p", prompt), args.takeLast(2))
        val outIdx = args.indexOf("--output-format")
        val pIdx = args.indexOf("-p")
        assertTrue(outIdx >= 0)
        assertTrue(pIdx > outIdx + 1)
        assertFalse(args.contains("--print"))
        assertEquals("20s", args[args.indexOf("--print-timeout") + 1])
    }
}
