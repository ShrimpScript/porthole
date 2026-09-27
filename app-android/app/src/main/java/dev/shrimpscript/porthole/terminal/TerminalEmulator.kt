package dev.shrimpscript.porthole.terminal

/**
 * A VT100/xterm emulator: bytes in, character grid out.
 *
 * Scope is deliberate rather than exhaustive. It covers what the tools this app exists to
 * drive actually emit - Claude Code's TUI, vim, btop, git, less - which means SGR colour
 * (including 256 and truecolour), cursor movement, erase, insert/delete, scroll regions,
 * and the alternate screen. Sequences outside that are consumed and ignored rather than
 * printed as garbage, because a stray "[38;5;196m" on screen is worse than a missing
 * colour.
 */
class TerminalEmulator(cols: Int, rows: Int) {

    var buffer = TerminalBuffer(cols, rows)
        private set

    // The alternate screen (?1049) is what full-screen apps switch into. Keeping a second
    // buffer is what lets vim exit and leave the shell's scrollback intact.
    private var mainBuffer = buffer
    private var altBuffer: TerminalBuffer? = null
    var usingAlt = false
        private set

    var cursorX = 0
        private set
    var cursorY = 0
        private set
    var cursorVisible = true
        private set

    private var savedX = 0
    private var savedY = 0
    private var savedFg = TerminalBuffer.DEFAULT_FG
    private var savedBg = TerminalBuffer.DEFAULT_BG
    private var savedAttrs = 0

    private var curFg = TerminalBuffer.DEFAULT_FG
    private var curBg = TerminalBuffer.DEFAULT_BG
    private var curAttrs = 0

    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var autoWrap = true
    private var wrapPending = false

    val cols get() = buffer.cols
    val rows get() = buffer.rows

    /** A line that scrolled off the top of the main screen, kept at the width it had then. */
    class HistoryLine(val chars: IntArray, val fg: IntArray, val bg: IntArray, val attrs: IntArray)

    // Scrollback: what a shell printed and the screen no longer shows. Only the main
    // screen feeds it - a full-screen app on the alternate screen redraws in place, and
    // its frames are not history.
    private val historyLines = ArrayDeque<HistoryLine>()
    var historyLimit = 2000

    /** Lines of scrollback held, oldest first. */
    val historySize: Int get() = historyLines.size

    fun historyLine(i: Int): HistoryLine = historyLines[i]

    /**
     * Lines ever pushed into the scrollback. A view scrolled back reads it to stay on the
     * same text while new output arrives underneath.
     */
    var historyPushed = 0L
        private set

    private fun keepTopLine() {
        if (buffer !== mainBuffer || scrollTop != 0) return
        val b = buffer
        val from = 0
        val to = b.cols
        historyLines.addLast(
            HistoryLine(
                b.chars.copyOfRange(from, to), b.fg.copyOfRange(from, to),
                b.bg.copyOfRange(from, to), b.attrs.copyOfRange(from, to),
            )
        )
        while (historyLines.size > historyLimit) historyLines.removeFirst()
        historyPushed++
    }

    private fun clearHistory() {
        historyLines.clear()
    }

    // ---- parser state ----------------------------------------------------------
    private enum class State { GROUND, ESC, CSI, OSC, CHARSET }

    private var state = State.GROUND
    private val params = ArrayList<Int>(8)
    private var paramAcc = -1
    private var priv = false
    private var marker = 0.toChar()
    // The last character printed, for REP (CSI n b): tmux repeats runs of spaces and line
    // drawing this way, and a missing run shifts the rest of the line.
    private var lastPrinted = ' '.code
    private val oscAcc = StringBuilder()

    // UTF-8 accumulation
    private var utf8Remaining = 0
    private var utf8Value = 0

