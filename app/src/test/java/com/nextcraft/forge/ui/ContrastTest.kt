package com.nextcraft.forge.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG contrast of the real theme tokens: text >= 4.5:1 (1.4.3) and control borders >= 3:1 (1.4.11),
 * on every surface a control can sit on. Translucent glass surfaces are composited over each canvas colour.
 */
class ContrastTest {
    private val modes = listOf(
        "dark" to resolveThemeTokens("dark", systemDark = false),
        "glass" to resolveThemeTokens("glass", systemDark = false),
        "white" to resolveThemeTokens("white", systemDark = false),
        "system-light" to resolveThemeTokens("system", systemDark = false),
        "system-dark" to resolveThemeTokens("system", systemDark = true),
    )

    private fun ratio(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble() + 0.05
        val l2 = b.luminance().toDouble() + 0.05
        return if (l1 > l2) l1 / l2 else l2 / l1
    }

    /** name -> surface colour, one set per canvas colour of the theme. */
    private fun surfaces(isGlass: Boolean, scheme: androidx.compose.material3.ColorScheme, tokens: ForgeThemeTokens) =
        (if (isGlass) GlassCanvasColors else listOf(scheme.background)).flatMap { canvas ->
            listOf(
                "canvas" to canvas,
                "card" to tokens.cardContainerColor.compositeOver(canvas),
                "elevated" to tokens.cardElevatedColor.compositeOver(canvas),
                "surface" to scheme.surface.compositeOver(canvas),
            )
        }

    @Test
    fun text_meetsAA_onEverySurface() {
        val failures = mutableListOf<String>()
        modes.forEach { (name, pair) ->
            val (scheme, tokens) = pair
            val text = mapOf(
                "onSurface" to scheme.onSurface,
                "onSurfaceVariant" to scheme.onSurfaceVariant,
                "accentText" to tokens.accentText,
                "successText" to tokens.successText,
                "warningText" to tokens.warningText,
                "dangerText" to tokens.dangerText,
            )
            surfaces(tokens.isGlass, scheme, tokens).forEach { (surfaceName, surface) ->
                text.forEach { (textName, color) ->
                    val r = ratio(color, surface)
                    if (r < 4.5) failures += "$name: $textName on $surfaceName = ${"%.2f".format(r)}"
                }
            }
        }
        assertTrue("text below 4.5:1:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun controlBorder_meets3to1_onEverySurface() {
        val failures = mutableListOf<String>()
        modes.forEach { (name, pair) ->
            val (scheme, tokens) = pair
            val border = scheme.onSurfaceVariant.copy(alpha = controlBorderAlpha(tokens))
            surfaces(tokens.isGlass, scheme, tokens).forEach { (surfaceName, surface) ->
                val r = ratio(border.compositeOver(surface), surface)
                if (r < 3.0) failures += "$name: border on $surfaceName = ${"%.2f".format(r)}"
            }
        }
        assertTrue("control border below 3:1:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun filledButton_labelMeetsAA() {
        val failures = modes.mapNotNull { (name, pair) ->
            val scheme = pair.first
            ratio(scheme.onPrimary, scheme.primary).takeIf { it < 4.5 }?.let { "$name: onPrimary on primary = ${"%.2f".format(it)}" }
        }
        assertTrue("button label below 4.5:1:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
