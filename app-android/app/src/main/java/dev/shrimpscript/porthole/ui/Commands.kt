package dev.shrimpscript.porthole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType

/**
 * Claude Code's built-in slash commands, as a phone can use them. Typed into the session's
 * tmux window exactly as a person would type them. Commands whose answer appears on the
 * CLI's own screen (or that open a picker there) switch the app to the Terminal view so the
 * answer is actually visible.
 *
 * List verified against the Claude Code commands reference, 2026-09-11.
 */
data class CliCommand(
    val name: String,
    val summary: String,
    /** A hint for the argument; the command is placed in the composer to finish typing. */
    val arg: String? = null,
    /** True when the result shows in the CLI's screen rather than in the transcript. */
    val terminal: Boolean = false,
    /** Bare, it opens the session sheet, which has the same switches and numbers natively. */
    val sheet: Boolean = false,
)

val CLI_COMMANDS = listOf(
    CliCommand("/model", "Switch the model", arg = "opus · sonnet · haiku · fable", sheet = true),
    CliCommand("/effort", "Set reasoning effort", arg = "low · medium · high · xhigh · max", sheet = true),
    CliCommand("/fast", "Toggle fast mode"),
    CliCommand("/compact", "Compress the conversation", arg = "optional focus"),
    CliCommand("/clear", "Start a fresh conversation"),
    CliCommand("/cost", "Token usage and cost", terminal = true, sheet = true),
    // The CLI writes /context's table to the transcript, so it answers in the feed.
    CliCommand("/context", "What is in the context window"),
    CliCommand("/status", "Session status", terminal = true, sheet = true),
    CliCommand("/usage", "Plan usage and limits", terminal = true),
    CliCommand("/stats", "Session statistics", terminal = true, sheet = true),
    CliCommand("/permissions", "Edit tool permissions", terminal = true),
    CliCommand("/resume", "Pick a past conversation", terminal = true),
    CliCommand("/rewind", "Undo recent changes", terminal = true),
    CliCommand("/export", "Export the conversation", terminal = true),
    CliCommand("/memory", "Edit memory files", terminal = true),
    CliCommand("/config", "Claude Code settings", terminal = true),
    CliCommand("/mcp", "MCP servers", terminal = true),
    CliCommand("/skills", "Skills", terminal = true),
    CliCommand("/rate-limit-options", "Wait and continue after a usage limit", terminal = true),
    CliCommand("/usage-credits", "Extra usage beyond the plan", terminal = true),
    CliCommand("/doctor", "Check the install", terminal = true),
    CliCommand("/help", "All commands", terminal = true),
)

/** A bare command (no argument) that the session sheet answers: "/model", "/cost". */
fun opensSheet(text: String): Boolean = text.trim().let { t -> CLI_COMMANDS.any { it.sheet && it.name == t } }

/** Commands matching what has been typed so far ("/mo" -> /model). */
fun matchingCommands(draft: String): List<CliCommand> {
    if (!draft.startsWith("/")) return emptyList()
    val typed = draft.substringBefore(' ').lowercase()
    if (draft.contains(' ')) return emptyList() // an argument is being typed; stop suggesting
    return CLI_COMMANDS.filter { it.name.startsWith(typed) }
}

/**
 * The suggestion panel above the composer. Tapping a command with an argument places it
 * in the composer to finish; one without is sent at once.
 */
@Composable
fun CommandSuggestions(
    commands: List<CliCommand>,
    modifier: Modifier = Modifier,
    onPick: (CliCommand) -> Unit,
) {
    val c = Porthole.colors
    if (commands.isEmpty()) return
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(c.surface, PortholeShape.card)
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        commands.forEach { cmd ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(cmd) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(cmd.name, style = PortholeType.mono, color = c.accent)
                Column(Modifier.weight(1f)) {
                    Text(cmd.summary, style = PortholeType.secondary, color = c.text)
                    if (cmd.arg != null) Text(cmd.arg, style = PortholeType.meta, color = c.faint)
                }
                if (cmd.terminal && !cmd.sheet) {
                    Icon(
                        Icons.Outlined.Terminal, contentDescription = "Shows in the terminal",
                        tint = c.faint, modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}
