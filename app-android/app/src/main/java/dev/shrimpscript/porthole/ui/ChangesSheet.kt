package dev.shrimpscript.porthole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shrimpscript.porthole.net.ChangedFile
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType
import dev.shrimpscript.porthole.net.ChangesState

/**
 * What the agent changed, as git sees it in the session's directory: the file list with
 * counts, and each file's diff against the last commit, rendered line by line. Every
 * byte comes from `git status` and `git diff` on the computer; nothing is summarised or
 * inferred here. Refreshed when opened and on the Refresh pill, not on a timer - a diff
 * that redraws under the reader's thumb is worse than one a few seconds old.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangesSheet(state: ChangesState?, connected: Boolean, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val c = Porthole.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var openPath by remember { mutableStateOf<String?>(null) }
    // Silence is shown as silence: after a while without an answer the sheet says so
    // rather than keep a pending line up for good.
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        waited = false
        if (state == null) { kotlinx.coroutines.delay(15_000); waited = true }
    }
    LaunchedEffect(Unit) { if (connected) onRefresh() }
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
                    .width(36.dp)
                    .height(4.dp)
                    .background(c.edge, PortholeShape.pill)
            )
        },
    ) {
        ChangesContent(state, connected, waited, openPath, onOpen = { openPath = it }, onRefresh = onRefresh)
    }
}

/** The sheet's body, separate from the sheet so it can be rendered and tested on its own. */
@Composable
fun ChangesContent(
    state: ChangesState?, connected: Boolean, waited: Boolean, openPath: String?,
    onOpen: (String?) -> Unit, onRefresh: () -> Unit,
) {
    val c = Porthole.colors
    run {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            // The open file follows a Refresh: the newest diff for that path, not the one tapped.
            val file = openPath?.let { p -> state?.files?.firstOrNull { it.path == p } }
            if (file != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconTarget(Icons.AutoMirrored.Outlined.ArrowBack, "Back to the file list", { onOpen(null) })
                    Text(file.path, style = PortholeType.mono, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Counts(file.added, file.removed)
                }
                when {
                    file.error.isNotBlank() -> Note(file.error)
                    file.binary -> Note("Binary file; nothing to show as text.")
                    file.diff.isBlank() && !file.truncated -> Note("No text difference (a mode change, or an empty file).")
                    file.diff.isBlank() -> Note("Too large to send alongside the others; open the file at the computer.")
                    else -> DiffView(file.diff, file.truncated)
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Changes", style = Porthole.type.display, color = c.text)
                        Text(
                            when {
                                !connected -> "Not connected"
                                state == null && waited -> "No answer from the computer"
                                state == null -> "Asking the computer…"
                                state.error.isNotBlank() -> state.error
                                state.notRepo -> "Not a git repository"
                                state.files.isEmpty() -> "Working tree clean" + if (state.branch.isNotBlank()) " · ${state.branch}" else ""
                                else -> "${state.files.size} file${if (state.files.size == 1) "" else "s"}" +
                                    (if (state.branch.isNotBlank()) " · ${state.branch}" else "") + " · uncommitted"
                            },
                            style = PortholeType.secondary, color = c.muted,
                        )
                    }
                    if (state != null && state.error.isBlank() && !state.notRepo && state.files.isNotEmpty()) Counts(state.added, state.removed)
                    Pill("Refresh", filled = false) { if (connected) onRefresh() }
                }
                if (state != null && state.truncated) Note("Only the first ${state.files.size} files are listed.")
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state?.files.orEmpty(), key = { it.path }) { f ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(f.path) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            StatusMark(f.status)
                            Text(
                                if (f.from.isNotBlank()) "${f.from} → ${f.path}" else f.path, style = PortholeType.mono, color = c.text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            when {
                                f.error.isNotBlank() -> Text("failed", style = PortholeType.meta, color = c.bad)
                                f.binary -> Text("binary", style = PortholeType.meta, color = c.faint)
                                else -> Counts(f.added, f.removed)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = PortholeType.secondary, color = Porthole.colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}

/** git's two status letters, read as a person would: added, modified, deleted, renamed, untracked. */
@Composable
private fun StatusMark(status: String) {
    val c = Porthole.colors
    val (label, colour) = when {
        status == "??" -> "new" to c.ok
        status.contains('D') -> "deleted" to c.bad
        status.contains('A') -> "added" to c.ok
        status.contains('R') -> "renamed" to c.warn
        status.contains('C') -> "copied" to c.warn
        status.contains('U') -> "conflict" to c.bad
        else -> "modified" to c.accent
    }
    Text(label, style = PortholeType.meta, color = colour, modifier = Modifier.width(56.dp))
}

@Composable
private fun Counts(added: Int, removed: Int) {
    val c = Porthole.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("+$added", style = PortholeType.mono, color = c.ok)
        Text("−$removed", style = PortholeType.mono, color = c.bad)
    }
}

/**
 * A unified diff, one row per line. Hunk headers muted, additions and removals tinted
 * with the semantic colours; long lines scroll sideways rather than wrap, so indentation
 * in code stays readable.
 */
@Composable
private fun DiffView(diff: String, truncated: Boolean) {
    val c = Porthole.colors
    val lines = remember(diff) { diff.split('\n').dropWhile { it.startsWith("diff --git") || it.startsWith("index ") || it.startsWith("new file") || it.startsWith("deleted file") || it.startsWith("similarity") || it.startsWith("rename ") } }
    val scroll = rememberScrollState()
    val mono = PortholeType.mono.copy(fontSize = 12.sp, lineHeight = 17.sp)
    // Rows share one width, the widest line's: a lazy list scrolling sideways would
    // otherwise size every row to its own text and the tint would stop short.
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val rowWidth = remember(lines, density) {
        val widest = lines.maxByOrNull { it.length } ?: ""
        val px = measurer.measure(androidx.compose.ui.text.AnnotatedString(widest.ifEmpty { " " }), style = mono, softWrap = false).size.width
        with(density) { (px.toDp() + 32.dp) }.coerceAtLeast(screen.dp)
    }
    LazyColumn(Modifier.fillMaxSize().horizontalScroll(scroll)) {
        itemsIndexed(lines) { i, line ->
            val (bg, ink, weight) = when {
                line.startsWith("+++") || line.startsWith("---") -> Triple(Color.Transparent, c.faint, FontWeight.Normal)
                line.startsWith("@@") -> Triple(c.raised, c.muted, FontWeight.Medium)
                line.startsWith("+") -> Triple(c.ok.copy(alpha = 0.14f), c.text, FontWeight.Normal)
                line.startsWith("-") -> Triple(c.bad.copy(alpha = 0.14f), c.text, FontWeight.Normal)
                line.startsWith("\\") -> Triple(Color.Transparent, c.faint, FontWeight.Normal)
                else -> Triple(Color.Transparent, c.muted, FontWeight.Normal)
            }
            Text(
                line.ifEmpty { " " }, style = mono.copy(fontWeight = weight), color = ink, softWrap = false, maxLines = 1,
                modifier = Modifier.width(rowWidth).background(bg).padding(horizontal = 16.dp, vertical = 1.dp),
            )
            if (truncated && i == lines.lastIndex) Note("Cut here: the rest is at the computer.")
        }
    }
}
