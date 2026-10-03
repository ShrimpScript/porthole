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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.net.CommandInfo
import dev.shrimpscript.porthole.net.Compaction
import dev.shrimpscript.porthole.net.ContextUsage
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType
import dev.shrimpscript.porthole.net.Row as FeedRow

/** The CLI's effort levels, lowest first. A level outside these (ultracode) shows without the meter. */
val EFFORT_LEVELS = listOf("low", "medium", "high", "xhigh", "max")

/**
 * A slash command run in the session, drawn as what it did rather than as the CLI's echo:
 * the effort level on a meter, the model by name, /context as a breakdown of the window.
 * A compaction or a /clear is a divider across the feed, since the conversation before it
 * is no longer what Claude sees.
 */
@Composable
fun CommandCard(r: FeedRow, cmd: CommandInfo, onOpen: () -> Unit) {
    val c = Porthole.colors
    if (cmd.compact != null) return CommandDivider(compactLine(cmd.compact), r.ts)
    if (cmd.name == "clear") return CommandDivider("Conversation cleared", r.ts)
    if (cmd.auto) return AutoSkill(cmd)
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, PortholeShape.card)
            .border(1.dp, c.edge, PortholeShape.card)
            .clickable(enabled = r.detail.isNotBlank(), onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The command as it was typed.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("/${cmd.name}", style = PortholeType.mono, color = c.accent)
            // The arguments take the width up to the clock; without any, a spacer does.
            if (cmd.args.isNotBlank()) {
                Text(
                    " ${cmd.args}", style = PortholeType.mono, color = c.muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            val t = clockTime(r.ts)
            if (t.isNotEmpty()) Text(t, style = PortholeType.meta, color = c.faint, modifier = Modifier.padding(start = 8.dp))
        }
        when {
            cmd.name == "effort" && cmd.value.isNotBlank() -> EffortBody(cmd)
            cmd.name == "model" && cmd.value.isNotBlank() -> ValueBody(
                cmd.value,
                when {
                    cmd.saved -> "Also the default for new sessions"
                    cmd.output.startsWith("Kept") -> "Unchanged"
                    else -> ""
                },
            )
            cmd.name == "rename" && cmd.value.isNotBlank() -> ValueBody(cmd.value, "The session's new name")
            cmd.context != null -> ContextBreakdown(cmd.context)
            else -> OutputBody(cmd)
        }
    }
}

@Composable
private fun EffortBody(cmd: CommandInfo) {
    val c = Porthole.colors
    val level = EFFORT_LEVELS.indexOf(cmd.value)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(cmd.value, style = PortholeType.rowTitle, color = c.text)
        if (level >= 0) EffortMeter(level, Modifier.weight(1f))
    }
    val scope = when {
        cmd.saved -> "Also the default for new sessions"
        cmd.output.contains("this session only") -> "This session only"
        else -> ""
    }
    if (scope.isNotEmpty()) Text(scope, style = PortholeType.meta, color = c.faint)
    // "Set effort level to high (...): Comprehensive implementation with ..." - the part
    // after the colon is the CLI's own description of the level.
    val about = cmd.output.substringAfter("): ", "").trim()
    if (about.isNotEmpty()) {
        Text(about, style = PortholeType.secondary, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Five steps, low to max, lit up to the level set. */
@Composable
fun EffortMeter(level: Int, modifier: Modifier = Modifier) {
    val c = Porthole.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        EFFORT_LEVELS.indices.forEach { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .background(if (i <= level) c.accent else c.raised, PortholeShape.pill)
            )
        }
    }
}

@Composable
private fun ValueBody(value: String, line: String) {
    val c = Porthole.colors
    Text(value, style = PortholeType.rowTitle, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (line.isNotEmpty()) Text(line, style = PortholeType.meta, color = c.faint)
}

@Composable
private fun OutputBody(cmd: CommandInfo) {
    val c = Porthole.colors
    if (cmd.skill) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.Extension, contentDescription = null, tint = c.muted, modifier = Modifier.size(15.dp))
            Text("Skill loaded", style = PortholeType.meta, color = c.muted)
        }
    }
    if (cmd.output.isNotBlank()) {
        Text(
            cmd.output, style = PortholeType.secondary, color = if (cmd.error) c.bad else c.muted,
            maxLines = 4, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A skill the CLI loaded on its own, for a mode: a quiet line, since nobody typed it. */
@Composable
private fun AutoSkill(cmd: CommandInfo) {
    val c = Porthole.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Extension, contentDescription = null, tint = c.faint, modifier = Modifier.size(15.dp))
        Text("Claude Code loaded the ${cmd.name} skill", style = PortholeType.meta, color = c.faint)
    }
}

