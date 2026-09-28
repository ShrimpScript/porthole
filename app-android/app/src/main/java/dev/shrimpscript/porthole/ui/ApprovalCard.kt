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
        Appear(Modifier.padding(16.dp)) {
            ApprovalCardBody(approval, remaining, onAllow, onDeny)
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
) {
    val c = Porthole.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(c.surface, PortholeShape.card)
            .border(1.dp, c.warn, PortholeShape.card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
                style = PortholeType.rowTitle, color = c.text,
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .background(c.ground, PortholeShape.control)
                .border(1.dp, c.edge, PortholeShape.control)
                .padding(12.dp)
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

        Text(
            if (remaining > 0) "Waiting · ${remaining}s left"
            else "Timed out — answer at the computer",
            style = PortholeType.secondary,
            color = if (remaining > 0) c.muted else c.faint,
        )

        if (onAllow != null && onDeny != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton("Deny", onDeny, Modifier.weight(1f))
                PrimaryButton("Allow", onAllow, Modifier.weight(1f), enabled = remaining > 0)
            }
        }
    }
}
