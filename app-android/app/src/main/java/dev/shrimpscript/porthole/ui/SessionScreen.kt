package dev.shrimpscript.porthole.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Difference
import dev.shrimpscript.porthole.net.ChangesState
import dev.shrimpscript.porthole.net.FilesState
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.animateFloat
import dev.shrimpscript.porthole.ui.theme.motionEnabled
import dev.shrimpscript.porthole.ui.theme.spec
import dev.shrimpscript.porthole.ui.theme.PortholeMotion
import dev.shrimpscript.porthole.net.ScreenQuestion
import dev.shrimpscript.porthole.net.parseIsoMs
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shrimpscript.porthole.net.Row as FeedRow
import dev.shrimpscript.porthole.net.PreviewState
import dev.shrimpscript.porthole.net.SessionState
import dev.shrimpscript.porthole.net.TuiStatus
import dev.shrimpscript.porthole.terminal.TerminalEmulator
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Image
import androidx.compose.foundation.layout.Spacer as LayoutSpacer
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType

enum class SessionView { Feed, Terminal }

/**
 * The session surface: one header, two views under it.
 *
 * Feed is the structured conversation read from the transcript. Terminal is the desktop's
 * tmux window, exactly. The toggle in the header is the whole navigation - there is no
 * sheet, no detent, nothing to drag. Every feed row came from a transcript record or a
 * hook event; nothing is synthesised locally, which is why an empty feed renders as
 * empty rather than as a spinner.
 */
