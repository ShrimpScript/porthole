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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.net.SessionState
import dev.shrimpscript.porthole.net.TuiStatus
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * The session at a glance: model, permission mode, how full the context window is,
 * what it has cost in tokens, what tools it has used. All of it from the transcript;
 * the CLI's own /cost, /context and /status are one tap away for the rest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsSheet(
    title: String,
    state: SessionState,
    status: TuiStatus?,
    onCommand: (CliCommand) -> Unit,
    onDismiss: () -> Unit,
    /** Types a line into the session (a slash command with its argument). */
    onSend: (String) -> Unit = {},
    /** A named key for the CLI: "BTab" cycles the permission mode. */
    onKey: (String) -> Unit = {},
    /** This session's notifications are off. */
    muted: Boolean = false,
    onMuted: (Boolean) -> Unit = {},
    /** Claude Code is running in the session, so /rename can be typed into it. */
    canRename: Boolean = false,
    /** The feed's latest /context card, if any: the window by category. */
    context: dev.shrimpscript.porthole.net.Row? = null,
) {
    val c = Porthole.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = c.surface,
        contentColor = c.text,
        // The sheet is its own window and reaches under the status bar when fully
        // expanded, so the inset is applied here rather than inherited from the app root.
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
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RenameTitle(title, canRename) { name -> onSend("/rename $name") }

            // model + mode
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(modelShortName(state.model).ifBlank { "model unknown" })
                Chip(status?.permissionMode?.ifBlank { null } ?: permissionLabel(state.permissionMode))
            }

            // context window
            val window = contextWindow(state.model)
            Column {
                Row(Modifier.fillMaxWidth()) {
                    Text("Context window", style = PortholeType.secondary, color = c.muted, modifier = Modifier.weight(1f))
                    Text(
                        if (window > 0) "${formatTokens(state.lastContext)} of ${formatTokens(window)}" else formatTokens(state.lastContext),
                        style = PortholeType.secondary, color = c.text,
                    )
                }
                Spacer(Modifier.height(8.dp))
                val frac = if (window > 0) (state.lastContext.toFloat() / window).coerceIn(0f, 1f) else 0f
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(c.raised, PortholeShape.pill)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(frac.coerceAtLeast(if (state.lastContext > 0) 0.01f else 0f))
                            .height(8.dp)
                            .background(if (frac > 0.8f) c.warn else c.accent, PortholeShape.pill)
                    )
                }
                Text(
                    "The last request carried ${formatTokens(state.lastContext)} tokens: prompt, history, files, and cache.",
                    style = PortholeType.meta, color = c.faint, modifier = Modifier.padding(top = 6.dp),
                )
            }
            ContextSection(context, canRename) { onSend("/context") }

            // tokens
            Section("Tokens this session") {
                StatRow("Input", formatTokens(state.usage.input))
                StatRow("Output", formatTokens(state.usage.output))
                StatRow("Thinking", formatTokens(state.usage.thinking))
                StatRow("Cache read", formatTokens(state.usage.cacheRead))
                StatRow("Cache written", formatTokens(state.usage.cacheWrite))
            }

            // the CLI's own accounting, when it has written any
            if (state.costUsd > 0 || state.linesAdded > 0 || state.linesRemoved > 0) {
                Section("As Claude Code counts it") {
                    if (state.costUsd > 0) StatRow("API-equivalent cost", "$%.2f".format(state.costUsd))
                    if (state.apiMs > 0) StatRow("Time in API calls", "${state.apiMs / 1000}s")
                    StatRow("Lines added", state.linesAdded.toString())
                    StatRow("Lines removed", state.linesRemoved.toString())
                    Text(
                        "From the CLI's cost-state records. On a subscription this is what the same " +
                            "work would cost at API prices, not a bill.",
                        style = PortholeType.meta, color = c.faint, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            // activity
            val toolTotal = state.tools.values.sum()
            Section("Activity") {
                StatRow("Turns", state.turns.toString())
                StatRow("Your prompts", state.prompts.toString())
                StatRow("Replies", state.replies.toString())
                StatRow("Tool calls", toolTotal.toString())
                val top = state.tools.entries.sortedByDescending { it.value }.take(6)
                val max = top.firstOrNull()?.value ?: 1
                top.forEach { (name, n) ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(toolIcon(verbFor(name)), contentDescription = null, tint = c.muted, modifier = Modifier.padding(end = 8.dp).height(16.dp))
                        Text(name, style = PortholeType.meta, color = c.muted, modifier = Modifier.padding(end = 8.dp))
                        Box(
                            Modifier
                                .weight(1f)
                                .height(6.dp)
                                .background(c.raised, PortholeShape.pill)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(n.toFloat() / max)
                                    .height(6.dp)
                                    .background(c.accent, PortholeShape.pill)
                            )
                        }
                        Text(n.toString(), style = PortholeType.meta, color = c.text, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            // time
            val first = runCatching { OffsetDateTime.parse(state.firstTs) }.getOrNull()
            val last = runCatching { OffsetDateTime.parse(state.lastTs) }.getOrNull()
            if (first != null && last != null) {
                Section("Time") {
                    val zone = ZoneId.systemDefault()
                    StatRow("Started", "%tR, %<tb %<td".format(first.atZoneSameInstant(zone)))
                    StatRow("Last activity", clockTime(state.lastTs))
                    val d = Duration.between(first, last)
                    StatRow("Span", if (d.toHours() > 0) "${d.toHours()}h ${d.toMinutes() % 60}m" else "${d.toMinutes()}m")
                }
            }

            // the CLI's own numbers
            SwitchesSection(state, status, onSend, onKey)

            MuteSwitch(muted, onMuted)

            Section("Ask Claude Code") {
                Text(
                    "These open in the Terminal view, where the CLI prints its answer.",
                    style = PortholeType.meta, color = c.faint,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("/cost", "/status", "/usage").forEach { n ->
                        val cmd = CLI_COMMANDS.first { it.name == n }
                        Chip(n, accent = true) { onCommand(cmd) }
                    }
                }
            }
        }
    }
}

/**
 * Maps a tool name back to the verb phrase the feed uses, so the icon matches the rows.
 * The trailing space matters: toolIcon matches "Read " so that "Ready…" never does.
 */
private fun verbFor(tool: String): String = when (tool) {
    "Bash" -> "Ran "; "Read" -> "Read "; "Edit", "Write", "NotebookEdit" -> "Edited "
    "Grep", "Glob" -> "Searched "; "Task" -> "Delegated "; "WebFetch" -> "Fetched "
    "WebSearch" -> "Searched the web"; "TodoWrite" -> "Updated the plan"; "Skill" -> "Loaded skill"
    "Artifact" -> "Published"; "SendUserFile" -> "Sent a file"; else -> tool
}

/**
 * Per session, because "tell me about my agents" and "tell me about this one" are
 * different questions: a loop that finishes a turn every four minutes should not train
 * anyone to ignore the status bar. Its own composable so it can be drawn - and looked
 * at - without the modal sheet, which draws in a window of its own.
 */
@Composable
fun MuteSwitch(muted: Boolean, onMuted: (Boolean) -> Unit) {
    val c = Porthole.colors
    Section("Notifications") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (muted) "Muted" else "Tell me about this session",
                    style = PortholeType.secondary, color = c.text,
                )
                Text(
                    if (muted) "It still shows in the list and in Needs you \u2014 it just stays silent."
                    else "The working timer, the finished turn, and a question waiting on you.",
                    style = PortholeType.meta, color = c.faint,
                )
            }
            androidx.compose.material3.Switch(
                checked = !muted,
                onCheckedChange = { onMuted(!it) },
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = c.onAccent, checkedTrackColor = c.accent,
                    uncheckedThumbColor = c.muted, uncheckedTrackColor = c.raised,
                ),
            )
        }
    }
}