    /**
     * Writes arrive on a network thread while the grid is drawn and resized on the main
     * one; a resize swaps the arrays under a draw in progress. Writes, resizes and draws
     * all hold this object's lock (the view draws inside synchronized(emulator)).
     */
    @Synchronized
    fun resize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        // Losing rows (the keyboard opening, say) must not cut off the line being typed on:
        // the top lines move into the scrollback until the cursor's row fits.
        val overflow = cursorY - (newRows - 1)
        if (overflow > 0 && buffer === mainBuffer) {
            val top = scrollTop
            scrollTop = 0
            repeat(overflow) {
                keepTopLine()
                mainBuffer.scrollUp(0, mainBuffer.rows - 1, TerminalBuffer.DEFAULT_FG, TerminalBuffer.DEFAULT_BG)
            }
            scrollTop = top
            cursorY -= overflow
        }
        mainBuffer.resize(newCols, newRows)
        altBuffer?.resize(newCols, newRows)
        scrollTop = 0
        scrollBottom = newRows - 1
        cursorX = cursorX.coerceIn(0, newCols - 1)
        cursorY = cursorY.coerceIn(0, newRows - 1)
    }

    @Synchronized
    fun write(bytes: ByteArray, length: Int = bytes.size) {
        for (i in 0 until length) feed(bytes[i].toInt() and 0xFF)
    }

    private fun feed(b: Int) {
        when (state) {
            State.GROUND -> ground(b)
            State.ESC -> esc(b)
            State.CSI -> csi(b)
            State.OSC -> osc(b)
            State.CHARSET -> state = State.GROUND // consume the charset designator
        }
    }

    // ---- ground ----------------------------------------------------------------
    private fun ground(b: Int) {
        if (utf8Remaining > 0) {
            if (b and 0xC0 == 0x80) {
                utf8Value = (utf8Value shl 6) or (b and 0x3F)
                if (--utf8Remaining == 0) putChar(utf8Value)
                return
            }
            // Malformed continuation: emit a replacement and reprocess this byte.
            utf8Remaining = 0
            putChar(0xFFFD)
        }
        when {
            b == 0x1B -> { state = State.ESC; params.clear(); paramAcc = -1; priv = false }
            b == 0x0A || b == 0x0B || b == 0x0C -> lineFeed()
            b == 0x0D -> { cursorX = 0; wrapPending = false }
            b == 0x08 -> { if (cursorX > 0) cursorX--; wrapPending = false }
            b == 0x09 -> tab()
            b == 0x07 -> Unit // bell
            b < 0x20 -> Unit // other C0: ignore
            b < 0x80 -> putChar(b)
            b and 0xE0 == 0xC0 -> { utf8Remaining = 1; utf8Value = b and 0x1F }
            b and 0xF0 == 0xE0 -> { utf8Remaining = 2; utf8Value = b and 0x0F }
            b and 0xF8 == 0xF0 -> { utf8Remaining = 3; utf8Value = b and 0x07 }
            else -> putChar(0xFFFD)
        }
    }

    private fun tab() {
        val next = ((cursorX / 8) + 1) * 8
        cursorX = next.coerceAtMost(cols - 1)
        wrapPending = false
    }

    private fun putChar(code: Int) {
        if (wrapPending && autoWrap) {
            cursorX = 0
            lineFeed()
            wrapPending = false
        }
        buffer.setCell(cursorX, cursorY, code, curFg, curBg, curAttrs)
        lastPrinted = code
        if (cursorX + 1 >= cols) {
            // Defer the wrap: a character in the last column must not scroll the screen
            // until the NEXT character arrives, or every full line double-spaces.
            if (autoWrap) wrapPending = true else cursorX = cols - 1
        } else {
            cursorX++
        }
    }

    private fun lineFeed() {
        if (cursorY == scrollBottom) {
            keepTopLine()
            buffer.scrollUp(scrollTop, scrollBottom, curFg, curBg)
        } else if (cursorY < rows - 1) {
            cursorY++
        }
        wrapPending = false
    }

    // ---- escape ----------------------------------------------------------------
    private fun esc(b: Int) {
        when (b.toChar()) {
            '[' -> state = State.CSI
            ']' -> { state = State.OSC; oscAcc.setLength(0) }
            '(', ')', '*', '+' -> state = State.CHARSET
            '7' -> { saveCursor(); state = State.GROUND }
            '8' -> { restoreCursor(); state = State.GROUND }
            'M' -> { reverseIndex(); state = State.GROUND }
            'D' -> { lineFeed(); state = State.GROUND }
            'E' -> { cursorX = 0; lineFeed(); state = State.GROUND }
            'c' -> { fullReset(); state = State.GROUND }
            else -> state = State.GROUND
        }
    }

    private fun reverseIndex() {
        if (cursorY == scrollTop) buffer.scrollDown(scrollTop, scrollBottom, curFg, curBg)
        else if (cursorY > 0) cursorY--
    }

    private fun saveCursor() {
        savedX = cursorX; savedY = cursorY
        savedFg = curFg; savedBg = curBg; savedAttrs = curAttrs
    }

    private fun restoreCursor() {
        cursorX = savedX.coerceIn(0, cols - 1)
        cursorY = savedY.coerceIn(0, rows - 1)
        curFg = savedFg; curBg = savedBg; curAttrs = savedAttrs
        wrapPending = false
    }

    private fun fullReset() {
        curFg = TerminalBuffer.DEFAULT_FG
        curBg = TerminalBuffer.DEFAULT_BG
        curAttrs = 0
        scrollTop = 0; scrollBottom = rows - 1
        cursorX = 0; cursorY = 0
        autoWrap = true; cursorVisible = true
        buffer.clearAll()
        clearHistory()
    }

    // ---- OSC -------------------------------------------------------------------
    private fun osc(b: Int) {
        // Terminated by BEL or ST (ESC \). Window titles land here; we consume them.
        if (b == 0x07) { state = State.GROUND; return }
        if (b == 0x1B) return          // wait for the backslash
        if (b == '\\'.code && oscAcc.isNotEmpty()) { state = State.GROUND; return }
        if (oscAcc.length < 512) oscAcc.append(b.toChar())
    }

    // ---- CSI -------------------------------------------------------------------
    private fun csi(b: Int) {
        val c = b.toChar()
        when {
            c in '0'..'9' -> {
                paramAcc = (if (paramAcc < 0) 0 else paramAcc) * 10 + (b - '0'.code)
                return
            }
            // ':' separates sub-parameters (SGR 4:3, 38:2::r:g:b). Read as ';' it keeps the
            // sequence whole; ending the sequence on it would print the rest as text.
            c == ';' || c == ':' -> { params.add(if (paramAcc < 0) 0 else paramAcc); paramAcc = -1; return }
            c == '?' || c == '>' || c == '<' || c == '=' -> { marker = c; priv = c == '?'; return }
            c == ' ' || c == '$' || c == '"' || c == '\'' || c == '!' -> return // intermediates
        }
        if (paramAcc >= 0) params.add(paramAcc)
        paramAcc = -1
        // A private marker other than '?' belongs to sequences this emulator has no use for
        // (tmux's CSI > 4 ; 1 m sets a keyboard mode - read as SGR it would turn on
        // underline and bold), and '?' only means something to the mode switches.
        if (marker == 0.toChar() || (marker == '?' && (c == 'h' || c == 'l' || c == 'J' || c == 'K'))) dispatchCsi(c)
        state = State.GROUND
        params.clear()
        priv = false
        marker = 0.toChar()
    }

    private fun p(i: Int, default: Int = 1): Int {
        val v = params.getOrNull(i) ?: 0
        return if (v == 0) default else v
    }

    private fun dispatchCsi(c: Char) {
        when (c) {
            'A' -> { cursorY = (cursorY - p(0)).coerceAtLeast(0); wrapPending = false }
            'B' -> { cursorY = (cursorY + p(0)).coerceAtMost(rows - 1); wrapPending = false }
            'C' -> { cursorX = (cursorX + p(0)).coerceAtMost(cols - 1); wrapPending = false }
            'D' -> { cursorX = (cursorX - p(0)).coerceAtLeast(0); wrapPending = false }
            'E' -> { cursorY = (cursorY + p(0)).coerceAtMost(rows - 1); cursorX = 0 }
            'F' -> { cursorY = (cursorY - p(0)).coerceAtLeast(0); cursorX = 0 }
            'G', '`' -> { cursorX = (p(0) - 1).coerceIn(0, cols - 1); wrapPending = false }
            'd' -> { cursorY = (p(0) - 1).coerceIn(0, rows - 1); wrapPending = false }
            'H', 'f' -> {
                cursorY = (p(0) - 1).coerceIn(0, rows - 1)
                cursorX = (p(1) - 1).coerceIn(0, cols - 1)
                wrapPending = false
            }
            'J' -> eraseDisplay(params.getOrNull(0) ?: 0)
            'K' -> eraseLine(params.getOrNull(0) ?: 0)
            'L' -> repeatInRegion(p(0)) { buffer.scrollDown(cursorY, scrollBottom, curFg, curBg) }
            'M' -> repeatInRegion(p(0)) { buffer.scrollUp(cursorY, scrollBottom, curFg, curBg) }
            'P' -> buffer.deleteChars(cursorX, cursorY, p(0).coerceAtMost(cols - cursorX), curFg, curBg)
            '@' -> buffer.insertChars(cursorX, cursorY, p(0).coerceAtMost(cols - cursorX), curFg, curBg)
            'S' -> repeat(p(0)) { keepTopLine(); buffer.scrollUp(scrollTop, scrollBottom, curFg, curBg) }
            'T' -> repeat(p(0)) { buffer.scrollDown(scrollTop, scrollBottom, curFg, curBg) }
            'X' -> {
                val n = p(0).coerceAtMost(cols - cursorX)
                val start = buffer.index(cursorX, cursorY)
                buffer.clearRegion(start, start + n, curFg, curBg)
            }
            'r' -> {
                scrollTop = (p(0) - 1).coerceIn(0, rows - 1)
                scrollBottom = (p(1, rows) - 1).coerceIn(scrollTop, rows - 1)
                cursorX = 0; cursorY = scrollTop
            }
            'm' -> applySgr()
            'b' -> repeat(p(0).coerceAtMost(cols * rows)) { putChar(lastPrinted) }
            'h' -> setMode(true)
            'l' -> setMode(false)
            's' -> saveCursor()
            'u' -> restoreCursor()
        }
    }

    private inline fun repeatInRegion(n: Int, body: () -> Unit) {
        if (cursorY < scrollTop || cursorY > scrollBottom) return
        repeat(n.coerceAtMost(rows)) { body() }
    }

    private fun eraseDisplay(mode: Int) {
        val here = buffer.index(cursorX, cursorY)
        when (mode) {
            0 -> buffer.clearRegion(here, cols * rows, curFg, curBg)
            1 -> buffer.clearRegion(0, here + 1, curFg, curBg)
            2 -> buffer.clearRegion(0, cols * rows, curFg, curBg)
            // ED 3 is "clear the scrollback" (what `clear` sends): the screen stays.
            3 -> if (buffer === mainBuffer) clearHistory()
        }
    }

    private fun eraseLine(mode: Int) {
        val rowStart = buffer.index(0, cursorY)
        val here = buffer.index(cursorX, cursorY)
        when (mode) {
            0 -> buffer.clearRegion(here, rowStart + cols, curFg, curBg)
            1 -> buffer.clearRegion(rowStart, here + 1, curFg, curBg)
            2 -> buffer.clearRegion(rowStart, rowStart + cols, curFg, curBg)
        }
    }

    private fun setMode(on: Boolean) {
        for (m in params) {
            if (!priv) continue
            when (m) {
                7 -> autoWrap = on
                25 -> cursorVisible = on
                1049, 47, 1047 -> switchAlt(on)
            }
        }
    }

    private fun switchAlt(toAlt: Boolean) {
        if (toAlt == usingAlt) return
        if (toAlt) {
            saveCursor()
            val alt = altBuffer ?: TerminalBuffer(buffer.cols, buffer.rows).also { altBuffer = it }
            alt.resize(buffer.cols, buffer.rows)
            alt.clearAll(curFg, curBg)
            buffer = alt
            usingAlt = true
            cursorX = 0; cursorY = 0
        } else {
            buffer = mainBuffer
            usingAlt = false
            restoreCursor()
        }
        scrollTop = 0
        scrollBottom = rows - 1
    }

    private fun applySgr() {
        if (params.isEmpty()) {
            curFg = TerminalBuffer.DEFAULT_FG; curBg = TerminalBuffer.DEFAULT_BG; curAttrs = 0
            return
        }
        var i = 0
        while (i < params.size) {
            when (val v = params[i]) {
                0 -> { curFg = TerminalBuffer.DEFAULT_FG; curBg = TerminalBuffer.DEFAULT_BG; curAttrs = 0 }
                1 -> curAttrs = curAttrs or TerminalBuffer.BOLD
                2 -> curAttrs = curAttrs or TerminalBuffer.DIM
                3 -> curAttrs = curAttrs or TerminalBuffer.ITALIC
                4 -> curAttrs = curAttrs or TerminalBuffer.UNDERLINE
                7 -> curAttrs = curAttrs or TerminalBuffer.REVERSE
                9 -> curAttrs = curAttrs or TerminalBuffer.STRIKE
                22 -> curAttrs = curAttrs and (TerminalBuffer.BOLD or TerminalBuffer.DIM).inv()
                23 -> curAttrs = curAttrs and TerminalBuffer.ITALIC.inv()
                24 -> curAttrs = curAttrs and TerminalBuffer.UNDERLINE.inv()
                27 -> curAttrs = curAttrs and TerminalBuffer.REVERSE.inv()
                29 -> curAttrs = curAttrs and TerminalBuffer.STRIKE.inv()
                in 30..37 -> curFg = ansi16(v - 30)
                in 90..97 -> curFg = ansi16(v - 90 + 8)
                39 -> curFg = TerminalBuffer.DEFAULT_FG
                in 40..47 -> curBg = ansi16(v - 40)
                in 100..107 -> curBg = ansi16(v - 100 + 8)
                49 -> curBg = TerminalBuffer.DEFAULT_BG
                38, 48 -> {
                    val (colour, consumed) = extendedColour(i)
                    if (colour != null) { if (v == 38) curFg = colour else curBg = colour }
                    i += consumed
                }
            }
            i++
        }
    }

    /** Returns the colour and how many extra params it consumed. */
    private fun extendedColour(at: Int): Pair<Int?, Int> {
        return when (params.getOrNull(at + 1)) {
            5 -> Pair(xterm256(params.getOrNull(at + 2) ?: 0), 2)
            2 -> {
                val r = params.getOrNull(at + 2) ?: 0
                val g = params.getOrNull(at + 3) ?: 0
                val b = params.getOrNull(at + 4) ?: 0
                Pair(rgb(r, g, b), 4)
            }
            else -> Pair(null, 1)
        }
    }

    private fun rgb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private fun ansi16(i: Int): Int = ANSI_16[i.coerceIn(0, 15)]

    private fun xterm256(i: Int): Int = when {
        i < 16 -> ANSI_16[i]
        i < 232 -> {
            val n = i - 16
            val r = CUBE[(n / 36) % 6]; val g = CUBE[(n / 6) % 6]; val b = CUBE[n % 6]
            rgb(r, g, b)
        }
        i < 256 -> { val v = 8 + (i - 232) * 10; rgb(v, v, v) }
        else -> TerminalBuffer.DEFAULT_FG
    }

    companion object {
        private val CUBE = intArrayOf(0, 95, 135, 175, 215, 255)

        /**
         * The 16 ANSI colours, chosen to sit on Porthole's ground rather than a generic
         * black - a terminal palette lifted from another product reads as a foreign
         * rectangle inside the app.
         */
        val ANSI_16 = intArrayOf(
            0xFF15201F.toInt(), // black
            0xFFF2685E.toInt(), // red      (matches `bad`)
            0xFF5FCF80.toInt(), // green    (matches `ok`)
            0xFFE8B54A.toInt(), // yellow   (matches `warn`)
            0xFF5B9BD5.toInt(), // blue
            0xFFB07CD8.toInt(), // magenta
            0xFF3FD4C0.toInt(), // cyan     (matches `accent`)
            0xFFC7D3D1.toInt(), // white
            0xFF44514F.toInt(), // bright black
            0xFFFF8A80.toInt(),
            0xFF87E8A2.toInt(),
            0xFFFFD37A.toInt(),
            0xFF82BEF0.toInt(),
            0xFFCEA1F0.toInt(),
            0xFF7FE9DC.toInt(),
            0xFFF2F7F6.toInt(),
        )
    }
}