@Composable
fun SessionScreen(
    title: String,
    branch: String,
    ring: RingState,
    /** The session id, so the header's ring and title are the row's ring and title, moved. */
    sharedKey: String = "",
    /** The daemon can start Claude Code here from the phone. */
    canStart: Boolean = false,
    /** A start was asked for and the session is not live yet. */
    starting: Boolean = false,
    /** "resume" or "new". */
    onStart: (String) -> Unit = {},
    /** Claude Code is running in the session's tmux pane. */
    live: Boolean,
    /** A tmux pane exists for the session (the terminal can attach). */
    tmux: Boolean = live,
    rows: List<FeedRow>,
    backfillCount: Int,
    loaded: Boolean,
    /** Older rows the computer still holds; > 0 shows "Earlier" at the top. */
    remaining: Int = 0,
    /** Bumped when a page of history arrives, so "Loading…" can end even on an empty page. */
    earlierEpoch: Int = 0,
    /** Bumped when an attach's first fill lands, so a re-attach is told apart from history. */
    loadedEpoch: Int = 0,
    onEarlier: () -> Unit = {},
    canSend: Boolean,
    onSend: (String) -> Unit,
    /** Drive the picker of the question Claude is asking; see [Answer]. */
    onAnswer: (Answer) -> Unit = {},
    onBack: () -> Unit,
    view: SessionView,
    onViewChange: (SessionView) -> Unit,
    terminal: TerminalEmulator,
    terminalRevision: Int,
    terminalOpen: Boolean,
    onOpenTerminal: () -> Unit,
    onTerminalKeys: (String) -> Unit,
    fontSp: Float,
    onFontSp: (Float) -> Unit,
    fit: Boolean,
    onFit: (Boolean) -> Unit,
    notice: String?,
    onDismissNotice: () -> Unit,
    state: SessionState? = null,
    status: TuiStatus? = null,
    /** The session's subagents, read by the computer from their own transcripts. */
    agents: List<dev.shrimpscript.porthole.net.AgentInfo> = emptyList(),
    onInterrupt: () -> Unit = {},
    onCommand: (CliCommand) -> Unit = {},
    onKey: (String) -> Unit = {},
    autoContinue: Boolean = true,
    images: Map<String, ByteArray> = emptyMap(),
    clips: Map<String, java.io.File> = emptyMap(),
    onNeedImage: (String) -> Unit = {},
    caps: List<String> = emptyList(),
    onCapture: (Int) -> Unit = {},
    onLiveFrame: () -> Unit = {},
    hostLabel: String = "",
    preview: PreviewState? = null,
    /** What git sees changed in the session's directory; null until asked. */
    changes: ChangesState? = null,
    onChangesRefresh: () -> Unit = {},
    /** A message with files that did not go, to put back in the box; [onFailedShown] when it is. */
    failedSend: dev.shrimpscript.porthole.net.PortholeClient.FailedSend? = null,
    onFailedShown: () -> Unit = {},
    /** Files matching an @ mention being typed; [onFiles] asks for a query's. */
    files: FilesState? = null,
    onFiles: (String) -> Unit = {},
    /** One-tap sends shown when Claude is idle; editable by long-press. */
    quickReplies: List<String> = emptyList(),
    onQuickReplies: (List<String>) -> Unit = {},
    onPreviewRefresh: () -> Unit = {},
    onPreviewOpen: (Int) -> Unit = {},
    onPreviewClose: (Int) -> Unit = {},
    /** A tapped link to the computer's loopback: port and path, to open through preview. */
    onLocalLink: (Int, String) -> Unit = { _, _ -> },
    onSendWithImages: (String, List<dev.shrimpscript.porthole.net.Attachment>) -> Unit = { t, _ -> onSend(t) },
    /** This session's last activity as of the previous visit, for the "since you left" line. */
    lastSeen: String = "",
    /** Walk the terminal pane's tmux history (lines > 0 back, 0 back to live). */
    onTerminalScroll: ((Int) -> Unit)? = null,
    /** Notifications for this session are off. */
    muted: Boolean = false,
    onMuted: (Boolean) -> Unit = {},
) {
    val c = Porthole.colors
    var draft by remember { mutableStateOf("") }
    // The question row whose "Type something" the person chose: the next send answers it.
    var answering by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<FeedRow?>(null) }
    var showStats by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var showChanges by remember { mutableStateOf(false) }
    var showAgents by remember { mutableStateOf(false) }
    var editReplies by remember { mutableStateOf(false) }
    var termFull by remember { mutableStateOf(false) }
    // Feedback the eyes need not be on the screen for: a tick when a turn finishes here,
    // a long pulse when a usage limit stops the task. Only on real transitions.
    val haptics = LocalHapticFeedback.current
    // History is not an event: no tick for the backfill, nor for a page of "Earlier".
    val turnCount = rows.count { it.kind == "turn" }
    var seenTurns by remember { mutableStateOf(-1) }
    var seenBackfill by remember { mutableStateOf(backfillCount) }
    LaunchedEffect(turnCount, backfillCount, loaded) {
        val history = !loaded || backfillCount != seenBackfill
        if (loaded && !history && seenTurns >= 0 && turnCount > seenTurns) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        if (loaded) { seenTurns = turnCount; seenBackfill = backfillCount }
    }
    val limitNow = status?.limitHit == true
    var wasLimit by remember { mutableStateOf(limitNow) }
    LaunchedEffect(limitNow) {
        if (limitNow && !wasLimit) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        wasLimit = limitNow
    }
    // Links to the computer's loopback are dead on a phone. When the daemon can share a
    // dev server, they open through preview instead - path and query kept.
    val systemUri = LocalUriHandler.current
    val uriHandler = remember(systemUri, caps) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val u = runCatching { android.net.Uri.parse(uri) }.getOrNull()
                val host = u?.host?.lowercase()
                val port = u?.port ?: -1
                if (u != null && "preview" in caps && port > 0 && host in setOf("localhost", "127.0.0.1", "0.0.0.0")) {
                    val path = (u.encodedPath.orEmpty().ifBlank { "/" }) + (u.encodedQuery?.let { "?$it" } ?: "") +
                    (u.encodedFragment?.let { "#$it" } ?: "")
                    onLocalLink(port, path)
                } else systemUri.openUri(uri)
            }
        }
    }
    var viewImage by remember { mutableStateOf<FeedRow?>(null) }
    var viewClip by remember { mutableStateOf<FeedRow?>(null) }
    var watching by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<List<dev.shrimpscript.porthole.net.Attachment>>(emptyList()) }
    // Why the last picked file was not attached; cleared by the next one that is.
    var attachError by remember { mutableStateOf<String?>(null) }
    // A message whose files did not go comes back, so nothing has to be picked again.
    LaunchedEffect(failedSend) {
        val f = failedSend ?: return@LaunchedEffect
        // Typed something since? The message comes back after it, not over it.
        draft = when {
            f.text.isBlank() -> draft
            draft.isBlank() -> f.text
            else -> draft.trimEnd() + "\n" + f.text
        }
        pending = f.attachments + pending.filter { p -> f.attachments.none { it === p } }
        onFailedShown()
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        Box(
            Modifier
                .fillMaxSize()
                .background(c.ground)
        ) {
            Column(Modifier.fillMaxSize().imePadding()) {
                // Full screen is the terminal's alone: the header and the Feed/Terminal
                // toggle go, and so do the system bars (a swipe from the edge shows them).
                val fullTerm = termFull && view == SessionView.Terminal
                Immersive(fullTerm)
                // Back leaves full screen before it leaves the session.
                androidx.activity.compose.BackHandler(enabled = fullTerm) { termFull = false }
                if (!fullTerm) SessionHeader(title, branch, ring, onBack, compact = landscape, state = state, sharedKey = sharedKey,
                    canCapture = "capture" in caps, canRecord = "record" in caps,
                    onCapture = { if (it < 0) watching = true else onCapture(it) },
                    canPreview = "preview" in caps, onPreview = { showPreview = true },
                    canChanges = "changes" in caps, onChanges = { showChanges = true }) { showStats = true }
                // With the keyboard up in the feed, the space goes to the conversation: the
                // toggle steps aside until the keyboard closes. In the terminal the keyboard is
                // the input, and the toggle is the way back, so it stays.
                val imeUp = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
                androidx.compose.animation.AnimatedVisibility(
                    visible = !(imeUp && view == SessionView.Feed) && !fullTerm,
                    enter = androidx.compose.animation.fadeIn(spec(PortholeMotion.ENTER_MS)) + androidx.compose.animation.expandVertically(spec(PortholeMotion.ENTER_MS)),
                    exit = androidx.compose.animation.fadeOut(spec(PortholeMotion.EXIT_MS)) + androidx.compose.animation.shrinkVertically(spec(PortholeMotion.EXIT_MS)),
                ) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = if (landscape) 4.dp else 8.dp)) {
                        SegmentedToggle(
                            "Feed" to "Terminal",
                            selected = view.ordinal,
                            onSelect = { onViewChange(SessionView.entries[it]) },
                        )
                    }
                }
    
                notice?.let { text ->
                    Appear {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .background(c.raised, PortholeShape.control)
                                .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Outlined.Warning, contentDescription = null, tint = c.warn, modifier = Modifier.size(18.dp))
                            Text(text, style = PortholeType.secondary, color = c.text, modifier = Modifier.weight(1f))
                            IconTarget(Icons.Outlined.Close, "Dismiss", onDismissNotice, Modifier.size(36.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
    
                when (view) {
                    SessionView.Feed -> {
                        Feed(
                            rows, backfillCount, loaded, state?.working == true, Modifier.weight(1f),
                            verb = status?.text.orEmpty(), remaining = remaining, earlierEpoch = earlierEpoch, loadedEpoch = loadedEpoch, onEarlier = onEarlier,
                            asking = status?.question != null,
                            lastSeen = lastSeen,

                            imageFor = { images[it] }, clipFor = { clips[it] }, onNeedImage = onNeedImage,
                            onOpenImage = { viewImage = it }, onOpenClip = { viewClip = it },
                            agents = agents, onAgents = { showAgents = true },
                        ) { detail = it }
                        if (status?.limitHit == true) {
                            LimitCard(
                                status = status, autoContinue = autoContinue, onKey = onKey,
                                onOptions = { onCommand(CLI_COMMANDS.first { it.name == "/rate-limit-options" }) },
                            )
                            LayoutSpacer(Modifier.height(8.dp))
                        } else {
                            val agentsAtWork = agents.filter { it.state == "running" }
                            val turnOpen = (status?.working == true) || (state?.working == true)
                            if (turnOpen) {
                                WorkingStrip(
                                    state = state, status = status,
                                    canInterrupt = live && canSend,
                                    onInterrupt = onInterrupt,
                                    agentsRunning = agentsAtWork.size,
                                    onAgents = { showAgents = true },
                                )
                            } else if (agentsAtWork.isNotEmpty()) {
                                // the turn has ended but background agents work on
                                AgentsStrip(agentsAtWork) { showAgents = true }
                                LayoutSpacer(Modifier.height(8.dp))
                            }
                        }
                        if (!live) {
                            LayoutSpacer(Modifier.height(8.dp))
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .background(c.surface, PortholeShape.card)
                                    .padding(14.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (starting) Spinner(color = c.accent, background = c.surface)
                                    else Icon(Icons.Outlined.Info, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
                                    Text(
                                        when {
                                            starting -> "Starting Claude Code on the computer\u2026"
                                            canStart -> "Claude Code isn't running here. Resume this session, or start a fresh one in the same folder."
                                            tmux -> "Claude Code isn't running in this session's tmux window, so messages have nowhere to go. Open Terminal and start it with claude."
                                            else -> "This session has no tmux window on the computer. Start one there with tmux, then claude."
                                        },
                                        style = PortholeType.secondary, color = c.muted, modifier = Modifier.weight(1f),
                                    )
                                }
                                if (canStart && !starting) {
                                    LayoutSpacer(Modifier.height(10.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                                        Pill("New session", filled = false) { onStart("new") }
                                        Pill("Resume", filled = true) { onStart("resume") }
                                    }
                                }
                            }
                        }
                        // A file named with "@": the daemon lists the session's files as the
                        // name is typed, and a tap puts the path in the draft.
                        val mention = remember(draft, canSend) { if ("files" in caps && canSend) trailingMention(draft) else null }
                        LaunchedEffect(mention) {
                            if (mention != null) {
                                if (mention.isNotEmpty()) kotlinx.coroutines.delay(120) // a pause in typing, not every key
                                onFiles(mention)
                            }
                        }
                        // The answer for what is typed; while that is on its way, the last
                        // answer narrowed to what still fits, so the list does not blink.
                        val fileList = files?.takeIf { mention != null && it.error.isEmpty() }?.let { f ->
                            when {
                                f.query == mention -> f.files
                                mention!!.startsWith(f.query) -> f.files.filter { it.contains(mention, ignoreCase = true) }.ifEmpty { null }
                                else -> null
                            }
                        }
                        if (mention != null && fileList != null) {
                            LayoutSpacer(Modifier.height(8.dp))
                            FileSuggestions(fileList, mention) { path -> draft = insertMention(draft, path) }
                        }
                        val suggestions = remember(draft) { matchingCommands(draft) }
                        if (suggestions.isNotEmpty()) {
                            LayoutSpacer(Modifier.height(8.dp))
                            CommandSuggestions(suggestions) { cmd ->
                                if (cmd.sheet) {
                                    showStats = true
                                    draft = ""
                                } else if (cmd.arg != null) {
                                    draft = cmd.name + " "
                                } else {
                                    onCommand(cmd)
                                    draft = ""
                                }
                            }
                        }
                        if (pending.isNotEmpty() || attachError != null) {
                            LayoutSpacer(Modifier.height(6.dp))
                            // Several files with long names: the row scrolls rather than squeezing them.
                            Row(
                                Modifier
                                    .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                                    .padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                attachError?.let { e -> Chip("$e  ✕") { attachError = null } }
                                pending.forEach { a ->
                                    val shown = if (a.name.length > 28) a.name.take(18) + "\u2026" + a.name.takeLast(9) else a.name
                                    Chip("$shown · ${formatBytes(a.bytes.size.toLong())}  ✕") { pending = pending - a }
                                }
                            }
                        }
                        // Idle and nothing asked: the three-word replies a phone is for.
                        // Once something is typed they are in the way, not a shortcut.
                        val idle = canSend && state?.working == false && status?.question == null && rows.isNotEmpty()
                        if (idle && draft.isEmpty()) {
                            QuickReplies(quickReplies, onSend = { onSend(it) }, onEdit = { editReplies = true })
                        }
                        val screenQ = status?.question
                        // The picker left the screen: whatever "Type something" was for is over.
                        LaunchedEffect(screenQ == null) { if (screenQ == null) answering = false }
                        if (screenQ != null && live) {
                            LiveQuestionCard(screenQ, onAnswer = { a -> onAnswer(a) }, onType = { answering = true })
                        }
                        if (answering && screenQ != null) {
                            LayoutSpacer(Modifier.height(6.dp))
                            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip("Answering: ${screenQ.text.take(40)}  ✕") { answering = false }
                            }
                        }
                        Composer(
                            value = draft,
                            onValueChange = { draft = it },
                            placeholder = if (answering) "Your answer" else "Message $title",
                            enabled = canSend,
                            // An older daemon closes the connection on any photo (its 32 KB
                            // limit), so attaching needs one that takes files in pieces.
                            canAttach = "attach" in caps,
                            canAttachFiles = "attach" in caps,
                            hasAttachments = pending.isNotEmpty(),
                            onAttach = { a ->
                                val total = pending.sumOf { it.bytes.size.toLong() } + a.bytes.size
                                if (total > ATTACH_LIMIT) {
                                    attachError = "${a.name} would make ${formatBytes(total)}. A message can carry ${formatBytes(ATTACH_LIMIT.toLong())}."
                                } else {
                                    pending = pending + a
                                    attachError = null
                                }
                            },
                            onAttachError = { attachError = it },
                            reason = when {
                                !tmux -> "No tmux window for this session on the computer"
                                !live -> "Claude Code isn't running here - open Terminal and run claude"
                                else -> "Not connected"
                            },
                            onSlash = { if (!draft.startsWith("/")) draft = "/" },
                            onSend = {
                                val typingInto = status?.question
                                if (answering && typingInto != null) {
                                    if (draft.isNotBlank()) {
                                        // "Type something" is a numbered entry of the picker (typed = 0
                                        // when the question is free text and takes typing directly).
                                        onAnswer(Answer(option = typingInto.typed, text = draft.trim()))
                                        draft = ""
                                        answering = false
                                    }
                                } else if (pending.isEmpty() && opensSheet(draft)) {
                                    // Its switches and numbers are in the sheet, native, rather than
                                    // a dialog on the computer's screen.
                                    showStats = true
                                    draft = ""
                                } else if (draft.isNotBlank() || pending.isNotEmpty()) {
                                    if (pending.isEmpty()) onSend(draft.trim()) else onSendWithImages(draft.trim(), pending)
                                    draft = ""
                                    pending = emptyList()
                                }
                            },
                        )
                    }
                    SessionView.Terminal -> TerminalBody(
                        emulator = terminal,
                        revision = terminalRevision,
                        open = terminalOpen,
                        live = tmux,
                        fontSp = fontSp,
                        onFontSp = onFontSp,
                        fit = fit,
                        onFit = onFit,
                        onSend = onTerminalKeys,
                        onConnect = onOpenTerminal,
                        modifier = Modifier.weight(1f),
                        onScroll = onTerminalScroll,
                        fullscreen = termFull,
                        onFullscreen = { termFull = it },
                    )
                }
            }
    
            detail?.let { r -> DetailSheet(r) { detail = null } }
            viewImage?.let { r -> images[r.imageRef]?.let { b -> ImageViewer(b, r.text) { viewImage = null } } }
            viewClip?.let { r -> clips[r.imageRef]?.let { f -> VideoViewer(f, r.text) { viewClip = null } } }
            if (watching) LiveScreen(frame = images["live"], onRequest = onLiveFrame) { watching = false }
            if (showChanges) ChangesSheet(state = changes, connected = canSend || tmux, onRefresh = onChangesRefresh) { showChanges = false }
            if (editReplies) QuickReplyEditor(quickReplies, onSave = { onQuickReplies(it); editReplies = false }) { editReplies = false }
            if (showPreview) PreviewSheet(
                hostLabel = hostLabel.ifBlank { "the computer" }, state = preview,
                onRefresh = onPreviewRefresh, onOpen = onPreviewOpen, onStop = onPreviewClose,
            ) { showPreview = false }
            if (showAgents) AgentsSheet(agents) { showAgents = false }
            if (showStats && state != null) {
                StatsSheet(
                    title = title, state = state, status = status,
                    context = rows.lastOrNull { it.command?.context != null },
                    onCommand = { showStats = false; onCommand(it) },
                    onDismiss = { showStats = false },
                    onSend = onSend, onKey = onKey,
                    muted = muted, onMuted = onMuted,
                    canRename = canSend,
                )
            }
        }
    }
}

