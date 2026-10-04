package dev.shrimpscript.porthole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Download
import dev.shrimpscript.porthole.BuildConfig
import dev.shrimpscript.porthole.net.BuildInfo
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.net.Failure
import dev.shrimpscript.porthole.net.SessionInfo
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeMotion
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

@Composable
fun Screen(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Porthole.colors.ground)
    ) { content() }
}

/* ---------------------------------------------------------------- connect --- */

@Composable
fun ConnectScreen(
    host: String,
    onHostChange: (String) -> Unit,
    checking: Boolean,
    result: String?,
    reachable: Boolean,
    onCheck: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) = OnboardingFrame(step = 4, onBack = onBack) {
    Text("Which computer?", style = Porthole.type.display, color = Porthole.colors.text)
    Spacer(Modifier.height(12.dp))
    Text(
        "Its name on your tailnet, or its 100.x address. Porthole checks the daemon " +
            "is actually answering before asking you for a code.",
        style = PortholeType.body, color = Porthole.colors.muted,
    )
    Spacer(Modifier.height(24.dp))
    Field(
        value = host,
        onValueChange = onHostChange,
        placeholder = "my-pc  or  100.x.y.z",
        mono = true,
    )
    Spacer(Modifier.height(12.dp))
    if (result != null) {
        Appear {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Ring(state = if (reachable) RingState.Live else RingState.Dropped, size = 16.dp)
                Text(
                    result,
                    style = PortholeType.secondary,
                    color = if (reachable) Porthole.colors.ok else Porthole.colors.bad,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (reachable) {
        PrimaryButton("Continue", onContinue)
    } else {
        PrimaryButton(
            if (checking) "Checking…" else "Check connection",
            onCheck,
            enabled = host.isNotBlank() && !checking,
        )
    }
    Spacer(Modifier.height(16.dp))
    CommandBlock("portholed serve", "On the computer, this prints the address it listens on:")
}

/* ---------------------------------------------------------------- consent --- */

@Composable
fun ConsentScreen(machine: String, onCancel: () -> Unit, onAuthorize: () -> Unit, caps: List<String> = emptyList()) = Screen {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Authorize Porthole", style = Porthole.type.display, color = Porthole.colors.text)
        Spacer(Modifier.height(12.dp))
        Text(
            "This phone will be able to do the following on $machine:",
            style = PortholeType.body, color = Porthole.colors.muted,
        )
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            buildList {
                add(Triple("›_", "Run commands", "As your user, with full shell access"))
                add(Triple("◧", "Read and write files", "Anywhere your user can"))
                add(Triple("✓", "Approve tool calls", "Decide permission prompts remotely"))
                add(Triple("≡", "Read session history", "Transcripts of your Claude Code sessions"))
                if ("capture" in caps || "record" in caps) {
                    add(Triple("▣", "Capture your screen", "Screenshots and short clips of the desktop, only when you ask; screens asleep are woken for them"))
                }
                if ("preview" in caps) {
                    add(Triple("⇢", "Share a dev server", "Open a site running on the computer in this phone's browser, over the tailnet, only when you ask"))
                }
                if ("start" in caps) {
                    add(Triple("▶", "Start Claude Code", "In a project it has been used in before, only when you ask"))
                }
            }.forEachIndexed { i, (g, n, a) ->
                Appear(delayMs = i * PortholeMotion.STAGGER_MS) { ScopeRow(g, n, a) }
            }
        }
        Text(
            "Porthole installs nothing on the computer. These are what portholed - which " +
                "you installed yourself, at the keyboard - will let this phone do. Revoke it " +
                "any time with portholed revoke.",
            style = PortholeType.secondary, color = Porthole.colors.faint,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GhostButton("Cancel", onCancel, Modifier.weight(1f))
            PrimaryButton("Authorize", onAuthorize, Modifier.weight(1f))
        }
    }
}

/* --------------------------------------------------------------- sessions --- */

/** "2m", "3h", "1d" - or "" when the timestamp is unreadable. */
fun relativeTime(iso: String, now: Instant = Instant.now()): String {
    val t = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull() ?: return ""
    val d = Duration.between(t, now)
    return when {
        d.isNegative || d.toMinutes() < 1 -> "now"
        d.toMinutes() < 60 -> "${d.toMinutes()}m"
        d.toHours() < 24 -> "${d.toHours()}h"
        else -> "${d.toDays()}d"
    }
}

/** "14:05" for a row's timestamp, local time. */
fun clockTime(iso: String): String =
    runCatching {
        val t = OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
        "%02d:%02d".format(t.hour, t.minute)
    }.getOrDefault("")

@Composable
fun SessionsScreen(
    machine: String,
    ring: RingState,
    sessions: List<SessionInfo>,
    onSession: (SessionInfo) -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    /** lastActive stamp per session id as of the last time it was opened, for the unseen mark. */
    seen: Map<String, String> = emptyMap(),
    /** "1 of 2 connected" when the phone has several computers; blank otherwise. */
    computers: String = "",
    /** The computer is unreachable and being retried: the bar's line; null while connected. */
    reconnecting: String? = null,
    /** After a few tries: open the failure card's tools. */
    onConnectionOptions: (() -> Unit)? = null,
    /** Something worth installing: a newer Porthole release, or a project's newest build. */
    update: BuildInfo? = null,
    /** Download progress 0..1 while fetching; null when idle. */
    updateProgress: Float? = null,
    updateNote: String? = null,
    onUpdate: () -> Unit = {},
    /** Put this build away; a newer version of the same app brings the banner back. */
    onDismissUpdate: () -> Unit = {},
    /** Start a fresh Claude Code in a folder; null when no computer can start one. */
    onNewSession: (() -> Unit)? = null,
) = Screen {
    val c = Porthole.colors
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Ring(state = ring, size = 20.dp)
            Column(Modifier.weight(1f)) {
                Text(machine, style = PortholeType.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${sessions.size} session${if (sessions.size == 1) "" else "s"} · ${computers.ifBlank { ring.label }}",
                    style = PortholeType.meta, color = c.faint,
                )
            }
            if (onNewSession != null) IconTarget(Icons.Outlined.Add, "New session", onNewSession)
            IconTarget(Icons.Outlined.Refresh, "Refresh sessions", onRefresh)
            IconTarget(Icons.Outlined.Settings, "Settings", onSettings)
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = reconnecting != null,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
        ) {
            var line by remember { androidx.compose.runtime.mutableStateOf("") }
            if (reconnecting != null) line = reconnecting
            ReconnectBar(line, onOptions = onConnectionOptions)
        }
        if (update != null) {
            Appear { UpdateBanner(update, updateProgress, updateNote, onUpdate, onDismissUpdate) }
        }
        // One clock for every working row; it ticks only while something is working.
        val anyWorking = sessions.any { it.working && it.workingSince > 0 }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(anyWorking) {
            while (anyWorking) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
        }
        if (sessions.isEmpty()) {
            EmptySessions()
        } else {
            // Sessions waiting on the person come first: away from the desk, the list's
            // job is triage. Then working sessions, then the rest.
            // Keys must be unique or the lazy list throws. The daemon promises one row per
            // session now; this keeps an older one from being able to close the app.
            val sessions = sessions.distinctBy { it.machineId + "/" + it.id }
            // One of several computers the phone cannot reach right now: its rows, as last
            // seen, apart. With one computer the rows keep their places, each as last seen,
            // so a drop does not reshuffle the list.
            fun apart(s: SessionInfo) = s.machineDown && s.machine.isNotBlank()
            val needs = sessions.filter { it.live && it.needsYou && !apart(it) }
            val live = sessions.filter { (it.live || it.tmux) && it !in needs && !apart(it) }.sortedWith(compareByDescending<SessionInfo> { it.working }.thenByDescending { it.live })
            val idle = sessions.filter { !it.live && !it.tmux && !apart(it) }
            val down = sessions.filter { apart(it) }.groupBy { it.machineId }
            LazyColumn(Modifier.fillMaxSize()) {
                if (needs.isNotEmpty()) item { SectionLabel("Needs you") }
                itemsIndexed(needs, key = { _, s -> s.machineId + "/" + s.id }) { i, s ->
                    Appear(delayMs = minOf(i, 5) * PortholeMotion.STAGGER_MS) { SessionRow(s, now) { onSession(s) } }
                }
                if (live.isNotEmpty()) item { SectionLabel("Live") }
                itemsIndexed(live, key = { _, s -> s.machineId + "/" + s.id }) { i, s ->
                    Appear(delayMs = minOf(i, 5) * PortholeMotion.STAGGER_MS) { SessionRow(s, now, unseen = !s.working && seen[s.id]?.let { it != s.lastActive } ?: false) { onSession(s) } }
                }
                if (idle.isNotEmpty()) item { SectionLabel("Recent") }
                itemsIndexed(idle, key = { _, s -> s.machineId + "/" + s.id }) { i, s ->
                    Appear(delayMs = minOf(i, 5) * PortholeMotion.STAGGER_MS) { SessionRow(s, now, unseen = seen[s.id]?.let { it != s.lastActive } ?: false) { onSession(s) } }
                }
                down.forEach { (machineId, rows) ->
                    val first = rows.first()
                    item(key = "down-$machineId") { SectionLabel("${first.machine} · ${first.machineState.ifBlank { "unreachable" }}") }
                    itemsIndexed(rows, key = { _, s -> machineId + "/" + s.id }) { i, s ->
                        Appear(delayMs = minOf(i, 5) * PortholeMotion.STAGGER_MS) { SessionRow(s, now) { onSession(s) } }
                    }
                }
            }
        }
    }
}

