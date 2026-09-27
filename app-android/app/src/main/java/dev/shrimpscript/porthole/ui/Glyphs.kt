package dev.shrimpscript.porthole.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

/*
 * Icons and glyphs used in the feed. Tool rows carry a verb, not a tool name (the daemon
 * speaks the user's language), so the icon is chosen from the verb. Unknown verbs get a
 * neutral wrench rather than nothing.
 */

fun toolIcon(text: String): ImageVector = when {
    text.startsWith("Ran ") || text == "Ran" -> Icons.Outlined.Terminal
    text.startsWith("Read ") -> Icons.Outlined.Description
    text.startsWith("Edited ") || text.startsWith("Wrote ") -> Icons.Outlined.Edit
    text.startsWith("Searched the web") || text.startsWith("Fetched ") -> Icons.Outlined.Language
    text.startsWith("Searched ") || text.startsWith("Globbed ") -> Icons.Outlined.Search
    text.startsWith("Delegated ") -> Icons.Outlined.AccountTree
    text.startsWith("Updated the plan") -> Icons.Outlined.Checklist
    text.startsWith("Loaded skill") -> Icons.Outlined.Extension
    text.startsWith("Published") -> Icons.Outlined.Public
    text.startsWith("Sent a file") -> Icons.Outlined.AttachFile
    else -> Icons.Outlined.Build
}

/** The CLI's own spinner frames, in its order. */
val SPINNER_FRAMES = listOf("✻", "✽", "✶", "✳", "✢", "·")

/** "claude-fable-5-1" -> "Fable 5.1". Unknown ids are shown as they are. */
fun modelShortName(id: String): String {
    if (id.isBlank()) return ""
    val parts = id.removePrefix("claude-").split('-').filter { it.isNotBlank() }
    if (parts.isEmpty()) return id
    val family = parts[0].replaceFirstChar { it.uppercase() }
    val version = parts.drop(1).filter { it.all(Char::isDigit) && it.length <= 2 }.joinToString(".")
    return if (version.isBlank()) family else "$family $version"
}

/** Context window per model family, from the models overview (verified 2026-09-11). */
fun contextWindow(modelId: String): Long = when {
    modelId.contains("haiku-4-5") -> 200_000L
    modelId.contains("haiku") -> 200_000L
    modelId.isBlank() -> 0L
    else -> 1_000_000L
}

fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "%.0fk".format(n / 1_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}

/** "bypassPermissions" -> "bypass permissions"; the TUI's own text wins when present. */
fun permissionLabel(mode: String): String = when (mode) {
    "bypassPermissions" -> "bypass permissions"
    "acceptEdits" -> "accept edits"
    "plan" -> "plan mode"
    "default", "" -> "default permissions"
    else -> mode
}
