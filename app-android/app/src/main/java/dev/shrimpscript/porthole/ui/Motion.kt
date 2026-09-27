@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package dev.shrimpscript.porthole.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeMotion
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType
import dev.shrimpscript.porthole.ui.theme.motionEnabled
import kotlin.math.roundToInt

/*
 * Motion primitives. Every animation in the app is one of these four, all on the
 * tokens in PortholeMotion and all inert when the person has animations off:
 *
 *  - Appear:          something arrives (fade + a 12dp settle upward)
 *  - RouteTransition: moving between screens (fade-through with a short slide)
 *  - pressScale:      the tap was heard (0.97 scale while pressed)
 *  - SegmentedToggle: the indicator glides between two options
 */

/** Fade + settle in, once, when this first enters composition. */
@Composable
fun Appear(
    modifier: Modifier = Modifier,
    delayMs: Int = 0,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val on = motionEnabled() && enabled
    val progress = remember { Animatable(if (on) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (on) progress.animateTo(1f, tween(PortholeMotion.ENTER_MS, delayMs, PortholeMotion.settle))
    }
    val travel = with(LocalDensity.current) { PortholeMotion.TRAVEL_DP.dp.toPx() }
    Box(
        modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * travel
        }
    ) { content() }
}

/**
 * Screen-to-screen. Forward slides the new screen in from the right by a tenth of its
 * width while the old one fades; back mirrors it. Fade-through, not a full push: the
 * ground never moves, which is what keeps the app feeling like one room.
 */
/**
 * Shared elements between routes. The layout is provided once, above the router; each
 * route's content runs inside the AnimatedContent, whose scope is provided here so a
 * composable can declare "this ring is that ring". Off when animations are off.
 */
val LocalSharedTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalRouteVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** The same object on both screens: it moves, it does not swap. AXIS tier, no overshoot. */
@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.sharedAcrossRoutes(key: String, bounds: Boolean = false): Modifier = composed {
    val st = LocalSharedTransition.current
    val av = LocalRouteVisibility.current
    if (st == null || av == null || !motionEnabled()) return@composed this
    val spec: BoundsTransform = BoundsTransform { _, _ -> tween(PortholeMotion.AXIS_MS, easing = PortholeMotion.standard) }
    with(st) {
        if (bounds) this@composed.sharedBounds(rememberSharedContentState(key), av, boundsTransform = spec)
        else this@composed.sharedElement(rememberSharedContentState(key), av, boundsTransform = spec)
    }
}

@Composable
fun <T> RouteTransition(
    target: T,
    forward: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val on = motionEnabled()
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            if (!on) {
                ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
            } else {
                val dir = if (forward) 1 else -1
                (fadeIn(tween(PortholeMotion.AXIS_MS, easing = PortholeMotion.settle)) +
                    slideInHorizontally(tween(PortholeMotion.AXIS_MS, easing = PortholeMotion.settle)) { dir * it / 10 })
                    .togetherWith(
                        fadeOut(tween(PortholeMotion.EXIT_MS, easing = PortholeMotion.leave)) +
                            slideOutHorizontally(tween(PortholeMotion.EXIT_MS, easing = PortholeMotion.leave)) { -dir * it / 14 }
                    ).also { it.targetContentZIndex = 1f }
            }
        },
        label = "route",
    ) { CompositionLocalProvider(LocalRouteVisibility provides this) { content(it) } }
}

/** Shrinks to 0.97 while pressed. Feedback that the tap was heard; nothing more. */
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val on = motionEnabled()
    val scale by animateFloatAsState(
        targetValue = if (pressed && on) 0.97f else 1f,
        animationSpec = tween(PortholeMotion.FAST_MS, easing = PortholeMotion.standard),
        label = "press",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Two options, one gliding indicator. Used for Feed <-> Terminal in the session header.
 * The indicator is the moving part; the labels stay put so the eye has an anchor.
 */
@Composable
fun SegmentedToggle(
    options: Pair<String, String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Porthole.colors
    val on = motionEnabled()
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(c.surface, PortholeShape.control)
            .padding(3.dp)
    ) {
        val half = maxWidth / 2
        val x by animateFloatAsState(
            targetValue = if (selected == 0) 0f else 1f,
            animationSpec = if (on) tween(PortholeMotion.AXIS_MS, easing = PortholeMotion.settle) else tween(0),
            label = "segment",
        )
        Box(
            Modifier
                .offset { IntOffset((half.toPx() * x).roundToInt(), 0) }
                .width(half)
                .height(34.dp)
                .background(c.raised, PortholeShape.key)
        )
        Row(Modifier.fillMaxWidth()) {
            listOf(options.first, options.second).forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(34.dp)
                        .semantics { role = Role.Tab; this.selected = i == selected }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, style = PortholeType.secondary,
                        color = if (i == selected) c.text else c.muted,
                    )
                }
            }
        }
    }
}