@Composable
private fun SessionHeader(
    title: String, branch: String, ring: RingState, onBack: () -> Unit, compact: Boolean,
    state: SessionState?, sharedKey: String = "", canCapture: Boolean = false, canRecord: Boolean = false,
    onCapture: (Int) -> Unit = {}, canPreview: Boolean = false, onPreview: () -> Unit = {},
    canChanges: Boolean = false, onChanges: () -> Unit = {},
    onStats: () -> Unit,
) {
    val c = Porthole.colors
    var captureMenu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 12.dp, top = if (compact) 0.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconTarget(Icons.AutoMirrored.Outlined.ArrowBack, "Back to sessions", onBack)
        Ring(
            state = ring, size = 18.dp,
            modifier = if (sharedKey.isNotBlank()) Modifier.sharedAcrossRoutes("ring-$sharedKey") else Modifier,
        )
        Column(Modifier.weight(1f)) {
            Text(
                title, style = PortholeType.rowTitle, color = c.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (sharedKey.isNotBlank()) Modifier.sharedAcrossRoutes("title-$sharedKey", bounds = true) else Modifier,
            )
            if (branch.isNotBlank() && !compact) {
                Text(branch, style = PortholeType.meta, color = c.faint)
            }
        }
        // the site under test, in this phone's browser - only when the daemon can share one
        if (canPreview) {
            IconTarget(androidx.compose.material.icons.Icons.Outlined.Language, "Open a dev server on this phone", onPreview, tint = c.muted)
        }
        // a look at the desktop, only when the computer can actually take one
        if (canCapture) {
            Box {
                IconTarget(androidx.compose.material.icons.Icons.Outlined.PhotoCamera, "Capture the screen", { captureMenu = true }, tint = c.muted)
                androidx.compose.material3.DropdownMenu(
                    expanded = captureMenu, onDismissRequest = { captureMenu = false },
                    containerColor = c.raised,
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Screenshot", style = PortholeType.body, color = c.text) },
                        onClick = { captureMenu = false; onCapture(0) },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Watch the screen", style = PortholeType.body, color = c.text) },
                        onClick = { captureMenu = false; onCapture(-1) },
                    )
                    if (canRecord) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Record 5 seconds", style = PortholeType.body, color = c.text) },
                            onClick = { captureMenu = false; onCapture(5) },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Record 10 seconds", style = PortholeType.body, color = c.text) },
                            onClick = { captureMenu = false; onCapture(10) },
                        )
                    }
                }
            }
        }
        // model + how full the context is; the tap opens the full picture
        if (state != null) {
            val window = contextWindow(state.model)
            val pct = if (window > 0) (state.lastContext * 100 / window).toInt() else -1
            Chip(
                listOfNotNull(modelShortName(state.model).ifBlank { null }, if (pct >= 0) "$pct%" else null)
                    .joinToString(" · ").ifBlank { "session" },
                onClick = onStats,
            )
        } else {
            if (canChanges) IconTarget(Icons.Outlined.Difference, "What changed", onChanges, tint = c.faint)
            IconTarget(Icons.Outlined.Info, "Session details", onStats, tint = c.faint)
        }
    }
}

