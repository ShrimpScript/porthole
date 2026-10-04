package dev.shrimpscript.porthole.ui

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType
import dev.shrimpscript.porthole.ui.theme.motionEnabled
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dev.shrimpscript.porthole.net.Row as FeedRow

/** "+12 −3", green and red, as Claude Code and git show a change. */
@Composable
fun DiffBadge(add: Int, del: Int) {
    val c = Porthole.colors
    Row(Modifier.padding(start = 2.dp)) {
        Text("+$add", style = PortholeType.mono, color = c.ok,
            modifier = Modifier.background(c.ok.copy(alpha = 0.14f), PortholeShape.key).padding(horizontal = 5.dp))
        Spacer(Modifier.width(3.dp))
        Text("−$del", style = PortholeType.mono, color = c.bad,
            modifier = Modifier.background(c.bad.copy(alpha = 0.14f), PortholeShape.key).padding(horizontal = 5.dp))
    }
}

/**
 * A stretch of work as one line: what it came to ("Ran 2 commands, edited a file"), its
 * edits' lines, how many steps failed. While a step runs, it is the line below, with the
 * screw and its time; when it finishes it folds into the line above. Tap for every step.
 */
@Composable
fun WorkLine(work: FeedItem.Work, working: Boolean, onOpen: () -> Unit) {
    val c = Porthole.colors
    val running = if (working) work.running else null
    val done = work.steps.filter { it !== running }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (done.isNotEmpty()) Row(
            Modifier
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                workSummary(done), style = PortholeType.secondary, color = c.muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            val add = done.sumOf { it.diff?.add ?: 0 }
            val del = done.sumOf { it.diff?.del ?: 0 }
            if (add + del > 0) DiffBadge(add, del)
            if (work.failed > 0) Text("${work.failed} failed", style = PortholeType.meta, color = c.bad)
            Text("›", style = PortholeType.secondary, color = c.faint)
        }
        if (running != null) {
            val since = remember(running.call.ts) { runCatching { java.time.Instant.parse(running.call.ts).toEpochMilli() }.getOrDefault(System.currentTimeMillis()) }
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(running.call.ts) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
            Row(
                Modifier.clickable(role = Role.Button, onClick = onOpen).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spinner(color = c.accent)
                Text(running.call.text, style = PortholeType.secondary, color = c.text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val s = ((now - since) / 1000).coerceAtLeast(0)
                Text(if (s < 3600) "%d:%02d".format(s / 60, s % 60) else "%dh %02dm".format(s / 3600, s % 3600 / 60),
                    style = PortholeType.mono, color = c.faint)
            }
        }
    }
}

private fun stepIcon(kind: String): ImageVector = when (kind) {
    "command" -> Icons.Outlined.Terminal
    "edit", "write" -> Icons.Outlined.Edit
    "read" -> Icons.Outlined.Visibility
    "search" -> Icons.Outlined.Search
    "web", "fetch" -> Icons.Outlined.Language
    "skill", "plan" -> Icons.AutoMirrored.Outlined.InsertDriveFile
    else -> Icons.Outlined.Build
}

/** Every step of a stretch, in order, joined by a line: what each did, its lines or its time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkSheet(work: FeedItem.Work, working: Boolean, took: (String) -> String, onStep: (FeedRow) -> Unit, onDismiss: () -> Unit) {
    val c = Porthole.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        WorkSteps(work, working, took, onStep)
    }
}

/** The sheet's body, drawn on its own too (a modal sheet is its own window). */
@Composable
fun WorkSteps(work: FeedItem.Work, working: Boolean, took: (String) -> String, onStep: (FeedRow) -> Unit) {
    val c = Porthole.colors
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
        Text(workSummary(work.steps), style = PortholeType.rowTitle, color = c.text, modifier = Modifier.padding(bottom = 14.dp))
        work.steps.forEachIndexed { i, step ->
            val kind = stepKind(step.call)
            val verb = step.call.text.substringBefore(' ')
            val target = step.call.text.substringAfter(' ', "")
            val open = remember(step) { step.detailRow() }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = open.detail.isNotBlank(), role = Role.Button) { onStep(open) }
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(stepIcon(kind), contentDescription = null, tint = if (step.failed) c.bad else c.muted, modifier = Modifier.size(18.dp))
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(verb, style = PortholeType.secondary, color = c.text)
                        if (target.isNotBlank()) Text(target, style = PortholeType.mono, color = c.muted, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                    val d = step.diff
                    when {
                        step.failed -> Text("failed", style = PortholeType.meta, color = c.bad)
                        d != null && d.add + d.del > 0 -> DiffBadge(d.add, d.del)
                        step.result == null && working -> Spinner(color = c.accent)
                        else -> Text(took(step.call.toolId), style = PortholeType.mono, color = c.faint)
                    }
                }
                // What went wrong, in its first line, as the feed used to say it.
                val why = step.result?.metric.orEmpty()
                if (step.failed && why.isNotBlank()) Text(why, style = PortholeType.mono, color = c.bad, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 30.dp))
            }
            if (i < work.steps.lastIndex) Box(Modifier.padding(start = 8.dp).width(1.dp).height(8.dp).background(c.edge))
        }
    }
}

