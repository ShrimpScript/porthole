package dev.shrimpscript.porthole.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.net.SessionState
import dev.shrimpscript.porthole.net.TuiStatus
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType
import dev.shrimpscript.porthole.ui.theme.motionEnabled
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The CLI's spinner, drawn rather than typeset. The glyphs ✻ ✽ ✶ ✳ ✢ · come from three
 * different fonts with three different centres and widths, so as text the dot sat low
 * and every frame change nudged the layout. Each frame here is a star of n spokes (or a
 * dot) in a fixed 16dp box, centred by construction. Static when animations are off.
 */
@Composable
fun Spinner(modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Porthole.colors.accent) {
    val on = motionEnabled()
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(on) {
        if (!on) return@LaunchedEffect
        while (true) { delay(140); i = (i + 1) % SPINNER_FRAMES.size }
    }
    // spokes, spoke length fraction, rotation degrees; the last frame is the dot
    // No 45-degree four-spoke frame: it reads as a close mark next to "Running…".
    val frames = listOf(Triple(6, 1f, 0f), Triple(8, 0.9f, 0f), Triple(6, 1f, 30f), Triple(4, 0.9f, 0f), Triple(4, 0.6f, 0f), Triple(0, 0f, 0f))
    val (spokes, len, rot) = frames[i]
    Canvas(modifier.size(16.dp)) {
        val c = center
        val r = size.minDimension / 2f
        val stroke = 1.8.dp.toPx()
        if (spokes == 0) {
            drawCircle(color, radius = stroke * 1.1f, center = c)
            return@Canvas
        }
        drawCircle(color, radius = stroke * 0.6f, center = c)
        for (k in 0 until spokes) {
            val a = Math.toRadians((rot + k * 360f / spokes).toDouble())
            val end = Offset(c.x + (r * len) * cos(a).toFloat(), c.y + (r * len) * sin(a).toFloat())
            drawLine(color, c, end, strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

/**
 * "Is it doing anything?" - answered from two real sources: the transcript's turn state
 * (a prompt is in flight until end_turn) and, when the session is live in tmux, the
 * CLI's own spinner line. The elapsed time counts locally from the transcript's
 * timestamp so it moves between daemon updates.
 */
@Composable
fun WorkingStrip(
    state: SessionState?,
    status: TuiStatus?,
    canInterrupt: Boolean,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Porthole.colors
    val working = (status?.working == true) || (state?.working == true)
    if (!working) return

    val since = remember(state?.workingSince) {
        runCatching { OffsetDateTime.parse(state?.workingSince).toInstant().toEpochMilli() }.getOrNull()
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(working) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val elapsed = status?.elapsed?.ifBlank { null } ?: since?.let { s ->
        val secs = ((now - s) / 1000).coerceAtLeast(0)
        if (secs < 60) "${secs}s" else "${secs / 60}m ${secs % 60}s"
    }
    // A picker on screen means Claude is waiting on the person, whatever the transcript's
    // last tool was; saying "Running AskUserQuestion…" there would be a lie about who is busy.
    val text = when {
        status?.question != null -> "Waiting for your answer"
        else -> status?.text?.ifBlank { null }
            ?: state?.pendingTool?.ifBlank { null }?.let { "Running $it…" }
            ?: "Working…"
    }

    Appear(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .background(c.surface, PortholeShape.card)
                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spinner()
            Column(Modifier.weight(1f)) {
                Text(text, style = PortholeType.secondary, color = c.text)
                val meta = listOfNotNull(elapsed, status?.tokens?.ifBlank { null }).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = PortholeType.meta, color = c.faint)
            }
            if (canInterrupt) {
                GhostButton("Interrupt", onInterrupt, Modifier.width(110.dp))
            }
        }
    }
}

/** Sortable "since" for the sessions list. */
fun epochOf(iso: String): Long = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrDefault(Instant.EPOCH).toEpochMilli()

/** The spinner's resting star, for the turn summary. */
@Composable
fun StarMark(modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Porthole.colors.faint) {
    Canvas(modifier.size(14.dp)) {
        val c = center; val r = size.minDimension / 2f; val stroke = 1.6.dp.toPx()
        for (k in 0 until 6) {
            val a = Math.toRadians((k * 60f).toDouble())
            drawLine(color, c, Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

/**
 * A usage limit, as the CLI reports it. Claude Code (2.1.234+) waits and continues on
 * its own by default; this card says what it is doing and offers the two keys that mean
 * something at that moment: Enter after a reset that happened while the computer slept,
 * and Esc to cancel a wait. Everything else goes through /rate-limit-options in the
 * terminal, because that is a picker.
 */
@Composable
fun LimitCard(
    status: TuiStatus,
    autoContinue: Boolean,
    onKey: (String) -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Porthole.colors
    Appear(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .background(c.surface, PortholeShape.card)
                .border(1.dp, c.warn, PortholeShape.card)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Outlined.Warning, contentDescription = null,
                    tint = c.warn, modifier = Modifier.size(20.dp),
                )
                Text(
                    when {
                        status.limitEnter -> "Usage limit reset"
                        status.limitStopped -> "Automatic continue stopped"
                        status.limitWaiting -> "Usage limit reached"
                        else -> "Usage limit hit"
                    },
                    style = PortholeType.rowTitle, color = c.text,
                )
            }
            Text(
                when {
                    status.limitEnter -> "The limit reset while the computer was asleep. Claude Code is waiting for Enter to pick the task back up."
                    status.limitStopped -> "Claude Code hit the limit again while continuing, or the wait was cancelled. It will not continue on its own for this reset window."
                    status.limitWaiting && status.limitResumeAt.isNotBlank() -> "Claude Code is waiting in the session and will continue the task on its own at ${status.limitResumeAt}."
                    status.limitWaiting -> "Claude Code is waiting in the session and will continue the task on its own when the limit resets."
                    autoContinue -> "Claude Code should start waiting to continue automatically. If it does not, open its options."
                    else -> "Automatic continue is off in Claude Code's settings. Choose \"Wait here, then continue automatically\" from its options to have it resume after the reset."
                },
                style = PortholeType.secondary, color = c.muted,
            )
            Text(status.limitText, style = PortholeType.meta, color = c.faint)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    status.limitEnter -> PrimaryButton("Continue now", { onKey("Enter") }, Modifier.weight(1f))
                    status.limitWaiting -> GhostButton("Cancel the wait", { onKey("Escape") }, Modifier.weight(1f))
                    else -> PrimaryButton("Open the options", onOptions, Modifier.weight(1f))
                }
                if (status.limitWaiting || status.limitEnter) GhostButton("Options", onOptions, Modifier.weight(1f))
            }
        }
    }
}
