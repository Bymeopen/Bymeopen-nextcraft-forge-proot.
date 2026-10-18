package com.nextcraft.forge.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG relative luminance / contrast, computed from sRGB floats so the test needs no Android classes. */
private fun luminance(c: Color): Double {
    fun lin(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
}

private fun contrast(a: Color, b: Color): Double {
    val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
    return (hi + 0.05) / (lo + 0.05)
}

class ThemeAndNavTest {
    private val scheme = ZuanDarkColorScheme

    private fun assertText(name: String, fg: Color, bg: Color, min: Double = 4.5) {
        val ratio = contrast(fg, bg)
        assertTrue("$name contrast $ratio < $min", ratio >= min)
    }

    @Test
    fun linearDark_textPairs_meetAA() {
        assertText("onBackground/background", scheme.onBackground, scheme.background)
        assertText("onSurface/surface", scheme.onSurface, scheme.surface)
        assertText("onSurfaceVariant/background", scheme.onSurfaceVariant, scheme.background)
        assertText("onSurfaceVariant/surface", scheme.onSurfaceVariant, scheme.surface)
        assertText("onPrimary/primary", scheme.onPrimary, scheme.primary)
        assertText("onPrimaryContainer/primaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer)
        assertText("onError/error", scheme.onError, scheme.error, min = 3.0) // large/bold UI text only
        assertText("error/surface", scheme.error, scheme.surface)
    }

    @Test
    fun linearDark_neutralGreys_haveNoColourCast() {
        listOf(scheme.background, scheme.surface, scheme.surfaceVariant, scheme.onSurfaceVariant).forEach {
            assertEquals(it.red, it.green, 0.001f)
            assertEquals(it.green, it.blue, 0.001f)
        }
    }

    @Test
    fun backTarget_toolScreensReturnToTools() {
        listOf(ForgeTab.FILES, ForgeTab.TERMINAL, ForgeTab.ANDROID, ForgeTab.PREVIEW).forEach {
            assertEquals(ForgeTab.TOOLS, forgeBackTarget(it))
        }
    }

    @Test
    fun backTarget_topLevelTabsReturnToProjects_andProjectsExits() {
        listOf(ForgeTab.AGENT, ForgeTab.TOOLS, ForgeTab.SETTINGS).forEach {
            assertEquals(ForgeTab.PROJECTS, forgeBackTarget(it))
        }
        assertNull(forgeBackTarget(ForgeTab.PROJECTS))
    }

    @Test
    fun backTarget_neverLoops() {
        ForgeTab.entries.forEach { start ->
            var tab: ForgeTab? = start
            var steps = 0
            while (tab != null && steps < ForgeTab.entries.size) {
                tab = forgeBackTarget(tab)
                steps++
            }
            assertNull("Back from $start never reaches exit", tab)
        }
    }
}
