package id.vern.antigravity.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.vern.antigravity.R
import id.vern.antigravity.nativebridge.RustTurboBridge
import id.vern.antigravity.util.ExternalFileOpener

sealed class MarkdownElement {
    data class Heading(val level: Int, val text: String) : MarkdownElement()
    data class CodeBlock(val language: String, val code: String) : MarkdownElement()
    data class ListItem(val bullet: String, val text: String) : MarkdownElement()
    data class TaskItem(val checked: Boolean, val text: String) : MarkdownElement()
    data class Table(
        val headers: List<String>,
        val alignments: List<Char>,
        val rows: List<List<String>>
    ) : MarkdownElement()
    data class BlockQuote(val text: String) : MarkdownElement()
    object Divider : MarkdownElement()
    data class Paragraph(val text: String) : MarkdownElement()
}

private val BULLET_REGEX = Regex("""^[-*+]\s+(.*)$""")
private val NUM_REGEX = Regex("""^(\d+)\.\s+(.*)$""")
private val BULLET_PREFIX_REGEX = Regex("""^[-*+]\s+""")
private val NUM_PREFIX_REGEX = Regex("""^\d+\.\s+""")
private val INLINE_TOKEN_REGEX = Regex("""(`[^`]+`|\*\*[^*]+\*\*|\*[^*]+\*|\[[^\]]+\]\([^)]+\))""")

private val markdownBlockCache = LruCache<String, List<MarkdownElement>>(64)
private val inlineMarkdownCache = LruCache<String, AnnotatedString>(256)
private val codeHighlightCache = LruCache<String, AnnotatedString>(128)

private val CODE_KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "enum", "sealed", "data",
    "return", "if", "else", "when", "for", "while", "do", "try", "catch", "finally",
    "suspend", "override", "private", "internal", "public", "protected", "companion",
    "fn", "let", "mut", "pub", "impl", "struct", "match", "use", "mod", "crate",
    "def", "import", "from", "as", "async", "await", "with", "yield", "lambda", "pass",
    "const", "function", "export", "default", "switch", "case", "break", "continue",
    "true", "false", "null", "None", "True", "False", "self", "this", "super", "new"
)

fun parseMarkdown(content: String): List<MarkdownElement> {
    if (content.isEmpty()) return emptyList()
    markdownBlockCache.get(content)?.let { return it }

    // Incremental fast-path for LLM streaming: reuse cached parsed prefix up to last safe block boundary.
    val splitIdx = findLastSafeBlockBoundary(content)
    if (splitIdx > 0) {
        val prefix = content.substring(0, splitIdx)
        val cachedPrefix = markdownBlockCache.get(prefix)
        if (cachedPrefix != null) {
            val tail = content.substring(splitIdx)
            val tailElements = parseMarkdownUncached(tail)
            val combined = ArrayList<MarkdownElement>(cachedPrefix.size + tailElements.size).apply {
                addAll(cachedPrefix)
                addAll(tailElements)
            }
            markdownBlockCache.put(content, combined)
            return combined
        } else {
            val prefixElements = parseMarkdownUncached(prefix)
            markdownBlockCache.put(prefix, prefixElements)
            val tail = content.substring(splitIdx)
            val tailElements = parseMarkdownUncached(tail)
            val combined = ArrayList<MarkdownElement>(prefixElements.size + tailElements.size).apply {
                addAll(prefixElements)
                addAll(tailElements)
            }
            markdownBlockCache.put(content, combined)
            return combined
        }
    }

    val parsed = parseMarkdownUncached(content)
    markdownBlockCache.put(content, parsed)
    return parsed
}

private fun findLastSafeBlockBoundary(content: String): Int {
    val lastBreak = content.lastIndexOf("\n\n")
    if (lastBreak <= 0) return -1
    var fenceCount = 0
    var idx = 0
    while (idx < lastBreak) {
        val lineEnd = content.indexOf('\n', idx).let { if (it == -1 || it > lastBreak) lastBreak else it }
        var p = idx
        while (p < lineEnd && (content[p] == ' ' || content[p] == '\t')) p++
        if (p + 2 < lineEnd && content[p] == '`' && content[p + 1] == '`' && content[p + 2] == '`') {
            fenceCount++
        }
        idx = lineEnd + 1
    }
    return if (fenceCount % 2 == 0) lastBreak + 2 else -1
}

