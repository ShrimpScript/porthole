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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.shrimpscript.porthole.net.ScreenQuestion
import dev.shrimpscript.porthole.net.SessionInfo
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeTermFace
import dev.shrimpscript.porthole.ui.theme.PortholeType
import kotlinx.coroutines.delay

/*
 * The sessions screen in the Clear language (DESIGN v3 §6): whatever needs you leads, full
 * width, with its own terminal behind glass and its answers on the glass; every other session
 * is seen through a porthole; New session and the inbox float at the bottom.
 */

/** The ring state a session's row and porthole show. As last seen, nothing moves. */
fun SessionInfo.ringState(): RingState = when {
    machineDown -> RingState.Idle
    needsYou -> RingState.NeedsYou
    working -> RingState.Working
    live -> RingState.Live
    else -> RingState.Idle
}

/** What a screen reader hears for that ring. */
fun SessionInfo.ringLabel(): String = when {
    asking.isNotBlank() -> "asking you"
    waiting -> "waiting for you"
    working -> "working"
    live -> "live"
    else -> "idle"
}

/**
 * A session seen through a round window: the bottom of its terminal drawn small, the glass
 * over it, and the state ring round it. A session with no pane shows dark glass: nothing is
 * made up to fill it.
 */
@Composable
fun SessionPorthole(s: SessionInfo, size: Dp = 48.dp, modifier: Modifier = Modifier, sharedKey: String? = null) {
    val ring = size + 10.dp
    Box(modifier.size(ring), contentAlignment = Alignment.Center) {
        Ring(
            state = s.ringState(), size = ring, strokeWidth = 2.2.dp, label = s.ringLabel(),
            modifier = if (sharedKey != null) Modifier.sharedAcrossRoutes(sharedKey) else Modifier,
        )
        Box(Modifier.alpha(if (s.live || s.tmux) 1f else 0.6f)) { TerminalWindow(s.peek, size) }
    }
}

/** The round window itself: a picture of the terminal, not text to read (it is skipped by TalkBack). */
@Composable
fun TerminalWindow(lines: List<String>, size: Dp) {
    val k = size.value / 48f
    val density = androidx.compose.ui.platform.LocalDensity.current
    val glyph = with(density) { (4.2f * k).dp.toSp() }
    val leading = with(density) { (5.4f * k).dp.toSp() }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF050506))
            .clearAndSetSemantics {}
            .drawWithContent {
                val px = size.toPx()
                // Behind the glass: the screen, or with no screen the room's dark.
                if (lines.isEmpty()) drawRect(Brush.radialGradient(0f to Color(0xFF2A2E35), 1f to Color(0xFF0D0F12), center = Offset(px * 0.5f, px * 0.2f), radius = px * 0.9f))
                drawContent()
                // The glass: a highlight at the top-left, one diagonal reflection, and the
                // rim's shade at the edge - so an empty window still reads as a window.
                drawRect(Brush.radialGradient(0f to Color.White.copy(alpha = 0.20f), 0.42f to Color.Transparent, center = Offset(px * 0.3f, px * 0.18f), radius = px * 0.6f))
                // A short curved highlight on the upper-left of the glass (a straight bar across a
                // dark disc reads as a "no entry" sign).
                drawArc(
                    Color.White.copy(alpha = if (lines.isEmpty()) 0.30f else 0.16f), startAngle = 200f, sweepAngle = 62f, useCenter = false,
                    topLeft = Offset(px * 0.16f, px * 0.16f), size = androidx.compose.ui.geometry.Size(px * 0.68f, px * 0.68f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = px * 0.06f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
                drawRect(Brush.radialGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f), radius = px * 0.5f))
            },
    ) {
        if (lines.isNotEmpty()) {
            Text(
                lines.takeLast(12).joinToString("\n"),
                style = TextStyle(fontFamily = PortholeTermFace, fontSize = glyph, lineHeight = leading),
                color = Color(0xFFB4B4BC),
                softWrap = false, maxLines = 12, overflow = TextOverflow.Clip,
                modifier = Modifier.padding(start = size * 0.12f, top = size * 0.18f).wrapContentSize(Alignment.TopStart, unbounded = true),
            )
        }
    }
}

