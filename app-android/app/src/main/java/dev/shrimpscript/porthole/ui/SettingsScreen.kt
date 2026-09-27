package dev.shrimpscript.porthole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.Role
import dev.shrimpscript.porthole.Notifier
import dev.shrimpscript.porthole.Updater
import dev.shrimpscript.porthole.net.BuildInfo
import dev.shrimpscript.porthole.ui.theme.Appearance
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeColors
import dev.shrimpscript.porthole.ui.theme.PortholeMotion
import dev.shrimpscript.porthole.ui.theme.ThemeChoice
import dev.shrimpscript.porthole.ui.theme.paletteFor
import dev.shrimpscript.porthole.ui.theme.spec
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType
import dev.shrimpscript.porthole.ui.theme.motionEnabled

/**
 * Settings. Only things that are real: the machine this phone is paired to, the
 * terminal's text size (with the column count it buys, so the trade-off is visible),
 * the guides, and the way out. Animations are reported, not toggled: the system setting
 * is the one source of truth and the app follows it.
 */
@Composable
fun SettingsScreen(
    host: String,
    deviceName: String,
    daemonVersion: String,
    appVersion: String,
    autoContinue: Boolean = true,
    notifyDone: Boolean = true,
    onNotifyDone: (Boolean) -> Unit = {},
    /** A Porthole release newer than this one, found on GitHub; null when up to date. */
    release: BuildInfo? = null,
    releaseChecks: Boolean = true,
    onReleaseChecks: (Boolean) -> Unit = {},
    /** When GitHub was last asked, epoch ms; 0 never, -1 while asking. */
    releaseCheckedAt: Long = 0L,
    releaseNote: String? = null,
    onCheckRelease: () -> Unit = {},
    builds: List<BuildInfo> = emptyList(),
    onUpdate: () -> Unit = {},
    onInstall: (BuildInfo) -> Unit = {},
    theme: ThemeChoice = ThemeChoice.Porthole,
    appearance: Appearance = Appearance.System,
    onTheme: (ThemeChoice) -> Unit = {},
    onAppearance: (Appearance) -> Unit = {},
    fontSp: Float,
    onFontSp: (Float) -> Unit,
    onGuide: () -> Unit,
    onSetup: () -> Unit,
    onLicences: () -> Unit = {},
    onUnpair: () -> Unit,
    onBack: () -> Unit,
    /** Every paired computer; the first is the one the rest of this screen describes. */
    machines: List<dev.shrimpscript.porthole.net.Machine> = emptyList(),
    machineStates: Map<String, String> = emptyMap(),
    onAddMachine: () -> Unit = {},
    onForgetMachine: (String) -> Unit = {},
    onOpenShell: () -> Unit = {},
    sshNote: String? = null,
    /** Sessions silenced one at a time from their details sheet. */
    mutedCount: Int = 0,
    onUnmuteAll: () -> Unit = {},
) = Screen {
    val c = Porthole.colors
    var confirmUnpair by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTarget(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
            Text("Settings", style = PortholeType.title, color = c.text)
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Section("Computer") {
                KeyValue("Host", host.ifBlank { "—" })
                KeyValue("This phone is known as", deviceName.ifBlank { "—" })
                KeyValue("portholed", daemonVersion.ifBlank { "not connected" })
            }

            // Porthole's own updates come from its GitHub releases. A store build has
            // the check compiled out and updates through the store, so this is absent.
            if (dev.shrimpscript.porthole.BuildConfig.SELF_UPDATE) Section("Updates") {
                var on by remember { mutableStateOf(releaseChecks) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Check GitHub for new versions", style = PortholeType.body, color = c.text)
                        Text(
                            "Once a day, one request to api.github.com. It carries nothing about you, " +
                                "this phone or your computer.",
                            style = PortholeType.meta, color = c.faint,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = on, onCheckedChange = { on = it; onReleaseChecks(it) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = c.onAccent, checkedTrackColor = c.accent,
                            uncheckedThumbColor = c.muted, uncheckedTrackColor = c.raised,
                        ),
                    )
                }
                Spacer(Modifier.height(10.dp))
                KeyValue(
                    "Porthole",
                    when {
                        release != null -> "${release.version} is out \u00b7 you have $appVersion"
                        releaseCheckedAt == -1L -> "$appVersion \u00b7 checking\u2026"
                        releaseCheckedAt == 0L -> "$appVersion \u00b7 not checked yet"
                        else -> "$appVersion \u00b7 up to date, checked " +
                            relativeTime(java.time.Instant.ofEpochMilli(releaseCheckedAt).toString()).let { if (it == "now") "just now" else "$it ago" }
                    },
                )
                releaseNote?.let {
                    Text(it, style = PortholeType.meta, color = c.warn, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                if (release != null) GhostButton("Update Porthole", onClick = onUpdate)
                else if (on && releaseCheckedAt != -1L) GhostButton("Check now", onClick = onCheckRelease)
            }

            // Other computers: each with its own daemon and pairing; forgotten one at a
            // time. The first computer stays the one Unpair below is about.
            Section(if (machines.size > 1) "Computers" else "Another computer") {
                machines.drop(1).forEachIndexed { i, m ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.shown, style = PortholeType.body, color = c.text)
                            val word = machineStates[m.id] ?: "not connected"
                            Text(
                                m.host + " · " + word,
                                style = PortholeType.meta, color = if (word == "connected") c.muted else c.warn,
                            )
                        }
                        Chip("Forget") { onForgetMachine(m.id) }
                    }
                }
                if (machines.size > 1) Spacer(Modifier.height(10.dp))
                Text(
                    "Pair this phone with a second computer that runs portholed; its sessions join the list, named.",
                    style = PortholeType.meta, color = c.faint,
                )
                Spacer(Modifier.height(8.dp))
                GhostButton("Add a computer", onClick = onAddMachine)
            }

            // Other apps being worked on at the computer: any Claude session there can run
            // `portholed publish`, and the build shows up here for one-tap install.
            val others = builds.filter { !it.isPorthole }
            if (others.isNotEmpty()) {
                Section("Other apps on the computer") {
                    others.forEachIndexed { i, b ->
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(b.name, style = PortholeType.body, color = c.text)
                                Text("${b.version} \u00b7 ${b.size / 1_048_576} MB", style = PortholeType.meta, color = c.faint)
                            }
                            Pill("Install", filled = true, onClick = { onInstall(b) })
                        }
                    }
                }
            }

            Section("Appearance") {
                Text("Theme", style = PortholeType.body, color = c.text)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThemeCard("Porthole", PortholeColors.Porthole, null, theme == ThemeChoice.Porthole, Modifier.weight(1f)) { onTheme(ThemeChoice.Porthole) }
                    val claudeShown = paletteFor(ThemeChoice.Claude, appearance, isSystemInDarkTheme())
                    ThemeCard("Claude", claudeShown, null, theme == ThemeChoice.Claude, Modifier.weight(1f)) { onTheme(ThemeChoice.Claude) }
                    val geminiShown = paletteFor(ThemeChoice.Gemini, appearance, isSystemInDarkTheme())
                    ThemeCard("Gemini", geminiShown, null, theme == ThemeChoice.Gemini, Modifier.weight(1f)) { onTheme(ThemeChoice.Gemini) }
                }
                AnimatedVisibility(
                    theme.hasAppearance,
                    enter = fadeIn(spec(PortholeMotion.ENTER_MS)) + expandVertically(spec(PortholeMotion.AXIS_MS)),
                    exit = fadeOut(spec(PortholeMotion.EXIT_MS)) + shrinkVertically(spec(PortholeMotion.EXIT_MS)),
                ) {
                    Column {
                        Spacer(Modifier.height(14.dp))
                        Text("Appearance", style = PortholeType.body, color = c.text)
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val family = if (theme.hasAppearance) theme else ThemeChoice.Claude
                            val light = paletteFor(family, Appearance.Light, false)
                            val dark = paletteFor(family, Appearance.Dark, true)
                            ThemeCard("Light", light, null, appearance == Appearance.Light, Modifier.weight(1f), accented = false) { onAppearance(Appearance.Light) }
                            ThemeCard("Dark", dark, null, appearance == Appearance.Dark, Modifier.weight(1f), accented = false) { onAppearance(Appearance.Dark) }
                            ThemeCard("System", light, dark, appearance == Appearance.System, Modifier.weight(1f), accented = false) { onAppearance(Appearance.System) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    when (theme) {
                        ThemeChoice.Claude -> "Claude's colours and its serif for replies. In the terminal, set Claude Code's /\u2060theme to match."
                        ThemeChoice.Gemini -> "Gemini's colours and Google Sans for replies. In the terminal, set Claude Code's /\u2060theme to match."
                        ThemeChoice.Porthole -> "Porthole's own palette."
                    },
                    style = PortholeType.meta, color = c.faint,
                )
            }

            Section("Home screen") {
                val ctx = LocalContext.current
                Text("A widget with your sessions, and a quick-settings tile.", style = PortholeType.meta, color = c.faint)
                Spacer(Modifier.height(8.dp))
                GhostButton("Add the widget", onClick = {
                    runCatching {
                        val mgr = android.appwidget.AppWidgetManager.getInstance(ctx)
                        if (mgr.isRequestPinAppWidgetSupported) mgr.requestPinAppWidget(android.content.ComponentName(ctx, dev.shrimpscript.porthole.widget.SessionsWidgetReceiver::class.java), null, null)
                    }
                })
                Text("The tile is in the quick-settings edit list, under Porthole.", style = PortholeType.meta, color = c.faint, modifier = Modifier.padding(top = 6.dp))
            }

            Section("Notifications") {
                val context = LocalContext.current
                // Android's own permission decides whether anything can show at all; a
                // switch that flips with no effect would be a lie, so it is disabled and
                // the way to fix it is one tap away.
                val allowed = Notifier.canPost(context)
                var on by remember { mutableStateOf(notifyDone) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("While a session works, and when it finishes", style = PortholeType.body, color = c.text)
                        Text(
                            "A timer in the status bar while a turn runs, then how long it took, with Reply. Not for the session on screen.",
                            style = PortholeType.meta, color = c.faint,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = on && allowed, enabled = allowed, onCheckedChange = { on = it; onNotifyDone(it) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = c.onAccent, checkedTrackColor = c.accent,
                            uncheckedThumbColor = c.muted, uncheckedTrackColor = c.raised,
                        ),
                    )
                }
                if (!allowed) {
                    // The note above the button, not beside it: the button fills its row,
                    // and a weighted text next to it was squeezed to one letter per line.
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "Android has notifications off for Porthole.",
                            style = PortholeType.secondary, color = c.warn, modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        GhostButton("Turn on", onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                )
                            }
                        })
                    }
                }
                Text(
                    "A usage limit that stops a task is always announced.",
                    style = PortholeType.meta, color = c.faint, modifier = Modifier.padding(top = 6.dp),
                )
                // Muting is done per session, in its details sheet; this is the only
                // place that says how many are silent, so a forgotten mute is findable.
                if (mutedCount > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (mutedCount == 1) "1 session is muted." else "$mutedCount sessions are muted.",
                        style = PortholeType.secondary, color = c.muted,
                    )
                    Spacer(Modifier.height(8.dp))
                    GhostButton("Unmute all", onClick = onUnmuteAll)
                }
            }

            Section("Claude Code") {
                KeyValue("Continue after a usage limit", if (autoContinue) "on" else "off")
                Text(
                    if (autoContinue)
                        "When a claude.ai usage limit stops a task, Claude Code waits in the session and " +
                            "continues on its own after the reset. The feed shows the countdown and lets you " +
                            "cancel it, or press Enter if the computer slept through the reset."
                    else
                        "Claude Code will not continue on its own after a limit. Turn it on at the computer: " +
                            "/config, then \"Continue automatically at usage limit\". Porthole never edits " +
                            "Claude Code's settings.",
                    style = PortholeType.meta, color = c.faint, modifier = Modifier.padding(top = 4.dp),
                )
            }

            Section("Terminal") {
                Text("Text size", style = PortholeType.secondary, color = c.muted)
                Spacer(Modifier.height(8.dp))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val density = LocalDensity.current
                    // Iosevka Term advances 0.5em, so the columns a size buys on this
                    // screen are simply width / (0.5 * size).
                    val cols = with(density) { (maxWidth.toPx() / (0.5f * fontSp.sp.toPx())).toInt() }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stepper("−") { onFontSp((fontSp - 1f).coerceAtLeast(TERMINAL_FONT_MIN)) }
                        Text("${fontSp.toInt()}sp", style = PortholeType.rowTitle, color = c.text)
                        Stepper("+") { onFontSp((fontSp + 1f).coerceAtMost(TERMINAL_FONT_MAX)) }
                        Spacer(Modifier.weight(1f))
                        Text("≈ $cols columns", style = PortholeType.secondary, color = c.muted)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "The terminal shows the computer's window at its real width. Fit mode shrinks " +
                        "it to the phone; pinch to zoom back in.",
                    style = PortholeType.meta, color = c.faint,
                )
            }

            Section("Help") {
                LinkRow("How Porthole works", "The four-page tour", onGuide)
                LinkRow("Set up a computer", "Install steps and commands", onSetup)
            }

            // Two things decide whether the phone stays useful away from the computer:
            // that Android lets the connection live in the background, and that there is
            // a way in when portholed is the thing that broke. The shell is offered here
            // too, so it can be tried before anything breaks.
            Section("While you are away") {
                val ctx = LocalContext.current
                // Re-read whenever the app comes back to the screen, so a trip to the
                // system settings and back shows the new answer rather than the old one.
                val onScreen by dev.shrimpscript.porthole.Foreground.state.collectAsState()
                val unrestricted = remember(onScreen) { batteryUnrestricted(ctx) }
                if (unrestricted) {
                    KeyValue("Background", "not battery-optimised")
                    Text(
                        "Android leaves Porthole's connection alone while the phone is idle.",
                        style = PortholeType.meta, color = c.faint, modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    Text("Android can stop Porthole in the background", style = PortholeType.body, color = c.text)
                    Text(
                        "Battery optimisation is on for Porthole, so a long-running connection can be cut " +
                            "while the phone is idle and notifications stop arriving. Exempting it keeps the " +
                            "socket alive while you are away.",
                        style = PortholeType.meta, color = c.faint, modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    GhostButton("Open battery settings", onClick = {
                        runCatching {
                            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }.onFailure {
                            runCatching {
                                ctx.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(android.net.Uri.parse("package:" + ctx.packageName))
                                )
                            }
                        }
                    })
                }
            }

            Section("If Porthole cannot connect") {
                Text(
                    "Open a shell on " + host.ifBlank { "the computer" } + " over Tailscale SSH. It does not go " +
                        "through portholed, so it still works when the daemon is stopped \u2014 that is how you " +
                        "restart it from here.",
                    style = PortholeType.meta, color = c.faint,
                )
                Spacer(Modifier.height(8.dp))
                GhostButton("Open a shell over SSH", onClick = onOpenShell)
                sshNote?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = PortholeType.secondary, color = c.warn)
                }
            }

            Section("This app") {
                KeyValue("Version", appVersion)
                KeyValue(
                    "Animations",
                    if (motionEnabled()) "on" else "off in system settings",
                )
                Text(
                    "No analytics, no telemetry, no Porthole servers. Your sessions move only between " +
                        "your phone and your computer, over your own tailnet." +
                        if (dev.shrimpscript.porthole.BuildConfig.SELF_UPDATE) " The only other place the app reaches is GitHub, for Porthole's own updates." else "",
                    style = PortholeType.meta, color = c.faint, modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(4.dp))
                LinkRow("Licences", "The fonts and libraries in this app", onLicences)
            }

            Section("Pairing") {
                if (!confirmUnpair) {
                    GhostButton("Unpair this phone", { confirmUnpair = true })
                } else {
                    Text(
                        "This forgets the computer on this phone. To cut the phone off from the " +
                            "computer's side as well, run portholed revoke there.",
                        style = PortholeType.secondary, color = c.muted,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GhostButton("Keep", { confirmUnpair = false }, Modifier.weight(1f))
                        Box(
                            Modifier
                                .weight(1f)
                                .height(48.dp)
                                .background(c.bad, PortholeShape.control)
                                .clickable { onUnpair() },
                            contentAlignment = Alignment.Center,
                        ) { Text("Unpair", style = PortholeType.body, color = c.onAccent) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * A theme swatch drawn the way the Claude app draws its own picker: a miniature page
 * with two text lines and an accent dot, and for System a diagonal split of both.
 */
@Composable
private fun ThemeCard(
    label: String,
    a: PortholeColors,
    b: PortholeColors?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    /** Accent marks the theme choice; the appearance row selects in text colour, so a screen never shows more than two accents. */
    accented: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Porthole.colors
    val mark = if (accented) c.accent else c.text
    val border by animateColorAsState(if (selected) mark else c.edge, spec(PortholeMotion.FAST_MS), label = "border")
    val width by animateDpAsState(if (selected) 2.dp else 1.dp, spec(PortholeMotion.FAST_MS), label = "width")
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .pressScale(interaction)
            .clickable(interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.45f)
                .clip(PortholeShape.control)
                .border(width, border, PortholeShape.control),
        ) {
            fun page(p: PortholeColors, clipPath: Path?) {
                val draw: DrawScope.() -> Unit = {
                    drawRect(p.ground)
                    val inset = 10.dp.toPx()
                    val card = Size(size.width - inset * 2, size.height - inset * 1.6f)
                    drawRoundRect(p.surface, Offset(inset, inset), card, CornerRadius(6.dp.toPx()))
                    val lx = inset + 8.dp.toPx(); val ly = inset + 9.dp.toPx(); val lh = 3.dp.toPx()
                    drawRoundRect(p.text, Offset(lx, ly), Size(card.width * 0.42f, lh), CornerRadius(lh))
                    drawRoundRect(p.muted, Offset(lx, ly + 7.dp.toPx()), Size(card.width * 0.6f, lh), CornerRadius(lh))
                    val r = 4.dp.toPx()
                    drawCircle(p.accent, r, Offset(inset + card.width - 9.dp.toPx(), inset + card.height - 9.dp.toPx()))
                }
                if (clipPath == null) draw() else clipPath(clipPath) { draw() }
            }
            if (b == null) page(a, null) else {
                val diag = Path().apply { moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
                page(a, null)
                page(b, diag)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = PortholeType.meta, color = if (selected) mark else c.muted)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val c = Porthole.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, PortholeShape.card)
            .padding(14.dp)
    ) {
        Text(title, style = PortholeType.meta, color = c.faint)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun KeyValue(k: String, v: String) {
    val c = Porthole.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(k, style = PortholeType.secondary, color = c.muted, modifier = Modifier.weight(1f))
        Text(v, style = PortholeType.secondary, color = c.text)
    }
}

@Composable
private fun LinkRow(title: String, detail: String, onClick: () -> Unit) {
    val c = Porthole.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = PortholeType.body, color = c.text)
            Text(detail, style = PortholeType.meta, color = c.faint)
        }
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Outlined.KeyboardArrowRight, contentDescription = null,
            tint = c.muted,
        )
    }
}

@Composable
private fun Stepper(label: String, onClick: () -> Unit) {
    val c = Porthole.colors
    Box(
        Modifier
            .size(44.dp)
            .background(c.raised, PortholeShape.key)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, style = PortholeType.rowTitle, color = c.text) }
}

/**
 * Whether Android has taken Porthole out of battery optimisation. Read rather than
 * asserted: the app cannot grant itself the exemption, and claiming a background
 * connection it may not get would be the same lie as a fake status light.
 */
private fun batteryUnrestricted(ctx: android.content.Context): Boolean = runCatching {
    ctx.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true
}.getOrDefault(true)
