package id.vern.antigravity.service

import android.content.Context
import id.vern.antigravity.executor.EngineExecutor
import java.net.URI

object ConversationTitleGenerator {

    private const val MAX_TITLE_CHARS = 48
    private const val FALLBACK_MAX_CHARS = 38

    private val URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val MARKDOWN_CHARS_REGEX = Regex("[`*_#>\\[\\]]+")
    private val MULTI_SPACE_REGEX = Regex("\\s+")
    private val LEADING_FILLER_REGEX = Regex(
        "^(?:oh\\s*iya|ohiya|oiya|halo|hello|hai|hi|hey|permisi|tolong|bisa\\s+bantu|minta\\s+tolong|coba|please|can\\s+you|could\\s+you|help\\s+me)(?:[,:;!\\s]+|$)",
        RegexOption.IGNORE_CASE
    )
    private val TITLE_PREFIX_REGEX = Regex(
        "^(?:(?:sure[,!:\\s]+)?(?:here\\s+is\\s+(?:a\\s+)?title|title|judul)\\s*[:\\-]\\s*)",
        RegexOption.IGNORE_CASE
    )

    private const val TITLE_SYSTEM_INSTRUCTION =
        "You generate concise, natural conversation titles like Google Gemini.\n" +
            "Rules:\n" +
            "- Capture the core topic or goal in 3 to 6 words.\n" +
            "- Use the same language as the user's message (Indonesian -> Indonesian, English -> English).\n" +
            "- Never copy URLs, greetings, filler words ('oiya', 'halo', 'tolong', 'please'), or raw opening words verbatim.\n" +
            "- Reply with ONLY the plain title. No quotes, no markdown, no prefixes, no trailing punctuation."

