package id.vern.antigravity.ui.theme

import androidx.compose.ui.graphics.Color
import id.vern.antigravity.data.AccentColor
import id.vern.antigravity.viewmodel.CustomizationCatalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class ThemeAndCatalogueTest {

    private fun linearizeChannel(channel: Float): Double {
        val c = channel.toDouble().coerceIn(0.0, 1.0)
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun relativeLuminance(color: Color): Double {
        return 0.2126 * linearizeChannel(color.red) +
            0.7152 * linearizeChannel(color.green) +
            0.0722 * linearizeChannel(color.blue)
    }

    private fun contrastRatio(fg: Color, bg: Color): Double {
        val l1 = relativeLuminance(fg)
        val l2 = relativeLuminance(bg)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun `gemini frontend design skill is present in default catalogue`() {
        val catalogue = CustomizationCatalogue.getDefaultSkillsCatalogue()
        val item = catalogue.find { it.id == "gemini-frontend-design" }
        assertNotNull("Expected gemini-frontend-design in default skills catalogue", item)
        assertEquals("https://github.com/google-gemini/demolab.git", item!!.repoUrl)
        assertEquals("skills/gemini-frontend-design", item.subPath)
        assertEquals("UI & Visual", item.category)
    }

    @Test
    fun `graphite studio dark and gallery light tokens satisfy wcag aa contrast`() {
        val darkScheme = buildColorScheme(
            darkTheme = true,
            accent = AccentColor.DEFAULT,
            themePresetName = "Default Dark"
        )
        val lightScheme = buildColorScheme(
            darkTheme = false,
            accent = AccentColor.DEFAULT,
            themePresetName = "Default Light"
        )

        assertEquals(DarkBackground, darkScheme.background)
        assertEquals(LightBackground, lightScheme.background)

        val variants = listOf(true to "Default Dark", false to "Default Light")
        for ((isDark, presetName) in variants) {
            for (accent in AccentColor.entries) {
                val scheme = buildColorScheme(
                    darkTheme = isDark,
                    accent = accent,
                    themePresetName = presetName
                )
                val pairs = listOf(
                    "onBackground/background" to (scheme.onBackground to scheme.background),
                    "onSurface/surface" to (scheme.onSurface to scheme.surface),
                    "onSurfaceVariant/background" to (scheme.onSurfaceVariant to scheme.background),
                    "onPrimary/primary" to (scheme.onPrimary to scheme.primary),
                    "onError/error" to (scheme.onError to scheme.error)
                )
                for ((label, colors) in pairs) {
                    val ratio = contrastRatio(colors.first, colors.second)
                    assertTrue(
                        "$presetName ($accent) $label contrast $ratio < 4.5",
                        ratio >= 4.5
                    )
                }
            }
        }
    }
}
