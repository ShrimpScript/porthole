package dev.shrimpscript.porthole.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shrimpscript.porthole.net.Approval
import dev.shrimpscript.porthole.net.Row as FeedRow
import dev.shrimpscript.porthole.terminal.KeyRow
import dev.shrimpscript.porthole.terminal.Keys
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeMotion
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType
import dev.shrimpscript.porthole.ui.theme.motionEnabled
import kotlinx.coroutines.launch

/*
 * Onboarding: welcome -> what you need -> Tailscale -> set up the computer -> which
 * computer -> consent -> pair -> a short tour. One decision per screen, a back arrow and
 * a dot row for orientation. The setup and tour screens are also reachable later from
 * Settings, because nobody remembers the install steps a month on.
 */

const val ONBOARDING_STEPS = 7

/** Top bar (back + dots) and a scrolling body with 24dp padding. */
@Composable
fun OnboardingFrame(
    step: Int?,
    onBack: (() -> Unit)?,
    bottom: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) = Screen {
    val c = Porthole.colors
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconTarget(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
            } else {
                Spacer(Modifier.size(44.dp))
            }
            Spacer(Modifier.weight(1f))
            if (step != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(ONBOARDING_STEPS) { i ->
                        Box(
                            Modifier
                                .size(if (i + 1 == step) 8.dp else 6.dp)
                                .background(if (i + 1 == step) c.accent else c.edge, PortholeShape.pill)
                        )
                    }
                }
            }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) { content() }
        if (bottom != null) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) { bottom() }
        }
    }
}

/* ---------------------------------------------------------------- welcome --- */

