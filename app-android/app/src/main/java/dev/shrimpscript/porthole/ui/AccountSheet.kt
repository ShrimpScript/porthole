package dev.shrimpscript.porthole.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.net.ClaudeAccount
import dev.shrimpscript.porthole.net.Restart
import dev.shrimpscript.porthole.net.SessionInfo
import dev.shrimpscript.porthole.net.SignIn
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType

/** "you@example.com · Max": who Claude Code is signed in as, on which plan. */
fun ClaudeAccount.label(): String = listOf(email.ifBlank { "Signed in" }, planName).filter { it.isNotBlank() }.joinToString(" · ")

/**
 * The account rows in Settings: who Claude Code on the computer is signed in as, and the
 * way to another account or out. Signing out asks first, in place.
 */
@Composable
fun ClaudeAccountRows(account: ClaudeAccount?, onSwitch: () -> Unit, onSignOut: () -> Unit) {
    val c = Porthole.colors
    var confirm by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Claude account", style = PortholeType.secondary, color = c.muted, modifier = Modifier.weight(1f))
            Text(
                when {
                    account == null -> "…"
                    account.error != null -> "unknown"
                    account.signedIn -> account.label()
                    else -> "signed out"
                },
                style = PortholeType.secondary, color = if (account?.signedIn == false && account.error == null) c.warn else c.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        account?.error?.let { Text(it, style = PortholeType.meta, color = c.faint) }
        if (account != null && !account.signedIn && account.error == null) {
            Text("Sessions on this computer cannot reach Claude until it is signed in again.", style = PortholeType.meta, color = c.faint)
            PrimaryButton("Sign in", onSwitch)
        } else if (account?.signedIn == true) {
            if (!confirm) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Switch account", onSwitch, Modifier.weight(1f))
                    GhostButton("Sign out", { confirm = true }, Modifier.weight(1f))
                }
            } else {
                Text(
                    "Claude Code on this computer signs out of ${account.email.ifBlank { "this account" }}. " +
                        "Its sessions stop reaching Claude until it is signed in again.",
                    style = PortholeType.secondary, color = c.muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Keep", { confirm = false }, Modifier.weight(1f))
                    Box(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .background(c.bad, PortholeShape.control)
                            .clickable { confirm = false; onSignOut() },
                        contentAlignment = Alignment.Center,
                    ) { Text("Sign out", style = PortholeType.body, color = c.onAccent) }
                }
            }
        }
    }
}

/**
 * Signing Claude Code on [machine] in to an account, as a sheet: the computer starts
 * Claude's own sign-in, the phone opens its page in the browser, and the code the page
 * shows comes back - picked up from the clipboard when it is copied, or pasted. Then the
 * sessions already running are offered a restart, so they use the account too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(
    machine: String,
    signIn: SignIn,
    sessions: List<SessionInfo>,
    restarts: Map<String, Restart>,
    onCode: (String) -> Unit,
    onRetry: () -> Unit,
    onRestart: (List<String>) -> Unit,
    onCancelRestart: (String) -> Unit,
    onClose: () -> Unit,
) {
    val c = Porthole.colors
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        AccountFlow(machine, signIn, sessions, restarts, onCode, onRetry, onRestart, onCancelRestart, onClose)
    }
}

/** The sheet's body, drawn on its own too (a modal sheet is its own window). */
@Composable
fun AccountFlow(
    machine: String,
    signIn: SignIn,
    sessions: List<SessionInfo>,
    restarts: Map<String, Restart>,
    onCode: (String) -> Unit,
    onRetry: () -> Unit,
    onRestart: (List<String>) -> Unit,
    onCancelRestart: (String) -> Unit = {},
    onClose: () -> Unit,
) {
    val c = Porthole.colors
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (signIn) {
            SignIn.Idle, SignIn.Starting -> {
                Text("Sign in to Claude", style = PortholeType.title, color = c.text)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spinner(color = c.accent)
                    Text("Starting Claude Code's sign-in on $machine…", style = PortholeType.secondary, color = c.muted)
                }
                GhostButton("Cancel", onClose)
            }
            is SignIn.Waiting -> Waiting(machine, signIn, onCode, onClose)
            is SignIn.Failed -> {
                Text("Not signed in", style = PortholeType.title, color = c.text)
                Text(signIn.error, style = PortholeType.secondary, color = c.bad)
                PrimaryButton("Try again", onRetry)
                GhostButton("Close", onClose)
            }
            is SignIn.Done -> {
                Text("Signed in", style = PortholeType.title, color = c.text)
                Text(
                    signIn.account?.let { "Claude Code on $machine now uses ${it.label()}." } ?: "Claude Code on $machine is signed in.",
                    style = PortholeType.body, color = c.text,
                )
                Restarts(sessions, restarts, onRestart, onCancelRestart, onClose)
            }
        }
    }
    // The page opens by itself once per sign-in; again from the button.
    val url = (signIn as? SignIn.Waiting)?.url
    var opened by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(url) { if (url != null && url != opened) { opened = url; openPage(context, url) } }
}

