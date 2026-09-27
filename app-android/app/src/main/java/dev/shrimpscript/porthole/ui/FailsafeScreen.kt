package dev.shrimpscript.porthole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.terminal.KeyRow
import dev.shrimpscript.porthole.terminal.TerminalInput
import dev.shrimpscript.porthole.terminal.TerminalEmulator
import dev.shrimpscript.porthole.terminal.TerminalView
import dev.shrimpscript.porthole.terminal.rememberTerminalInputController
import androidx.compose.foundation.layout.size
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import dev.shrimpscript.porthole.ui.theme.PortholeShape

/**
 * The failsafe shell.
 *
 * Deliberately labelled rather than disguised as the normal terminal: the session feed,
 * approvals and prompt sending are all unavailable here, and pretending otherwise would
 * be the same class of lie as a fake connection indicator. What it does offer is the
 * thing that matters - a real shell on the machine, over a path that does not depend on
 * Porthole running at all.
 */
@Composable
fun FailsafeScreen(
    machine: String,
    terminal: TerminalEmulator,
    revision: Int,
    onKeys: (String) -> Unit,
    onBack: () -> Unit,
    /** False when the shell was opened on purpose from Settings and the daemon is fine. */
    daemonDown: Boolean = true,
    /** The grid that fits the screen, so the shell on the computer draws at that size. */
    onResize: (Int, Int) -> Unit = { _, _ -> },
    fontSp: Float = 13f,
    /** The SSH connection ended; the screen stays, typing does nothing. */
    closed: Boolean = false,
) {
    val c = Porthole.colors
    val input = rememberTerminalInputController()
    // Lines scrolled back into the shell's own scrollback. New output while scrolled back
    // does not move the text being read: the offset grows by what was pushed underneath.
    var offset by remember { mutableIntStateOf(0) }
    var pushedSeen by remember { mutableLongStateOf(terminal.historyPushed) }
    LaunchedEffect(revision) {
        val pushed = terminal.historyPushed
        if (offset > 0) offset = (offset + (pushed - pushedSeen).toInt()).coerceIn(0, terminal.historySize)
        pushedSeen = pushed
    }
    var full by remember { mutableStateOf(false) }
    Immersive(full)
    // Back leaves full screen before it leaves the shell.
    androidx.activity.compose.BackHandler(enabled = full) { full = false }
    // Typing is for the prompt, so it brings the prompt back into view.
    val keys: (String) -> Unit = { k -> offset = 0; onKeys(k) }
    Box(
        Modifier
            .fillMaxSize()
            .background(c.ground)
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            if (!full) Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface)
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconTarget(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
                Ring(state = if (daemonDown) RingState.Retrying else RingState.Live, size = 18.dp)
                Column(Modifier.weight(1f)) {
                    Text("$machine · SSH", style = PortholeType.rowTitle, color = c.text)
                    Text(
                        when {
                            closed -> "Failsafe shell \u2014 the connection has closed"
                            daemonDown -> "Failsafe shell \u2014 the daemon is not answering"
                            else -> "Failsafe shell \u2014 a plain SSH login, not portholed"
                        },
                        style = PortholeType.meta, color = if (daemonDown || closed) c.warn else c.faint,
                    )
                }
                IconTarget(Icons.Outlined.Fullscreen, "Full screen", { full = true })
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Porthole.colors.deep)
            ) {
                TerminalView(
                    emulator = terminal, revision = revision, fontSize = fontSp.sp,
                    onTap = { input.showKeyboard() },
                    historyOffset = offset,
                    onScrollLines = { n -> offset = (offset + n).coerceIn(0, terminal.historySize) },
                    // The shell is sized to the phone, not the other way round: a plain
                    // login has no desk window to stay faithful to.
                    onGridSize = { cols, rows -> terminal.resize(cols, rows); onResize(cols, rows) },
                )
                // A shell you cannot type into is not a failsafe.
                TerminalInput(keys, Modifier.size(1.dp), controller = input)
                if (offset > 0) {
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) {
                        Pill("$offset lines up \u00b7 back to the prompt", filled = true) { offset = 0 }
                    }
                }
                if (full) {
                    Box(Modifier.align(Alignment.TopEnd).padding(6.dp).background(c.surface.copy(alpha = 0.85f), PortholeShape.pill)) {
                        IconTarget(Icons.Outlined.FullscreenExit, "Leave full screen", { full = false })
                    }
                }
            }

            KeyRow(onSend = keys)
        }
    }
}
