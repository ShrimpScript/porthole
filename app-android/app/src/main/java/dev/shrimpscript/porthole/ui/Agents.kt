package dev.shrimpscript.porthole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.net.AgentCall
import dev.shrimpscript.porthole.net.AgentInfo
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType
import kotlinx.coroutines.delay

/*
 * Subagents: the work a session hands to agents of its own. The session's transcript records
 * each Agent call; the daemon follows each agent's own transcript for how far it has got. A
 * background agent keeps working after its call returned, even after the session's own turn
 * has ended, so its card follows the agent, never the call.
 */

/** "general-purpose · sonnet · background": what kind of agent, on what, how it runs. */
private fun agentKind(type: String, model: String, background: Boolean): String =
    listOfNotNull(type.ifBlank { null }, model.ifBlank { null }?.let(::modelShortName)?.ifBlank { model },
        if (background) "background" else null).joinToString(" · ")

private fun span(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}

/** A clock for "running for …": ticks once a second while anything shown is running. */
@Composable
private fun rememberNow(ticking: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ticking) { while (ticking) { now = System.currentTimeMillis(); delay(1000) } }
    return now
}

/** What an agent is doing or did, in one line: tools, its latest call, and for how long. */
private fun agentLine(a: AgentInfo, now: Long): String = when (a.state) {
    "running" -> listOfNotNull(
        "${a.tools} tool${if (a.tools == 1) "" else "s"}",
        a.doing.ifBlank { null },
        if (a.startedMs > 0) span(now - a.startedMs) else null,
    ).joinToString(" · ")
    "done" -> "Done in ${span(a.lastActiveMs - a.startedMs)} · ${a.tools} tool${if (a.tools == 1) "" else "s"}"
    else -> "Stopped after ${span(a.lastActiveMs - a.startedMs)} · ${a.tools} tool${if (a.tools == 1) "" else "s"}"
}

@Composable
private fun AgentStateMark(state: String?, background: androidx.compose.ui.graphics.Color) {
    val c = Porthole.colors
    when (state) {
        "running" -> Spinner(background = background)
        "done" -> Icon(Icons.Outlined.Check, contentDescription = "done", tint = c.ok, modifier = Modifier.size(16.dp))
        "stopped" -> Icon(Icons.Outlined.PauseCircle, contentDescription = "stopped", tint = c.faint, modifier = Modifier.size(16.dp))
        else -> Spinner(background = background) // called, not yet started
    }
}

/**
 * An Agent call in the feed: what was asked, of what kind of agent, and - from the agent's
 * own transcript - whether it is at work, what on, and for how long. Tapping opens every agent.
 */