private fun openPage(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun Waiting(machine: String, w: SignIn.Waiting, onCode: (String) -> Unit, onClose: () -> Unit) {
    val c = Porthole.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var code by rememberSaveable(w.url) { mutableStateOf("") }
    var picked by rememberSaveable(w.url) { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    // Coming back from the browser with the code copied: it is this sign-in's (it ends with
    // the link's own state), so it is used at once. The clipboard is read only while this
    // window has focus, which is when Android allows it.
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused, w.url, w.sending) {
        if (!focused || w.sending) return@LaunchedEffect
        val clip = runCatching { clipboard.getText()?.text }.getOrNull().orEmpty().trim()
        if (clip != picked && w.isCode(clip)) {
            picked = clip
            code = clip
            onCode(clip)
        }
    }
    Text("Sign in to Claude", style = PortholeType.title, color = c.text)
    Step("1", "Sign in on Claude's page", "It opened in your browser, signed in as whichever Claude account the browser uses. " +
        "For another account, sign out of claude.ai there first, or open the link in a private tab.")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton("Open the page again", { openPage(context, w.url) }, Modifier.weight(1f))
        GhostButton(if (copied) "Link copied" else "Copy link", { clipboard.setText(AnnotatedString(w.url)); copied = true }, Modifier.weight(1f))
    }
    Step("2", "Bring back the code", "Copy the code the page shows and come back: Porthole picks it up. Or paste it here.")
    Field(code, { code = it.trim() }, "Code from the sign-in page", mono = true)
    w.error?.let { Text(it, style = PortholeType.secondary, color = c.bad) }
    PrimaryButton(if (w.sending) "Signing in…" else "Sign in", { onCode(code) }, enabled = code.isNotBlank() && !w.sending)
    GhostButton("Cancel", onClose)
}

@Composable
private fun Step(n: String, title: String, detail: String) {
    val c = Porthole.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.background(c.raised, PortholeShape.pill).padding(horizontal = 9.dp, vertical = 2.dp)) {
            Text(n, style = PortholeType.secondary, color = c.text)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = PortholeType.rowTitle, color = c.text)
            Text(detail, style = PortholeType.meta, color = c.muted)
        }
    }
}

/**
 * The sessions already running on the computer, offered a restart: each picks its
 * conversation back up, signed in as the new account. One in the middle of a turn is
 * restarted when the turn ends.
 */
@Composable
private fun Restarts(sessions: List<SessionInfo>, restarts: Map<String, Restart>, onRestart: (List<String>) -> Unit, onCancel: (String) -> Unit, onClose: () -> Unit) {
    val c = Porthole.colors
    val running = sessions.filter { it.live && it.tmux }
    if (running.isEmpty()) {
        PrimaryButton("Done", onClose)
        return
    }
    Spacer(Modifier.height(4.dp))
    Text("Sessions already running", style = PortholeType.rowTitle, color = c.text)
    Text(
        "They may keep using the account they started with. A restart picks each conversation back up on the new one; " +
            "a session in the middle of something restarts when it finishes, and one waiting out a usage limit stops waiting.",
        style = PortholeType.meta, color = c.muted,
    )
    running.forEach { s ->
        val r = restarts[s.id]
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(s.title, style = PortholeType.secondary, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            when {
                r?.restarted == true -> Text("restarted", style = PortholeType.meta, color = c.ok)
                r?.waiting == true -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Spinner(color = c.accent)
                    Text("after it finishes", style = PortholeType.meta, color = c.muted)
                    Text("Cancel", style = PortholeType.meta, color = c.accent,
                        modifier = Modifier.clickable { onCancel(s.id) }.padding(horizontal = 6.dp, vertical = 8.dp))
                }
                r?.cancelled == true -> Text("not restarted", style = PortholeType.meta, color = c.faint)
                r?.failed == true -> Text("not restarted", style = PortholeType.meta, color = c.bad)
                s.working -> Text("working", style = PortholeType.meta, color = c.muted)
                else -> Text("idle", style = PortholeType.meta, color = c.faint)
            }
        }
        r?.takeIf { it.failed && it.error.isNotBlank() }?.let { Text(it.error.replaceFirstChar { ch -> ch.uppercase() }, style = PortholeType.meta, color = c.faint) }
    }
    val asked = running.any { restarts.containsKey(it.id) }
    if (!asked) {
        PrimaryButton(if (running.size == 1) "Restart it" else "Restart all ${running.size}", { onRestart(running.map { it.id }) })
        GhostButton("Not now", onClose)
    } else {
        PrimaryButton("Done", onClose)
    }
}
