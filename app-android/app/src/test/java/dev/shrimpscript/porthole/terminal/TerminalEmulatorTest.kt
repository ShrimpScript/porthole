package dev.shrimpscript.porthole.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ESC = "\u001B"
private const val CSI = "$ESC["

class TerminalEmulatorTest {

    private fun emu(cols: Int = 20, rows: Int = 5) = TerminalEmulator(cols, rows)

    private fun TerminalEmulator.feed(s: String) = write(s.toByteArray(Charsets.UTF_8))

    private fun TerminalEmulator.line(y: Int): String =
        (0 until cols).map { buffer.charAt(it, y).toChar() }.joinToString("").trimEnd()

    @Test
    fun plainTextLands() {
        val e = emu(); e.feed("hello")
        assertEquals("hello", e.line(0))
        assertEquals(5, e.cursorX)
    }

    @Test
    fun newlineAndCarriageReturn() {
        val e = emu(); e.feed("ab\r\ncd")
        assertEquals("ab", e.line(0))
        assertEquals("cd", e.line(1))
    }

    /**
     * A character in the last column must not scroll until the NEXT one arrives, or
     * every full-width line double-spaces.
     */
    @Test
    fun deferredWrapAtLastColumn() {
        val e = emu(cols = 5, rows = 3)
        e.feed("abcde")
        assertEquals("abcde", e.line(0))
        assertEquals(0, e.cursorY)
        e.feed("f")
        assertEquals("f", e.line(1))
        assertEquals(1, e.cursorY)
    }

    @Test
    fun cursorPositioning() {
        val e = emu(); e.feed("${CSI}2;3Hx")
        assertEquals("  x", e.line(1))
    }

    @Test
    fun eraseToEndOfLine() {
        val e = emu(); e.feed("abcdef${CSI}1;4H${CSI}K")
        assertEquals("abc", e.line(0))
    }

    @Test
    fun eraseWholeDisplay() {
        val e = emu(); e.feed("junk\r\nmore${CSI}2J")
        assertEquals("", e.line(0))
        assertEquals("", e.line(1))
    }

    @Test
    fun sgrSetsColourAndResets() {
        val e = emu(); e.feed("${CSI}31mR${CSI}0mN")
        assertEquals(TerminalEmulator.ANSI_16[1], e.buffer.fg[0])
        assertEquals(TerminalBuffer.DEFAULT_FG, e.buffer.fg[1])
    }

    @Test
    fun truecolourIsParsed() {
        val e = emu(); e.feed("${CSI}38;2;10;20;30mX")
        val expected = (0xFF shl 24) or (10 shl 16) or (20 shl 8) or 30
        assertEquals(expected, e.buffer.fg[0])
    }

    @Test
    fun xterm256GreyRamp() {
        val e = emu(); e.feed("${CSI}38;5;232mX")
        assertEquals((0xFF shl 24) or (8 shl 16) or (8 shl 8) or 8, e.buffer.fg[0])
    }

    @Test
    fun boldAndReverseAttributes() {
        val e = emu(); e.feed("${CSI}1;7mX")
        val a = e.buffer.attrs[0]
        assertTrue(a and TerminalBuffer.BOLD != 0)
        assertTrue(a and TerminalBuffer.REVERSE != 0)
    }

    /** btop and vim live on the alternate screen; exiting must leave the shell intact. */
    @Test
    fun alternateScreenPreservesMainBuffer() {
        val e = emu()
        e.feed("shell output")
        e.feed("${CSI}?1049h")
        assertTrue(e.usingAlt)
        e.feed("${CSI}2Jfullscreen app")
        assertEquals("fullscreen app", e.line(0))
        e.feed("${CSI}?1049l")
        assertFalse(e.usingAlt)
        assertEquals("shell output", e.line(0))
    }

    @Test
    fun scrollRegionLeavesOutsideRowsAlone() {
        val e = emu(cols = 6, rows = 4)
        e.feed("a\r\nb\r\nc\r\nd")
        e.feed("${CSI}2;3r")
        e.feed("${CSI}3;1H\n")
        assertEquals("a", e.line(0))
        assertEquals("d", e.line(3))
    }

    @Test
    fun deleteAndInsertCharacters() {
        val e = emu(cols = 10)
        e.feed("abcdef${CSI}1;2H${CSI}2P")
        assertEquals("adef", e.line(0))
        e.feed("${CSI}1;2H${CSI}2@")
        assertEquals("a  def", e.line(0))
    }

    @Test
    fun utf8AndBoxDrawing() {
        val e = emu()
        e.feed("┌─┐ ✓ ⣿")
        assertEquals("┌─┐ ✓ ⣿", e.line(0))
    }

    @Test
    fun malformedUtf8BecomesReplacement() {
        val e = emu()
        e.write(byteArrayOf(0xC3.toByte(), 0x28))
        assertEquals('�', e.buffer.charAt(0, 0).toChar())
    }

