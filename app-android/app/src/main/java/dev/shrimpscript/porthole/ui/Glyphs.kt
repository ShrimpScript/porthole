package dev.shrimpscript.porthole.ui

import androidx.compose.ui.graphics.vector.ImageVector

/*
 * Icons and glyphs used in the feed. Tool rows carry a verb, not a tool name (the daemon
 * speaks the user's language), so the icon is chosen from the verb. Unknown verbs get a
 * neutral wrench rather than nothing.
 */

fun toolIcon(text: String): ImageVector = when {
    text.startsWith("Ran ") || text == "Ran" -> Symbols.Terminal
    text.startsWith("Read ") -> Symbols.Description
    text.startsWith("Edited ") || text.startsWith("Wrote ") -> Symbols.Edit
    text.startsWith("Searched the web") || text.startsWith("Fetched ") -> Symbols.Language
    text.startsWith("Searched ") || text.startsWith("Globbed ") -> Symbols.Search
    text.startsWith("Delegated ") -> Symbols.AccountTree
    text.startsWith("Updated the plan") -> Symbols.Checklist
    text.startsWith("Loaded skill") -> Symbols.Extension
    text.startsWith("Published") -> Symbols.Public
    text.startsWith("Sent a file") -> Symbols.AttachFile
    else -> Symbols.Build
}

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
