package dev.shrimpscript.porthole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeMono
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType

/*
 * A small markdown renderer for assistant messages.
 *
 * Claude formats everything - headings, bullets, bold, fenced code, the occasional
 * table - and drawing that as raw text is what made the feed read like a log dump. This
 * covers the subset Claude Code actually emits, verified against real transcripts, and
 * renders anything outside it as plain text rather than as broken markup.
 *
 * The parser is pure Kotlin so it is unit-tested on the JVM (MarkdownTest).
 */

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class ListItem(val ordered: Boolean, val index: Int, val depth: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

object Markdown {

    private val heading = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val bullet = Regex("""^(\s*)([-*+])\s+(.*)$""")
    private val numbered = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
    private val rule = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val tableSep = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    fun parse(src: String): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val lines = src.replace("\r\n", "\n").split('\n')
        val para = StringBuilder()
        fun flushPara() {
            if (para.isNotEmpty()) {
                out += MdBlock.Paragraph(para.toString().trim())
                para.setLength(0)
            }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // fenced code: ``` or ~~~, optional language, until the matching fence
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                flushPara()
                val fence = trimmed.substring(0, 3)
                val lang = trimmed.removePrefix(fence).trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                out += MdBlock.Code(lang, code.toString().trimEnd('\n'))
                i++ // past the closing fence (or EOF)
                continue
            }

            if (trimmed.isEmpty()) { flushPara(); i++; continue }

            val h = heading.matchEntire(trimmed)
            if (h != null) {
                flushPara()
                out += MdBlock.Heading(h.groupValues[1].length, h.groupValues[2])
                i++
                continue
            }

            if (rule.matches(trimmed)) { flushPara(); out += MdBlock.Rule; i++; continue }

            // table: a header row followed by a separator row
            if (trimmed.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1])) {
                flushPara()
                val header = splitRow(trimmed)
                val rows = ArrayList<List<String>>()
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += splitRow(lines[i].trim())
                    i++
                }
                out += MdBlock.Table(header, rows)
                continue
            }

            val bm = bullet.matchEntire(line)
            if (bm != null) {
                flushPara()
                out += MdBlock.ListItem(false, 0, bm.groupValues[1].length / 2, bm.groupValues[3])
                i++
                continue
            }

            val nm = numbered.matchEntire(line)
            if (nm != null) {
                flushPara()
                out += MdBlock.ListItem(true, nm.groupValues[2].toIntOrNull() ?: 1,
                    nm.groupValues[1].length / 2, nm.groupValues[3])
                i++
                continue
            }

            if (trimmed.startsWith(">")) {
                flushPara()
                val q = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    q.append(lines[i].trim().removePrefix(">").trim()).append('\n')
                    i++
                }
                out += MdBlock.Quote(q.toString().trim())
                continue
            }

            // continuation line of a list item is indented text right after one
            val last = out.lastOrNull()
            if (para.isEmpty() && last is MdBlock.ListItem && line.startsWith("  ")) {
                out[out.size - 1] = last.copy(text = last.text + " " + trimmed)
                i++
                continue
            }

            if (para.isNotEmpty()) para.append(' ')
            para.append(trimmed)
            i++
        }
        flushPara()
        return out
    }

    private fun splitRow(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /** One inline span: text plus how it should look. */
    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val strike: Boolean = false,
        val link: String? = null,
    )

    private val bareUrl = Regex("""https?://[^\s<>()\[\]]+""")
    /** "localhost:5173/app" and friends: on the phone these are only useful as links. */
    private val bareLocal = Regex("""(?:localhost|127\.0\.0\.1|0\.0\.0\.0):\d{2,5}(?:/[^\s<>()\[\]]*)?""")

    /** Inline markup -> spans. Unmatched markers stay as literal text. */
    fun inlines(s: String): List<Span> {
        val out = ArrayList<Span>()
        var bold = false
        var italic = false
        var strike = false
        val buf = StringBuilder()
        fun flush(link: String? = null) {
            if (buf.isNotEmpty()) {
                out += Span(buf.toString(), bold, italic, false, strike, link)
                buf.setLength(0)
            }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in "*_`~[]\\" -> { buf.append(s[i + 1]); i += 2 }
                c == '`' -> {
                    // inline code runs to the next backtick, and nothing inside is markup
                    val end = s.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flush()
                        val code = s.substring(i + 1, end)
                        // `http://localhost:5173` is how Claude usually prints an address.
                        val link = when {
                            bareUrl.matches(code) -> code
                            bareLocal.matches(code) -> "http://$code"
                            else -> null
                        }
                        out += Span(code, code = true, link = link)
                        i = end + 1
                    } else { buf.append(c); i++ }
                }
                // A marker closes whatever it opened unconditionally; it only opens when a
                // matching closer exists later, so a lone ** stays literal text.
                s.startsWith("**", i) && (bold || hasCloser(s, i + 2, "**")) -> { flush(); bold = !bold; i += 2 }
                s.startsWith("__", i) && (bold || hasCloser(s, i + 2, "__")) -> { flush(); bold = !bold; i += 2 }
                s.startsWith("~~", i) && (strike || hasCloser(s, i + 2, "~~")) -> { flush(); strike = !strike; i += 2 }
                (c == '*' || c == '_') && (italic && !s.startsWith("$c$c", i) || emphasisOk(s, i)) -> { flush(); italic = !italic; i++ }
                c == '[' -> {
                    val close = s.indexOf(']', i + 1)
                    if (close > i && close + 1 < s.length && s[close + 1] == '(') {
                        val end = s.indexOf(')', close + 2)
                        if (end > close) {
                            flush()
                            val label = s.substring(i + 1, close)
                            val url = s.substring(close + 2, end).substringBefore(' ')
                            out += Span(label, bold, italic, false, strike, url)
                            i = end + 1
                            continue
                        }
                    }
                    buf.append(c); i++
                }
                c == 'h' && bareUrl.matchAt(s, i) != null -> {
                    val m = bareUrl.matchAt(s, i)!!
                    flush()
                    val url = m.value.trimEnd('.', ',', ';', ':')
                    out += Span(url, bold, italic, false, strike, url)
                    i += url.length
                }
                (c == 'l' || c == '1' || c == '0') && (i == 0 || !s[i - 1].isLetterOrDigit()) &&
                    bareLocal.matchAt(s, i) != null -> {
                    val m = bareLocal.matchAt(s, i)!!
                    flush()
                    val host = m.value.trimEnd('.', ',', ';', ':')
                    out += Span(host, bold, italic, false, strike, "http://$host")
                    i += host.length
                }
                else -> { buf.append(c); i++ }
            }
        }
        flush()
        return out
    }

    private fun hasCloser(s: String, from: Int, marker: String): Boolean {
        val idx = s.indexOf(marker, from)
        return idx > from
    }

    /** A single * or _ opens/closes emphasis only when it is not just a stray char in a word. */
    private fun emphasisOk(s: String, i: Int): Boolean {
        val c = s[i]
        if (s.startsWith("$c$c", i)) return false
        val prev = if (i > 0) s[i - 1] else ' '
        val next = if (i + 1 < s.length) s[i + 1] else ' '
        // snake_case and file_names_like_this are never emphasis
        if (c == '_' && prev.isLetterOrDigit() && next.isLetterOrDigit()) return false
        if (next == ' ' && prev == ' ') return false
        val closer = s.indexOf(c, i + 1)
        return closer > i + 1
    }

    /** True when the text has any markup worth rendering as markdown. */
    fun looksFormatted(s: String): Boolean =
        s.contains("```") || s.contains("**") || s.contains("`") ||
            s.lines().any { l ->
                val t = l.trimStart()
                t.startsWith("#") || t.startsWith("- ") || t.startsWith("* ") ||
                    t.startsWith("> ") || numbered.matches(l) || t.startsWith("|")
            }
}