/**
 * The phone reads an answer aloud with its own voice (Android's text to speech): offline,
 * nothing leaves the phone. One voice for the app; a second answer stops the first.
 */
object Narrator {
    private var tts: TextToSpeech? = null
    private var ready = false
    /** What to read once the engine is up: the latest tap wins. */
    private var waiting: Pair<String, String>? = null
    private val _speaking = MutableStateFlow<String?>(null)
    /** The id of the answer being read, or null. */
    val speaking: StateFlow<String?> = _speaking

    fun toggle(context: Context, id: String, markdown: String) {
        if (_speaking.value == id) { stop(); return }
        val text = spoken(markdown)
        if (text.isBlank()) return
        _speaking.value = id
        if (ready) { say(id, text); return }
        waiting = id to text
        if (tts == null) start(context)
    }

    /**
     * The engine reports whether it started, sometimes before its constructor returns; it
     * is settled once both are known. One that failed (a phone with no voice installed) is
     * let go, so the button is not left saying "Stop" over silence and a later tap tries again.
     */
    private fun start(context: Context) {
        var engine: TextToSpeech? = null
        var status: Int? = null
        fun settle() {
            val e = engine ?: return
            val s = status ?: return
            if (s != TextToSpeech.SUCCESS) {
                e.shutdown()
                if (tts === e) tts = null
                waiting = null
                _speaking.value = null
                return
            }
            ready = true
            e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == "${_speaking.value}#last") _speaking.value = null }
                @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) {
                    if (utteranceId?.substringBeforeLast('#') == _speaking.value) _speaking.value = null
                }
            })
            waiting?.let { (wid, wt) -> say(wid, wt) }
            waiting = null
        }
        engine = TextToSpeech(context.applicationContext) { status = it; settle() }
        tts = engine
        settle()
    }

    private fun say(id: String, text: String) {
        val t = tts ?: return
        val max = TextToSpeech.getMaxSpeechInputLength().coerceAtMost(3800)
        val parts = text.split(Regex("\n{2,}")).flatMap { p -> p.chunked(max) }.filter { it.isNotBlank() }
        parts.forEachIndexed { i, p ->
            t.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null,
                if (i == parts.lastIndex) "$id#last" else "$id#$i")
        }
    }

    fun stop() {
        waiting = null
        tts?.stop()
        _speaking.value = null
    }

    /** Markdown as it is said: no code blocks, no markers, links by their words. */
    fun spoken(md: String): String = md
        .replace(Regex("(?s)```.*?```"), " ")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^\\s{0,3}(#{1,6}|[-*+]|\\d+[.)])\\s+"), "")
        .replace(Regex("[*_~]{1,3}"), "")
        .replace(Regex("(?m)^\\s*\\|.*\\|\\s*$"), " ")
        .trim()
}

/** Copy, listen and share, under Claude's answer. */
@Composable
fun AnswerActions(id: String, text: String) {
    val c = Porthole.colors
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }
    val speaking by Narrator.speaking.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
        IconTarget(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, if (copied) "Copied" else "Copy the answer",
            { clipboard.setText(AnnotatedString(text)); copied = true }, tint = if (copied) c.ok else c.faint)
        IconTarget(if (speaking == id) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, if (speaking == id) "Stop reading" else "Read the answer aloud",
            { Narrator.toggle(context, id, text) }, tint = if (speaking == id) c.accent else c.faint)
        IconTarget(Icons.Outlined.Share, "Share the answer", {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, tint = c.faint)
    }
}

/**
 * New text arrives top to bottom: a straight, linear fade down the block, as if read in.
 * Only for what is new; history and reduced motion show at once.
 */
fun Modifier.revealDown(enabled: Boolean): Modifier = composed {
    val on = motionEnabled() && enabled
    val p = remember { Animatable(if (on) 0f else 1f) }
    LaunchedEffect(Unit) { if (on) p.animateTo(1f, tween(700, easing = LinearEasing)) }
    val band = with(LocalDensity.current) { 56.dp.toPx() }
    // Composition only hears when the fade ends; each frame of it is read while drawing.
    val done by remember { derivedStateOf { p.value >= 1f } }
    if (done) this
    else this
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val edge = p.value * (size.height + band)
            drawRect(
                Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = edge - band, endY = edge),
                blendMode = BlendMode.DstIn,
            )
        }
}