@Composable
private fun Feed(
    rows: List<FeedRow>,
    backfillCount: Int,
    loaded: Boolean,
    working: Boolean,
    modifier: Modifier,
    verb: String = "",
    remaining: Int = 0,
    earlierEpoch: Int = 0,
    loadedEpoch: Int = 0,
    onEarlier: () -> Unit = {},
    imageFor: (String) -> ByteArray? = { null },
    clipFor: (String) -> java.io.File? = { null },
    onNeedImage: (String) -> Unit = {},
    onOpenImage: (FeedRow) -> Unit = {},
    onOpenClip: (FeedRow) -> Unit = {},
    /** A question picker is on the CLI's screen: Claude is waiting, not composing. */
    asking: Boolean = false,
    /** The session's last activity as of the previous visit; "" the first time. */
    lastSeen: String = "",
    /** The session's subagents: an Agent call's row shows its agent's progress. */
    agents: List<dev.shrimpscript.porthole.net.AgentInfo> = emptyList(),
    onAgents: () -> Unit = {},
    onOpen: (FeedRow) -> Unit,
) {
    val c = Porthole.colors
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Tool calls whose result has landed, and how long each took: the gap between the
    // call's record and its result's. A call without a result, while Claude is
    // working, is running right now.
    val toolStart = remember(rows) { rows.asSequence().filter { it.kind == "tool" && it.toolId.isNotBlank() }.associate { it.toolId to parseIsoMs(it.ts) } }
    val took = remember(rows) {
        rows.asSequence().filter { it.kind == "result" && it.toolId.isNotBlank() }
            .associate { r -> r.toolId to ((parseIsoMs(r.ts) - (toolStart[r.toolId] ?: 0L)).takeIf { it > 0 && (toolStart[r.toolId] ?: 0L) > 0 } ?: -1L) }
    }
    val lastToolIndex = remember(rows) { rows.indexOfLast { it.kind == "tool" } }
    // Stable identity per row, so history can be prepended without re-animating or
    // re-folding what is already on screen. Duplicates get a suffix.
    val keys = remember(rows) {
        val counts = HashMap<String, Int>()
        rows.map { r -> val k = "${r.ts}|${r.kind}|${r.text.hashCode()}"; val n = counts.merge(k, 1, Int::plus)!!; if (n == 1) k else "$k#$n" }
    }
    val seen = remember { mutableSetOf<String>() }
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }

    // Everything after the stamp this session carried when it was last opened. Only a
    // marker if there is something on both sides of it: a feed that is new all the way
    // down is a first visit, and a line saying so would be noise.
    val newFrom = remember(rows, lastSeen) {
        if (lastSeen.isBlank()) -1
        else {
            val since = epochOf(lastSeen)
            val at = rows.indexOfFirst { it.ts.isNotBlank() && epochOf(it.ts) > since }
            if (at > 0) at else -1
        }
    }

    // Follow the tail only while the reader is at it. Someone reading history during
    // a long turn is not yanked down; new rows are counted and offered instead.
    // Whether the reader was at the end as of the last layout. Read from scroll events,
    // not recomputed when rows land: a new row grows the count before the list has
    // moved, and judging against the new count would call every reader "scrolled up".
    var atEnd by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collect { (last, total) -> if (total > 0 && last >= 0) atEnd = last >= total - 2 }
    }
    var pendingNew by remember { mutableStateOf(0) }
    var lastSize by remember { mutableStateOf(-1) }
    var lastBackfill by remember { mutableStateOf(backfillCount) }
    var lastEpoch by remember { mutableStateOf(loadedEpoch) }
    // No "composing" while Claude is blocked on the person: a question on screen (the
    // transcript has not written it yet) or a question row without its answer.
    val composing = working && !asking && rows.isNotEmpty() && rows.last().kind != "tool" && rows.last().kind != "question"
    // The last list index, from what the list will hold rather than what it has laid
    // out: on first composition the layout is still empty.
    val lastIndex = rows.size - 1 + (if (remaining > 0) 1 else 0) + (if (composing) 1 else 0)
    LaunchedEffect(rows.size, backfillCount, loadedEpoch) {
        // The feed filling for the first time, or refilled by a re-attach (a new load
        // epoch; the count reset says the same), lands at the end. History prepended
        // after that - the feed grew by exactly the history delta - keeps the reader's
        // place, including a reader at the top of an idle session pressing Earlier.
        val first = lastSize <= 0 || loadedEpoch != lastEpoch || backfillCount < lastBackfill
        val delta = if (lastSize < 0) 0 else rows.size - lastSize
        val grewFromHistory = !first && lastSize > 0 && backfillCount > lastBackfill && delta == backfillCount - lastBackfill
        lastSize = rows.size; lastBackfill = backfillCount; lastEpoch = loadedEpoch
        if (rows.isEmpty() || grewFromHistory) return@LaunchedEffect
        // Coming back to a session that ran on without you: land on the line where you
        // stopped reading, not at the bottom, so what happened is above you in order.
        // Only when there is enough of it to be worth the scroll back down.
        val marker = if (newFrom > 0 && rows.size - newFrom >= 3) newFrom + (if (remaining > 0) 1 else 0) else -1
        when {
            first && marker > 0 -> { listState.scrollToItem(marker); pendingNew = 0 }
            first || atEnd || delta <= 0 -> listState.animateScrollToItem(lastIndex.coerceAtLeast(0))
            else -> pendingNew += delta
        }
    }
    LaunchedEffect(atEnd) { if (atEnd) pendingNew = 0 }

    // Ghost rows after 150ms without history: a fast link never sees them.
    var ghosts by remember { mutableStateOf(false) }
    LaunchedEffect(loaded) { if (loaded) ghosts = false else { kotlinx.coroutines.delay(150); ghosts = !loaded } }
    var loadingEarlier by remember { mutableStateOf(false) }
    LaunchedEffect(earlierEpoch) { loadingEarlier = false }

    Box(modifier.fillMaxWidth()) {
        when {
            !loaded -> if (ghosts) GhostRows() else Box(Modifier.fillMaxSize())
            rows.isEmpty() -> Centered("Nothing in this session yet.")
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (remaining > 0) item(key = "earlier") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Pill(if (loadingEarlier) "Loading…" else "Earlier · $remaining more", filled = false) {
                            if (!loadingEarlier) { loadingEarlier = true; onEarlier() }
                        }
                    }
                }
                itemsIndexed(rows, key = { i, _ -> keys[i] }) { i, r ->
                    if (i == newFrom) SinceYouLeft(rows.drop(newFrom).count { it.kind == "turn" }, lastSeen)
                    val animate = i >= backfillCount && seen.add(keys[i])
                    val pending = working && r.kind == "tool" && r.toolId.isNotBlank() && !took.containsKey(r.toolId)
                    val done = r.toolId.isNotBlank() && took.containsKey(r.toolId)
                    FeedRowView(r, keys[i].hashCode(), onOpen, animate, expanded, pending, imageFor(r.imageRef), clipFor(r.imageRef),
                        onNeedImage = { onNeedImage(r.imageRef) }, onOpenImage = { onOpenImage(r) }, onOpenClip = { onOpenClip(r) },
                        duration = if (r.kind == "tool" && done) tookLabel(took[r.toolId] ?: -1L) else "",
                        dim = done && i < lastToolIndex,
                        answered = r.kind == "question" && (done || !working),
                        agentInfo = if (r.agent != null) agents.firstOrNull { it.toolId == r.toolId } else null,
                        onAgents = onAgents)
                }
                if (composing) item(key = "composing") { ComposingChip(verb) }
            }
        }
        // New rows arrived below a reader who scrolled up.
        androidx.compose.animation.AnimatedVisibility(
            pendingNew > 0,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            enter = androidx.compose.animation.fadeIn(spec(PortholeMotion.ENTER_MS)) +
                androidx.compose.animation.slideInVertically(spec(PortholeMotion.ENTER_MS)) { it / 2 },
            exit = androidx.compose.animation.fadeOut(spec(PortholeMotion.EXIT_MS)),
        ) {
            Pill("$pendingNew new ↓", filled = true) {
                scope.launch { listState.animateScrollToItem(lastIndex.coerceAtLeast(0)) }
            }
        }
    }
}

