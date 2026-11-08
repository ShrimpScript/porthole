package dev.shrimpscript.porthole.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.net.Inbox
import dev.shrimpscript.porthole.net.InboxAnswered
import dev.shrimpscript.porthole.net.InboxItem
import dev.shrimpscript.porthole.net.InboxProject
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.motionEnabled
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType
import kotlinx.coroutines.delay

/** How long a tapped answer waits, with Undo, before it is written to the project. */
const val INBOX_UNDO_MS = 4_000

/** An answer tapped and not yet sent, to one question in one project on one computer. */
data class PendingAnswer(val machineId: String, val dir: String, val item: String, val text: String) {
    val key: String get() = inboxKey(machineId, dir, item)
}

/** Names a question across every computer and project: ids are only unique within a project. */
fun inboxKey(machineId: String, dir: String, item: String) = "$machineId\u0000$dir\u0000$item"

/** The project and question a pending answer is for, while the inbox still has them. */
fun Inbox.find(pa: PendingAnswer): Pair<InboxProject, InboxItem>? {
    val p = projects.firstOrNull { it.machineId == pa.machineId && it.dir == pa.dir } ?: return null
    return p.items.firstOrNull { it.id == pa.item }?.let { p to it }
}

/**
 * The questions agents left waiting on the person, across every project the computer reads,
 * newest first. A choice is one tap, held a moment with Undo before it is written; anything
 * else is answered in words. Answers go to the project's decisions file, and to a Claude Code
 * session running there.
 */
