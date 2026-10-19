@file:OptIn(ExperimentalAnimationApi::class)

package com.nextcraft.forge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.with
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat

/**
 * One motion vocabulary for the whole app. Durations and springs live here so every screen moves the
 * same way, and every helper collapses to a snap when the user has disabled animations.
 */
object ForgeMotion {
    const val SCREEN_MS = 280
    const val FADE_MS = 180
    const val EXPAND_MS = 220

    /** Pill, send button, pressed scale: slight overshoot, settles fast. */
    fun <T> springSnappy(): androidx.compose.animation.core.SpringSpec<T> =
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium)

    /** Screen push: slide 24% + fade in, the leaving screen fades and drifts 12% the other way. */
    fun screenTransition(forward: Boolean, reduceMotion: Boolean): ContentTransform {
        if (reduceMotion) return fadeIn(snap()) with fadeOut(snap())
        val dir = if (forward) 1 else -1
        return (
            slideInHorizontally(tween(SCREEN_MS, easing = FastOutSlowInEasing)) { (it * 0.24f * dir).toInt() } +
                fadeIn(tween(SCREEN_MS))
            ) with (
            slideOutHorizontally(tween(SCREEN_MS, easing = FastOutSlowInEasing)) { (-it * 0.12f * dir).toInt() } +
                fadeOut(tween(FADE_MS))
            )
    }

    /** Tab switch: content cross-fades; nothing moves because the pill already did. */
    fun crossfade(reduceMotion: Boolean): ContentTransform =
        if (reduceMotion) fadeIn(snap()) with fadeOut(snap()) else fadeIn(tween(FADE_MS)) with fadeOut(tween(FADE_MS / 2))
}

/**
 * Hosts a screen keyed by [key]; a new key pushes (slide from the right) when [forward], pops otherwise,
 * and cross-fades when [slide] is false (sibling tabs).
 */
@Composable
fun <K> ForgeScreenHost(
    key: K,
    forward: Boolean,
    slide: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (K) -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    AnimatedContent(
        targetState = key,
        modifier = modifier,
        transitionSpec = { if (slide) ForgeMotion.screenTransition(forward, reduceMotion) else ForgeMotion.crossfade(reduceMotion) },
    ) { state -> content(state) }
}

/** Scales the element to 96% while pressed; pair it with the clickable that owns [interactionSource]. */
fun Modifier.forgePressScale(interactionSource: MutableInteractionSource, pressedScale: Float = 0.96f): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = rememberReduceMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (reduceMotion) snap() else ForgeMotion.springSnappy(),
        label = "pressScale",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Dialogs and menus scale in from 92% with a fade on first composition instead of popping. */
fun Modifier.forgePopIn(): Modifier = composed {
    val reduceMotion = rememberReduceMotion()
    var shown by remember { mutableStateOf(reduceMotion) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(ForgeMotion.EXPAND_MS, easing = FastOutSlowInEasing),
        label = "popIn",
    )
    graphicsLayer {
        alpha = progress
        scaleX = 0.92f + 0.08f * progress
        scaleY = 0.92f + 0.08f * progress
    }
}

/** 0..1 blink clock for the streaming caret; constant 1 under reduce-motion. */
@Composable
fun rememberBlink(): Float {
    val reduceMotion = rememberReduceMotion()
    if (reduceMotion) return 1f
    val transition = rememberInfiniteTransition()
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
    )
    return if (t < 0.5f) 1f else 0.15f
}
