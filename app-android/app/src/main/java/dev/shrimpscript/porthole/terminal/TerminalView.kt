package dev.shrimpscript.porthole.terminal

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import dev.shrimpscript.porthole.R
import dev.shrimpscript.porthole.ui.theme.Porthole

/**
 * Draws the character grid.
 *
 * A Canvas rather than composed Text: a 200x50 session is 10,000 cells, and one Text per
 * cell would be 10,000 layout nodes per frame. Runs of same-styled cells are batched into
 * single drawText calls, which is what keeps it smooth on a phone.
 *
 * Sizing: [fontSize] is the normal case. [fitWidthPx] overrides it so the desktop's whole
 * grid fits the phone's width - the exact screen from the desk, small. Pinch changes the
 * size through [onZoom]; a tap asks for the keyboard through [onTap].
 */
@Composable
fun TerminalView(
    emulator: TerminalEmulator,
    revision: Int,
    fontSize: TextUnit = 13.sp,
    modifier: Modifier = Modifier,
    fitWidthPx: Float? = null,
    onTap: (() -> Unit)? = null,
    onZoom: ((Float) -> Unit)? = null,
    /** Lines scrolled back into the emulator's own scrollback; 0 is the live screen. */
    historyOffset: Int = 0,
    /**
     * Vertical drags in whole lines, positive for further back, whenever the grid is no
     * taller than the view (a taller grid pans instead).
     */
    onScrollLines: ((Int) -> Unit)? = null,
    /**
     * Size the grid to the view rather than to the other end: the columns and rows that
     * fit at [fontSize] are reported here, and nothing scrolls sideways.
     */
    onGridSize: ((Int, Int) -> Unit)? = null,
) {
    val colors = Porthole.colors
    val density = LocalDensity.current
    val context = LocalContext.current

    val typeface = remember {
        runCatching { ResourcesCompat.getFont(context, R.font.iosevka_term_regular) }
            .getOrNull() ?: Typeface.MONOSPACE
    }
    val boldTypeface = remember {
        runCatching { ResourcesCompat.getFont(context, R.font.iosevka_term_bold) }
            .getOrNull() ?: Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    // Advance width per pixel of text size, measured once: fit mode solves for the
    // text size that makes cols * advance equal the available width.
    val advanceRatio = remember(typeface) {
        Paint().apply { this.typeface = typeface; textSize = 100f }.measureText("M") / 100f
    }
    val textPx = if (fitWidthPx != null && emulator.cols > 0) {
        (fitWidthPx / (emulator.cols * advanceRatio)).coerceIn(with(density) { 4.sp.toPx() }, with(density) { 24.sp.toPx() })
    } else {
        with(density) { fontSize.toPx() }
    }
    val paint = remember(typeface, textPx) {
        Paint().apply {
            this.typeface = typeface
            textSize = textPx
            isAntiAlias = true
            // subpixel text positioning keeps the grid from drifting across columns
            isSubpixelText = true
        }
    }
    val boldPaint = remember(boldTypeface, textPx) {
        Paint().apply {
            this.typeface = boldTypeface
            textSize = textPx
            isAntiAlias = true
            isSubpixelText = true
        }
    }
    val cellW = remember(paint) { paint.measureText("M") }
    val metrics = remember(paint) { paint.fontMetrics }
    val cellH = remember(metrics) { metrics.descent - metrics.ascent }

    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    BoxWithConstraints(modifier.fillMaxSize()) {
    val viewW = constraints.maxWidth.toFloat()
    val viewH = constraints.maxHeight.toFloat()
    if (onGridSize != null && cellW > 0f && cellH > 0f && constraints.hasBoundedHeight) {
        val fitCols = (viewW / cellW).toInt().coerceAtLeast(20)
        val fitRows = (viewH / cellH).toInt().coerceAtLeast(5)
        LaunchedEffect(fitCols, fitRows) { onGridSize(fitCols, fitRows) }
    }
    val fitsHeight = !constraints.hasBoundedHeight || cellH * emulator.rows <= viewH + 1f
    val dragsHistory = onScrollLines != null && (fitsHeight || onGridSize != null)
    val pansVertically = onGridSize == null && !dragsHistory
    // A terminal's live line is its last one, so the grid's bottom row belongs at the
    // bottom of the view, just above the keys - whether the grid is shorter than the view
    // (fit) or taller (zoomed in, where it pans). Panned, it stays pinned to the bottom as
    // the size changes, until a drag moves it up to read; dragging back down pins it again.
    var pinned by remember { mutableStateOf(true) }
    if (pansVertically) {
        // Decided when a scroll ends, never at the start: before the first layout the
        // extent is not known, and reading it then would unpin a view no one has moved.
        LaunchedEffect(vScroll) {
            snapshotFlow { vScroll.isScrollInProgress }.drop(1).collect { moving ->
                if (!moving) pinned = vScroll.value >= vScroll.maxValue - 8
            }
        }
        LaunchedEffect(vScroll.maxValue, pinned) {
            if (pinned && vScroll.maxValue < Int.MAX_VALUE) vScroll.scrollTo(vScroll.maxValue)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            // One finger up or down walks the scrollback: whole lines, measured from
            // where the finger started, so a slow drag moves as far as a fast one.
            .then(
                if (dragsHistory) Modifier.pointerInput(onScrollLines, cellH) {
                    var acc = 0f
                    detectVerticalDragGestures(onDragStart = { acc = 0f }) { change, dy ->
                        acc += dy
                        val lines = (acc / cellH).toInt()
                        if (lines != 0) {
                            onScrollLines?.invoke(lines)
                            acc -= lines * cellH
                        }
                        change.consume()
                    }
                } else Modifier
            )
            // Pinch is read on the initial pass, before the scrollers see the event, and
            // only when two fingers are down - so one-finger drags still scroll.
            .pointerInput(onZoom) {
                if (onZoom == null) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.size >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                onZoom(zoom)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .then(
                if (onTap != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onTap() } else Modifier
            )
            // The session is sized to the desktop, so a 200-column window is wider than
            // the phone. Scroll rather than reflow: reflowing a TUI corrupts it. A grid
            // sized to the view never needs either.
            .then(if (onGridSize == null) Modifier.horizontalScroll(hScroll) else Modifier)
            .then(if (pansVertically) Modifier.verticalScroll(vScroll) else Modifier),
        contentAlignment = Alignment.BottomStart,
    ) {
        val gridW = with(density) { (cellW * emulator.cols).toDp() }
        val gridH = with(density) { (cellH * emulator.rows).toDp() }

        Canvas(Modifier.width(gridW).height(gridH)) {
            // revision is read so Compose redraws when the buffer mutates
            @Suppress("UNUSED_EXPRESSION") revision

            synchronized(emulator) {
            val buf = emulator.buffer
            val offset = historyOffset.coerceIn(0, emulator.historySize)
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val bgPaint = Paint()
                val sb = StringBuilder()

                for (y in 0 until buf.rows) {
                    val baseline = -metrics.ascent + y * cellH
                    // Scrolled back k lines: the top k rows are the newest k lines of
                    // scrollback, the rest is the screen pushed down by k.
                    val src = y - offset
                    val chars: IntArray; val fgs: IntArray; val bgs: IntArray; val ats: IntArray
                    val base: Int; val width: Int
                    if (src < 0) {
                        val h = emulator.historyLine(emulator.historySize + src)
                        chars = h.chars; fgs = h.fg; bgs = h.bg; ats = h.attrs
                        base = 0; width = minOf(h.chars.size, buf.cols)
                    } else {
                        chars = buf.chars; fgs = buf.fg; bgs = buf.bg; ats = buf.attrs
                        base = buf.index(0, src); width = buf.cols
                    }
                    var x = 0
                    while (x < width) {
                        val i = base + x
                        val a = ats[i]
                        var fgC = fgs[i]
                        var bgC = bgs[i]
                        if (a and TerminalBuffer.REVERSE != 0) {
                            val t = fgC; fgC = bgC; bgC = t
                        }

                        // extend the run while style matches
                        var end = x
                        sb.setLength(0)
                        while (end < width) {
                            val j = base + end
                            var f2 = fgs[j]; var b2 = bgs[j]
                            if (ats[j] and TerminalBuffer.REVERSE != 0) {
                                val t = f2; f2 = b2; b2 = t
                            }
                            if (ats[j] != a || f2 != fgC || b2 != bgC) break
                            sb.append(chars[j].toChar())
                            end++
                        }

                        val runW = cellW * (end - x)
                        if (bgC != TerminalBuffer.DEFAULT_BG) {
                            bgPaint.color = bgC
                            native.drawRect(x * cellW, y * cellH, x * cellW + runW, (y + 1) * cellH, bgPaint)
                        }

                        val p = if (a and TerminalBuffer.BOLD != 0) boldPaint else paint
                        p.color = when {
                            fgC == TerminalBuffer.DEFAULT_FG -> colors.text.toArgb()
                            fgC == TerminalBuffer.DEFAULT_BG -> colors.ground.toArgb()
                            else -> fgC
                        }
                        p.alpha = if (a and TerminalBuffer.DIM != 0) 140 else 255
                        p.isUnderlineText = a and TerminalBuffer.UNDERLINE != 0
                        p.isStrikeThruText = a and TerminalBuffer.STRIKE != 0
                        native.drawText(sb.toString(), x * cellW, baseline, p)

                        x = end
                    }
                }

                val cursorRow = emulator.cursorY + offset
                if (emulator.cursorVisible && cursorRow < buf.rows) {
                    val cp = Paint().apply {
                        color = colors.accent.toArgb()
                        alpha = 150
                    }
                    native.drawRect(
                        emulator.cursorX * cellW, cursorRow * cellH,
                        (emulator.cursorX + 1) * cellW, (cursorRow + 1) * cellH, cp,
                    )
                }
            }
            }
        }
    }
    }
}