@Composable
fun InboxScreen(
    inbox: Inbox?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAnswer: (InboxProject, InboxItem, String) -> Unit,
    onDismiss: (InboxProject, InboxItem) -> Unit,
    /** How the last answer went; shown under the header until the next one. */
    answered: InboxAnswered? = null,
    /** A tapped answer still inside its Undo window; held above the screen so it outlives a recomposition. */
    pending: PendingAnswer? = null,
    onPending: (PendingAnswer?) -> Unit = {},
    /** Answers handed to the computer and not yet confirmed, by [inboxKey]. */
    sending: Map<String, String> = emptyMap(),
    /** An answer that could not be handed over: the computer is not connected. */
    failed: String? = null,
) = Screen {
    val c = Porthole.colors
    fun commit(pa: PendingAnswer) { inbox?.find(pa)?.let { (p, it) -> onAnswer(p, it, pa.text) } }
    // The Undo window runs here, not in the card: a card scrolled out of view leaves the
    // list's composition, and its timer would stop with it.
    val left = remember(pending) { Animatable(1f) }
    val motion = motionEnabled()
    LaunchedEffect(pending) {
        val pa = pending ?: return@LaunchedEffect
        if (motion) left.animateTo(0f, tween(INBOX_UNDO_MS, easing = LinearEasing))
        else delay(INBOX_UNDO_MS.toLong())
        commit(pa)
        onPending(null)
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTarget(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
            Column(Modifier.weight(1f)) {
                Text("Inbox", style = PortholeType.title, color = c.text)
                if (inbox != null && inbox.count > 0) {
                    Text(
                        "${inbox.count} waiting on you · ${inbox.projects.size} project${if (inbox.projects.size == 1) "" else "s"}",
                        style = PortholeType.meta, color = c.faint,
                    )
                }
            }
            IconTarget(Icons.Outlined.Refresh, "Read the notes again", onRefresh)
        }
        if (failed != null) {
            Text(failed, style = PortholeType.secondary, color = c.bad, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        } else if (answered != null) AnsweredNote(answered)
        when {
            inbox == null -> InboxEmpty("Reading the notes…", null)
            !inbox.configured -> InboxSetup()
            inbox.count == 0 -> InboxEmpty(
                "Nothing is waiting on you.",
                "Questions appear here when a project's notes say they are waiting on you.",
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                inbox.projects.forEach { p ->
                    item(key = "p-${p.machineId}/${p.dir}") { ProjectLabel(p) }
                    items(p.items, key = { inboxKey(p.machineId, p.dir, it.id) }) { it ->
                        val key = inboxKey(p.machineId, p.dir, it.id)
                        InboxCard(
                            item = it,
                            pending = pending?.takeIf { pa -> pa.key == key },
                            progress = left.value,
                            sending = sending[key],
                            onPick = { text ->
                                // Another card's answer is not dropped by this tap: it goes now.
                                pending?.takeIf { pa -> pa.key != key }?.let { pa -> commit(pa) }
                                onPending(PendingAnswer(p.machineId, p.dir, it.id, text))
                            },
                            onUndo = { onPending(null) },
                            onDismiss = { onDismiss(p, it) },
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ProjectLabel(p: InboxProject) {
    val c = Porthole.colors
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(p.name, style = PortholeType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        val extra = listOfNotNull(p.machine.ifBlank { null }, if (p.live) "session running" else null)
        if (extra.isNotEmpty()) {
            Text(" · " + extra.joinToString(" · "), style = PortholeType.meta, color = if (p.live) c.accent else c.faint, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InboxCard(
    item: InboxItem,
    pending: PendingAnswer?,
    progress: Float,
    sending: String?,
    onPick: (String) -> Unit,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Porthole.colors
    var expanded by remember(item.id) { mutableStateOf(false) }
    var replying by remember(item.id) { mutableStateOf(false) }
    var draft by remember(item.id) { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(c.raised, PortholeShape.card)
            .border(1.dp, if (pending != null) c.accent else c.edge, PortholeShape.card)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            item.asked,
            style = PortholeType.body, color = c.text,
            maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClickLabel = if (expanded) "Show less" else "Show all") { expanded = !expanded },
        )
        if (item.suggested.isNotBlank() && item.options.none { it == item.suggested }) {
            Text("Suggested: ${item.suggested}", style = PortholeType.secondary, color = c.muted)
        }
        when {
            sending != null -> Text("Sending: $sending", style = PortholeType.body, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            pending != null -> PendingRow(pending, progress, onUndo = onUndo)
            replying -> ReplyBox(
                draft, { draft = it },
                onSend = { if (draft.isNotBlank()) { onPick(draft.trim()); replying = false; draft = "" } },
                onCancel = { replying = false },
            )
            else -> {
                if (item.options.isNotEmpty() || item.suggested.isNotBlank()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item.options.forEach { o -> Choice(o, suggested = o == item.suggested) { onPick(o) } }
                        if (item.options.isEmpty()) Choice("Use the suggestion", suggested = true) { onPick(item.suggested) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOf(relativeTime(item.since), "${item.file}:${item.line}").filter { it.isNotBlank() }.joinToString(" · "),
                        style = PortholeType.meta, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextAction(if (item.options.isEmpty()) "Answer" else "Other", c.accent) { replying = true }
                    TextAction("Put aside", c.muted, onDismiss)
                }
            }
        }
    }
}

/** One choice from the note: a single tap answers (with a moment to undo). */
@Composable
private fun Choice(text: String, suggested: Boolean, onClick: () -> Unit) {
    val c = Porthole.colors
    Text(
        text,
        style = PortholeType.body, color = c.text,
        modifier = Modifier
            .clip(PortholeShape.control)
            .background(c.surface)
            .border(1.dp, if (suggested) c.accent.copy(alpha = 0.6f) else c.edge, PortholeShape.control)
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .semantics { contentDescription = "Answer: $text" + if (suggested) ", suggested" else "" },
    )
}

@Composable
private fun TextAction(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier
            .defaultMinSize(minHeight = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PortholeType.meta, color = color)
    }
}

/** The answer, waiting out its Undo window; it is sent when the bar runs out. */
@Composable
private fun PendingRow(p: PendingAnswer, left: Float, onUndo: () -> Unit) {
    val c = Porthole.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Answering: ${p.text}", style = PortholeType.body, color = c.accent, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Pill("Undo", filled = false, onClick = onUndo)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(c.edge)) {
            Box(Modifier.fillMaxWidth(left).fillMaxHeight().background(c.accent))
        }
    }
}

@Composable
private fun ReplyBox(value: String, onChange: (String) -> Unit, onSend: () -> Unit, onCancel: () -> Unit) {
    val c = Porthole.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .background(c.surface, PortholeShape.control)
                .border(1.dp, c.edge, PortholeShape.control)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (value.isEmpty()) Text("Your answer", style = PortholeType.body, color = c.faint)
            BasicTextField(
                value = value, onValueChange = onChange,
                textStyle = PortholeType.body.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Your answer" },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Cancel", filled = false, onClick = onCancel)
            Pill("Answer", filled = value.isNotBlank(), onClick = onSend)
        }
    }
}

@Composable
private fun AnsweredNote(a: InboxAnswered) {
    val c = Porthole.colors
    val ok = a.error.isBlank()
    Text(
        when {
            !ok -> "Not recorded: ${a.error}"
            a.session.isNotBlank() -> "Recorded in ${a.decisions}, and told ${a.session}."
            else -> "Recorded in ${a.decisions}."
        },
        style = PortholeType.secondary, color = if (ok) c.muted else c.bad,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun InboxEmpty(title: String, body: String?) {
    val c = Porthole.colors
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = PortholeType.rowTitle, color = c.text)
        if (body != null) Text(body, style = PortholeType.secondary, color = c.muted)
    }
}

@Composable
private fun InboxSetup() {
    val c = Porthole.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Which folders hold your projects?", style = PortholeType.rowTitle, color = c.text)
        Text(
            "The inbox reads each project's notes (.claude/progress.md, handovers, backlogs, roadmaps) " +
                "for a \"Waiting on you\" section. Tell the computer where your projects are:",
            style = PortholeType.secondary, color = c.muted,
        )
        CommandBlock("portholed inbox add ~/code")
        Text("If older notes call you by name (\"Waiting on Sam\"):", style = PortholeType.secondary, color = c.muted)
        CommandBlock("portholed inbox name Sam")
        Text(
            "Answers are written to each project's DECISIONS.md, and nothing else in a project is changed.",
            style = PortholeType.secondary, color = c.faint,
        )
    }
}