/**
 * The session's name, and a pencil that turns it into a field. Saving types Claude Code's
 * own /rename into the session, so the name is the CLI's - the same at the desk, in
 * `claude --resume`, and in this list. Its own composable so it can be drawn without the
 * modal sheet.
 */
@Composable
fun RenameTitle(title: String, canRename: Boolean, onRename: (String) -> Unit) {
    val c = Porthole.colors
    var editing by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (!editing) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = PortholeType.title, color = c.text, modifier = Modifier.weight(1f))
            if (canRename) {
                IconTarget(
                    androidx.compose.material.icons.Icons.Outlined.Edit, "Rename this session",
                    onClick = { editing = true }, tint = c.muted,
                )
            }
        }
        return
    }
    var name by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = androidx.compose.ui.text.input.TextFieldValue.Saver) {
        androidx.compose.runtime.mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(title, androidx.compose.ui.text.TextRange(0, title.length)))
    }
    val clean = cleanSessionName(name.text)
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.compose.foundation.text.BasicTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            textStyle = PortholeType.title.copy(color = c.text),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                if (clean.isNotEmpty()) { onRename(clean); editing = false }
            }),
            modifier = Modifier
                .fillMaxWidth()
                .background(c.raised, PortholeShape.card)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .focusRequester(focus)
                .semantics { contentDescription = "Session name" },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            Pill("Cancel", filled = false) { editing = false }
            Pill("Rename", filled = clean.isNotEmpty() && clean != title) {
                if (clean.isNotEmpty() && clean != title) onRename(clean)
                editing = false
            }
        }
    }
}

