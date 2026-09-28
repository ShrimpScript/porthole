package dev.shrimpscript.porthole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType

/**
 * @ mentions: a file named with "@" in a prompt is read by Claude Code along with it. The
 * CLI completes them at the desk; on the phone the daemon lists the session's files and
 * the composer offers them as the name is typed.
 */

/**
 * What has been typed after an "@" at the end of the draft, or null when the draft does
 * not end in a mention being typed. An "@" counts only at the start of a word, so an
 * email address is not a mention.
 */
fun trailingMention(draft: String): String? {
    val start = draft.lastIndexOfAny(charArrayOf(' ', '\t', '\n')) + 1
    val word = draft.substring(start)
    if (!word.startsWith("@")) return null
    return word.substring(1).takeIf { '@' !in it }
}

/**
 * The draft with its trailing mention replaced by [path], and a space to type on after.
 * The path goes in as it is, spaces too: that is what the CLI's own completion inserts
 * (measured on 2.1.283), so a message from the phone reads the same as one from the desk.
 */
fun insertMention(draft: String, path: String): String {
    val query = trailingMention(draft) ?: return draft
    return draft.dropLast(query.length + 1) + "@" + path + " "
}

/**
 * The file list above the composer while a mention is being typed. Each row is the
 * file's name, with its folder beneath it; a tap puts the path in the draft.
 */
@Composable
fun FileSuggestions(
    files: List<String>,
    query: String,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
) {
    val c = Porthole.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(c.surface, PortholeShape.card)
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        if (files.isEmpty()) {
            Text(
                if (query.isEmpty()) "No files in this folder" else "No files match “$query”",
                style = PortholeType.secondary, color = c.muted,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        files.forEach { path ->
            val name = path.substringAfterLast('/')
            val folder = path.substringBeforeLast('/', "")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(path) }
                    .semantics { contentDescription = path }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.Description, contentDescription = null, tint = c.faint, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = PortholeType.mono, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (folder.isNotEmpty()) {
                        // The start of a deep path matters least: cut there, not at the end.
                        Text(
                            folder, style = PortholeType.meta, color = c.faint, maxLines = 1,
                            overflow = TextOverflow.StartEllipsis,
                        )
                    }
                }
            }
        }
    }
}