/** The bottom of a terminal as the hero's picture: the question's lines lit amber. */
@Composable
private fun TerminalArt(lines: List<String>, q: ScreenQuestion?, modifier: Modifier = Modifier) {
    val c = Porthole.colors
    val dim = Color(0xFF9D9DA6)
    val text = buildAnnotatedString {
        lines.forEachIndexed { i, l ->
            if (i > 0) append('\n')
            val lit = q != null && (l.contains('❯') || (q.text.isNotBlank() && l.contains(q.text.take(24))) ||
                q.options.any { o -> l.trimStart().startsWith("${o.n}.") })
            withStyle(SpanStyle(color = if (lit) c.warn else dim)) { append(l) }
        }
    }
    Text(
        text,
        style = TextStyle(fontFamily = PortholeTermFace, fontSize = 11.sp, lineHeight = 15.sp),
        softWrap = false, overflow = TextOverflow.Clip,
        modifier = modifier.clearAndSetSemantics {},
    )
}

/** The screen's title row: the computer's name, its ring, and the two controls. */
@Composable
fun SessionsHeader(
    machine: String,
    ring: RingState,
    subtitle: String,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    overContent: Boolean,
) {
    val c = Porthole.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f).padding(start = 2.dp)) {
            Text(machine, style = Porthole.type.display, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The connection, stated: its ring leads the line that says it.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Ring(state = ring, size = 12.dp, strokeWidth = 1.8.dp)
                Text(subtitle, style = PortholeType.secondary, color = if (overContent) c.text.copy(alpha = 0.78f) else c.muted, maxLines = 1)
            }
        }
        IconTarget(Symbols.Refresh, "Refresh sessions", onRefresh, glass = overContent)
        IconTarget(Symbols.Settings, "Settings", onSettings, glass = overContent)
    }
}

/**
 * What needs you, first and full width: that session's terminal behind glass, and on the glass
 * the question with its choices. A single-choice question is answered here with one tap (the
 * daemon presses the digit, after checking the picker is still on screen); anything else opens
 * the session at its card.
 */
