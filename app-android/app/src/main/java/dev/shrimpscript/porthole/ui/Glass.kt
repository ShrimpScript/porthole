package dev.shrimpscript.porthole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeColors
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * The glass layer (DESIGN v3 §4a). Controls that float over content - the session header,
 * the composer, the sessions dock, the terminal's bars, sheets - are frosted glass: the
 * content behind them blurred and darkened, a sheen at the top, a lit rim brighter at the
 * top-left, a soft shadow. Glass is never decoration: it only exists where something
 * scrolls or sits underneath.
 *
 * A screen opts in with [GlassHost]; the content glass floats over is marked [glassSource].
 * Android 12+ blurs the backdrop (RenderEffect, through Haze); older phones get the same
 * fill nearly opaque, which reads as a raised surface.
 */

/** The backdrop glass blurs, for the screen inside [GlassHost]; null outside one. */
val LocalGlass = staticCompositionLocalOf<HazeState?> { null }

/**
 * Whether glass may blur at all. True on a phone. The screen tests' JVM renderer has no
 * GPU surface for a blur (Haze falls to RenderScript there and finds none), so under
 * Robolectric glass draws its fallback fill: the renders check layout and colour, and the
 * blur itself is checked on an emulator or the phone.
 */
val LocalGlassBlur = staticCompositionLocalOf { android.os.Build.FINGERPRINT != "robolectric" }

@Composable
fun GlassHost(content: @Composable () -> Unit) {
    val state = remember { HazeState() }
    CompositionLocalProvider(LocalGlass provides state, content = content)
}

/** Marks what floating glass in this screen blurs. A no-op outside a [GlassHost]. */
@Composable
fun Modifier.glassSource(): Modifier {
    val state = LocalGlass.current ?: return this
    return this.hazeSource(state)
}

/** The glass's own colour under the blur: near-black on a dark palette, near-white on a light one. */
internal fun glassFill(c: PortholeColors): Color =
    if (c.isLight) Color(0xFFF7F7F8) else Color(0xFF1A1A1C)

/**
 * A floating glass surface in [shape].
 *
 * @param textBearing glass with text on it is a little denser (62%) than an icon-only circle (50%),
 *   so muted text stays above AA over a bright backdrop.
 * @param shadow floating glass casts a soft shadow; glass set into a larger glass area does not.
 */
@Composable
fun Modifier.glass(shape: Shape, textBearing: Boolean = true, shadow: Boolean = true): Modifier {
    val c = Porthole.colors
    val state = LocalGlass.current
    val blur = LocalGlassBlur.current
    val fill = glassFill(c)
    val alpha = if (textBearing) 0.58f else 0.45f
    val fallback = c.surface.copy(alpha = 0.94f)
    var m = this
    if (shadow) m = m.shadow(elevation = 14.dp, shape = shape, ambientColor = Color.Black, spotColor = Color.Black, clip = false)
    m = m.clip(shape)
    m = if (state != null) {
        m.hazeEffect(state) {
            blurEnabled = blur && blurEnabled
            blurRadius = 8.dp
            noiseFactor = 0f
            backgroundColor = c.ground
            tints = listOf(HazeTint(fill.copy(alpha = alpha)))
            fallbackTint = HazeTint(fallback)
        }
    } else {
        m.background(fallback)
    }
    val sheen = Color.White.copy(alpha = if (c.isLight) 0.30f else 0.07f)
    return m
        .drawBehind {
            drawRect(Brush.verticalGradient(0f to sheen, 0.6f to Color.Transparent))
        }
        .drawWithContent {
            drawContent()
            drawOutline(
                shape.createOutline(size, layoutDirection, this),
                brush = rimBrush(size, light = c.isLight),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
}

/**
 * The specular rim: white, brightest at the top-left, gone through the middle, a little back
 * at the bottom-right. CSS `linear-gradient(162deg, ...)` across the shape's box.
 */
internal fun rimBrush(size: Size, light: Boolean = false, angleDeg: Float = 162f): Brush {
    val a = Math.toRadians(angleDeg.toDouble())
    val dx = sin(a).toFloat(); val dy = -cos(a).toFloat()
    val len = abs(size.width * dx) + abs(size.height * dy)
    val cx = size.width / 2f; val cy = size.height / 2f
    val start = Offset(cx - dx * len / 2f, cy - dy * len / 2f)
    val end = Offset(cx + dx * len / 2f, cy + dy * len / 2f)
    val k = if (light) 0.6f else 1f
    return Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.42f * k),
        0.34f to Color.White.copy(alpha = 0.05f * k),
        0.58f to Color.Transparent,
        1f to Color.White.copy(alpha = 0.16f * k),
        start = start, end = end,
    )
}