/* ------------------------------------------------------------- rendering ---- */

@Composable
fun MarkdownText(
    source: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Porthole.type.prose,
    color: Color = Porthole.colors.text,
) {
    val blocks = remember(source) { Markdown.parse(source) }
    val c = Porthole.colors
    val type = Porthole.type
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { idx, b ->
            when (b) {
                is MdBlock.Heading -> {
                    val hs = when (b.level) {
                        1 -> type.proseTitle
                        2 -> type.proseHeading
                        else -> style.copy(fontWeight = FontWeight.SemiBold)
                    }
                    InlineText(b.text, hs, color, Modifier.padding(top = if (idx == 0) 0.dp else 6.dp))
                }
                is MdBlock.Paragraph -> InlineText(b.text, style, color)
                is MdBlock.Code -> CodeBlock(b.lang, b.code)
                is MdBlock.ListItem -> Row(
                    Modifier.padding(start = (b.depth * 16).dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (b.ordered) "${b.index}." else "•",
                        style = style, color = c.muted,
                        modifier = Modifier.width(if (b.ordered) 22.dp else 12.dp),
                    )
                    InlineText(b.text, style, color, Modifier.weight(1f))
                }
                is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(18.dp)
                            .background(c.accent, PortholeShape.pill)
                    )
                    InlineText(
                        b.text, style, c.muted,
                        Modifier.padding(start = 10.dp).weight(1f),
                    )
                }
                is MdBlock.Table -> TableBlock(b)
                MdBlock.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .height(1.dp)
                        .background(c.edge)
                )
            }
        }
    }
}