@Composable
fun NeedsYouHero(
    s: SessionInfo,
    header: @Composable () -> Unit,
    onOpen: () -> Unit,
    onAnswer: ((Int) -> Unit)?,
) {
    val c = Porthole.colors
    val art = remember { HazeState() }
    // Its own clock, a minute at a time: how long the question has waited.
    var now by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.id) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }
    val q = s.question
    // One tap answers; the pressed choice stays lit until the list says the question is gone.
    var pressed by remember(s.id, q?.text) { mutableStateOf(0) }
    LaunchedEffect(pressed) { if (pressed != 0) { delay(8000); pressed = 0 } }
    val interaction = remember { MutableInteractionSource() }
    CompositionLocalProvider(LocalGlass provides art) {
        Box(
            Modifier
                .fillMaxWidth()
                .clickable(interaction, indication = null, role = Role.Button, onClickLabel = "Open ${s.title}", onClick = onOpen),
        ) {
            // The picture: the session's own screen, its last lines - the question - behind the
            // glass, lit from there; the lines before it above.
            Box(Modifier.matchParentSize().glassSource()) {
                TerminalArt(s.peek.takeLast(16), q, Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 34.dp))
                Box(
                    Modifier
                        .matchParentSize()
                        .drawWithContent {
                            drawContent()
                            drawRect(Brush.radialGradient(0f to c.warn.copy(alpha = 0.22f), 1f to Color.Transparent, center = Offset(size.width * 0.3f, size.height * 0.72f), radius = size.width * 0.9f))
                        }
                        .background(
                            Brush.verticalGradient(
                                0f to c.ground.copy(alpha = 0.92f), 0.22f to c.ground.copy(alpha = 0.15f), 0.5f to Color.Transparent,
                                0.78f to c.ground.copy(alpha = 0.55f), 1f to c.ground,
                            ),
                        ),
                )
            }
            Column(Modifier.fillMaxWidth()) {
                header()
                Spacer(Modifier.height(132.dp))
                GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
                        val since = if (s.workingSince > 0 && now > 0) " · " + relativeShort(now - s.workingSince) else ""
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Ring(RingState.NeedsYou, size = 14.dp, strokeWidth = 2.dp, label = s.ringLabel(), modifier = Modifier.apertureSource(s.id))
                            Text(
                                (if (s.asking.isNotBlank()) "Asking you" else "Waiting for you") + since,
                                style = PortholeType.secondary.copy(fontWeight = FontWeight.SemiBold), color = c.warn,
                            )
                        }
                        Text(
                            s.title, style = PortholeType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp).sharedAcrossRoutes("title-${s.id}", bounds = true),
                        )
                        val line = s.asking.ifBlank { s.waitingWhat }
                        if (line.isNotBlank()) Text(line, style = PortholeType.secondary, color = c.text.copy(alpha = 0.82f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                        Spacer(Modifier.height(12.dp))
                        val single = q != null && !q.multi && q.total <= 1 && !q.review && q.options.isNotEmpty()
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (single && onAnswer != null) {
                                q!!.options.take(4).forEach { o ->
                                    AnswerCapsule(o.label, lit = pressed == o.n, enabled = pressed == 0) {
                                        pressed = o.n
                                        onAnswer(o.n)
                                    }
                                }
                                // More choices than fit, or a typed answer: the session's own card has them.
                                if (q.options.size > 4) AnswerCapsule("More…", quiet = true, enabled = pressed == 0, onClick = onOpen)
                                else if (q.typed > 0) AnswerCapsule("Type…", quiet = true, enabled = pressed == 0, onClick = onOpen)
                            } else {
                                AnswerCapsule(if (s.asking.isNotBlank()) "Answer" else "Open", lit = true, onClick = onOpen)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** A choice on the hero: glass until it is the one pressed, then the bright capsule. */
@Composable
private fun AnswerCapsule(label: String, lit: Boolean = false, quiet: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Porthole.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .pressScale(interaction)
            .then(if (lit) Modifier.bright() else Modifier.glass(PortholeShape.pill, shadow = false))
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = PortholeType.body.copy(fontWeight = FontWeight.SemiBold),
            color = when { lit -> c.onAccent; quiet -> c.muted; else -> c.text },
            modifier = Modifier.widthIn(max = 220.dp),
        )
    }
}

/** "2m", "1h": how long a question has waited, short enough for its label. */
fun relativeShort(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(0)
    return when {
        m < 1 -> "now"
        m < 60 -> "${m}m"
        else -> "${m / 60}h"
    }
}

/** New session and the inbox, floating in glass at the bottom, in reach of a thumb. */
@Composable
fun SessionsDock(onNewSession: (() -> Unit)?, onInbox: (() -> Unit)?, inboxCount: Int, modifier: Modifier = Modifier) {
    if (onNewSession == null && onInbox == null) return
    val c = Porthole.colors
    Row(
        modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onNewSession != null) {
            val interaction = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .weight(1f)
                    .height(54.dp)
                    .pressScale(interaction)
                    .bright()
                    .clickable(interaction, indication = null, role = Role.Button, onClick = onNewSession),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Symbols.Add, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("New session", style = PortholeType.rowTitle, color = c.onAccent)
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (onInbox != null) {
            GlassCapsule(
                onClick = onInbox, height = 54.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                label = if (inboxCount > 0) "Inbox, $inboxCount waiting on you" else "Inbox",
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = if (inboxCount > 0) "Inbox, $inboxCount waiting on you" else "Inbox" },
            ) {
                Icon(Symbols.Inbox, contentDescription = null, tint = c.text, modifier = Modifier.size(22.dp))
                if (inboxCount > 0) {
                    Text(
                        if (inboxCount > 99) "99+" else "$inboxCount",
                        style = PortholeType.meta.copy(fontWeight = FontWeight.Bold), color = c.onAccent,
                        modifier = Modifier.clip(PortholeShape.pill).background(c.warn).padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/** A group of rows on a card: rows set off by value, with a short inset rule between them. */
@Composable
fun RowGroup(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(PortholeShape.panel)
            .background(Porthole.colors.surface)
            .padding(vertical = 4.dp),
    ) { content() }
}