/** The bright capsule's fill: white light on Clear; the theme's own accent elsewhere. */
@Composable
fun brightBrush(): Brush {
    val c = Porthole.colors
    return if (c.accent.luminance() > 0.8f) {
        Brush.verticalGradient(listOf(Color(0xFFFBFBFD), Color(0xFFD9D9DE)))
    } else {
        Brush.verticalGradient(listOf(lighten(c.accent, 0.10f), c.accent))
    }
}

private fun lighten(color: Color, by: Float): Color = Color(
    red = color.red + (1f - color.red) * by,
    green = color.green + (1f - color.green) * by,
    blue = color.blue + (1f - color.blue) * by,
    alpha = color.alpha,
)

/** The accent surface (DESIGN v3 §4a): the main action, the selected option, an active key. */
@Composable
fun Modifier.bright(shape: Shape = PortholeShape.pill, glow: Boolean = true): Modifier {
    var m = this
    if (glow) m = m.shadow(elevation = 10.dp, shape = shape, ambientColor = Color.Black, spotColor = Color.Black, clip = false)
    return m
        .clip(shape)
        .background(brightBrush())
        .drawWithContent {
            drawContent()
            drawOutline(
                shape.createOutline(size, layoutDirection, this),
                brush = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.75f), 0.5f to Color.Transparent),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
}

/** A glass capsule button: the secondary action beside a bright one, or a control over content. */
@Composable
fun GlassCapsule(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp),
    label: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .defaultMinSize(minHeight = height)
            .pressScale(interaction)
            .glass(PortholeShape.pill)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        content = content,
    )
}

/** A 44dp icon button set in glass, for controls that float over content. */
@Composable
fun GlassCircle(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Porthole.colors.text,
    selected: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val c = Porthole.colors
    Box(
        modifier
            .size(44.dp)
            .pressScale(interaction)
            .then(if (selected) Modifier.bright(androidx.compose.foundation.shape.CircleShape) else Modifier.glass(androidx.compose.foundation.shape.CircleShape, textBearing = false))
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = if (selected) c.onAccent else tint, modifier = Modifier.size(22.dp))
    }
}

/** A glass panel: a rounded area of glass holding a group (the hero's caption, a sheet's body). */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = PortholeShape.panel,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(shape), content = content)
}

/**
 * The content floating glass blurs. Inside it, glass has nothing of its own to blur (it would
 * sample itself), so its children see no glass state and draw the fallback fill - unless a
 * part of it sets up its own, as the sessions hero does for the glass over its picture.
 */
@Composable
fun GlassSource(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val state = LocalGlass.current
    Box(if (state != null) modifier.hazeSource(state) else modifier) {
        CompositionLocalProvider(LocalGlass provides null) { content() }
    }
}

/** Needs you, on a glass card: an amber edge and a little of its light around it. */
@Composable
fun Modifier.warnEdge(shape: Shape): Modifier {
    val warn = Porthole.colors.warn
    return this.drawWithContent {
        drawContent()
        drawOutline(shape.createOutline(size, layoutDirection, this), color = warn.copy(alpha = 0.6f), style = Stroke(width = 3.dp.toPx()))
    }
}

/** A small glass capsule with words: a quick reply, an attachment, a filter. */
@Composable
fun GlassChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, quiet: Boolean = false) {
    val c = Porthole.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .defaultMinSize(minHeight = 40.dp)
            .pressScale(interaction)
            .glass(PortholeShape.pill, shadow = false)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(text, style = dev.shrimpscript.porthole.ui.theme.PortholeType.secondary.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = if (quiet) c.muted else c.text, maxLines = 1)
    }
}