@Composable
private fun InlineText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val c = Porthole.colors
    val spans = remember(text) { Markdown.inlines(text) }
    val annotated = remember(spans, color) {
        buildAnnotatedString {
            spans.forEach { sp ->
                val st = SpanStyle(
                    color = if (sp.link != null) c.accent else if (sp.code) c.text else color,
                    fontWeight = if (sp.bold) FontWeight.SemiBold else null,
                    fontStyle = if (sp.italic) FontStyle.Italic else null,
                    fontFamily = if (sp.code) PortholeMono else null,
                    fontSize = if (sp.code) (style.fontSize.value - 1.5f).sp else style.fontSize,
                    background = if (sp.code) c.raised else Color.Unspecified,
                    textDecoration = when {
                        sp.strike -> TextDecoration.LineThrough
                        sp.link != null -> TextDecoration.Underline
                        else -> null
                    },
                )
                if (sp.link != null) {
                    pushStringAnnotation("url", sp.link)
                    withStyle(st) { append(sp.text) }
                    pop()
                } else {
                    withStyle(st) { append(sp.text) }
                }
            }
        }
    }
    val uri = LocalUriHandler.current
    val hasLink = spans.any { it.link != null }
    Text(
        annotated, style = style, color = color,
        modifier = if (hasLink) modifier.clickable {
            annotated.getStringAnnotations("url", 0, annotated.length).firstOrNull()
                ?.let { runCatching { uri.openUri(it.item) } }
        } else modifier,
    )
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    val c = Porthole.colors
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, PortholeShape.control)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(lang.ifBlank { "code" }, style = PortholeType.meta, color = c.faint)
            Box(Modifier.weight(1f))
            Text(
                "copy", style = PortholeType.meta, color = c.accent,
                modifier = Modifier
                    .clickable { clipboard.setText(AnnotatedString(code)) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        Text(
            code, style = PortholeType.mono, color = c.text,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        )
    }
}

/**
 * Tables render as an aligned mono grid inside a horizontal scroller. A phone is too
 * narrow for a real column layout of a five-column table, and reflowing cells destroys
 * the alignment that made it a table in the first place.
 */
@Composable
private fun TableBlock(t: MdBlock.Table) {
    val c = Porthole.colors
    val widths = remember(t) {
        val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0)
        IntArray(cols) { i ->
            maxOf(t.header.getOrNull(i)?.length ?: 0, t.rows.maxOfOrNull { it.getOrNull(i)?.length ?: 0 } ?: 0)
        }
    }
    fun line(cells: List<String>) = widths.indices.joinToString("  ") { i ->
        (cells.getOrNull(i) ?: "").padEnd(widths[i])
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, PortholeShape.control)
            .padding(12.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        Text(line(t.header), style = PortholeType.mono.copy(fontWeight = FontWeight.Bold), color = c.text)
        Text(widths.joinToString("  ") { "─".repeat(it) }, style = PortholeType.mono, color = c.edge)
        t.rows.forEach { r -> Text(line(r), style = PortholeType.mono, color = c.text) }
    }
}