/**
 * The line where the last visit ended: a rule across the feed with the count on it.
 * Placed above the first row that is newer than the stamp, so everything below it is
 * what the session did while nobody was watching.
 */
@Composable
private fun SinceYouLeft(turns: Int, lastSeen: String) {
    val c = Porthole.colors
    val ago = relativeTime(lastSeen)
    // Turns, because that is the unit the rest of the app counts in and the one a
    // person means by "how much did it get through". A turn still running is not one
    // yet, so the line simply says when you left.
    val what = when (turns) {
        0 -> "Since you left"
        1 -> "1 turn since you left"
        else -> "$turns turns since you left"
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(c.accent.copy(alpha = 0.35f)))
        Text(
            if (ago.isBlank()) what else "$what \u00b7 $ago ago",
            style = PortholeType.meta, color = c.accent,
        )
        Box(Modifier.weight(1f).height(1.dp).background(c.accent.copy(alpha = 0.35f)))
    }
}

/** "1.2s", "48s", "2m 05s": how long a tool call took, from the transcript's own clocks. */
private fun tookLabel(ms: Long): String = when {
    ms < 0 -> ""
    ms < 10_000 -> "%.1fs".format(java.util.Locale.ROOT, ms / 1000.0)
    ms < 60_000 -> "${ms / 1000}s"
    else -> "${ms / 60_000}m %02ds".format(java.util.Locale.ROOT, (ms % 60_000) / 1000)
}

