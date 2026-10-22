package com.nextcraft.forge.ui

import androidx.compose.material3.MaterialTheme
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * Liquid-glass material for the Glass theme, built on Kyant0's Backdrop library.
 *
 * Screens draw into one [LayerBackdrop] ([forgeGlassSource]); floating chrome (the bottom bar) re-draws that layer
 * blurred and refracted behind itself ([forgeGlassSurface]). Backdrop effects need RenderEffect (Android 12+), the
 * lens needs AGSL RuntimeShader (Android 13+). Below that, and in every non-Glass theme, the plain translucent
 * surface stays in use, so nothing here changes how Dark and White look.
 */
internal val LocalForgeBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

internal val glassBlurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
internal val glassLensSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** Records the content behind floating chrome. Apply to the container that holds the screens, not the chrome. */
@Composable
internal fun Modifier.forgeGlassSource(backdrop: LayerBackdrop?): Modifier =
    if (backdrop == null) this else layerBackdrop(backdrop)

@Composable
internal fun rememberForgeBackdrop(enabled: Boolean): LayerBackdrop? {
    val backdrop = rememberLayerBackdrop()
    return if (enabled && glassBlurSupported) backdrop else null
}

/**
 * Glass fill for a capsule: blur + vibrancy everywhere it is supported, plus the refracting lens on Android 13+.
 * [tint] is the theme's translucent container colour, drawn over the blurred backdrop.
 */
@Composable
internal fun Modifier.forgeGlassSurface(backdrop: LayerBackdrop?, tint: Color): Modifier {
    if (backdrop == null) return this
    val lens = glassLensSupported
    return drawBackdrop(
        backdrop = backdrop,
        shape = { RoundedCornerShape(percent = 50) },
        effects = {
            vibrancy()
            blur(10.dp.toPx())
            if (lens) lens(refractionHeight = 14.dp.toPx(), refractionAmount = 18.dp.toPx())
        },
        onDrawSurface = {
            drawRect(tint)
            // Soft top sheen so the bar reads as glass rather than a flat tinted pane.
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent)))
        },
    )
}

/**
 * Container colour for modal bottom sheets. A sheet covers the screen, so unlike a card it must never let the content
 * behind it show through: the Glass card colour is translucent by design, which made sheets unreadable there. Glass
 * uses [GlassFloatingSurface], opaque and darker than the page; the other themes keep the card colour.
 */
@Composable
internal fun forgeSheetColor(): Color {
    val tokens = LocalForgeThemeTokens.current
    return if (tokens.isGlass) GlassFloatingSurface else tokens.cardContainerColor
}