/**
 * Something to install. Either a new Porthole release, from GitHub, or the newest build
 * of an app being worked on at the computer, published there with `portholed publish`.
 * One tap downloads it and hands it to Android's installer; the first time, Android asks
 * to allow installs from Porthole. Either can be put away until a newer version comes.
 */
@Composable
internal fun UpdateBanner(update: BuildInfo, progress: Float?, note: String?, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    val c = Porthole.colors
    val own = update.isPorthole
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(c.surface, PortholeShape.card)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Download, null, tint = c.muted, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (own) "Porthole ${update.version} is out" else "${update.name} ${update.version} is on the computer",
                    style = PortholeType.rowTitle, color = c.text,
                )
                Text(
                    when {
                        progress != null -> "Downloading \u00b7 ${(progress * 100).toInt()}%"
                        note != null -> note
                        own -> "You have ${BuildConfig.VERSION_NAME}. Downloads from GitHub, then Android asks to update."
                        else -> "Downloads from your computer, then Android asks to install or update it."
                    },
                    style = PortholeType.meta, color = if (note != null && progress == null) c.warn else c.faint,
                )
            }
        }
        if (progress == null) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Pill("Not now", filled = false, onClick = onDismiss)
                Pill(if (own) "Update" else "Install", filled = true, onClick = onUpdate)
            }
        }
        if (progress != null) {
            // The ring is the app's one loading motif: it fills as the download does.
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Ring(state = if (progress > 0f) RingState.Live else RingState.Connecting, size = 18.dp, reveal = progress)
                Text("${(progress * 100).toInt()}% of the download", style = PortholeType.meta, color = c.faint)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, style = PortholeType.secondary, color = Porthole.colors.muted,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
/** m:ss, or h:mm:ss past an hour. */
fun elapsedLabel(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, sec) else "%d:%02d".format(java.util.Locale.ROOT, m, sec)
}