/** A name as /rename can take it: one line, no runs of spaces, not too long for a list row. */
fun cleanSessionName(raw: String): String {
    val s = raw.replace(Regex("\\s+"), " ").trim()
    // Counted in characters as a person sees them, so an emoji is never cut in half.
    return if (s.codePointCount(0, s.length) <= 80) s else s.substring(0, s.offsetByCodePoints(0, 80)).trimEnd()
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val c = Porthole.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.raised, PortholeShape.card)
            .padding(14.dp)
    ) {
        Text(title, style = PortholeType.meta, color = c.faint)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun StatRow(k: String, v: String) {
    val c = Porthole.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, style = PortholeType.secondary, color = c.muted, modifier = Modifier.weight(1f))
        Text(v, style = PortholeType.secondary, color = c.text)
    }
}

@Composable
fun Chip(text: String, accent: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = Porthole.colors
    Box(
        Modifier
            .background(if (accent) c.accent else c.raised, PortholeShape.pill)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text, style = PortholeType.meta, color = if (accent) c.onAccent else c.text)
    }
}


/**
 * The three things people change mid-session, as one-tap switches. Each tap types the
 * CLI's own command with its argument (`/model sonnet`, `/effort high`) or presses
 * Shift-Tab; the current value comes from the transcript (model) and the screen (effort,
 * permission mode), so the highlight moves when the CLI confirms, not when tapped.
 * Measured on 2.1.270: `/model` and `/effort` with an argument apply at once and the
 * CLI also saves them as the defaults for new sessions - said here, once.
 */
@Composable
fun SwitchesSection(state: SessionState?, status: TuiStatus?, onSend: (String) -> Unit, onKey: (String) -> Unit) {
    val c = Porthole.colors
    val model = modelShortName(state?.model.orEmpty())
    val effort = status?.effort.orEmpty()
    val effortDefault = status?.effortDefault.orEmpty()
    val mode = status?.permissionMode.orEmpty()
    Section("Switches") {
        SwitchRow("Model", model.ifBlank { "unknown" }) {
            listOf("Fable 5.1" to "fable", "Opus 5" to "opus", "Sonnet 5" to "sonnet", "Haiku 4.5" to "haiku").forEach { (label, alias) ->
                Chip(label, accent = label.substringBefore(' ') == model.substringBefore(' ')) { onSend("/model $alias") }
            }
        }
        Spacer(Modifier.height(10.dp))
        // The CLI draws its effort line only after a turn; the rest of the time all that is
        // known is the saved default, and the row says which of the two it is showing.
        SwitchRow("Effort", when {
            effort.isNotBlank() -> effort
            effortDefault.isNotBlank() -> "$effortDefault (the saved default; this session's level shows after its next turn)"
            else -> "not shown"
        }) {
            EFFORT_LEVELS.forEach { level ->
                Chip(level, accent = level == effort) { onSend("/effort $level") }
            }
        }
        Spacer(Modifier.height(10.dp))
        SwitchRow("Permissions", mode.ifBlank { "not shown" }) {
            Chip("Cycle (Shift-Tab)", accent = false) { onKey("BTab") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Model and effort also become the defaults for new sessions; the CLI saves them. Max effort is the exception: it lasts this session only.",
            style = PortholeType.meta, color = c.faint,
        )
    }
}

@Composable
private fun SwitchRow(name: String, current: String, chips: @Composable () -> Unit) {
    val c = Porthole.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = PortholeType.secondary, color = c.muted, modifier = Modifier.width(96.dp))
        Text(current, style = PortholeType.body, color = c.text)
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
}

/**
 * What the window is made of, from the feed's latest /context card, and a way to measure
 * again: the CLI writes its table to the transcript, so the answer lands as a card in the
 * feed and here, with no trip to the terminal.
 */
@Composable
fun ContextSection(context: dev.shrimpscript.porthole.net.Row?, canMeasure: Boolean, onMeasure: () -> Unit) {
    val c = Porthole.colors
    val usage = context?.command?.context
    Section("By category") {
        if (usage != null) {
            ContextBreakdown(usage)
            val t = clockTime(context.ts)
            if (t.isNotEmpty()) Text("From /context at $t.", style = PortholeType.meta, color = c.faint, modifier = Modifier.padding(top = 6.dp))
        } else {
            Text("Run /context to see what fills the window.", style = PortholeType.meta, color = c.faint)
        }
        if (canMeasure) {
            Spacer(Modifier.height(8.dp))
            Chip(if (usage != null) "Measure again" else "Run /context", accent = true) { onMeasure() }
        }
    }
}
