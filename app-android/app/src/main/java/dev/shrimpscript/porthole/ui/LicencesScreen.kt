package dev.shrimpscript.porthole.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeMotion
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType
import dev.shrimpscript.porthole.ui.theme.spec

/** Something Porthole ships that someone else made, and the terms it ships under. */
internal data class Notice(
    val name: String,
    val holder: String,
    val licence: String,
    /** The licence text in assets/licenses; null when the terms need no copy (CC0) or live at a URL. */
    val file: String? = null,
    val note: String? = null,
)

/** Every font and library in the APK. THIRD_PARTY.md carries the same list. */
internal val Notices = listOf(
    Notice("Schibsted Grotesk", "Copyright 2023 The Schibsted-Grotesk Project Authors", "SIL Open Font License 1.1", "OFL-SchibstedGrotesk.txt"),
    Notice("Iosevka Term", "Copyright 2015-2025, Renzhi Li (Belleve Invis)", "SIL Open Font License 1.1", "OFL-Iosevka.txt"),
    Notice(
        "Porthole Serif", "© 2014 - 2021 Adobe Systems Incorporated, with Reserved Font Name ‘Source’",
        "SIL Open Font License 1.1", "OFL-SourceSerif4.txt",
        note = "Source Serif 4, modified: instanced at opsz 16, subset to Latin, and renamed as the licence requires.",
    ),
    Notice("Google Sans Flex", "Copyright 2015 The Google Sans Flex Authors", "SIL Open Font License 1.1", "OFL-GoogleSansFlex.txt"),
    Notice(
        "AndroidX and Jetpack Compose", "The Android Open Source Project", "Apache License 2.0", "Apache-2.0.txt",
        note = "Including Glance, Browser, Core SplashScreen and the Material icons.",
    ),
    Notice("Kotlin and kotlinx.coroutines", "JetBrains s.r.o. and Kotlin contributors", "Apache License 2.0", "Apache-2.0.txt"),
    Notice(
        "OkHttp and Okio", "Square, Inc.", "Apache License 2.0", "Apache-2.0.txt",
        note = "OkHttp carries the Public Suffix List (publicsuffix.org), under the Mozilla Public License 2.0: mozilla.org/MPL/2.0",
    ),
    Notice("sshj", "SSHJ Contributors", "Apache License 2.0", "Apache-2.0.txt", note = "With its ASN.1 library, asn-one."),
    Notice(
        "Supporting libraries", "Their respective authors", "Apache License 2.0", "Apache-2.0.txt",
        note = "Guava ListenableFuture, javax.inject, JSpecify, JetBrains annotations, Firebase components and " +
            "Google Data Transport, pulled in by the libraries above.",
    ),
    Notice("Bouncy Castle", "Copyright (c) 2000-2023 The Legion of the Bouncy Castle Inc.", "Bouncy Castle Licence (MIT)", "BouncyCastle.txt"),
    Notice("SLF4J API", "Copyright (c) 2004-2022 QOS.ch Sarl", "MIT License", "slf4j-MIT.txt"),
    Notice("EdDSA-Java", "The EdDSA-Java authors", "CC0 1.0, public domain"),
    Notice(
        "Google Play services code scanner", "Google LLC", "Android Software Development Kit License, ML Kit Terms of Service",
        note = "With Play services base, basement and tasks, and ML Kit. Terms at developer.android.com/studio/terms " +
            "and developers.google.com/ml-kit/terms",
    ),
    Notice(
        "Tool marks", "Simple Icons", "CC0 1.0, public domain",
        note = "The Tailscale, tmux, GitHub and Claude Code marks. Each belongs to its owner and is shown only to name the tool.",
    ),
)

/**
 * The licences of everything Porthole bundles, each text read from the APK's own copy
 * in assets/licenses, so what is shown is what ships.
 */
@Composable
fun LicencesScreen(onBack: () -> Unit) = Screen {
    val c = Porthole.colors
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTarget(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
            Text("Licences", style = PortholeType.title, color = c.text)
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Porthole includes these fonts and libraries, each under its own licence. Tap one to read it.",
                style = PortholeType.meta, color = c.faint,
            )
            Notices.forEach { NoticeCard(it) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NoticeCard(n: Notice) {
    val c = Porthole.colors
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, PortholeShape.card)
            .then(if (n.file != null) Modifier.clickable(role = Role.Button) { open = !open } else Modifier)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(n.name, style = PortholeType.body, color = c.text)
                Text(n.licence, style = PortholeType.secondary, color = c.muted)
                Text(n.holder, style = PortholeType.meta, color = c.faint, modifier = Modifier.padding(top = 2.dp))
            }
            if (n.file != null) {
                Icon(
                    if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = if (open) "Hide the licence" else "Show the licence",
                    tint = c.muted,
                )
            }
        }
        n.note?.let { Text(it, style = PortholeType.meta, color = c.faint, modifier = Modifier.padding(top = 6.dp)) }
        if (n.file != null) {
            AnimatedVisibility(
                open,
                enter = fadeIn(spec(PortholeMotion.ENTER_MS)) + expandVertically(spec(PortholeMotion.AXIS_MS)),
                exit = fadeOut(spec(PortholeMotion.EXIT_MS)) + shrinkVertically(spec(PortholeMotion.EXIT_MS)),
            ) {
                val text = remember(n.file) {
                    runCatching { context.assets.open("licenses/${n.file}").use { reflow(it.reader().readText()) } }
                        .getOrDefault("The licence text is missing from this build.")
                }
                Text(text, style = PortholeType.meta, color = c.muted, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

/**
 * Licence files are hard-wrapped near 70 columns, which breaks mid-line on a phone. Lines
 * of a paragraph are joined so the text wraps to the screen; a short all-caps heading
 * keeps its own line, and rules drawn in dashes are dropped.
 */
internal fun reflow(text: String): String =
    text.replace("\r\n", "\n").split(Regex("\n[ \t]*\n")).mapNotNull { para ->
        val lines = para.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.all { ch -> ch == '-' } }
        val head = lines.firstOrNull() ?: return@mapNotNull null
        val heading = lines.size > 1 && head.length <= 30 && head.any { it.isLetter() } && head == head.uppercase()
        if (heading) head + "\n" + lines.drop(1).joinToString(" ") else lines.joinToString(" ")
    }.joinToString("\n\n").replace(Regex(" {2,}"), " ")