@Composable
private fun SessionRow(s: SessionInfo, now: Long = 0L, unseen: Boolean = false, onClick: () -> Unit) {
    val c = Porthole.colors
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // The only ring that spins on this screen is a session that is working.
            Ring(
                // As last seen, nothing spins: whether it is still working is not known.
                state = when { s.machineDown -> RingState.Idle; s.needsYou -> RingState.NeedsYou; s.working -> RingState.Connecting; s.live -> RingState.Live; else -> RingState.Idle }, size = 18.dp,
                label = when { s.asking.isNotBlank() -> "asking you"; s.waiting -> "waiting for you"; s.working -> "working"; s.live -> "live"; else -> "idle" },
                modifier = Modifier.padding(top = 2.dp).sharedAcrossRoutes("ring-${s.id}"),
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        s.title,
                        style = PortholeType.rowTitle,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).sharedAcrossRoutes("title-${s.id}", bounds = true),
                    )
                    // Finished since you last opened it: a mark, not a badge with a number.
                    if (unseen) Box(Modifier.size(7.dp).background(c.accent, PortholeShape.pill).semantics { contentDescription = "new since you looked" })
                }
                // The state on a line of its own, in colour; the details quieter beneath it.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.working && !s.needsYou && !s.machineDown) {
                        Spinner(Modifier.padding(end = 6.dp))
                    }
                    Text(
                        when {
                            s.machineDown -> "as last seen"
                            s.asking.isNotBlank() -> "asking you: ${s.asking}"
                            s.waiting -> if (s.waitingWhat.isNotBlank()) "waiting for you: ${s.waitingWhat}" else "waiting for you"
                            // Time first: it is the number that decides whether to wait, and a
                            // long command must never push it off the row.
                            s.working -> listOfNotNull(
                                if (s.workingSince > 0 && now > 0) elapsedLabel(now - s.workingSince) else null,
                                s.doing.ifBlank { "working" },
                            ).joinToString(" · ")
                            s.live -> "live"
                            s.tmux -> "shell only, Claude not running"
                            else -> "idle"
                        },
                        style = PortholeType.secondary,
                        color = when { s.needsYou -> c.accent; s.working && !s.machineDown -> c.text; else -> c.muted },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // The tmux session is the handle the person has for "which terminal": with two
                // sessions in one directory it is the only thing on the row that tells them apart.
                val details = listOfNotNull(
                    if (s.agents > 0) (if (s.agents == 1) "1 agent" else "${s.agents} agents") else null,
                    s.machine.ifBlank { null },
                    s.branch.ifBlank { null },
                    if (s.tmuxName.isNotBlank() && (s.live || s.tmux)) "tmux ${s.tmuxName}" else null,
                    modelShortName(s.model).ifBlank { null },
                ).joinToString(" · ")
                if (details.isNotBlank()) Text(details, style = PortholeType.meta, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(relativeTime(s.lastActive), style = PortholeType.meta, color = c.faint)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 46.dp)
                .height(1.dp)
                .background(c.edge)
        )
    }
}