    @Test
    fun cursorVisibilityToggles() {
        val e = emu()
        e.feed("${CSI}?25l"); assertFalse(e.cursorVisible)
        e.feed("${CSI}?25h"); assertTrue(e.cursorVisible)
    }

    @Test
    fun oscTitleIsSwallowedNotPrinted() {
        val e = emu()
        e.feed("${ESC}]0;my title\u0007done")
        assertEquals("done", e.line(0))
    }

    /**
     * An unsupported sequence must be consumed, never printed. A stray "[38;5;196m" on
     * screen is worse than a missing colour.
     */
    @Test
    fun unknownSequenceIsNotPrinted() {
        val e = emu()
        e.feed("${CSI}>4;2mok")
        assertEquals("ok", e.line(0))
    }

    @Test
    fun resizeKeepsContent() {
        val e = emu(cols = 10, rows = 3)
        e.feed("keepme")
        e.resize(20, 6)
        assertEquals("keepme", e.line(0))
        assertEquals(20, e.cols)
    }

    @Test
    fun scrollingPushesOldLinesOff() {
        val e = emu(cols = 4, rows = 2)
        e.feed("1\r\n2\r\n3")
        assertEquals("2", e.line(0))
        assertEquals("3", e.line(1))
    }

    @Test
    fun backspaceMovesLeft() {
        val e = emu(); e.feed("ab\bX")
        assertEquals("aX", e.line(0))
    }

    @Test
    fun tabAdvancesToNextStop() {
        val e = emu(cols = 20); e.feed("a\tb")
        assertEquals(8, e.line(0).indexOf('b'))
    }

    @Test
    fun savedCursorRestores() {
        val e = emu()
        e.feed("${CSI}3;5H${ESC}7${CSI}1;1H${ESC}8X")
        assertEquals(4, e.cursorX - 1)
        assertEquals('X', e.buffer.charAt(4, 2).toChar())
    }

    @Test fun `lines scrolled off the main screen are kept, the alternate screen's are not`() {
        val e = TerminalEmulator(20, 5)
        e.write((1..12).joinToString("\r\n") { "line $it" }.toByteArray())
        assertEquals(7, e.historySize)
        assertEquals("line 1", String(e.historyLine(0).chars.map { it.toChar() }.toCharArray()).trimEnd())
        assertEquals("line 7", String(e.historyLine(6).chars.map { it.toChar() }.toCharArray()).trimEnd())
        // A full-screen app redraws in place; its frames are not history.
        e.write("\u001b[?1049h".toByteArray())
        e.write((1..30).joinToString("\r\n") { "frame $it" }.toByteArray())
        e.write("\u001b[?1049l".toByteArray())
        assertEquals(7, e.historySize)
        // `clear` sends ED 3: the scrollback goes, the screen stays.
        e.write("\u001b[3J".toByteArray())
        assertEquals(0, e.historySize)
        assertEquals("line 12", e.buffer.snapshotText().lines().last { it.isNotBlank() }.trimEnd())
    }

    @Test fun `the scrollback is bounded`() {
        val e = TerminalEmulator(10, 3)
        e.historyLimit = 50
        e.write((1..500).joinToString("\r\n") { "$it" }.toByteArray())
        assertEquals(50, e.historySize)
        assertEquals(497L, e.historyPushed)
    }

    @Test fun `shrinking keeps the cursor's line on screen`() {
        val e = TerminalEmulator(20, 10)
        e.write(((1..9).joinToString("\r\n") { "out $it" } + "\r\n$ typing").toByteArray())
        e.resize(20, 4)
        assertEquals(3, e.cursorY)
        assertEquals("$ typing", e.buffer.snapshotText().lines()[3].trimEnd())
        assertEquals("out 6", String(e.historyLine(e.historySize - 1).chars.map { it.toChar() }.toCharArray()).trimEnd())
    }

    // What tmux sends when the phone attaches to it through an xterm-256color PTY.

    @Test fun `REP repeats the last character, as tmux draws runs of spaces and lines`() {
        val e = TerminalEmulator(20, 3)
        e.write("a\u2500${ESC}[5bz".toByteArray())
        assertEquals("a\u2500\u2500\u2500\u2500\u2500\u2500z", e.buffer.snapshotText().lines()[0].trimEnd())
    }

    @Test fun `a keyboard-mode sequence is not SGR`() {
        val e = TerminalEmulator(20, 3)
        e.write("${ESC}[>4;1mplain".toByteArray())
        assertEquals(0, e.buffer.attrs[0])
        assertEquals("plain", e.buffer.snapshotText().lines()[0].trimEnd())
    }

    @Test fun `unknown private sequences and sub-parameters are swallowed whole`() {
        val e = TerminalEmulator(30, 3)
        e.write("${ESC}[=5u${ESC}[<1u${ESC}[?2026h${ESC}[4:3mok${ESC}[38:2::255:0:0m!".toByteArray())
        assertEquals("ok!", e.buffer.snapshotText().lines()[0].trimEnd())
    }
}