    internal fun extractReadableUrlLabel(url: String): String? {
        return try {
            val uri = URI(url.trim().trimEnd(',', '.', ')', ']', ';'))
            val host = (uri.host ?: return null).removePrefix("www.")
            val segments = uri.path.orEmpty()
                .split('/')
                .map { it.trim() }
                .filter { it.isNotBlank() }
            when {
                host.equals("github.com", ignoreCase = true) && segments.size >= 2 ->
                    "${segments[0]}/${segments[1].removeSuffix(".git")}"
                segments.isNotEmpty() -> "$host/${segments[0]}"
                host.isNotBlank() -> host
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun stripLeadingFillers(text: String): String {
        var current = text.trim()
        repeat(3) {
            val match = LEADING_FILLER_REGEX.find(current) ?: return current
            val remainder = current.substring(match.range.last + 1).trim()
            if (remainder.isBlank()) return current
            current = remainder
        }
        return current
    }

    fun buildImmediateFallbackTitle(userPrompt: String, attachmentName: String? = null): String {
        val nonBlankLines = userPrompt
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

        if (nonBlankLines.isEmpty()) {
            return attachmentName?.trim()?.takeIf { it.isNotBlank() }?.let {
                truncateAtWordBoundary(it, FALLBACK_MAX_CHARS)
            } ?: "New Conversation"
        }

        val topicLine = nonBlankLines.firstOrNull { line ->
            URL_REGEX.replace(line, "").replace(MARKDOWN_CHARS_REGEX, " ").trim().isNotBlank()
        } ?: nonBlankLines.first()

        val firstUrl = URL_REGEX.find(topicLine)?.value
        val withoutUrls = URL_REGEX.replace(topicLine, "")
            .replace(MARKDOWN_CHARS_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()

        val base = when {
            withoutUrls.isNotBlank() -> stripLeadingFillers(withoutUrls)
            firstUrl != null -> extractReadableUrlLabel(firstUrl) ?: firstUrl
            else -> topicLine
        }.ifBlank { "New Conversation" }

        val capitalized = base.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase() else ch.toString()
        }

        return truncateAtWordBoundary(capitalized, FALLBACK_MAX_CHARS)
            .ifBlank { "New Conversation" }
    }

    fun isSubstantiveForEarlyTitle(userPrompt: String): Boolean {
        val trimmed = userPrompt.trim()
        if (trimmed.isEmpty()) return false
        val hasUrl = URL_REGEX.containsMatchIn(trimmed)
        val withoutUrls = URL_REGEX.replace(trimmed, " ")
            .replace(MARKDOWN_CHARS_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
        val stripped = stripLeadingFillers(withoutUrls)
        val words = stripped.split(MULTI_SPACE_REGEX).filter { it.isNotBlank() }
        return (stripped.length >= 14 && words.size >= 2) || (hasUrl && words.isNotEmpty())
    }

    fun looksLikeLegacyFallbackTitle(currentTitle: String, firstUserMessage: String?): Boolean {
        val trimmedTitle = currentTitle.trim()
        if (trimmedTitle.isEmpty() || trimmedTitle.equals("New Conversation", ignoreCase = true)) {
            return true
        }
        if (trimmedTitle.startsWith("http://", ignoreCase = true) ||
            trimmedTitle.startsWith("https://", ignoreCase = true)
        ) {
            return true
        }
        if (!firstUserMessage.isNullOrBlank()) {
            val trimmedMsg = firstUserMessage.trim()
            if (trimmedTitle == buildImmediateFallbackTitle(trimmedMsg)) return true
            val firstLine = trimmedMsg.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            if (firstLine.isNotEmpty() && trimmedTitle.equals(firstLine, ignoreCase = true)) {
                return true
            }
            val rawPrefix = firstLine.take(12).lowercase()
            if (rawPrefix.length >= 12 &&
                (trimmedTitle.endsWith("…") || trimmedTitle.endsWith("...")) &&
                trimmedTitle.lowercase().startsWith(rawPrefix)
            ) {
                return true
            }
        }
        return false
    }

    private fun isLowEffort(model: CliModel): Boolean {
        return model.effort.equals("Low", ignoreCase = true) ||
            model.cliId.lowercase().endsWith("-low")
    }

    private fun isHighEffort(model: CliModel): Boolean {
        val lowerEffort = model.effort.lowercase()
        val lowerId = model.cliId.lowercase()
        return lowerEffort in setOf("high", "xhigh", "max") ||
            lowerId.endsWith("-high") ||
            lowerId.endsWith("-xhigh") ||
            lowerId.endsWith("-max")
    }

    private fun isGoogleModel(model: CliModel): Boolean {
        return model.provider.equals("Google", ignoreCase = true) ||
            model.cliId.lowercase().contains("gemini")
    }

    fun pickLightweightModelId(
        availableModels: List<CliModel>,
        fallbackModelId: String?
    ): String? {
        val cleanedFallback = fallbackModelId?.let { AgyModelsService.cleanModelId(it) }?.takeIf { it.isNotBlank() }
        if (availableModels.isEmpty()) return cleanedFallback

        fun hay(model: CliModel) = "${model.cliId} ${model.displayName}".lowercase()

        val flashLiteModels = availableModels.filter { m ->
            val h = hay(m)
            h.contains("flash-lite") || h.contains("flash_lite") || h.contains("flash lite")
        }
        flashLiteModels.firstOrNull { isLowEffort(it) }?.let { return AgyModelsService.cleanModelId(it.cliId) }
        flashLiteModels.firstOrNull()?.let { return AgyModelsService.cleanModelId(it.cliId) }

        val flashModels = availableModels.filter { m -> hay(m).contains("flash") }
        flashModels.firstOrNull { isLowEffort(it) }?.let { return AgyModelsService.cleanModelId(it.cliId) }
        flashModels.firstOrNull { it.effort.equals("Medium", ignoreCase = true) || it.cliId.lowercase().endsWith("-medium") }
            ?.let { return AgyModelsService.cleanModelId(it.cliId) }
        flashModels.firstOrNull { !isHighEffort(it) }?.let { return AgyModelsService.cleanModelId(it.cliId) }
        flashModels.firstOrNull()?.let { return AgyModelsService.cleanModelId(it.cliId) }

        val carbonLow = availableModels.firstOrNull { m ->
            hay(m).contains("carbon") && isLowEffort(m)
        }
        if (carbonLow != null) return AgyModelsService.cleanModelId(carbonLow.cliId)

        val googleLow = availableModels.firstOrNull { m -> isGoogleModel(m) && isLowEffort(m) }
        if (googleLow != null) return AgyModelsService.cleanModelId(googleLow.cliId)

        return cleanedFallback ?: AgyModelsService.cleanModelId(availableModels.first().cliId)
    }

    suspend fun generateContextualTitle(
        context: Context,
        userPrompt: String,
        assistantResponse: String,
        availableModels: List<CliModel>,
        fallbackModelId: String?
    ): String? {
        val userSnippet = userPrompt.trim().take(600)
        val assistantSnippet = assistantResponse.trim().take(600)
        if (userSnippet.isBlank() && assistantSnippet.isBlank()) return null

        val userContext = buildString {
            appendLine("User: $userSnippet")
            if (assistantSnippet.isNotBlank()) {
                appendLine("Assistant: $assistantSnippet")
            }
        }

        // Priority 1: Direct CloudCode HTTP API (~1s, no subprocess cold-start)
        val directRaw = CloudCodeQuotaService.generateLightweightText(
            context = context,
            systemInstruction = TITLE_SYSTEM_INSTRUCTION,
            userText = userContext,
            maxOutputTokens = 48
        )
        if (!directRaw.isNullOrBlank()) {
            sanitizeGeneratedTitle(directRaw)?.let { return it }
        }

        // Priority 2: Lightweight agy CLI fallback
        val modelId = pickLightweightModelId(availableModels, fallbackModelId)
        val cliFallbackPrompt = buildString {
            appendLine(TITLE_SYSTEM_INSTRUCTION)
            appendLine("Do NOT call any tools.")
            appendLine()
            append(userContext)
        }

        val rawOutput = EngineExecutor.runAgyQuickPrint(
            context = context,
            prompt = cliFallbackPrompt,
            cliModelId = modelId,
            timeoutMs = 20_000L
        ) ?: return null

        return sanitizeGeneratedTitle(rawOutput)
    }

    fun sanitizeGeneratedTitle(rawOutput: String): String? {
        val candidateLine = rawOutput
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotBlank() &&
                    !line.startsWith("!") &&
                    !line.startsWith("Fetching ") &&
                    !line.startsWith("[") &&
                    !line.lowercase().startsWith("error:") &&
                    !line.lowercase().startsWith("warning:")
            } ?: return null

        var cleaned = candidateLine
            .replace(Regex("^[#*\\-•>\\s]+"), "")
            .replace(TITLE_PREFIX_REGEX, "")
            .replace(Regex("^[#*\\-•>\\s]+"), "")
            .trim()
            .trim('"', '\'', '`', '*', '_', '.', ':', ';')
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()

        if (cleaned.length < 3) return null

        val lower = cleaned.lowercase()
        if (lower.startsWith("error") ||
            lower.contains("timed out") ||
            lower.contains("unauthorized") ||
            lower.contains("failed to ") ||
            lower.startsWith("i cannot") ||
            lower.startsWith("i can't") ||
            lower.startsWith("as an ai") ||
            lower.startsWith("maaf, saya") ||
            lower.startsWith("maaf saya") ||
            lower.startsWith("sorry,") ||
            lower == "new conversation"
        ) {
            return null
        }

        if (cleaned.length > 70 &&
            (cleaned.contains(". ") || cleaned.contains("? ") || cleaned.contains("! "))
        ) {
            return null
        }

        if (cleaned.length > MAX_TITLE_CHARS) {
            cleaned = truncateAtWordBoundary(cleaned, MAX_TITLE_CHARS)
        }

        return cleaned.takeIf { it.length >= 3 }
    }

    private fun truncateAtWordBoundary(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val slice = text.take(maxChars)
        val lastSpace = slice.lastIndexOf(' ')
        val cut = if (lastSpace >= maxChars / 2) slice.substring(0, lastSpace) else slice
        return cut.trimEnd(',', '.', ';', ':', '-', ' ') + "…"
    }
}