private fun parseTableCells(line: String): List<String> {
    var trimmed = line.trim()
    if (trimmed.startsWith("|")) {
        trimmed = trimmed.substring(1)
    }
    if (trimmed.endsWith("|")) {
        var backslashes = 0
        var k = trimmed.length - 2
        while (k >= 0 && trimmed[k] == '\\') {
            backslashes++
            k--
        }
        if (backslashes % 2 == 0) {
            trimmed = trimmed.substring(0, trimmed.length - 1)
        }
    }
    val cells = mutableListOf<String>()
    val cur = StringBuilder()
    var i = 0
    while (i < trimmed.length) {
        val c = trimmed[i]
        if (c == '\\' && i + 1 < trimmed.length) {
            val next = trimmed[i + 1]
            if (next == '|' || next == '\\') {
                cur.append(next)
                i += 2
                continue
            }
        }
        if (c == '|') {
            cells.add(cur.toString().trim())
            cur.clear()
        } else {
            cur.append(c)
        }
        i++
    }
    cells.add(cur.toString().trim())
    return cells
}

private fun parseTableDelimiter(line: String): List<Char>? {
    val trimmed = line.trim()
    if (!trimmed.contains('|')) return null
    val cells = parseTableCells(trimmed)
    if (cells.isEmpty()) return null
    val aligns = ArrayList<Char>(cells.size)
    for (cell in cells) {
        if (cell.isEmpty()) return null
        val left = cell.startsWith(":")
        val right = cell.endsWith(":")
        val innerStart = if (left) 1 else 0
        val innerEnd = if (right && cell.length > innerStart) cell.length - 1 else cell.length
        val dashes = cell.substring(innerStart, innerEnd)
        if (dashes.isEmpty() || !dashes.all { it == '-' }) return null
        aligns.add(
            when {
                left && right -> 'C'
                right -> 'R'
                else -> 'L'
            }
        )
    }
    return aligns
}

private fun tryTableStart(lines: List<String>, idx: Int): Pair<List<String>, List<Char>>? {
    if (idx + 1 >= lines.size) return null
    val headerLine = lines[idx].trim()
    if (!headerLine.contains('|') || headerLine.startsWith("```")) return null
    val aligns = parseTableDelimiter(lines[idx + 1]) ?: return null
    val headers = parseTableCells(headerLine)
    if (headers.isEmpty()) return null
    return headers to aligns
}

private fun parseHeadingLine(trimmed: String): Pair<Int, String>? {
    if (!trimmed.startsWith("#")) return null
    val level = trimmed.takeWhile { it == '#' }.length
    return if (level in 1..6 && trimmed.getOrNull(level) == ' ') {
        level to trimmed.substring(level).trim()
    } else {
        null
    }
}