@Composable
private fun EmptySessions() {
    val c = Porthole.colors
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No sessions yet", style = PortholeType.title, color = c.text)
        Spacer(Modifier.height(12.dp))
        Text(
            "Porthole lists the Claude Code sessions it finds on the computer. Start one at " +
                "the desk in your project's folder, with porthole instead of claude - it runs " +
                "Claude Code inside tmux, so this phone can type to it too:",
            style = PortholeType.body, color = c.muted,
        )
        Spacer(Modifier.height(16.dp))
        // The empty state is the actual command, not an illustration.
        CommandBlock("porthole")
    }
}

/* ---------------------------------------------------------------- failure --- */

@Composable
fun FailureScreen(
    reason: Failure,
    detail: String,
    machine: String,
    onRetry: () -> Unit,
    onRepair: () -> Unit,
    onOpenSsh: (() -> Unit)? = null,
    onRestartDaemon: (() -> Unit)? = null,
    busy: String? = null,
    /** The app is still trying underneath this card, so say so and keep the ring moving. */
    retrying: Boolean = false,
) = Screen {
    val c = Porthole.colors
    val (title, body, action) = when (reason) {
        Failure.NotPaired -> Triple(
            "This phone isn't paired",
            "Run portholed pair on $machine and enter the code.",
            "Pair now",
        )
        Failure.BadCode -> Triple(
            "That code didn't work",
            "It may have expired. Run portholed pair again for a new one.",
            "Try again",
        )
        Failure.Revoked -> Triple(
            "This device was revoked",
            "$machine removed this phone. Pair again to restore access.",
            "Pair again",
        )
        Failure.NotTailnet -> Triple(
            "Not recognised on the tailnet",
            "Check that Tailscale is connected on this phone and that both devices are on the same tailnet.",
            "Retry",
        )
        Failure.NeedsReauth -> Triple(
            "Tailscale wants you to sign in again",
            "This tailnet's SSH rule is set to \"check\", which asks for a browser sign-in every " +
                "12 hours. Setting that rule to \"accept\" in the Tailscale admin console removes it.",
            "Open Tailscale",
        )
        Failure.VersionSkew -> Triple(
            "$machine runs a newer Porthole",
            if (detail.isNotBlank()) detail
            else "Update the app to use everything this computer now offers.",
            "Retry anyway",
        )
        else -> Triple(
            "Can't reach $machine",
            if (detail.isNotBlank()) detail
            else "Tailscale may be off, the computer asleep, or portholed stopped.",
            "Retry",
        )
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Ring(
            state = if (retrying) RingState.Retrying else RingState.Dropped,
            size = 44.dp, strokeWidth = 3.dp,
        )
        Spacer(Modifier.height(24.dp))
        Appear { Text(title, style = Porthole.type.display, color = c.text) }
        Spacer(Modifier.height(12.dp))
        Appear(delayMs = PortholeMotion.STAGGER_MS) { Text(body, style = PortholeType.body, color = c.muted) }
        if (retrying) {
            Spacer(Modifier.height(8.dp))
            // Not decoration: without it the card reads as "gave up", and someone walks
            // back to their desk for a connection that was about to return on its own.
            Text(
                "Still trying. This clears itself when the computer answers.",
                style = PortholeType.secondary, color = c.faint,
            )
        }
        Spacer(Modifier.height(28.dp))
        PrimaryButton(
            action,
            if (reason == Failure.NotPaired || reason == Failure.Revoked || reason == Failure.BadCode) onRepair else onRetry,
        )

        // The promise that makes this app trustworthy away from a desk: when the daemon is
        // the thing that is broken, there is still a real shell, and a way to repair it
        // from here rather than from the chair in front of the computer.
        if (reason == Failure.Unreachable && onOpenSsh != null) {
            Spacer(Modifier.height(12.dp))
            GhostButton("Open terminal anyway", onOpenSsh)
            if (onRestartDaemon != null) {
                Spacer(Modifier.height(12.dp))
                GhostButton("Restart the daemon over SSH", onRestartDaemon)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Both go over SSH - Tailscale SSH, or on a Mac this phone's own key - which does " +
                    "not depend on Porthole running.",
                style = PortholeType.secondary, color = c.faint,
            )
        }
        if (busy != null) {
            Spacer(Modifier.height(16.dp))
            Appear { Text(busy, style = PortholeType.secondary, color = c.muted) }
        }
    }
}