@Composable
fun AgentCard(call: AgentCall, text: String, agent: AgentInfo?, pending: Boolean, onOpen: () -> Unit) {
    val c = Porthole.colors
    val state = agent?.state ?: if (pending) null else "done"
    val now = rememberNow(state == "running" || state == null)
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, PortholeShape.card)
            .border(1.dp, if (state == "running" || state == null) c.accent.copy(alpha = 0.35f) else c.edge, PortholeShape.card)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.AccountTree, contentDescription = null, tint = c.accent, modifier = Modifier.size(17.dp))
            Text(
                call.description.ifBlank { text.removePrefix("Delegated ") }.ifBlank { "An agent" },
                style = PortholeType.secondary, fontWeight = FontWeight.SemiBold, color = c.text,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
        val kind = agentKind(call.type.ifBlank { agent?.type.orEmpty() }, call.model.ifBlank { agent?.model.orEmpty() }, call.background || agent?.background == true)
        if (kind.isNotBlank()) Text(kind, style = PortholeType.meta, color = c.faint, modifier = Modifier.padding(start = 25.dp))
        Row(
            Modifier.padding(start = 25.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            AgentStateMark(state, c.surface)
            Text(
                when {
                    agent != null -> agentLine(agent, now)
                    state == null -> "Starting…"
                    else -> "Done"
                },
                style = PortholeType.meta, color = if (state == "running" || state == null) c.text else c.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "3 agents": on the working strip, or on its own once the session's turn has ended. */
@Composable
fun AgentsChip(running: Int, onClick: () -> Unit) {
    val c = Porthole.colors
    Row(
        Modifier
            .background(c.raised, PortholeShape.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.AccountTree, contentDescription = null, tint = c.accent, modifier = Modifier.size(14.dp))
        Text("$running agent${if (running == 1) "" else "s"}", style = PortholeType.meta, color = c.text)
    }
}

/**
 * Background agents still at work after the session's own turn ended: the working strip is
 * gone, but work is not. One quiet line, tap for the list.
 */
@Composable
fun AgentsStrip(running: List<AgentInfo>, onClick: () -> Unit) {
    val c = Porthole.colors
    val now = rememberNow(true)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(c.surface, PortholeShape.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spinner(background = c.surface)
        Column(Modifier.weight(1f)) {
            Text(
                if (running.size == 1) running[0].description.ifBlank { "An agent is working" }
                else "${running.size} agents are working",
                style = PortholeType.secondary, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val first = running.firstOrNull()
            if (first != null) Text(agentLine(first, now), style = PortholeType.meta, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("View", style = PortholeType.secondary, color = c.accent)
    }
}

/** Every agent of the session: the ones at work first, then the rest, newest first; an agent's agents indented under it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsSheet(agents: List<AgentInfo>, onDismiss: () -> Unit) {
    val c = Porthole.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val now = rememberNow(agents.any { it.state == "running" })
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
        AgentsList(agents, now, Modifier.verticalScroll(rememberScrollState()))
    }
}

/** The sheet's contents: a heading with the counts, then each agent. */
@Composable
fun AgentsList(agents: List<AgentInfo>, now: Long, modifier: Modifier = Modifier) {
    val c = Porthole.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val running = agents.count { it.state == "running" }
        Text("Agents", style = PortholeType.rowTitle, color = c.text)
        Text(
            if (running > 0) "$running at work · ${agents.size} in this session" else "${agents.size} in this session, none at work",
            style = PortholeType.meta, color = c.faint,
        )
        Spacer(Modifier.height(10.dp))
        if (agents.isEmpty()) {
            Text("This session has not started any agents.", style = PortholeType.secondary, color = c.muted)
        }
        for (a in orderAgents(agents)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = (16 * (a.depth - 1).coerceIn(0, 3)).dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.padding(top = 2.dp)) { AgentStateMark(a.state, c.surface) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(a.description.ifBlank { "An agent" }, style = PortholeType.secondary, fontWeight = FontWeight.SemiBold, color = c.text)
                    val kind = agentKind(a.type, a.model, a.background)
                    if (kind.isNotBlank()) Text(kind, style = PortholeType.meta, color = c.faint)
                    Text(
                        agentLine(a, now), style = PortholeType.meta,
                        color = if (a.state == "running") c.text else c.muted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Running first, then the rest; within each, newest first, with each agent's own agents after it. */
internal fun orderAgents(agents: List<AgentInfo>): List<AgentInfo> {
    val byParent = agents.groupBy { it.parent }
    val ids = agents.map { it.id }.toSet()
    val roots = agents.filter { it.parent.isBlank() || it.parent !in ids }
        .sortedWith(compareByDescending<AgentInfo> { it.state == "running" }.thenByDescending { it.startedMs })
    val out = mutableListOf<AgentInfo>()
    fun visit(a: AgentInfo, seen: MutableSet<String>) {
        if (!seen.add(a.id)) return
        out += a
        byParent[a.id].orEmpty().sortedByDescending { it.startedMs }.forEach { visit(it, seen) }
    }
    val seen = mutableSetOf<String>()
    roots.forEach { visit(it, seen) }
    return out
}