@Composable
fun WelcomeScreen(onStart: () -> Unit, onHowItWorks: () -> Unit) = Screen {
    val c = Porthole.colors
    val on = motionEnabled()
    // The one choreographed moment: the mark draws itself in, then the words settle
    // under it. First run sees this once; a paired phone never sees it at all.
    val reveal = remember { Animatable(if (on) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (on) reveal.animateTo(1f, tween(PortholeMotion.SLOW_MS * 2, easing = PortholeMotion.settle))
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Ring(state = RingState.Mark, size = 96.dp, strokeWidth = 5.dp, reveal = reveal.value)
        Spacer(Modifier.height(28.dp))
        Appear(delayMs = 300) { Wordmark(ring = RingState.Mark) }
        Spacer(Modifier.height(28.dp))
        Appear(delayMs = 380) {
            Text(
                Brand.HEADLINE,
                style = PortholeType.title, color = c.text,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        Appear(delayMs = 440) {
            Text(Brand.SUB, style = PortholeType.body, color = c.muted, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(40.dp))
        Appear(delayMs = 520) {
            Column {
                PrimaryButton("Get started", onStart)
                Spacer(Modifier.height(12.dp))
                GhostButton("How it works", onHowItWorks)
            }
        }
    }
}

/* ------------------------------------------------------------- what you need --- */

@Composable
fun NeedsScreen(onContinue: () -> Unit, onBack: () -> Unit) = OnboardingFrame(
    step = 1, onBack = onBack,
    bottom = { PrimaryButton("Continue", onContinue) },
) {
    val c = Porthole.colors
    Text("What you'll need", style = Porthole.type.display, color = c.text)
    Spacer(Modifier.height(12.dp))
    Text(
        "Three things, all on your side. There is no account to create, because there is " +
            "no service to sign into.",
        style = PortholeType.body, color = c.muted,
    )
    Spacer(Modifier.height(24.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Appear(delayMs = 0) {
            ToolRow(
                Brand.tailscale, "Tailscale",
                "On this phone and on the computer, signed into the same tailnet. Free for personal use.",
            )
        }
        Appear(delayMs = PortholeMotion.STAGGER_MS) {
            ToolRow(
                Brand.claudeCode, "Claude Code, inside tmux",
                "Running on the computer. Start it with porthole and it lands in tmux for you; Porthole reads its transcript and attaches to that window.",
            )
        }
        Appear(delayMs = PortholeMotion.STAGGER_MS * 2) {
            ToolRow(
                Brand.tmux, "portholed",
                "The small companion service for the computer. It listens on your tailnet only, never the internet.",
            )
        }
    }
}

/* --------------------------------------------------------------- tailscale --- */

@Composable
fun TailscaleScreen(
    installed: Boolean,
    onOpenStore: () -> Unit,
    onOpenApp: () -> Unit,
    onRecheck: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) = OnboardingFrame(
    step = 2, onBack = onBack,
    bottom = {
        if (installed) {
            PrimaryButton("Continue", onContinue)
            Spacer(Modifier.height(12.dp))
            GhostButton("Open Tailscale to check it's connected", onOpenApp)
        } else {
            PrimaryButton("Get Tailscale", onOpenStore)
            Spacer(Modifier.height(12.dp))
            GhostButton("I've installed it", onRecheck)
        }
    },
) {
    val c = Porthole.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ToolMark(Brand.tailscale, size = 56.dp, tint = c.text)
        if (installed) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = c.ok, modifier = Modifier.size(18.dp))
                Text("Installed on this phone", style = PortholeType.secondary, color = c.ok)
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    Text(
        if (installed) "Tailscale is installed" else "First, install Tailscale",
        style = Porthole.type.display, color = c.text,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        if (installed)
            "Porthole reaches your computer through Tailscale's private network. Make sure " +
                "it is connected and signed into the same account as the computer, then continue."
        else
            "Tailscale is a free private network between your own devices. Porthole uses it " +
                "so your phone can reach your computer directly, wherever you are, with " +
                "nothing in between.",
        style = PortholeType.body, color = c.muted,
    )
    Spacer(Modifier.height(20.dp))
    Text("On the computer", style = PortholeType.secondary, color = c.muted)
    Spacer(Modifier.height(8.dp))
    CommandBlock("tailscale up --ssh", "Install Tailscale there too, then:")
    Spacer(Modifier.height(8.dp))
    Text(
        "--ssh turns on Tailscale SSH, which is what Porthole falls back to if its own " +
            "daemon ever stops answering.",
        style = PortholeType.secondary, color = c.faint,
    )
}

/* ------------------------------------------------------------ computer setup --- */

@Composable
fun SetupScreen(onContinue: () -> Unit, onBack: () -> Unit, fromSettings: Boolean = false) = OnboardingFrame(
    step = if (fromSettings) null else 3, onBack = onBack,
    bottom = {
        PrimaryButton(if (fromSettings) "Done" else "Continue", onContinue)
        if (!fromSettings) {
            Spacer(Modifier.height(12.dp))
            GhostButton("Already set up", onContinue)
        }
    },
) {
    val c = Porthole.colors
    Text("Set up the computer", style = Porthole.type.display, color = c.text)
    Spacer(Modifier.height(12.dp))
    Text(
        "Three steps at the keyboard, once. Everything here is copyable.",
        style = PortholeType.body, color = c.muted,
    )
    Spacer(Modifier.height(24.dp))

    SetupStep(1, Brand.github, "Install portholed") {
        Text(
            "Clone the Porthole repository and run the installer. Read it first: it installs " +
                "a user service that can run commands as you, and a Claude Code hook for " +
                "remote approvals.",
            style = PortholeType.secondary, color = c.muted,
        )
        Spacer(Modifier.height(10.dp))
        CommandBlock("git clone https://github.com/ShrimpScript/porthole")
        Spacer(Modifier.height(8.dp))
        CommandBlock("cd porthole && ./tools/install.sh")
    }
    SetupStep(2, Brand.claudeCode, "Start Claude Code with porthole") {
        Text(
            "In your project's folder, run porthole where you would run claude. It starts " +
                "Claude Code inside tmux, so the terminal on your phone is the same screen as " +
                "at the desk; running it again in the same folder brings that session back. " +
                "Already inside tmux? Plain claude works too.",
            style = PortholeType.secondary, color = c.muted,
        )
        Spacer(Modifier.height(10.dp))
        CommandBlock("porthole")
    }
    SetupStep(3, Brand.tmux, "Get a pairing code") {
        Text(
            "It prints a 6-digit code that is good for five minutes. The next screens ask for it.",
            style = PortholeType.secondary, color = c.muted,
        )
        Spacer(Modifier.height(10.dp))
        CommandBlock("portholed pair")
    }
}

@Composable
private fun SetupStep(n: Int, icon: Int, title: String, content: @Composable () -> Unit) {
    val c = Porthole.colors
    Appear(delayMs = (n - 1) * PortholeMotion.STAGGER_MS) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .background(c.surface, PortholeShape.card)
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolMark(icon, size = 36.dp, tint = c.muted)
                Text("$n", style = PortholeType.meta, color = c.accent)
                Text(title, style = PortholeType.rowTitle, color = c.text)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/* ------------------------------------------------------------------- pair --- */

@Composable
fun PairScreen(
    code: String,
    onCodeChange: (String) -> Unit,
    host: String,
    error: String?,
    connecting: Boolean,
    onPair: () -> Unit,
    onBack: () -> Unit,
    /** Opens the phone's code scanner; null when the app cannot offer one. */
    onScan: (() -> Unit)? = null,
    /** Where the filled-in address came from, when it was not typed here. */
    note: String? = null,
) = OnboardingFrame(
    step = 6, onBack = onBack,
    bottom = {
        PrimaryButton(
            if (connecting) "Pairing…" else "Pair",
            onPair,
            enabled = code.length == 6 && !connecting,
        )
    },
) {
    val c = Porthole.colors
    Text("Pair with $host", style = Porthole.type.display, color = c.text)
    Spacer(Modifier.height(12.dp))
    if (note != null) {
        Text(note, style = PortholeType.body, color = c.accent)
        Spacer(Modifier.height(12.dp))
    }
    Text(
        "On the computer, run the command below. It prints a QR to scan and a 6-digit code to type.",
        style = PortholeType.body, color = c.muted,
    )
    Spacer(Modifier.height(16.dp))
    CommandBlock("portholed pair")
    if (onScan != null) {
        Spacer(Modifier.height(16.dp))
        GhostButton("Scan the QR", onClick = onScan)
    }
    Spacer(Modifier.height(24.dp))
    Text("Or the pairing code", style = PortholeType.secondary, color = c.muted)
    Spacer(Modifier.height(10.dp))
    CodeCells(code, onCodeChange)
    if (error != null) {
        Spacer(Modifier.height(12.dp))
        Appear { Text(error, style = PortholeType.secondary, color = c.bad) }
    }
    Spacer(Modifier.height(16.dp))
    Text(
        "The code expires after five minutes. Run the command again for a new one.",
        style = PortholeType.secondary, color = c.faint,
    )
}

/** Six cells over one hidden numeric field. The GitHub device-verification shape. */
@Composable
private fun CodeCells(code: String, onCodeChange: (String) -> Unit) {
    val c = Porthole.colors
    BasicTextField(
        value = code,
        onValueChange = { v -> if (v.length <= 6 && v.all(Char::isDigit)) onCodeChange(v) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        textStyle = PortholeType.mono.copy(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(6) { i ->
                        val ch = code.getOrNull(i)?.toString() ?: ""
                        val active = i == code.length
                        Box(
                            Modifier
                                .weight(1f)
                                .height(56.dp)
                                .background(c.raised, PortholeShape.control)
                                .border(1.dp, if (active) c.accent else c.edge, PortholeShape.control),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(ch, style = PortholeType.mono.copy(fontSize = 24.sp), color = c.text)
                        }
                    }
                }
                // The real field: zero-size but composed, so the keyboard still binds to it.
                Box(Modifier.size(1.dp)) { inner() }
            }
        },
    )
}

/* ------------------------------------------------------------------- tour --- */

private data class TourPage(val title: String, val body: String, val illustration: @Composable () -> Unit)

/**
 * How to use it, in four pages, shown once after pairing and again from Settings. Every
 * illustration is a real component with example content, never a picture of one.
 */
@Composable
fun TourScreen(onDone: () -> Unit, onSkip: (() -> Unit)?) = Screen {
    val c = Porthole.colors
    val pages = remember {
        listOf(
            TourPage(
                "The feed",
                "Everything Claude does, as it happens, read from its own transcript: your prompts, " +
                    "its replies, each tool call and its result. Long replies fold; tap to unfold. " +
                    "Tap a tool row for the full output.",
            ) { SampleFeed() },
            TourPage(
                "Approve from anywhere",
                "When Claude Code needs permission, the request lands here with the command in " +
                    "full, exactly as it will run. Allow or deny. Nothing is ever approved for you, " +
                    "and a request you ignore falls back to the prompt at the desk.",
            ) {
                ApprovalCardBody(
                    Approval("x", "s", "Bash", "rm -rf target/ && cargo build --release", "~/orbit-sim", 90),
                    remaining = 74, onAllow = null, onDeny = null,
                )
                Text("Example", style = PortholeType.meta, color = c.faint, modifier = Modifier.padding(top = 6.dp))
            },
            TourPage(
                "The real terminal",
                "Switch to Terminal to see the exact tmux window from your computer, at the " +
                    "computer's own width, and type into it. Pinch to zoom, or tap fit to see the " +
                    "whole screen. The key row has what Claude Code needs; try one below.",
            ) { SampleKeyRow() },
            TourPage(
                "Never stranded",
                "If portholed stops answering, Porthole says so and offers a plain SSH shell over " +
                    "Tailscale - a path that does not depend on Porthole running at all. From there " +
                    "you can restart the daemon and carry on.",
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(c.surface, PortholeShape.card)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Ring(state = RingState.Dropped, size = 24.dp, strokeWidth = 2.5.dp)
                        Text("Can't reach workstation", style = PortholeType.rowTitle, color = c.text)
                    }
                    Text("You'll be offered:", style = PortholeType.secondary, color = c.muted)
                    Text("· Open terminal anyway", style = PortholeType.secondary, color = c.text)
                    Text("· Restart the daemon over SSH", style = PortholeType.secondary, color = c.text)
                    Text("Example", style = PortholeType.meta, color = c.faint)
                }
            },
        )
    }
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("How Porthole works", style = PortholeType.secondary, color = c.muted, modifier = Modifier.weight(1f))
            if (onSkip != null) {
                Text(
                    "Skip", style = PortholeType.secondary, color = c.accent,
                    modifier = Modifier
                        .clickable { onSkip() }
                        .padding(12.dp),
                )
            }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { i ->
            val p = pages[i]
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text(p.title, style = Porthole.type.display, color = c.text)
                Spacer(Modifier.height(12.dp))
                Text(p.body, style = PortholeType.body, color = c.muted)
                Spacer(Modifier.height(24.dp))
                p.illustration()
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                repeat(pages.size) { i ->
                    Box(
                        Modifier
                            .size(if (i == pager.currentPage) 8.dp else 6.dp)
                            .background(if (i == pager.currentPage) c.accent else c.edge, PortholeShape.pill)
                    )
                }
            }
            val last = pager.currentPage == pages.size - 1
            PrimaryButton(
                if (last) "Done" else "Next",
                onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                modifier = Modifier.width(140.dp),
            )
        }
    }
}

