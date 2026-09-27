package dev.shrimpscript.porthole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.net.SessionInfo
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeMotion
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType

/** A folder Claude Code has been used in, on one computer: somewhere to start a session. */
data class Project(
    val cwd: String,
    val machineId: String,
    val machine: String,
    val lastActiveMs: Long,
    /** Claude Code sessions running there now. */
    val running: Int,
) {
    val name: String get() = cwd.trimEnd('/').substringAfterLast('/').ifBlank { cwd }
}

/**
 * Every folder in the session list, once per computer, most recently used first. The
 * list already holds one row for each folder Claude Code has been used in (its live
 * sessions, or its newest transcript), so nothing more needs asking for.
 */
fun projectsOf(sessions: List<SessionInfo>): List<Project> = sessions
    .filter { it.cwd.isNotBlank() && !it.machineDown }
    .groupBy { it.machineId to it.cwd }
    .map { (key, rows) ->
        Project(
            cwd = key.second, machineId = key.first, machine = rows.first().machine,
            lastActiveMs = rows.maxOf { isoMs(it.lastActive) }, running = rows.count { it.live },
        )
    }
    .sortedByDescending { it.lastActiveMs }

private fun isoMs(iso: String): Long =
    runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrDefault(0L)

/** A path as a person reads it: the home directory, on a Mac or Linux, as ~. */
fun shortPath(path: String): String = path.replace(Regex("^/(home|Users)/[^/]+(?=/|$)"), "~")

/**
 * New session: a fresh Claude Code, in its own tmux session named after the folder -
 * what `porthole` does at the desk. Never an existing session reused; that is what
 * tapping a row in the list is for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionSheet(
    projects: List<Project>,
    /** Computers to start a typed folder on; more than one shows a choice. */
    machines: List<Pair<String, String>>,
    /** The folder being started ("machineId|cwd"), while it is. */
    starting: String?,
    note: String?,
    onStart: (machineId: String, cwd: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Porthole.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val several = machines.size > 1
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
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Text("New session", style = PortholeType.title, color = c.text)
            Spacer(Modifier.height(4.dp))
            Text(
                "Claude Code in a tmux session of its own, in the folder you pick. It opens here " +
                    "when it is ready, and it is at the desk too.",
                style = PortholeType.secondary, color = c.muted,
            )
            Spacer(Modifier.height(12.dp))
            note?.let {
                Text(it, style = PortholeType.secondary, color = c.warn)
                Spacer(Modifier.height(8.dp))
            }
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                projects.forEachIndexed { i, p ->
                    val key = p.machineId + "|" + p.cwd
                    Appear(delayMs = minOf(i, 5) * PortholeMotion.STAGGER_MS) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = starting == null, role = Role.Button) { onStart(p.machineId, p.cwd) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Outlined.Folder, null, tint = if (starting == key) c.accent else c.muted, modifier = Modifier.size(22.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = PortholeType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        shortPath(p.cwd),
                                        if (p.running > 0) "${p.running} running" else null,
                                        if (several) p.machine.ifBlank { null } else null,
                                    ).joinToString(" · "),
                                    style = PortholeType.meta, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Pill(if (starting == key) "Starting…" else "Start", filled = starting == key) {
                                if (starting == null) onStart(p.machineId, p.cwd)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Another folder", style = PortholeType.secondary, color = c.muted)
            Spacer(Modifier.height(8.dp))
            var typed by remember { mutableStateOf("") }
            var on by remember { mutableStateOf(machines.firstOrNull()?.first.orEmpty()) }
            if (several) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    machines.forEach { (id, name) -> Pill(name, filled = id == on) { on = id } }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(typed, { typed = it }, placeholder = "~/code/new-project", modifier = Modifier.weight(1f), mono = true)
                val key = on + "|" + typed.trim()
                Pill(if (starting == key) "Starting…" else "Start", filled = typed.isNotBlank()) {
                    if (starting == null && typed.isNotBlank()) onStart(on, typed.trim())
                }
            }
        }
    }
}
