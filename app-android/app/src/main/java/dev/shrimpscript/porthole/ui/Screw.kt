package dev.shrimpscript.porthole.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.motionEnabled
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * The working icon: the engine room's screw, seen three-quarter on. Three swept blades, each
 * pitched (its leading edge raised, its trailing edge lowered), turn inside the faint disc their
 * tips sweep. A blade brightens as its pitch turns it square to the viewer and dims edge-on, and
 * two fading copies trail each one. Depth is drawn as colour - the accent mixed toward the ground
 * behind it - never as transparency, so overlapping shapes don't double up. With motion off it
 * is the still screw.
 *
 * The geometry lives in a unit disc: x right, y down, z toward the viewer, and a mild
 * perspective (F) makes the near side a little larger.
 */
private object ScrewShape {
    const val F = 5f
    const val TURN_MS = 1180            // 0.85 turns a second
    const val N = 12                    // stations along a blade, hub to tip
    const val POINTS = 2 * (N + 1)
    /** How far each edge stands off the disc: enough to turn the light, never enough to go edge-on. */
    const val PITCH = 0.35f

    /** One blade's outline at angle 0: leading edge hub to tip, then trailing edge back. */
    val bx = FloatArray(POINTS); val by = FloatArray(POINTS); val bz = FloatArray(POINTS)

    /** Its area as drawn flat, for how squarely a turned blade faces us. */
    val flatArea: Float

    /** The disc turned three-quarter on: 0.62 rad about the vertical, then 0.32 toward us. */
    val m = FloatArray(9)

    init {
        val r0 = 0.2f
        for (i in 0..N) {
            val s = i / N.toFloat()
            val r = r0 + (1 - r0) * s
            val hw = 0.3f * sin(PI.toFloat() * s.pow(0.8f)) * (1 - 0.22f * s)
            val mid = 0.42f * s * s                          // swept back toward the tip
            val off = hw / max(r, 0.25f)
            // leading edge: stations in order; trailing edge: the same stations, reversed
            bx[i] = r * cos(mid + off); by[i] = r * sin(mid + off); bz[i] = PITCH * hw
            val j = POINTS - 1 - i
            bx[j] = r * cos(mid - off); by[j] = r * sin(mid - off); bz[j] = -PITCH * hw
        }
        flatArea = area(bx, by, POINTS)
        val (cy, sy) = cos(0.62f) to sin(0.62f)
        val (cx, sx) = cos(0.32f) to sin(0.32f)
        // Rx(0.32) * Ry(0.62)
        m[0] = cy;          m[1] = 0f;  m[2] = sy
        m[3] = sx * sy;     m[4] = cx;  m[5] = -sx * cy
        m[6] = -cx * sy;    m[7] = sx;  m[8] = cx * cy
    }

    fun area(x: FloatArray, y: FloatArray, n: Int): Float {
        var a = 0f
        for (i in 0 until n) {
            val k = (i + 1) % n
            a += x[i] * y[k] - x[k] * y[i]
        }
        return abs(a) / 2f
    }
}

/** Blade copies drawn per blade: how far behind the blade (radians) and how strongly. */
private val TRAILS = floatArrayOf(0.26f, 0.2f, 0.13f, 0.38f, 0f, 1f)
private val STILL = floatArrayOf(0f, 1f)

/**
 * Draws the screw into a square of side [side], turned to [spin] radians. [ink] is the accent;
 * [ground] is what is behind the icon, for mixing the far and edge-on parts toward it.
 */
private class ScrewDrawer {
    private val paths = List(9) { Path() }
    private val disc = Path()
    private val hub = Path()
    private val sx = FloatArray(ScrewShape.POINTS)
    private val sy = FloatArray(ScrewShape.POINTS)
    private val depthOf = FloatArray(9)
    private val alphaOf = FloatArray(9)
    private val order = IntArray(9)

    private fun project(x: Float, y: Float, z: Float, c: Float, r: Float, out: FloatArray, at: Int) {
        val m = ScrewShape.m
        val X = m[0] * x + m[1] * y + m[2] * z
        val Y = m[3] * x + m[4] * y + m[5] * z
        val Z = m[6] * x + m[7] * y + m[8] * z
        val k = ScrewShape.F / (ScrewShape.F - Z)
        out[at] = c + X * r * k; out[at + 1] = c + Y * r * k; out[at + 2] = Z
    }