@Composable
private fun SampleFeed() {
    val c = Porthole.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, PortholeShape.card)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FeedRowView(FeedRow("user", "", "Run the tests and fix whatever fails.", "", "", false), 0, {}, false)
        FeedRowView(FeedRow("tool", "▸", "Ran cargo test", "", "", false), 1, {}, false)
        FeedRowView(FeedRow("result", "✓", "done", "41 lines", "", false), 2, {}, false)
        FeedRowView(FeedRow("assistant", "", "Two failures in `solver.rs`, both the same off-by-one. Fixed and **all 41 pass**.", "", "", false), 3, {}, false)
        Text("Example", style = PortholeType.meta, color = c.faint)
    }
}

/** The real key row; here it reports what it would send instead of sending it. */
@Composable
private fun SampleKeyRow() {
    val c = Porthole.colors
    var last by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SegmentedToggle("Feed" to "Terminal", selected = 1, onSelect = {})
        KeyRow(onSend = { last = it })
        Text(
            when (val k = last) {
                null -> "Tap a key to see what it sends."
                Keys.ESC -> "Sends Esc - interrupts Claude Code."
                Keys.SHIFT_TAB -> "Sends Shift-Tab - cycles Claude Code's permission mode."
                Keys.ctrl('C') -> "Sends Ctrl-C."
                Keys.ctrl('B') -> "Sends Ctrl-B - the tmux prefix."
                Keys.TAB -> "Sends Tab."
                else -> "Sends " + k.map { ch -> if (ch.code < 32) "^" + (ch.code + 64).toChar() else ch.toString() }.joinToString("") + "."
            },
            style = PortholeType.secondary, color = c.muted,
        )
    }
}
