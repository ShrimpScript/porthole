package dev.shrimpscript.porthole.terminal

/**
 * A character grid with attributes: the model half of the terminal.
 *
 * Cells are stored in parallel arrays rather than objects. A 200x50 grid is 10,000 cells
 * redrawn on every frame, and allocating that many objects per screen would make the
 * renderer stutter on a phone.
 */
class TerminalBuffer(var cols: Int, var rows: Int) {

    companion object {
        const val DEFAULT_FG = -1      // "use the theme's foreground"
        const val DEFAULT_BG = -2      // "use the theme's background"

        const val BOLD = 1
        const val DIM = 1 shl 1
        const val ITALIC = 1 shl 2
        const val UNDERLINE = 1 shl 3
        const val REVERSE = 1 shl 4
        const val STRIKE = 1 shl 5
    }

    var chars = IntArray(cols * rows) { ' '.code }
        private set
    var fg = IntArray(cols * rows) { DEFAULT_FG }
        private set
    var bg = IntArray(cols * rows) { DEFAULT_BG }
        private set
    var attrs = IntArray(cols * rows)
        private set

    /** Bumped on every mutation so the renderer knows to redraw. */
    var revision = 0
        private set

    fun index(x: Int, y: Int) = y * cols + x

    fun charAt(x: Int, y: Int): Int = chars[index(x, y)]

    fun setCell(x: Int, y: Int, code: Int, f: Int, b: Int, a: Int) {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return
        val i = index(x, y)
        chars[i] = code
        fg[i] = f
        bg[i] = b
        attrs[i] = a
        revision++
    }

    fun clearRegion(from: Int, to: Int, f: Int, b: Int) {
        val lo = from.coerceIn(0, chars.size)
        val hi = to.coerceIn(0, chars.size)
        for (i in lo until hi) {
            chars[i] = ' '.code
            fg[i] = f
            bg[i] = b
            attrs[i] = 0
        }
        revision++
    }

    fun clearAll(f: Int = DEFAULT_FG, b: Int = DEFAULT_BG) = clearRegion(0, chars.size, f, b)

    /** Scroll [top]..[bottom] up by one line, filling the vacated row. */
    fun scrollUp(top: Int, bottom: Int, f: Int, b: Int) {
        if (top >= bottom) return
        val width = cols
        System.arraycopy(chars, (top + 1) * width, chars, top * width, (bottom - top) * width)
        System.arraycopy(fg, (top + 1) * width, fg, top * width, (bottom - top) * width)
        System.arraycopy(bg, (top + 1) * width, bg, top * width, (bottom - top) * width)
        System.arraycopy(attrs, (top + 1) * width, attrs, top * width, (bottom - top) * width)
        clearRegion(bottom * width, (bottom + 1) * width, f, b)
    }

    fun scrollDown(top: Int, bottom: Int, f: Int, b: Int) {
        if (top >= bottom) return
        val width = cols
        for (y in bottom downTo top + 1) {
            System.arraycopy(chars, (y - 1) * width, chars, y * width, width)
            System.arraycopy(fg, (y - 1) * width, fg, y * width, width)
            System.arraycopy(bg, (y - 1) * width, bg, y * width, width)
            System.arraycopy(attrs, (y - 1) * width, attrs, y * width, width)
        }
        clearRegion(top * width, (top + 1) * width, f, b)
    }

    /** Shift a row's tail left (DCH) or right (ICH) from [x]. */
    fun deleteChars(x: Int, y: Int, n: Int, f: Int, b: Int) {
        if (y !in 0 until rows) return
        val start = index(x, y)
        val end = index(cols, y) // one past the row
        val count = (end - start - n).coerceAtLeast(0)
        if (count > 0) {
            System.arraycopy(chars, start + n, chars, start, count)
            System.arraycopy(fg, start + n, fg, start, count)
            System.arraycopy(bg, start + n, bg, start, count)
            System.arraycopy(attrs, start + n, attrs, start, count)
        }
        clearRegion(end - n.coerceAtMost(end - start), end, f, b)
    }

    fun insertChars(x: Int, y: Int, n: Int, f: Int, b: Int) {
        if (y !in 0 until rows) return
        val start = index(x, y)
        val end = index(cols, y)
        val count = (end - start - n).coerceAtLeast(0)
        for (i in count - 1 downTo 0) {
            chars[start + n + i] = chars[start + i]
            fg[start + n + i] = fg[start + i]
            bg[start + n + i] = bg[start + i]
            attrs[start + n + i] = attrs[start + i]
        }
        clearRegion(start, (start + n).coerceAtMost(end), f, b)
    }

    /**
     * Resize, preserving as much content as fits. Rotating the phone is a resize, and
     * losing the screen every time would make landscape useless.
     */
    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        if (newCols <= 0 || newRows <= 0) return
        val nc = IntArray(newCols * newRows) { ' '.code }
        val nf = IntArray(newCols * newRows) { DEFAULT_FG }
        val nb = IntArray(newCols * newRows) { DEFAULT_BG }
        val na = IntArray(newCols * newRows)
        val copyRows = minOf(rows, newRows)
        val copyCols = minOf(cols, newCols)
        for (y in 0 until copyRows) {
            for (x in 0 until copyCols) {
                val src = y * cols + x
                val dst = y * newCols + x
                nc[dst] = chars[src]; nf[dst] = fg[src]; nb[dst] = bg[src]; na[dst] = attrs[src]
            }
        }
        chars = nc; fg = nf; bg = nb; attrs = na
        cols = newCols; rows = newRows
        revision++
    }

    fun snapshotText(): String = buildString {
        for (y in 0 until rows) {
            for (x in 0 until cols) append(chars[index(x, y)].toChar())
            if (y < rows - 1) append('\n')
        }
    }
}
