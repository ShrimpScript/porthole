package dev.shrimpscript.porthole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.Icons
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shrimpscript.porthole.terminal.KeyRow
import dev.shrimpscript.porthole.terminal.TerminalEmulator
import dev.shrimpscript.porthole.terminal.TerminalInput
import dev.shrimpscript.porthole.terminal.TerminalView
import dev.shrimpscript.porthole.terminal.rememberTerminalInputController
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType

/** The TTY's own ground: a shade below the app's, so the quoted region reads as one. */
/** The terminal pane sits on the theme's deepest ground. */
val TerminalGround: Color @Composable get() = Porthole.colors.deep

/** Terminal text floor and ceiling, in sp. Fit mode may go smaller; it says so. */
const val TERMINAL_FONT_MIN = 8f
const val TERMINAL_FONT_MAX = 22f

/**
 * The terminal view of a session: the desktop's tmux window, exactly, plus the key row.
 *
 * This is the same screen a person would see at the desk - the daemon sizes the PTY to
 * the desktop's window, so nothing is reflowed. On a phone that window is usually wider
 * than the screen, so there are two ways to look at it: fit (the whole grid, small) or
 * scroll (readable size, pan sideways). Pinch to zoom switches to scroll at that size.
 */
@Composable
fun TerminalBody(
    emulator: TerminalEmulator,
    revision: Int,
    open: Boolean,
    live: Boolean,
    fontSp: Float,
    onFontSp: (Float) -> Unit,
    fit: Boolean,
    onFit: (Boolean) -> Unit,
    onSend: (String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    /** Walk the pane's history (lines > 0 back, 0 back to live); null where there is none. */
    onScroll: ((Int) -> Unit)? = null,
    fullscreen: Boolean = false,
    onFullscreen: (Boolean) -> Unit = {},
) {
    val c = Porthole.colors
    val input = rememberTerminalInputController()
    val density = LocalDensity.current
    // How far back the pane has been walked, as far as the phone knows. Keys pressed while
    // tmux is in its history would drive the history, not the program, so typing first
    // brings the pane back to live.
    var back by remember { mutableIntStateOf(0) }
    val send: (String) -> Unit = { k ->
        if (back > 0) { onScroll?.invoke(0); back = 0 }
        onSend(k)
    }

    Column(modifier.fillMaxSize()) {
        // status strip: real numbers only - the grid the daemon reported, the size in use
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.surface)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (open) "${emulator.cols}×${emulator.rows} · tap to type" else if (live) "attaching" else "no live terminal",
                style = PortholeType.meta, color = c.faint,
            )
            Spacer(Modifier.weight(1f))
            StripButton(if (fit) "fit" else "${fontSp.toInt()}sp", accent = fit) { onFit(!fit) }
            StripButton("−") { onFit(false); onFontSp((fontSp - 1f).coerceAtLeast(TERMINAL_FONT_MIN)) }
            StripButton("+") { onFit(false); onFontSp((fontSp + 1f).coerceAtMost(TERMINAL_FONT_MAX)) }
            IconTarget(
                if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                if (fullscreen) "Leave full screen" else "Full screen",
                { onFullscreen(!fullscreen) },
                Modifier.size(40.dp),
            )
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(TerminalGround)
        ) {
            when {
                !live -> NoTerminal(
                    "This session is not in tmux.",
                    "The phone can read it but not type to it. Start Claude Code with porthole " +
                        "instead of claude and its terminal appears here.",
                )
                // Switching to this view asks the daemon to attach; the window arrives a
                // moment later. Say that, rather than flashing a button for 100ms.
                !open -> Attaching(onConnect)
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val widthPx = with(density) { maxWidth.toPx() }
                    TerminalView(
                        emulator = emulator,
                        revision = revision,
                        fontSize = fontSp.sp,
                        fitWidthPx = if (fit) widthPx else null,
                        onTap = { input.showKeyboard() },
                        onZoom = { z ->
                            onFit(false)
                            onFontSp((fontSp * z).coerceIn(TERMINAL_FONT_MIN, TERMINAL_FONT_MAX))
                        },
                        onScrollLines = onScroll?.let { scroll ->
                            { n: Int ->
                                // Forward past the live screen is nothing to walk.
                                val step = if (n < 0) maxOf(n, -back) else n
                                if (step != 0) { back += step; scroll(step) }
                            }
                        },
                    )
                    // The keyboard owner sits beside the grid, not over it, so the grid
                    // keeps its gestures (see TerminalInput). The keyboard waits for a
                    // tap: this view is for looking first, typing second.
                    TerminalInput(send, Modifier.size(1.dp), controller = input, showOnStart = false)
                    if (back > 0) {
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) {
                            Pill("$back lines back \u00b7 back to live", filled = true) { onScroll?.invoke(0); back = 0 }
                        }
                    }
                }
            }
        }

        if (open) KeyRow(onSend = send)
    }
}

@Composable
private fun StripButton(label: String, accent: Boolean = false, onClick: () -> Unit) {
    val c = Porthole.colors
    Box(
        Modifier
            .height(36.dp)
            .background(if (accent) c.accent else c.raised, PortholeShape.key)
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = PortholeType.meta, color = if (accent) c.onAccent else c.muted)
    }
}

/** The attach is in flight. Real state: the ring spins only while the request is out. */
@Composable
private fun Attaching(onRetry: () -> Unit) {
    val c = Porthole.colors
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Ring(state = RingState.Connecting, size = 22.dp, strokeWidth = 2.5.dp)
            Text("Attaching to the computer's tmux window…", style = PortholeType.body, color = c.muted)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Taking a while? Tap to try again.", style = PortholeType.meta, color = c.faint,
            modifier = Modifier.clickable { onRetry() }.padding(vertical = 8.dp),
        )
    }
}

/**
 * The honest empty state: no fake terminal, the real reason, and the
 * command that changes it.
 */
@Composable
private fun NoTerminal(title: String, body: String, action: Pair<String, () -> Unit>? = null) {
    val c = Porthole.colors
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = PortholeType.rowTitle, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text(body, style = PortholeType.body, color = c.muted)
        if (action == null) {
            Spacer(Modifier.height(16.dp))
            CommandBlock("porthole", "in the project's folder at the desk:")
        } else {
            Spacer(Modifier.height(20.dp))
            PrimaryButton(action.first, action.second, Modifier.width(160.dp))
        }
    }
}