private fun parseMarkdownUncached(content: String): List<MarkdownElement> {
    RustTurboBridge.parseMarkdownNative(content)?.let { return it }
    val elements = mutableListOf<MarkdownElement>()
    val lines = content.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            val language = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (i < lines.size) i++
            elements.add(MarkdownElement.CodeBlock(language, codeLines.joinToString("\n")))
            continue
        }

        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            elements.add(MarkdownElement.Divider)
            i++
            continue
        }

        val heading = parseHeadingLine(trimmed)
        if (heading != null) {
            elements.add(MarkdownElement.Heading(heading.first, heading.second))
            i++
            continue
        }

        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quoteLines.add(lines[i].trim().removePrefix(">").trim())
                i++
            }
            elements.add(MarkdownElement.BlockQuote(quoteLines.joinToString(" ")))
            continue
        }

        val bulletMatch = BULLET_REGEX.find(trimmed)
        if (bulletMatch != null) {
            val text = bulletMatch.groupValues[1]
            when {
                text.startsWith("[ ] ") -> elements.add(MarkdownElement.TaskItem(false, text.substring(4).trimStart()))
                text.startsWith("[x] ") || text.startsWith("[X] ") -> elements.add(MarkdownElement.TaskItem(true, text.substring(4).trimStart()))
                text == "[ ]" -> elements.add(MarkdownElement.TaskItem(false, ""))
                text == "[x]" || text == "[X]" -> elements.add(MarkdownElement.TaskItem(true, ""))
                else -> elements.add(MarkdownElement.ListItem("•", text))
            }
            i++
            continue
        }

        val numMatch = NUM_REGEX.find(trimmed)
        if (numMatch != null) {
            val num = numMatch.groupValues[1]
            val text = numMatch.groupValues[2]
            elements.add(MarkdownElement.ListItem("$num.", text))
            i++
            continue
        }

        if (trimmed.isEmpty()) {
            i++
            continue
        }

        val tableStart = tryTableStart(lines, i)
        if (tableStart != null) {
            val rawHeaders = tableStart.first.toMutableList()
            val rawAligns = tableStart.second.toMutableList()
            val colCount = maxOf(rawHeaders.size, rawAligns.size)
            while (rawHeaders.size < colCount) rawHeaders.add("")
            while (rawAligns.size < colCount) rawAligns.add('L')
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size) {
                val rowTrim = lines[i].trim()
                if (rowTrim.isEmpty() ||
                    !rowTrim.contains('|') ||
                    rowTrim.startsWith("```") ||
                    parseHeadingLine(rowTrim) != null ||
                    rowTrim.startsWith(">")
                ) {
                    break
                }
                val cells = parseTableCells(rowTrim).toMutableList()
                while (cells.size < colCount) cells.add("")
                rows.add(cells.take(colCount))
                i++
            }
            elements.add(MarkdownElement.Table(rawHeaders.take(colCount), rawAligns, rows))
            continue
        }

        val paraLines = mutableListOf<String>()
        while (i < lines.size) {
            val cur = lines[i]
            val curTrim = cur.trim()
            if (paraLines.isNotEmpty() && (
                curTrim.isEmpty() ||
                    curTrim.startsWith("```") ||
                    parseHeadingLine(curTrim) != null ||
                    curTrim.startsWith(">") ||
                    curTrim == "---" ||
                    curTrim == "***" ||
                    curTrim == "___" ||
                    BULLET_PREFIX_REGEX.containsMatchIn(curTrim) ||
                    NUM_PREFIX_REGEX.containsMatchIn(curTrim) ||
                    tryTableStart(lines, i) != null
                )
            ) {
                break
            }
            paraLines.add(curTrim)
            i++
        }
        if (paraLines.isNotEmpty()) {
            elements.add(MarkdownElement.Paragraph(paraLines.joinToString(" ")))
        }
    }
    return elements
}

fun parseInlineMarkdown(
    text: String,
    primaryColor: Color = Color(0xFF1D64E6),
    codeBgColor: Color = Color(0x2B888888),
    codeTextColor: Color = Color.Unspecified
): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    val cacheKey = "${primaryColor.value}:${codeBgColor.value}:${codeTextColor.value}:$text"
    inlineMarkdownCache.get(cacheKey)?.let { return it }

    val result = buildAnnotatedString {
        var cursor = 0
        val matches = INLINE_TOKEN_REGEX.findAll(text)

        for (match in matches) {
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }

            val token = match.value
            when {
                token.startsWith("`") && token.endsWith("`") && token.length >= 2 -> {
                    val code = token.substring(1, token.length - 1)
                    val start = length
                    append(" $code ")
                    addStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBgColor,
                            color = codeTextColor,
                            fontSize = 11.5.sp
                        ),
                        start,
                        length
                    )
                }
                token.startsWith("**") && token.endsWith("**") && token.length >= 4 -> {
                    val boldText = token.substring(2, token.length - 2)
                    val start = length
                    append(boldText)
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
                }
                token.startsWith("*") && token.endsWith("*") && token.length >= 2 -> {
                    val italicText = token.substring(1, token.length - 1)
                    val start = length
                    append(italicText)
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
                }
                token.startsWith("[") && token.contains("](") && token.endsWith(")") -> {
                    val label = token.substringAfter("[").substringBefore("](")
                    val url = token.substringAfter("](").substringBeforeLast(")")
                    val start = length
                    append(label)
                    addStyle(
                        SpanStyle(
                            color = primaryColor,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Medium
                        ),
                        start,
                        length
                    )
                    addStringAnnotation(tag = "URL", annotation = url, start = start, end = length)
                }
                else -> {
                    append(token)
                }
            }
            cursor = match.range.last + 1
        }

        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
    inlineMarkdownCache.put(cacheKey, result)
    return result
}