/**
 * The context window to scale - what is in it, the share held back for autocompact, the
 * rest free - and what it is made of, largest first. Tools the CLI lists as deferred are
 * not in the window until used, so they are named apart and not drawn.
 */
@Composable
fun ContextBreakdown(ctx: ContextUsage, modifier: Modifier = Modifier) {
    val c = Porthole.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (ctx.total > 0) "${formatTokens(ctx.used)} of ${formatTokens(ctx.total)}" else formatTokens(ctx.used),
                style = PortholeType.rowTitle, color = c.text,
            )
            Text(" tokens", style = PortholeType.secondary, color = c.muted, modifier = Modifier.padding(bottom = 1.dp))
            Spacer(Modifier.weight(1f))
            val model = modelShortName(ctx.model)
            val pct = if (ctx.total > 0) "${Math.round(ctx.used * 100.0 / ctx.total)}%" else ""
            Text(listOf(pct, model).filter { it.isNotBlank() }.joinToString(" · "), style = PortholeType.meta, color = c.faint)
        }
        if (ctx.total > 0) {
            val buffer = ctx.parts.filter { it.kind == "buffer" }.sumOf { it.tokens }
            val used = (ctx.used.toFloat() / ctx.total).coerceIn(0f, 1f)
            val held = (buffer.toFloat() / ctx.total).coerceIn(0f, 1f - used)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(c.raised, PortholeShape.pill)
            ) {
                // The sliver a nearly empty window really is, still visible.
                if (used > 0f) Box(Modifier.weight(used.coerceAtLeast(0.008f)).height(8.dp).background(c.accent, PortholeShape.pill))
                val free = 1f - used.coerceAtLeast(0.008f) - held
                if (free > 0f) Spacer(Modifier.weight(free))
                if (held > 0f) Box(Modifier.weight(held).height(8.dp).background(c.warn.copy(alpha = 0.45f), PortholeShape.pill))
            }
            if (buffer > 0) {
                Text(
                    "${formatTokens(buffer)} at the end is held back for autocompact.",
                    style = PortholeType.meta, color = c.faint,
                )
            }
        }
        val inWindow = ctx.parts.filter { it.kind.isEmpty() && it.tokens > 0 }.sortedByDescending { it.tokens }
        val top = inWindow.firstOrNull()?.tokens ?: 0L
        inWindow.take(7).forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.name, style = PortholeType.meta, color = c.muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(150.dp),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(5.dp)
                        .background(c.raised, PortholeShape.pill)
                ) {
                    if (top > 0) Box(
                        Modifier
                            .fillMaxWidth((p.tokens.toFloat() / top).coerceIn(0.02f, 1f))
                            .height(5.dp)
                            .background(c.accent.copy(alpha = 0.7f), PortholeShape.pill)
                    )
                }
                Text(
                    formatTokens(p.tokens), style = PortholeType.meta, color = c.text,
                    modifier = Modifier.width(52.dp).padding(start = 8.dp),
                )
            }
        }
        val deferred = ctx.parts.filter { it.kind == "deferred" && it.tokens > 0 }
        if (deferred.isNotEmpty()) {
            Text(
                "Loaded only when used: " + deferred.joinToString(" · ") { "${it.name} ${formatTokens(it.tokens)}" },
                style = PortholeType.meta, color = c.faint,
            )
        }
    }
}

/** "Compacted automatically · 970k → 13.4k tokens". */
fun compactLine(cp: Compaction): String {
    val head = if (cp.trigger == "auto") "Compacted automatically" else "Compacted"
    return if (cp.before > 0 && cp.after > 0) "$head · ${formatTokens(cp.before)} → ${formatTokens(cp.after)} tokens" else head
}

/** A rule across the feed: what came before it is no longer what Claude sees. */
@Composable
private fun CommandDivider(text: String, ts: String) {
    val c = Porthole.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(c.edge))
        val t = clockTime(ts)
        Text(if (t.isNotEmpty()) "$text · $t" else text, style = PortholeType.meta, color = c.muted)
        Box(Modifier.weight(1f).height(1.dp).background(c.edge))
    }
}
