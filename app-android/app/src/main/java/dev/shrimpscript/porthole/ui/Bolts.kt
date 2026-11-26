package dev.shrimpscript.porthole.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeMotion
import dev.shrimpscript.porthole.ui.theme.motionEnabled
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

/*
 * The working sign (DESIGN v3 §7c): the porthole's eight bolts, moving. Zeke's pick from the
 * motion board (2026-11-26): two choreographies, each running through four movements so it
 * never reads as one loop - Orbit while a tool runs (busy, mechanical), Tide while Claude
 * thinks (slower and rounder). With animations off it is the still ring of bolts, one lit.
 */

enum class BoltsMode { Running, Thinking }

/** One bolt at one moment: its turn round the ring (degrees), its distance as a share of the ring's, its light. */
private class Bolt(val a: Float, val r: Float, val b: Float)

private object Choreo {
    const val N = 8
    /** Each movement lasts this long at pace 1; Thinking runs at [THINK_PACE], so slower. */
    const val BEAT_S = 1.3f
    const val THINK_PACE = 0.62f
    /** Movements blend into the next over the last share of each beat. */
    const val BLEND = 0.16f

    fun smooth(x: Float) = x * x * (3 - 2 * x)
    fun ease(x: Float) = if (x < .5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2
    fun clamp(x: Float, a: Float = 0f, b: Float = 1f) = x.coerceIn(a, b)
    fun mod(a: Float, n: Float) = ((a % n) + n) % n
    /** How lit bolt [i] is in a light's tail that is at [pos] on the ring and [len] bolts long. */
    fun tail(pos: Float, i: Int, len: Float): Float { val d = mod(pos - i, N.toFloat()); return if (d < len) 1 - d / len else 0f }
    /** A fixed flicker per bolt, the same every lap. */
    fun rnd(i: Int, k: Int): Float { val x = sin(i * 12.9898f + k * 78.233f) * 43758.5453f; return x - floor(x) }

    val orbit: List<(Float, Int) -> Bolt> = listOf(
        // a light chases round, a soft tail behind it
        { u, i -> Bolt(0f, 1f, maxOf(.2f, tail(u * 8 * 1.2f, i, 3.6f))) },
        // two lights opposite each other, quicker, the ring turning an eighth
        { u, i -> Bolt(45f * smooth(u), 1f, maxOf(.2f, tail(u * 8 * 1.6f, i, 2.6f), tail(u * 8 * 1.6f + 4, i, 2.6f))) },
        // the ring winds a full turn, drawing in as it goes and lighting all at once
        { u, i -> val w = sin(PI.toFloat() * u); Bolt(45f + 360f * ease(u), 1 - .42f * w * (.8f + .2f * sin(i * 1.7f)), .35f + .65f * w) },
        // back out to the rim, the light gathering into one again
        { u, i -> Bolt(405f + 25f * smooth(u), 1f, maxOf(.2f, (1 - u) * .7f, tail(u * 8, i, 3.6f) * u)) },
    )

    val tide: List<(Float, Int) -> Bolt> = listOf(
        { u, i -> Bolt(0f, 1f, maxOf(.2f, tail(u * 8 * 1.2f, i, 3.6f))) },
        // a wave of light and depth goes round twice; each bolt breathes in turn
        { u, i -> val ph = sin(2 * PI.toFloat() * (u * 2 - i / N.toFloat())); Bolt(0f, 1 - .12f * (ph + 1) / 2, .3f + .7f * maxOf(0f, ph)) },
        // one by one the bolts fall in to the centre, then all spring back out bright
        { u, i ->
            val st = i / N.toFloat() * .45f; val x = clamp((u - st) / .35f); val back = clamp((u - .75f) / .25f)
            Bolt(22.5f * smooth(u), 1 - .82f * smooth(x) * (1 - smooth(back)), .3f + .7f * maxOf(smooth(x) * (1 - back), back))
        },
        // the bolts twinkle like light on water, settling to the chase's start
        { u, i ->
            val settle = smooth(clamp((u - .8f) / .2f))
            Bolt(22.5f, 1f, .22f + .78f * (.5f + .5f * sin(2 * PI.toFloat() * (u * 3 + rnd(i, 1)))).pow(3) * (1 - settle) + tail(0f, i, 3.6f) * settle)
        },
    )

    fun at(t: Float, moves: List<(Float, Int) -> Bolt>, pace: Float): Array<Bolt> {
        val beat = BEAT_S / pace
        val m = moves.size
        val tc = mod(t, beat * m)
        val k = (tc / beat).toInt().coerceIn(0, m - 1)
        val u = (tc - k * beat) / beat
        return Array(N) { i ->
            val s = moves[k](u, i)
            if (u <= 1 - BLEND) s else {
                val n = moves[(k + 1) % m](0f, i)
                val w = smooth((u - (1 - BLEND)) / BLEND)
                // the last movement hands over to the first a whole number of turns on
                val sa = if (k == m - 1) s.a - round((s.a - n.a) / 360f) * 360f else s.a
                Bolt(sa + (n.a - sa) * w, s.r + (n.r - s.r) * w, s.b + (n.b - s.b) * w)
            }
        }
    }
}

/**
 * The bolts, at work. [label] is what a screen reader hears; none by default, as the words
 * beside the bolts (or the control holding them) already say what is under way.
 */
@Composable
fun Bolts(
    mode: BoltsMode = BoltsMode.Running,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    color: Color = Porthole.colors.text,
    label: String? = null,
) {
    val on = motionEnabled()
    val pace = if (mode == BoltsMode.Thinking) Choreo.THINK_PACE else 1f
    val cycleS = Choreo.BEAT_S / pace * 4
    val t = if (on) {
        val tr = rememberInfiniteTransition(label = "bolts")
        tr.animateFloat(
            0f, cycleS,
            infiniteRepeatable(tween((cycleS * 1000).toInt(), easing = LinearEasing)),
            label = "time",
        ).value
    } else 0f
    val moves = if (mode == BoltsMode.Thinking) Choreo.tide else Choreo.orbit
    val described = if (label != null) Modifier.semantics { contentDescription = label } else Modifier
    Canvas(modifier.size(size).then(described)) { drawBolts(Choreo.at(t, moves, pace), color) }
}

/** The bolts at rest, for a finished turn ("Worked for 41s"): the ring, quiet. */
@Composable
fun BoltsStill(modifier: Modifier = Modifier, size: Dp = 14.dp, color: Color = Porthole.colors.faint) {
    Canvas(modifier.size(size)) { drawBolts(Array(Choreo.N) { Bolt(0f, 1f, .55f) }, color) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBolts(bolts: Array<Bolt>, color: Color) {
    // the 20-unit square the clips were drawn in: ring radius 7, bolts 1.25 to 1.8
    val u = size.minDimension / 20f
    val c = Offset(size.width / 2f, size.height / 2f)
    bolts.forEachIndexed { i, s ->
        val th = Math.toRadians((i / Choreo.N.toDouble() * 360 - 90 + s.a))
        val r = 7f * s.r * u
        drawCircle(
            color.copy(alpha = color.alpha * Choreo.clamp(s.b)),
            radius = (1.25f + .55f * s.b) * u,
            center = Offset(c.x + r * cos(th).toFloat(), c.y + r * sin(th).toFloat()),
        )
    }
}

/**
 * Words that say something is happening ("Compiling…", "Thinking…"): a band of light crosses
 * them every two seconds; the words themselves never move. Still with animations off.
 */
@Composable
fun ShimmerText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Porthole.colors.text,
    maxLines: Int = 1,
) {
    if (!motionEnabled()) {
        Text(text, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
        return
    }
    var w by remember { mutableFloatStateOf(0f) }
    val p by rememberInfiniteTransition(label = "shimmer").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "band",
    )
    val x = -0.3f * w + p * 1.6f * w
    val dim = color.copy(alpha = color.alpha * 0.55f)
    val brush = if (w > 0f) Brush.linearGradient(
        0f to dim, 0.5f to color, 1f to dim,
        start = Offset(x - 0.3f * w, 0f), end = Offset(x + 0.3f * w, 0f),
    ) else Brush.linearGradient(listOf(dim, dim))
    Text(
        text, style = style.copy(brush = brush), maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        modifier = modifier.onSizeChanged { w = it.width.toFloat() },
    )
}

/**
 * A number that changes in place ("0:47"): each changed digit slides up into its place, on
 * the enter curve; the others stay put. Figures are tabular so nothing shifts sideways.
 */
@Composable
fun RollingText(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Porthole.colors.text) {
    val st = style.copy(fontFeatureSettings = "tnum")
    if (!motionEnabled()) {
        Text(text, style = st, color = color, maxLines = 1, modifier = modifier)
        return
    }
    Row(modifier.clearAndSetSemantics { contentDescription = text }) {
        text.forEachIndexed { i, ch ->
            key(i) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        if (initialState.isDigit() && targetState.isDigit() && initialState != targetState)
                            (slideInVertically(tween(PortholeMotion.ENTER_MS + 60, easing = PortholeMotion.settle)) { it } + fadeIn(tween(PortholeMotion.ENTER_MS))) togetherWith
                                (slideOutVertically(tween(PortholeMotion.EXIT_MS + 60, easing = PortholeMotion.leave)) { -it } + fadeOut(tween(PortholeMotion.EXIT_MS)))
                        else EnterTransition.None togetherWith ExitTransition.None
                    },
                    label = "digit",
                ) { c -> Text(c.toString(), style = st, color = color, maxLines = 1) }
            }
        }
    }
}
