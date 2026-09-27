package dev.shrimpscript.porthole.ui

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.ui.theme.Porthole

/**
 * The connection state, and the app's only animated element.
 *
 * Stillness is the signal: a healthy connection does not move, so motion always means
 * something is happening. Shape differs per state as well as colour, so the meaning
 * survives colour-blindness, and every state carries a label for TalkBack.
 */
enum class RingState(val label: String) {
    Connecting("connecting"),
    Live("connected"),
    Retrying("reconnecting"),
    Dropped("disconnected"),
    NeedsYou("needs your approval"),
    Idle("idle"),
    /** The logo, not a connection indicator: accent, closed, still. Only in the wordmark and on welcome. */
    Mark("Porthole"),
}

@Composable
private fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        // Respect the system animation scale: with animations off, colour and shape
        // carry the state on their own.
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
}

@Composable
fun Ring(
    state: RingState,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    strokeWidth: Dp = 2.dp,
    /** How much of a closed ring is drawn, 0..1. Used once, to draw the mark in on welcome. */
    reveal: Float = 1f,
    /** What a screen reader hears. The state's own word, unless the ring stands for something else, as on a session row. */
    label: String = state.label,
) {
    val c = Porthole.colors
    val animate = animationsEnabled()

    val color = when (state) {
        RingState.Connecting, RingState.Live, RingState.Mark -> c.accent
        RingState.Retrying, RingState.NeedsYou -> c.warn
        RingState.Dropped -> c.bad
        RingState.Idle -> c.faint
    }

    val transition = rememberInfiniteTransition(label = "ring")
    val spin by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = androidx.compose.animation.core.LinearEasing)),
        label = "spin",
    )
    val breath by transition.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), repeatMode = RepeatMode.Reverse),
        label = "breath",
    )

    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = label }
    ) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val inset = strokeWidth.toPx() / 2f
        val arcSize = Size(this.size.width - strokeWidth.toPx(), this.size.height - strokeWidth.toPx())
        val topLeft = Offset(inset, inset)

        fun track(alpha: Float = 1f) = drawArc(
            color = c.edge.copy(alpha = alpha), startAngle = 0f, sweepAngle = 360f,
            useCenter = false, topLeft = topLeft, size = arcSize, style = stroke,
        )

        when (state) {
            RingState.Connecting -> {
                track()
                // Four dashes chasing round the ring.
                val rotation = if (animate) spin else 0f
                repeat(4) { i ->
                    drawArc(
                        color = color, startAngle = rotation + i * 90f, sweepAngle = 38f,
                        useCenter = false, topLeft = topLeft, size = arcSize, style = stroke,
                    )
                }
            }
            RingState.Live, RingState.NeedsYou, RingState.Mark ->
                drawArc(
                    color = color, startAngle = -90f, sweepAngle = 360f * reveal.coerceIn(0f, 1f),
                    useCenter = false, topLeft = topLeft, size = arcSize, style = stroke,
                )
            RingState.Retrying -> {
                track()
                drawArc(
                    color = color.copy(alpha = if (animate) breath else 1f),
                    startAngle = -90f, sweepAngle = 270f,
                    useCenter = false, topLeft = topLeft, size = arcSize, style = stroke,
                )
            }
            RingState.Dropped -> {
                // A visibly broken circle: two arcs with gaps, so the state reads without
                // relying on the red.
                drawArc(
                    color = color, startAngle = -60f, sweepAngle = 130f,
                    useCenter = false, topLeft = topLeft, size = arcSize, style = stroke,
                )
                drawArc(
                    color = color, startAngle = 120f, sweepAngle = 130f,
                    useCenter = false, topLeft = topLeft, size = arcSize, style = stroke,
                )
            }
            RingState.Idle ->
                drawArc(
                    color = color, startAngle = 0f, sweepAngle = 360f,
                    useCenter = false, topLeft = topLeft, size = arcSize, style = stroke,
                )
        }
    }
}