@Composable
fun FormattedText(
    annotatedString: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hasLinks = remember(annotatedString) {
        annotatedString.getStringAnnotations("URL", 0, annotatedString.length).isNotEmpty()
    }

    if (hasLinks) {
        @Suppress("DEPRECATION")
        ClickableText(
            text = annotatedString,
            style = style,
            modifier = modifier,
            onClick = { offset ->
                annotatedString.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { annotation ->
                    ExternalFileOpener.openUrlOrFile(context, annotation.item)
                }
            }
        )
    } else {
        Text(
            text = annotatedString,
            style = style,
            modifier = modifier
        )
    }
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    fontSize: TextUnit = 12.sp,
    lineHeight: TextUnit = 17.sp,
    selectable: Boolean = true
) {
    val context = LocalContext.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val codeBgColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f)
    val codeTextColor = MaterialTheme.colorScheme.onSurface
    val elements = remember(markdown) { parseMarkdown(markdown) }

    val markdownBody = @Composable {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            elements.forEachIndexed { index, element ->
                key(index) {
                    when (element) {
                        is MarkdownElement.Heading -> {
                            val (size, weight) = when (element.level) {
                                1 -> 15.sp to FontWeight.Bold
                                2 -> 14.sp to FontWeight.Bold
                                3 -> 13.sp to FontWeight.SemiBold
                                else -> 12.sp to FontWeight.SemiBold
                            }
                            val annotated = remember(element.text, primaryColor, codeBgColor, codeTextColor) {
                                parseInlineMarkdown(element.text, primaryColor, codeBgColor, codeTextColor)
                            }
                            FormattedText(
                                annotatedString = annotated,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = size,
                                    fontWeight = weight,
                                    color = textColor,
                                    lineHeight = (size.value * 1.35f).sp
                                ),
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                            )
                        }

                        is MarkdownElement.CodeBlock -> {
                            CodeBlockCard(
                                language = element.language,
                                code = element.code,
                                onCopy = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Code", element.code))
                                    Toast.makeText(context, context.getString(R.string.markdown_code_copied), Toast.LENGTH_SHORT).show()
                                }
                            )
                        }

                        is MarkdownElement.ListItem -> {
                            val annotated = remember(element.text, primaryColor, codeBgColor, codeTextColor) {
                                parseInlineMarkdown(element.text, primaryColor, codeBgColor, codeTextColor)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 4.dp, top = 1.dp, bottom = 1.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = element.bullet,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = primaryColor
                                    ),
                                    modifier = Modifier.width(20.dp)
                                )
                                FormattedText(
                                    annotatedString = annotated,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = fontSize,
                                        lineHeight = lineHeight,
                                        color = textColor
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        is MarkdownElement.TaskItem -> {
                            val annotated = remember(element.text, primaryColor, codeBgColor, codeTextColor) {
                                parseInlineMarkdown(element.text, primaryColor, codeBgColor, codeTextColor)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 4.dp, top = 1.dp, bottom = 1.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = if (element.checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = null,
                                    tint = if (element.checked) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .padding(top = 1.5.dp)
                                        .size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                FormattedText(
                                    annotatedString = annotated,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = fontSize,
                                        lineHeight = lineHeight,
                                        color = if (element.checked) textColor.copy(alpha = 0.8f) else textColor
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        is MarkdownElement.Table -> {
                            MarkdownTableCard(
                                table = element,
                                textColor = textColor,
                                fontSize = fontSize,
                                lineHeight = lineHeight
                            )
                        }

                        is MarkdownElement.BlockQuote -> {
                            val annotated = remember(element.text, primaryColor, codeBgColor, codeTextColor) {
                                parseInlineMarkdown(element.text, primaryColor, codeBgColor, codeTextColor)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min)
                                    .padding(vertical = 3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(2.5.dp)
                                        .fillMaxHeight()
                                        .background(primaryColor, RoundedCornerShape(2.dp))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                FormattedText(
                                    annotatedString = annotated,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontStyle = FontStyle.Italic,
                                        fontSize = fontSize,
                                        lineHeight = lineHeight,
                                        color = textColor.copy(alpha = 0.85f)
                                    )
                                )
                            }
                        }

                        is MarkdownElement.Divider -> {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        }

                        is MarkdownElement.Paragraph -> {
                            val annotated = remember(element.text, primaryColor, codeBgColor, codeTextColor) {
                                parseInlineMarkdown(element.text, primaryColor, codeBgColor, codeTextColor)
                            }
                            FormattedText(
                                annotatedString = annotated,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = fontSize,
                                    lineHeight = lineHeight,
                                    color = textColor
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    if (selectable) {
        SelectionContainer {
            markdownBody()
        }
    } else {
        markdownBody()
    }
}

private fun stripSimpleMarkdownForWidth(text: String): Int {
    return text.replace("**", "").replace("`", "").length
}

private fun alignmentToTextAlign(align: Char): TextAlign = when (align) {
    'C' -> TextAlign.Center
    'R' -> TextAlign.End
    else -> TextAlign.Start
}

@Composable
fun MarkdownTableCard(
    table: MarkdownElement.Table,
    textColor: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val codeBgColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f)
    val codeTextColor = MaterialTheme.colorScheme.onSurface
    val colCount = table.headers.size.coerceAtLeast(1)
    val colWidths: List<Dp> = remember(table) {
        (0 until colCount).map { colIdx ->
            var maxChars = stripSimpleMarkdownForWidth(table.headers.getOrElse(colIdx) { "" })
            for (row in table.rows) {
                val cellLen = stripSimpleMarkdownForWidth(row.getOrElse(colIdx) { "" })
                if (cellLen > maxChars) maxChars = cellLen
            }
            ((maxChars * 7.4f) + 24f).coerceIn(72f, 260f).dp
        }
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
            ) {
                for (c in 0 until colCount) {
                    val headerText = table.headers.getOrElse(c) { "" }
                    val annotated = remember(headerText, primaryColor, codeBgColor, codeTextColor) {
                        parseInlineMarkdown(headerText, primaryColor, codeBgColor, codeTextColor)
                    }
                    val align = alignmentToTextAlign(table.alignments.getOrElse(c) { 'L' })
                    Box(
                        modifier = Modifier
                            .width(colWidths[c])
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        FormattedText(
                            annotatedString = annotated,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                fontWeight = FontWeight.Bold,
                                color = textColor,
                                textAlign = align,
                                fontFeatureSettings = "tnum"
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            table.rows.forEachIndexed { rIdx, row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                val rowBg = if (rIdx % 2 == 1) {
                    MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.45f)
                } else {
                    Color.Transparent
                }
                Row(modifier = Modifier.background(rowBg)) {
                    for (c in 0 until colCount) {
                        val cellText = row.getOrElse(c) { "" }
                        val annotated = remember(cellText, primaryColor, codeBgColor, codeTextColor) {
                            parseInlineMarkdown(cellText, primaryColor, codeBgColor, codeTextColor)
                        }
                        val align = alignmentToTextAlign(table.alignments.getOrElse(c) { 'L' })
                        Box(
                            modifier = Modifier
                                .width(colWidths[c])
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            FormattedText(
                                annotatedString = annotated,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = fontSize,
                                    lineHeight = lineHeight,
                                    color = textColor,
                                    textAlign = align,
                                    fontFeatureSettings = "tnum"
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun highlightCodeSyntax(code: String, isDark: Boolean): AnnotatedString {
    if (code.length > 16_000) return AnnotatedString(code)
    val cacheKey = "${if (isDark) 1 else 0}:$code"
    codeHighlightCache.get(cacheKey)?.let { return it }

    val keywordColor = if (isDark) Color(0xFFC792EA) else Color(0xFF7B1FA2)
    val stringColor = if (isDark) Color(0xFFC3E88D) else Color(0xFF2E7D32)
    val commentColor = if (isDark) Color(0xFF7F8C98) else Color(0xFF607D8B)
    val numberColor = if (isDark) Color(0xFFF78C6C) else Color(0xFFD84315)
    val annotationColor = if (isDark) Color(0xFFFFCB6B) else Color(0xFFE65100)

    val result = buildAnnotatedString {
        append(code)
        val n = code.length
        var i = 0
        while (i < n) {
            val ch = code[i]
            // Line comments: //, --, or #
            if ((ch == '/' && i + 1 < n && code[i + 1] == '/') ||
                (ch == '-' && i + 1 < n && code[i + 1] == '-') ||
                ch == '#'
            ) {
                val start = i
                while (i < n && code[i] != '\n') i++
                addStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic), start, i)
                continue
            }
            // Block comments: /* ... */
            if (ch == '/' && i + 1 < n && code[i + 1] == '*') {
                val start = i
                i += 2
                while (i + 1 < n && !(code[i] == '*' && code[i + 1] == '/')) i++
                i = if (i + 1 < n) i + 2 else n
                addStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic), start, i)
                continue
            }
            // Strings: "...", '...', `...`
            if (ch == '"' || ch == '\'' || ch == '`') {
                val quote = ch
                val start = i
                i++
                while (i < n && code[i] != quote && code[i] != '\n') {
                    if (code[i] == '\\' && i + 1 < n) i += 2 else i++
                }
                if (i < n && code[i] == quote) i++
                addStyle(SpanStyle(color = stringColor), start, i)
                continue
            }
            // Annotations: @Foo
            if (ch == '@' && i + 1 < n && (code[i + 1].isLetter() || code[i + 1] == '_')) {
                val start = i
                i++
                while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                addStyle(SpanStyle(color = annotationColor), start, i)
                continue
            }
            // Numbers
            if (ch.isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit() && code[i - 1] != '_')) {
                val start = i
                while (i < n && (code[i].isLetterOrDigit() || code[i] == '.' || code[i] == '_')) i++
                addStyle(SpanStyle(color = numberColor), start, i)
                continue
            }
            // Identifiers / keywords
            if (ch.isLetter() || ch == '_') {
                val start = i
                while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                val word = code.substring(start, i)
                if (word in CODE_KEYWORDS) {
                    addStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.Medium), start, i)
                }
                continue
            }
            i++
        }
    }
    codeHighlightCache.put(cacheKey, result)
    return result
}

@Composable
fun CodeBlockCard(
    language: String,
    code: String,
    onCopy: () -> Unit
) {
    val lines = remember(code) { code.lines() }
    val isCollapsible = lines.size > 30
    var isExpanded by remember(code) { mutableStateOf(lines.size <= 60) }
    val displayCode = remember(code, isExpanded, isCollapsible) {
        if (!isCollapsible || isExpanded) code else lines.take(24).joinToString("\n")
    }
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val highlightedCode = remember(displayCode, isDark) {
        highlightCodeSyntax(displayCode, isDark)
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.ifBlank { "code" }.lowercase(),
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCollapsible) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { isExpanded = !isExpanded }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = if (isExpanded) "Collapse (${lines.size})" else "Expand (${lines.size})",
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onCopy)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.chat_copy_code),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.chat_btn_copy),
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = highlightedCode,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (isCollapsible && !isExpanded) {
                Text(
                    text = "... (${lines.size - 24} more lines · tap to expand)",
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isExpanded = true }
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