    fun DrawScope.drawScrew(spin: Float, trails: Boolean, ink: Color, ground: Color, line: Float) {
        val side = size.minDimension
        val c = side / 2f
        val r = side * 0.44f
        val p = FloatArray(3)

        // The disc the tips sweep: a faint ellipse.
        disc.reset()
        for (i in 0..48) {
            val a = i / 48f * 2f * PI.toFloat()
            project(cos(a), sin(a), 0f, c, r, p, 0)
            if (i == 0) disc.moveTo(p[0], p[1]) else disc.lineTo(p[0], p[1])
        }
        drawPath(disc, lerp(ground, ink, 0.28f), style = Stroke(width = line * 0.45f))

        // Three blades, far ones first; each blade's trails are painted before it, so a trail
        // never covers its own blade.
        val copies = if (trails) TRAILS else STILL
        val per = copies.size / 2
        var n = 0
        for (k in 0 until 3) {
            for (t in copies.indices step 2) {
                val theta = spin - copies[t] + k * 2f * PI.toFloat() / 3f
                val ct = cos(theta); val st = sin(theta)
                var zs = 0f
                val path = paths[n]
                path.reset()
                for (i in 0 until ScrewShape.POINTS) {
                    val x = ScrewShape.bx[i] * ct - ScrewShape.by[i] * st
                    val y = ScrewShape.bx[i] * st + ScrewShape.by[i] * ct
                    project(x, y, ScrewShape.bz[i], c, r, p, 0)
                    sx[i] = p[0]; sy[i] = p[1]; zs += p[2]
                    if (i == 0) path.moveTo(p[0], p[1]) else path.lineTo(p[0], p[1])
                }
                path.close()
                val z = zs / ScrewShape.POINTS
                val facing = min(1f, ScrewShape.area(sx, sy, ScrewShape.POINTS) / (ScrewShape.flatArea * r * r * 0.82f))
                val depth = ((z + 1f) / 2f).coerceIn(0f, 1f)
                depthOf[n] = z
                // on a dark ground the dimmest part of a blade must still read as teal
                alphaOf[n] = copies[t + 1] * (0.5f + 0.5f * (0.6f * facing + 0.4f * depth))
                n++
            }
        }
        // the blades in depth order by their own (last, full-strength) copy, far first
        for (k in 0 until 3) order[k] = k
        for (i in 1 until 3) {
            val v = order[i]; var j = i - 1
            while (j >= 0 && depthOf[order[j] * per + per - 1] > depthOf[v * per + per - 1]) { order[j + 1] = order[j]; j-- }
            order[j + 1] = v
        }
        for (i in 0 until 3) {
            val k = order[i]
            for (q in 0 until per) {
                val b = k * per + q
                drawPath(paths[b], lerp(ground, ink, alphaOf[b].coerceIn(0f, 1f)))
            }
        }

        // The hub, standing a little proud of the disc.
        hub.reset()
        for (i in 0..24) {
            val a = i / 24f * 2f * PI.toFloat()
            project(0.2f * cos(a), 0.2f * sin(a), 0.06f, c, r, p, 0)
            if (i == 0) hub.moveTo(p[0], p[1]) else hub.lineTo(p[0], p[1])
        }
        hub.close()
        drawPath(hub, ink)
    }
}

/**
 * The working icon: the screw, turning. [background] is the colour behind it (the feed and the
 * list sit on the ground; cards on the surface), which its far and edge-on parts fade toward.
 */
@Composable
fun Spinner(
    modifier: Modifier = Modifier,
    color: Color = Porthole.colors.accent,
    background: Color = Porthole.colors.ground,
    size: Dp = 16.dp,
) {
    val on = motionEnabled()
    val turn = if (on) {
        rememberInfiniteTransition(label = "screw").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(ScrewShape.TURN_MS, easing = LinearEasing)),
            label = "turn",
        ).value
    } else 0f
    ScrewAt(turn * 2f * PI.toFloat(), trails = on, modifier = modifier, color = color, background = background, size = size)
}

/** The screw at rest, for a finished turn ("Worked for 41s"). */
@Composable
fun ScrewMark(
    modifier: Modifier = Modifier,
    color: Color = Porthole.colors.faint,
    background: Color = Porthole.colors.ground,
) = ScrewAt(0f, trails = false, modifier = modifier, color = color, background = background, size = 14.dp)

/** The screw turned [spin] radians past its rest pose: one frame of [Spinner], or its rest. */
@Composable
internal fun ScrewAt(
    spin: Float,
    trails: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Porthole.colors.accent,
    background: Color = Porthole.colors.ground,
    size: Dp = 16.dp,
) {
    val drawer = remember { ScrewDrawer() }
    Canvas(modifier.size(size)) {
        // line weight grows with the icon up to 32dp, then stops: big views stay drawn, not heavy
        val line = min(this.size.minDimension, 32.dp.toPx()) * 0.1f
        with(drawer) { drawScrew(0.3f + spin, trails = trails, ink = color, ground = background, line = line) }
    }
}
