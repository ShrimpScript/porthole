package dev.shrimpscript.porthole.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.em
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.cos
import kotlin.math.sin
import dev.shrimpscript.porthole.R
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType
import dev.shrimpscript.porthole.ui.theme.LocalPortholeFonts
import dev.shrimpscript.porthole.ui.theme.PortholeFonts
import dev.shrimpscript.porthole.ui.theme.PortholeLogoFace

/*
 * The brand, as components (DESIGN v3 §3).
 *
 * The mark is a porthole: a bolted rim round a pane of dark glass with a white edge and a
 * diagonal reflection. It is the app icon, the splash, and the "o" of the wordmark. Tool
 * marks name the things a person has to install (Tailscale, Claude Code, tmux) with their
 * own icons, tinted into our palette so they read as references, not as our brand.
 */

/**
 * The porthole. [light] draws the rim in white with dark bolts - the version that stands in
 * for a letter, where a dark rim would vanish into the ground and the "o" would read small.
 */
@Composable
fun PortholeMark(modifier: Modifier = Modifier, size: Dp = 64.dp, light: Boolean = false) {
    Canvas(modifier.size(size).semantics { contentDescription = "Porthole" }) { drawPorthole(light) }
}

/** The porthole on a 100-unit square, scaled to this draw scope. */
fun DrawScope.drawPorthole(light: Boolean = false) {
    val u = size.minDimension / 100f
    val c = Offset(size.width / 2f, size.height / 2f)
    fun p(x: Float, y: Float) = Offset(c.x + (x - 50f) * u, c.y + (y - 50f) * u)
    // The rim.
    val rim = if (light) Brush.linearGradient(
        0f to Color(0xFFFFFFFF), 0.5f to Color(0xFFECECEF), 1f to Color(0xFFC9C9CF),
        start = p(3f, 3f), end = p(97f, 97f),
    ) else Brush.linearGradient(
        0f to Color(0xFF6A6A72), 0.35f to Color(0xFF2C2C31), 0.75f to Color(0xFF18181B), 1f to Color(0xFF2A2A2F),
        start = p(3f, 3f), end = p(97f, 97f),
    )
    drawCircle(rim, radius = 47f * u, center = c)
    if (!light) {
        drawCircle(
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.85f), 0.4f to Color.White.copy(alpha = 0.08f),
                0.75f to Color.Transparent, 1f to Color.White.copy(alpha = 0.30f),
                start = p(3f, 3f), end = p(97f, 97f),
            ),
            radius = 46.4f * u, center = c, style = Stroke(1.2f * u),
        )
    }
    // Eight bolts on the rim.
    for (i in 0 until 8) {
        val t = Math.toRadians(i * 45.0 + 22.5)
        val b = Offset(c.x + 40f * u * cos(t).toFloat(), c.y + 40f * u * sin(t).toFloat())
        if (light) {
            drawCircle(Color(0xFF0B0B0C).copy(alpha = 0.85f), radius = 2.6f * u, center = b)
        } else {
            drawCircle(Color(0xFF050506).copy(alpha = 0.7f), radius = 2.5f * u, center = b + Offset(0f, 0.5f * u))
            drawCircle(
                Brush.radialGradient(
                    0f to Color(0xFFD9D9DE), 0.5f to Color(0xFF77777F), 1f to Color(0xFF2C2C31),
                    center = b + Offset(-0.8f * u, -1f * u), radius = 3.4f * u,
                ),
                radius = 2.3f * u, center = b,
            )
        }
    }
    // The seat the glass sits in.
    drawCircle(Color(0xFF050506), radius = 33.6f * u, center = c)
    drawCircle(Color.White.copy(alpha = 0.10f), radius = 33.6f * u, center = c, style = Stroke(0.8f * u))
    // The glass: the room's dark behind it, the rim's shadow across its top, one reflection.
    val glass = Path().apply { addOval(Rect(center = c, radius = 32f * u)) }
    clipPath(glass) {
        drawRect(
            Brush.radialGradient(
                0f to Color(0xFF3A4049), 0.5f to Color(0xFF1D2127), 1f to Color(0xFF0D0F12),
                center = p(50f, 18f), radius = 78f * u,
            ),
        )
        drawRect(
            Brush.radialGradient(0f to Color.Black.copy(alpha = 0.55f), 0.55f to Color.Transparent, center = p(50f, 8f), radius = 75f * u),
        )
        rotate(-42f, pivot = c) {
            drawRect(Color.White.copy(alpha = 0.55f), topLeft = p(16f, 22f), size = Size(68f * u, 9f * u))
            drawRect(Color.White.copy(alpha = 0.38f), topLeft = p(16f, 35f), size = Size(68f * u, 3.4f * u))
        }
    }
    drawCircle(Color.Black.copy(alpha = 0.5f), radius = 32f * u, center = c, style = Stroke(1.4f * u))
}

/** "Porthole", with the porthole as its "o" (DESIGN v3 §3). */
@Composable
fun Wordmark(
    modifier: Modifier = Modifier,
    style: TextStyle = wordmarkStyle(),
    color: Color = Porthole.colors.text,
) {
    // The rim is as tall as the face's "o" (0.52em with overshoot) and sits on the baseline.
    val em = style.fontSize.value
    val box = (em * 0.55f).dp
    val overshoot = with(LocalDensity.current) { (em * 0.012f).dp.roundToPx() }
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = Brand.NAME }) {
        Text("P", style = style, color = color, modifier = Modifier.alignByBaseline())
        // The mark's bottom, less the "o"'s overshoot, sits on the text's baseline.
        PortholeMark(
            size = box, light = true,
            modifier = Modifier
                .padding(start = (em * 0.012f).dp, end = (em * 0.008f).dp)
                .alignBy { it.measuredHeight - overshoot },
        )
        Text("rthole", style = style, color = color, modifier = Modifier.alignByBaseline())
    }
}

/** The wordmark's style: Hanken Bold, tight, in Porthole's own theme; other themes set it in their voice. */
@Composable
private fun wordmarkStyle(): TextStyle =
    if (LocalPortholeFonts.current == PortholeFonts.Porthole) Porthole.type.display.copy(fontFamily = PortholeLogoFace, letterSpacing = (-0.035).em)
    else Porthole.type.display

/** A tool's own mark in a raised tile. */
@Composable
fun ToolMark(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = Porthole.colors.text,
    contentDescription: String? = null,
) {
    Box(
        modifier
            .size(size)
            .background(Porthole.colors.raised, PortholeShape.control),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon), contentDescription = contentDescription, tint = tint,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** icon + name + one line. The rhythm of the "what you need" list and the setup steps. */
@Composable
fun ToolRow(
    @DrawableRes icon: Int,
    name: String,
    detail: String,
    modifier: Modifier = Modifier,
    tint: Color = Porthole.colors.text,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Porthole.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(c.surface, PortholeShape.card)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolMark(icon, tint = tint, contentDescription = name)
        Column(Modifier.weight(1f)) {
            Text(name, style = PortholeType.rowTitle, color = c.text)
            Text(detail, style = PortholeType.secondary, color = c.muted)
        }
        trailing?.invoke()
    }
}

object Brand {
    const val NAME = "Porthole"
    const val HEADLINE = "Run Claude Code on your computer, from your phone."
    const val SUB = "Over your own Tailscale network, with no Porthole server in between."
    val tailscale = R.drawable.ic_brand_tailscale
    val claudeCode = R.drawable.ic_brand_claudecode
    val tmux = R.drawable.ic_brand_tmux
    val github = R.drawable.ic_brand_github
}