/** Three shapes where history will be: surface colour, no motion of their own. */
@Composable
private fun GhostRows() {
    val c = Porthole.colors
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(0.62f, 0.88f, 0.45f).forEachIndexed { i, w ->
            Box(
                Modifier
                    .fillMaxWidth(w)
                    .height(14.dp)
                    .align(if (i == 0) Alignment.End else Alignment.Start)
                    .background(c.raised, PortholeShape.pill)
            )
        }
        Text("Loading history", style = PortholeType.meta, color = c.faint)
    }
}

/**
 * Where the reply will appear: the CLI's own verb beside the spinner that already means
 * "working". Nothing else moves: no pulsing dots, no invented activity.
 */
@Composable
private fun ComposingChip(verb: String) {
    val c = Porthole.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 2.dp)) {
        Spinner(color = c.accent)
        Text(verb.ifBlank { "Working" }.trimEnd('…', '.') + "…", style = PortholeType.secondary, color = c.muted)
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = PortholeType.body, color = Porthole.colors.faint)
    }
}

/** Beyond this, an assistant reply folds and offers "Show more". */
private const val FOLD_CHARS = 700
private const val FOLD_LINES = 12

@Composable
fun FeedRowView(
    r: FeedRow,
    index: Int,
    onOpen: (FeedRow) -> Unit,
    animate: Boolean,
    expanded: MutableMap<Int, Boolean> = remember { mutableStateMapOf() },
    pending: Boolean = false,
    imageBytes: ByteArray? = null,
    clipFile: java.io.File? = null,
    onNeedImage: () -> Unit = {},
    onOpenImage: () -> Unit = {},
    onOpenClip: () -> Unit = {},
    /** How long a finished tool call took, shown on its row. */
    duration: String = "",
    /** A finished call that later rows have moved past: one step quieter. */
    dim: Boolean = false,
    /** A question row whose answer has landed (or whose turn ended): choices are no longer live. */
    answered: Boolean = false,
    /** An Agent call's row: its agent, as last read. */
    agentInfo: dev.shrimpscript.porthole.net.AgentInfo? = null,
    onAgents: () -> Unit = {},
) {
    val c = Porthole.colors
    Appear(enabled = animate) {
        when (r.kind) {
            "question" -> QuestionRow(r, answered)
            "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Column(horizontalAlignment = Alignment.End) {
                    Box(
                        Modifier
                            .widthIn(max = 320.dp)
                            .background(c.raised, PortholeShape.card)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        SelectionContainer { Text(r.text, style = PortholeType.body, color = c.text) }
                    }
                    val t = clockTime(r.ts)
                    if (t.isNotEmpty()) {
                        Text(t, style = PortholeType.meta, color = c.faint, modifier = Modifier.padding(top = 3.dp, end = 4.dp))
                    }
                }
            }

            // A prompt sent while Claude was busy. Dimmed and labelled, so a message never
            // just disappears between sending and delivery.
            "queued" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Column(horizontalAlignment = Alignment.End) {
                    Box(
                        Modifier
                            .widthIn(max = 320.dp)
                            .background(c.surface, PortholeShape.card)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(r.text, style = PortholeType.body, color = c.muted)
                    }
                    Text("⋯ queued", style = PortholeType.meta, color = c.faint,
                        modifier = Modifier.padding(top = 4.dp, end = 4.dp))
                }
            }

            "assistant" -> {
                val long = r.text.length > FOLD_CHARS || r.text.count { it == '\n' } > FOLD_LINES
                val open = expanded[index] == true || !long
                Column(Modifier.fillMaxWidth()) {
                    Box(
                        if (open) Modifier.fillMaxWidth()
                        else Modifier.fillMaxWidth().heightIn(max = 210.dp).clipToBounds()
                    ) {
                        SelectionContainer { MarkdownText(r.text) }
                        if (!open) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, c.ground)))
                            )
                        }
                    }
                    if (long) {
                        Text(
                            if (open) "Show less" else "Show more",
                            style = PortholeType.secondary, color = c.accent,
                            modifier = Modifier
                                .clickable { expanded[index] = !open }
                                .padding(top = 4.dp, bottom = 4.dp, end = 8.dp),
                        )
                    }
                    if (r.truncated) {
                        Text(
                            "Cut at 16,000 characters - the rest is on the computer.",
                            style = PortholeType.meta, color = c.faint,
                        )
                    }
                }
            }

            "tool" -> if (r.agent != null) AgentCard(r.agent, r.text, agentInfo, pending, r.ts, onAgents) else Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = r.detail.isNotBlank()) { onOpen(r) },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                val ink by androidx.compose.animation.animateColorAsState(
                    when { pending -> c.text; dim -> c.faint; else -> c.muted }, spec(PortholeMotion.ENTER_MS), label = "toolInk",
                )
                Icon(
                    toolIcon(r.text), contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(18.dp).padding(top = 1.dp),
                )
                Text(
                    r.text, style = PortholeType.secondary, color = ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (pending) Spinner(color = c.accent)
                else if (duration.isNotBlank()) Text(duration, style = PortholeType.mono, color = c.faint)
            }

            "result" -> Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = r.detail.isNotBlank()) { onOpen(r) },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (r.glyph == "✗") Icons.Outlined.Close else Icons.Outlined.Check,
                    contentDescription = if (r.glyph == "✗") "failed" else "done",
                    tint = if (r.glyph == "✗") c.bad else c.ok,
                    modifier = Modifier.size(18.dp),
                )
                Text(r.text, style = PortholeType.secondary, color = if (dim) c.faint else c.muted, modifier = Modifier.weight(1f))
                if (r.metric.isNotBlank()) {
                    // A failed result carries its first error line here, which can be long;
                    // bounded so the summary on the left keeps its width.
                    Text(
                        r.metric, style = PortholeType.mono, color = if (r.glyph == "✗") c.bad else c.faint,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp),
                    )
                }
                if (r.detail.isNotBlank()) {
                    Text("›", style = PortholeType.secondary, color = c.faint)
                }
            }

            "command" -> if (r.command != null) CommandCard(r, r.command) { onOpen(r) }
                else Text(r.text, style = PortholeType.mono, color = c.muted)

            "image" -> ImageRow(r.text, imageBytes, onNeedImage, onOpenImage)
            "video" -> VideoRow(r.text, clipFile, onOpenClip)

            // The CLI's own "Cooked for 1m 6s · done 6:05 PM", in our type.
            "turn" -> Row(
                Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScrewMark()
                val t = clockTime(r.ts)
                Text(
                    if (t.isNotEmpty()) "${r.text} · done $t" else r.text,
                    style = PortholeType.meta, color = c.faint, modifier = Modifier.padding(start = 8.dp),
                )
            }

            "event" -> Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(5.dp).background(c.faint, PortholeShape.pill))
                Text(r.text, style = PortholeType.meta, color = c.faint, modifier = Modifier.padding(start = 8.dp))
            }

            else -> Text(r.text, style = PortholeType.secondary, color = c.muted)
        }
    }
}

