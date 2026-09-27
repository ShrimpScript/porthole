package dev.shrimpscript.porthole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.net.PreviewState
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeMotion
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType
import kotlinx.coroutines.delay

/**
 * Dev servers listening on the computer, each one tap from opening in this phone's
 * browser. The daemon shares it over the tailnet to paired devices only; the list is
 * re-read every few seconds while the sheet is up, so a server started at the desk
 * appears without a gesture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewSheet(
    hostLabel: String,
    state: PreviewState?,
    onRefresh: () -> Unit,
    onOpen: (Int) -> Unit,
    onStop: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Porthole.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) {
        while (true) { onRefresh(); delay(3000) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = c.surface,
        contentColor = c.text,
        dragHandle = {
            Box(
                Modifier
                    .statusBarsPadding()
                    .padding(top = 10.dp, bottom = 6.dp)
                    .background(c.edge, PortholeShape.pill)
                    .height(4.dp)
                    .padding(horizontal = 18.dp)
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text("Open on this phone", style = PortholeType.title, color = c.text)
            Spacer(Modifier.height(4.dp))
            Text(
                "Dev servers listening on $hostLabel. Opening one shares it with this phone " +
                    "over your tailnet, and with nothing else.",
                style = PortholeType.secondary, color = c.muted,
            )
            Spacer(Modifier.height(16.dp))
            when {
                state == null -> Text("Looking\u2026", style = PortholeType.body, color = c.faint)
                state.servers.isEmpty() -> Column(
                    Modifier
                        .fillMaxWidth()
                        .background(c.raised, PortholeShape.card)
                        .padding(14.dp),
                ) {
                    Text("Nothing is listening", style = PortholeType.rowTitle, color = c.text)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Start a dev server on the computer - vite, next dev, expo start - " +
                            "and it appears here.",
                        style = PortholeType.secondary, color = c.muted,
                    )
                }
                else -> state.servers.forEachIndexed { i, sv ->
                    val share = state.active.firstOrNull { it.upstream == sv.port }
                    Appear(delayMs = minOf(i, 4) * PortholeMotion.STAGGER_MS) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Language, null,
                                tint = if (share != null) c.accent else c.muted,
                                modifier = Modifier.size(22.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(sv.name, style = PortholeType.rowTitle, color = c.text)
                                Text(
                                    when {
                                        share != null -> "localhost:${sv.port} \u00b7 shared on :${share.port}"
                                        sv.process != sv.name -> "localhost:${sv.port} \u00b7 ${sv.process}"
                                        else -> "localhost:${sv.port}"
                                    },
                                    style = PortholeType.meta, color = c.faint,
                                )
                            }
                            if (share != null) {
                                Pill("Stop", filled = false, onClick = { onStop(sv.port) })
                            }
                            Pill("Open", filled = true, onClick = { onOpen(sv.port) })
                        }
                    }
                }
            }
        }
    }
}
