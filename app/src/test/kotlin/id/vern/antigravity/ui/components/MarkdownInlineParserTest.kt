package id.vern.antigravity.ui.components

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownInlineParserTest {

    @Test
    fun parseInlineMarkdown_handlesScreenshotNestedCodeInsideBoldFollowedByItalic() {
        val input = "**Fix Config Sync & Hapus Deprecated `unsandboxed(*)` Grant (`fix(config)`)** *(dari `a439c82`, `261eafb`)*"
        val result = parseInlineMarkdown(input)

        assertEquals(
            "Fix Config Sync & Hapus Deprecated  unsandboxed(*)  Grant ( fix(config) ) (dari  a439c82 ,  261eafb )",
            result.text
        )

        val boldSpans = result.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertTrue(boldSpans.any { result.text.substring(it.start, it.end) == "Fix Config Sync & Hapus Deprecated  unsandboxed(*)  Grant ( fix(config) )" })

        val italicSpans = result.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
        assertTrue(italicSpans.any { result.text.substring(it.start, it.end) == "(dari  a439c82 ,  261eafb )" })

        val codeTexts = result.spanStyles
            .filter { it.item.fontFamily == FontFamily.Monospace }
            .map { result.text.substring(it.start, it.end) }
        assertEquals(
            listOf(" unsandboxed(*) ", " fix(config) ", " a439c82 ", " 261eafb "),
            codeTexts
        )
        assertFalse(result.text.contains('`'))
    }

    @Test
    fun parseInlineMarkdown_handlesBoldWithMultipleInlineCodes() {
        val input = "**Fix `LD_LIBRARY_PATH` & `agy-daemon` (`fix(daemon)`)**"
        val result = parseInlineMarkdown(input)

        assertEquals("Fix  LD_LIBRARY_PATH  &  agy-daemon  ( fix(daemon) )", result.text)
        assertFalse(result.text.contains('*'))
        assertFalse(result.text.contains('`'))

        val boldSpans = result.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, boldSpans.size)
        assertEquals(0, boldSpans[0].start)
        assertEquals(result.text.length, boldSpans[0].end)

        val codeTexts = result.spanStyles
            .filter { it.item.fontFamily == FontFamily.Monospace }
            .map { result.text.substring(it.start, it.end) }
        assertEquals(listOf(" LD_LIBRARY_PATH ", " agy-daemon ", " fix(daemon) "), codeTexts)
    }

    @Test
    fun parseInlineMarkdown_handlesNestedBoldAndItalicAndTripleEmphasis() {
        val nested = parseInlineMarkdown("**bold *and italic* end**")
        assertEquals("bold and italic end", nested.text)
        assertTrue(nested.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 0 && it.end == nested.text.length })
        assertTrue(nested.spanStyles.any { it.item.fontStyle == FontStyle.Italic && nested.text.substring(it.start, it.end) == "and italic" })

        val triple = parseInlineMarkdown("***bold italic***")
        assertEquals("bold italic", triple.text)
        assertTrue(
            triple.spanStyles.any {
                it.item.fontWeight == FontWeight.Bold &&
                    it.item.fontStyle == FontStyle.Italic &&
                    it.start == 0 &&
                    it.end == triple.text.length
            }
        )

        val tailNested = parseInlineMarkdown("**bold *italic***")
        assertEquals("bold italic", tailNested.text)
        assertTrue(tailNested.spanStyles.any { it.item.fontWeight == FontWeight.Bold && tailNested.text.substring(it.start, it.end) == "bold italic" })
        assertTrue(tailNested.spanStyles.any { it.item.fontStyle == FontStyle.Italic && tailNested.text.substring(it.start, it.end) == "italic" })
    }

    @Test
    fun parseInlineMarkdown_ignoresSpacedAsterisksAndIntrawordUnderscores() {
        val math = parseInlineMarkdown("a * b * c and 2 * 3 + 4 * 5")
        assertEquals("a * b * c and 2 * 3 + 4 * 5", math.text)
        assertTrue(math.spanStyles.none { it.item.fontStyle == FontStyle.Italic || it.item.fontWeight == FontWeight.Bold })

        val snake = parseInlineMarkdown("Check LD_LIBRARY_PATH and snake_case_var plus _standalone italic_")
        assertEquals("Check LD_LIBRARY_PATH and snake_case_var plus standalone italic", snake.text)
        val italicSpans = snake.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
        assertEquals(1, italicSpans.size)
        assertEquals("standalone italic", snake.text.substring(italicSpans[0].start, italicSpans[0].end))
    }

    @Test
    fun parseInlineMarkdown_handlesEscapesStrikethroughAndNestedLinks() {
        val escaped = parseInlineMarkdown("""\*not italic\* and \`not code\` and ~~deleted~~""")
        assertEquals("*not italic* and `not code` and deleted", escaped.text)
        assertTrue(escaped.spanStyles.none { it.item.fontStyle == FontStyle.Italic || it.item.fontFamily == FontFamily.Monospace })
        assertTrue(
            escaped.spanStyles.any {
                it.item.textDecoration == TextDecoration.LineThrough &&
                    escaped.text.substring(it.start, it.end) == "deleted"
            }
        )

        val linked = parseInlineMarkdown("[**`MarkdownText.kt`**](file:///test/MarkdownText.kt)")
        assertEquals(" MarkdownText.kt ", linked.text)
        val urlAnnotations = linked.getStringAnnotations("URL", 0, linked.length)
        assertEquals(1, urlAnnotations.size)
        assertEquals("file:///test/MarkdownText.kt", urlAnnotations[0].item)
        assertTrue(linked.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(linked.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun parseInlineMarkdown_streamingPartialPrefixesNeverThrowOrHang() {
        val sample = "**Fix `unsandboxed(*)` & [File `a_b`](file:///a/b)** *(dari `c1`, `c2`)* plus **nested *italic***"
        for (len in 1..sample.length) {
            val prefix = sample.substring(0, len)
            val parsed = parseInlineMarkdown(prefix)
            assertTrue(parsed.text.isNotEmpty() || prefix.isEmpty())
        }
    }

    @Test
    fun computeTableColumnWidthsDp_keepsSmallColumnsCompactAndCapsLongColumns() {
        val widths = computeTableColumnWidthsDp(
            headers = listOf("ID", "File", "Very Long Description Column That Should Be Capped"),
            rows = listOf(
                listOf("1", "[**`MarkdownText.kt`**](file:///data/home/repos/Antigravity/app/MarkdownText.kt)", "A".repeat(120))
            )
        )
        assertEquals(3, widths.size)
        assertTrue("Expected compact first column, got ${widths[0]}", widths[0] in 48f..71f)
        assertTrue("Expected link column measured by label not URL, got ${widths[1]}", widths[1] < 130f)
        assertEquals(168f, widths[2], 0.01f)
    }
}