/**
 * The drill-in. Long tool output is never inlined in the feed, so this is
 * where the raw text lives, in mono, with the truncation stated rather than hidden.
 */
@Composable
private fun DetailSheet(r: FeedRow, onClose: () -> Unit) {
    val c = Porthole.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(c.ground)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTarget(Icons.Outlined.Close, "Close", onClose)
                Text(r.text, style = PortholeType.rowTitle, color = c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                val context = androidx.compose.ui.platform.LocalContext.current
                IconTarget(Icons.Outlined.ContentCopy, "Copy", { clipboard.setText(androidx.compose.ui.text.AnnotatedString(r.detail)) }, tint = c.faint)
                IconTarget(Icons.Outlined.Share, "Share", {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, r.detail)
                    runCatching { context.startActivity(android.content.Intent.createChooser(send, r.text)) }
                }, tint = c.faint)
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(c.surface, PortholeShape.control)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer { Text(r.detail, style = PortholeType.mono, color = c.text) }
            }
            if (r.truncated) {
                Text(
                    "Output truncated — open it on the computer for the rest.",
                    style = PortholeType.meta, color = c.faint,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    reason: String,
    onSlash: () -> Unit,
    canAttach: Boolean = false,
    /** The daemon takes any file, not only photos. */
    canAttachFiles: Boolean = false,
    /** Files are waiting to go: Send works with no words typed. */
    hasAttachments: Boolean = false,
    onAttach: (dev.shrimpscript.porthole.net.Attachment) -> Unit = {},
    onAttachError: (String) -> Unit = {},
    onSend: () -> Unit,
) {
    val c = Porthole.colors
    val ready = enabled && (value.isNotBlank() || hasAttachments)
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // Re-encoded to JPEG at <= 1600px: the transcript stores what Claude reads, so a
        // 12MP original would bloat it and slow the send for no gain.
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@rememberLauncherForActivityResult
        val bmp = decodeBounded(bytes, 1600) ?: return@rememberLauncherForActivityResult
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        onAttach(dev.shrimpscript.porthole.net.Attachment("photo-${System.currentTimeMillis() % 100000}.jpg", "image/jpeg", out.toByteArray()))
    }
    // Any file, through the system's own picker: no storage permission, and every place a
    // file can be (the phone, Drive, a USB stick) is in it. Read off the main thread.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val picked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readAttachment(context, uri) }
            picked.fold(onSuccess = onAttach, onFailure = { onAttachError(it.message ?: "That file could not be read") })
        }
    }
    // Typing folds the photo and command buttons into one, so the field gets their width
    // (a third more on a typical phone) and wraps later. The fold opens again on a tap and
    // closes with the next keystroke.
    var tools by remember { mutableStateOf(false) }
    LaunchedEffect(value) { tools = false }
    // The field keeps its own cursor. When the draft is changed from outside - a command or
    // a file picked from the list above - the cursor goes to the end, where typing goes on;
    // a plain string field would leave it where the finger last was, mid-word.
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))) }
    val shown = if (field.text == value) field
        else androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))
    val folded = value.isNotEmpty() && !tools
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (folded) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(PortholeShape.pill)
                    .clickable(enabled = enabled, role = Role.Button) { tools = true }
                    .semantics { contentDescription = "Photo and commands" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.Outlined.Add, contentDescription = null,
                    tint = if (enabled) c.muted else c.faint, modifier = Modifier.size(22.dp),
                )
            }
        }
        if (canAttach && !folded) {
            val photo = {
                picker.launch(androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            var attachMenu by remember { mutableStateOf(false) }
            Box {
                Box(
                    Modifier
                        .size(48.dp)
                        .background(c.raised, PortholeShape.pill)
                        // A daemon that takes files gets a choice; an older one, the photo picker.
                        .clickable(enabled = enabled, role = Role.Button) { if (canAttachFiles) attachMenu = true else photo() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (canAttachFiles) androidx.compose.material.icons.Icons.Outlined.AttachFile
                        else androidx.compose.material.icons.Icons.Outlined.Image,
                        contentDescription = if (canAttachFiles) "Attach a photo or a file" else "Attach a photo",
                        tint = if (enabled) c.muted else c.faint, modifier = Modifier.size(20.dp),
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = attachMenu, onDismissRequest = { attachMenu = false },
                    containerColor = c.raised,
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Photo", style = PortholeType.body, color = c.text) },
                        leadingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.Image, contentDescription = null, tint = c.muted) },
                        onClick = { attachMenu = false; photo() },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("File", style = PortholeType.body, color = c.text) },
                        leadingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.Description, contentDescription = null, tint = c.muted) },
                        onClick = { attachMenu = false; filePicker.launch(arrayOf("*/*")) },
                    )
                }
            }
        }
        // Claude Code's slash commands, one tap away.
        if (!folded) Box(
            Modifier
                .size(48.dp)
                .background(c.raised, PortholeShape.pill)
                .clickable(enabled = enabled, role = Role.Button) { onSlash() }
                .semantics { contentDescription = "Slash commands" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "/", style = PortholeType.mono.copy(fontSize = 18.sp), color = if (enabled) c.muted else c.faint,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        Box(
            Modifier
                .weight(1f)
                .background(c.raised, PortholeShape.card)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (value.isEmpty()) {
                // The field itself carries the label for screen readers; the drawn
                // placeholder would otherwise be announced a second time as loose text.
                Text(
                    if (enabled) placeholder else reason,
                    style = PortholeType.body, color = c.faint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
            BasicTextField(
                value = shown,
                // Every edit goes up, even one that returns to the text of the last frame:
                // skipping those would let the next recomposition throw an edit away.
                onValueChange = { field = it; onValueChange(it.text) },
                enabled = enabled,
                // Five lines, then it scrolls: past that the draft is better read than seen whole,
                // and the feed above it is what the person is answering.
                maxLines = 5,
                textStyle = PortholeType.body.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                // The keyboard's own send key submits, so the thumb never has to travel
                // to the corner button for a short prompt.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = if (enabled) placeholder else reason },
            )
        }
        Box(
            Modifier
                .size(48.dp)
                .background(if (ready) c.accent else c.raised, PortholeShape.pill)
                .clickable(enabled = ready, role = Role.Button) { onSend() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Send, contentDescription = "Send",
                tint = if (ready) c.onAccent else c.faint, modifier = Modifier.size(20.dp),
            )
        }
    }
}


/**
 * One press on the CLI's question picker, in the order the daemon applies them: the
 * option's digit, typed text (for "Type something"), Right to leave a multi-select
 * question, Enter on the review screen.
 */
data class Answer(val option: Int = 0, val text: String = "", val advance: Boolean = false, val submit: Boolean = false)

/**
 * A question as the transcript recorded it - which the CLI does only once it has been
 * answered (measured on 2.1.270). History, therefore: what was asked and offered; the
 * result row beneath says what was chosen. The live picker is [LiveQuestionCard].
 */
@Composable
private fun QuestionRow(r: FeedRow, answered: Boolean) {
    val c = Porthole.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.raised, PortholeShape.card)
            .border(1.dp, c.edge, PortholeShape.card)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (answered) "Claude asked" else "Claude is asking", style = PortholeType.meta, color = if (answered) c.faint else c.accent)
        r.questions.forEachIndexed { qi, q ->
            if (q.header.isNotBlank()) Text(q.header, style = PortholeType.rowTitle, color = c.text)
            Text(q.text, style = PortholeType.body, color = c.text)
            q.options.forEachIndexed { i, o ->
                Text(
                    "${i + 1}. ${o.label}" + if (o.description.isNotBlank() && o.description != o.label) " · ${o.description}" else "",
                    style = PortholeType.secondary, color = c.muted,
                )
            }
            if (qi < r.questions.size - 1) LayoutSpacer(Modifier.height(4.dp))
        }
    }
}

