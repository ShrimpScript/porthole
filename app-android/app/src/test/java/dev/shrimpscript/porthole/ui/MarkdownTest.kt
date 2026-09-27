package dev.shrimpscript.porthole.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun headingsParagraphsAndRules() {
        val b = Markdown.parse("# Title\n\nSome text\nthat wraps.\n\n---\n\n## Sub")
        assertEquals(MdBlock.Heading(1, "Title"), b[0])
        assertEquals(MdBlock.Paragraph("Some text that wraps."), b[1])
        assertEquals(MdBlock.Rule, b[2])
        assertEquals(MdBlock.Heading(2, "Sub"), b[3])
    }

    @Test
    fun fencedCodeKeepsWhitespaceAndLanguage() {
        val b = Markdown.parse("Before\n```kotlin\nfun x() {\n    return 1\n}\n```\nAfter")
        assertEquals(MdBlock.Paragraph("Before"), b[0])
        assertEquals(MdBlock.Code("kotlin", "fun x() {\n    return 1\n}"), b[1])
        assertEquals(MdBlock.Paragraph("After"), b[2])
    }

    @Test
    fun unterminatedFenceStillRendersAsCode() {
        val b = Markdown.parse("```\necho hi")
        assertEquals(MdBlock.Code("", "echo hi"), b[0])
    }

    @Test
    fun listsBulletNumberedAndNested() {
        val b = Markdown.parse("- one\n- two\n  - nested\n1. first\n2) second\n   continued")
        assertEquals(MdBlock.ListItem(false, 0, 0, "one"), b[0])
        assertEquals(MdBlock.ListItem(false, 0, 0, "two"), b[1])
        assertEquals(MdBlock.ListItem(false, 0, 1, "nested"), b[2])
        assertEquals(MdBlock.ListItem(true, 1, 0, "first"), b[3])
        assertEquals(MdBlock.ListItem(true, 2, 0, "second continued"), b[4])
    }

    @Test
    fun tables() {
        val b = Markdown.parse("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |")
        val t = b[0] as MdBlock.Table
        assertEquals(listOf("a", "b"), t.header)
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4")), t.rows)
    }

    @Test
    fun quotes() {
        val b = Markdown.parse("> a line\n> another")
        assertEquals(MdBlock.Quote("a line\nanother"), b[0])
    }

    @Test
    fun inlineBoldItalicCodeStrike() {
        val s = Markdown.inlines("plain **bold** and *it* and `code` and ~~gone~~")
        assertEquals("plain ", s[0].text)
        assertEquals(Markdown.Span("bold", bold = true), s[1])
        assertEquals(Markdown.Span("it", italic = true), s[3])
        assertEquals(Markdown.Span("code", code = true), s[5])
        assertEquals(Markdown.Span("gone", strike = true), s[7])
    }

    @Test
    fun inlineLinksAndBareUrls() {
        val s = Markdown.inlines("see [docs](https://x.dev/a) or https://y.dev/b.")
        assertEquals(Markdown.Span("docs", link = "https://x.dev/a"), s[1])
        assertEquals(Markdown.Span("https://y.dev/b", link = "https://y.dev/b"), s[3])
        assertEquals(".", s[4].text)
    }

    @Test
    fun underscoresInIdentifiersAreNotEmphasis() {
        val s = Markdown.inlines("file_name_here and snake_case")
        assertEquals(1, s.size)
        assertFalse(s[0].italic)
    }

    @Test
    fun strayMarkersStayLiteral() {
        val s = Markdown.inlines("2 * 3 = 6 and a lone ` tick")
        assertEquals("2 * 3 = 6 and a lone ` tick", s.joinToString("") { it.text })
    }

    @Test
    fun codeSpansAreNotParsedInside() {
        val s = Markdown.inlines("`**not bold**`")
        assertEquals(Markdown.Span("**not bold**", code = true), s[0])
    }

    @Test
    fun looksFormatted() {
        assertTrue(Markdown.looksFormatted("- a\n- b"))
        assertTrue(Markdown.looksFormatted("run `ls`"))
        assertFalse(Markdown.looksFormatted("Just a sentence."))
    }

    @Test fun `bare localhost addresses become http links, plain hosts do not`() {
        val spans = Markdown.inlines("running on localhost:5173/app now, see 127.0.0.1:8080.")
        val links = spans.filter { it.link != null }
        assertEquals(listOf("http://localhost:5173/app", "http://127.0.0.1:8080"), links.map { it.link })
        assertEquals(listOf("localhost:5173/app", "127.0.0.1:8080"), links.map { it.text })
        assertEquals(0, Markdown.inlines("mylocalhost:5173 and localhost").count { it.link != null })
    }

    @Test fun `a backticked address is code and a link, a backticked command is only code`() {
        val addr = Markdown.inlines("open `http://localhost:5173` now").first { it.code }
        assertEquals("http://localhost:5173", addr.link)
        val bare = Markdown.inlines("or `localhost:5173/app`").first { it.code }
        assertEquals("http://localhost:5173/app", bare.link)
        assertNull(Markdown.inlines("run `npm run dev`").first { it.code }.link)
    }
}
