package dev.shrimpscript.porthole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.net.Approval
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType
import kotlinx.coroutines.delay

/**
 * The feature that justifies the app: a tool call waiting on you, from anywhere.
 *
 * Rules, all load-bearing:
 *  - the command is shown verbatim and in full, wrapped so the whole of it is in view,
 *    never truncated and never on one line that scrolls sideways: the dangerous part of
 *    a long command is usually its end. It scrolls down only past the card's height.
 *  - there is no default and no pre-focused button.
 *  - neither button is visually louder than the command itself.
 *  - letting it expire is not an answer: it falls back to the prompt at the desk.
 */
@Composable
fun ApprovalOverlay(
    approval: Approval,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onExpired: () -> Unit,
) {
    val c = Porthole.colors
    val elapsed = ((System.currentTimeMillis() - approval.receivedAtMs) / 1000).toInt()
    var remaining by remember(approval.toolUseId) {
        mutableIntStateOf((approval.expiresInSeconds - elapsed).coerceAtLeast(0))
    }

    LaunchedEffect(approval.toolUseId) {
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
        onExpired()
    }

    Box(
        Modifier
            .fillMaxSize()
            // A scrim, not a dismissible one: tapping outside must not count as an
            // answer in either direction.
            .background(Porthole.colors.scrim),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // A sheet set 8dp in from the screen's edges, its corners concentric with the phone's.
        Appear(Modifier.padding(8.dp)) {
            ApprovalCardBody(approval, remaining, onAllow, onDeny, total = approval.expiresInSeconds)
        }
    }
}

/** The card itself. Also drawn, static and labelled, in the tour. */
@Composable
fun ApprovalCardBody(
    approval: Approval,
    remaining: Int,
    onAllow: (() -> Unit)?,
    onDeny: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** The whole wait, for the bar that drains; 0 draws no bar. */
    total: Int = 0,
) {
    val c = Porthole.colors
    Column(
        modifier
            .fillMaxWidth()
            .glass(PortholeShape.sheet)
            .warnEdge(PortholeShape.sheet)
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Outlined.Warning, contentDescription = null,
                tint = c.warn, modifier = Modifier.height(20.dp),
            )
            Text(
                when (approval.toolName) {
                    "Bash" -> "Claude wants to run a command"
                    "Write" -> "Claude wants to write a file"
                    "Edit" -> "Claude wants to edit a file"
                    else -> "Claude wants to use ${approval.toolName}"
                },
                style = Porthole.type.title, color = c.text,
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .background(c.deep.copy(alpha = 0.85f), PortholeShape.option)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                approval.command.ifBlank { "(no command)" },
                style = PortholeType.mono,
                color = c.text,
            )
            if (approval.cwd.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("in ${approval.cwd}", style = PortholeType.meta, color = c.faint)
            }
        }

        Row(Modifier.fillMaxWidth()) {
            Text(
                if (remaining > 0) "Waiting for you" else "Timed out — answer at the computer",
                style = PortholeType.secondary, color = c.muted, modifier = Modifier.weight(1f),
            )
            if (remaining > 0) Text("${remaining}s left", style = PortholeType.secondary, color = c.text)
        }
        // The wait, draining: the same amber as the edge, so the card reads as one call.
        if (total > 0 && remaining > 0) {
            Box(Modifier.fillMaxWidth().height(6.dp).background(c.text.copy(alpha = 0.08f), PortholeShape.pill)) {
                Box(
                    Modifier
                        .fillMaxWidth((remaining.toFloat() / total).coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(c.warn, PortholeShape.pill),
                )
            }
        }

        if (onAllow != null && onDeny != null) {
            // Two equal answers: neither is louder than the command, and neither is a default
            // (DESIGN §8.4). The words tell them apart.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Deny", onDeny, Modifier.weight(1f), glass = true)
                GhostButton(if (remaining > 0) "Allow" else "Allow (timed out)", { if (remaining > 0) onAllow() }, Modifier.weight(1f), glass = true)
            }
        }
    }
}