/**
 * The picker on the CLI's screen, answerable by tap. Every button is one key press the
 * daemon makes in the picker; the next status frame (a second later) shows the result,
 * so ticks, the next question and the review screen are always what the desk would see.
 */
@Composable
private fun LiveQuestionCard(q: ScreenQuestion, onAnswer: (Answer) -> Unit, onType: () -> Unit) {
    val c = Porthole.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(c.raised, PortholeShape.card)
            .border(1.dp, c.accent, PortholeShape.card)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            when {
                q.review -> "Claude is asking you · review"
                q.total > 1 -> "Claude is asking you · ${q.index} of ${q.total}"
                else -> "Claude is asking you"
            },
            style = PortholeType.meta, color = c.accent,
        )
        if (q.header.isNotBlank() && !q.review) Text(q.header, style = PortholeType.rowTitle, color = c.text)
        Text(q.text, style = PortholeType.body, color = c.text)
        q.options.forEach { o ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(if (o.checked) c.accent.copy(alpha = 0.16f) else c.surface, PortholeShape.control)
                    .border(1.dp, if (o.checked) c.accent else c.edge, PortholeShape.control)
                    .clickable(role = Role.Button) { onAnswer(Answer(option = o.n)) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .semantics { contentDescription = "Option ${o.n}: ${o.label}" + if (o.checked) ", selected" else "" },
            ) {
                Text((if (q.multi) (if (o.checked) "☑ " else "☐ ") else "${o.n}. ") + o.label, style = PortholeType.body, color = c.text)
                if (o.description.isNotBlank() && o.description != o.label) Text(o.description, style = PortholeType.secondary, color = c.muted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // A multi-select is left with Right; the CLI then shows the next question or
            // the review screen, which has its own Submit answers button.
            if (q.multi) Pill("Next", filled = q.options.any { it.checked }) { onAnswer(Answer(advance = true)) }
            if (q.typed > 0 || (q.options.isEmpty() && !q.review)) Pill("Type something", filled = false, onClick = onType)
        }
    }
}


/** One tap sends the words; the last pill edits the set (a visible control, not a hidden gesture). */
@Composable
private fun QuickReplies(replies: List<String>, onSend: (String) -> Unit, onEdit: () -> Unit) {
    val c = Porthole.colors
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        replies.filter { it.isNotBlank() }.forEach { text -> Pill(text, filled = false) { onSend(text) } }
        IconTarget(Icons.Outlined.Edit, "Edit the quick replies", onEdit, tint = c.faint)
    }
}

/** Four slots, blank to remove one. What is typed here is sent verbatim, like the composer. */
@Composable
private fun QuickReplyEditor(current: List<String>, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val c = Porthole.colors
    val slots = remember { (0 until 4).map { androidx.compose.runtime.mutableStateOf(current.getOrNull(it) ?: "") } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(c.surface, PortholeShape.card)
                .border(1.dp, c.edge, PortholeShape.card)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Quick replies", style = Porthole.type.display, color = c.text)
            Text("Sent as typed, the moment you tap one.", style = PortholeType.secondary, color = c.muted)
            slots.forEachIndexed { i, st -> Field(st.value, { st.value = it }, placeholder = "Reply ${i + 1}") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Cancel", filled = false, onClick = onDismiss)
                Pill("Save", filled = true) { onSave(slots.map { it.value.trim() }) }
            }
        }
    }
}
