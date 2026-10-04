package dev.shrimpscript.porthole.ui

import dev.shrimpscript.porthole.net.AgentCall
import dev.shrimpscript.porthole.net.LineDiff
import dev.shrimpscript.porthole.net.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedItemsTest {
    private fun r(kind: String, text: String, id: String = "", tool: String = "", diff: LineDiff? = null, glyph: String = "", agent: AgentCall? = null) =
        Row(kind = kind, glyph = glyph, text = text, metric = "", detail = "", truncated = false, toolId = id, tool = tool, diff = diff, agent = agent)

    private val prompt = r("user", "Fix the render")
    private val say1 = r("assistant", "Checking whether the worker was killed.")
    private val bash1 = r("tool", "Ran journalctl -k", "t1", "Bash")
    private val res1 = r("result", "done", "t1", glyph = "✓")
    private val edit = r("tool", "Edited render.py", "t2", "Edit")
    private val res2 = r("result", "done", "t2", glyph = "✓", diff = LineDiff(12, 3))
    private val bash2 = r("tool", "Ran python render.py", "t3", "Bash")
    private val res3 = r("result", "failed", "t3", glyph = "✗")
    private val answer = r("assistant", "The worker ran out of memory; fixed.")
    private val turn = r("turn", "Worked for 6m 2s")

    @Test
    fun aStretchOfToolsIsOneWorkLine() {
        val items = feedItems(listOf(prompt, say1, bash1, res1, edit, res2, bash2, res3, answer, turn))
        assertEquals(listOf("One", "One", "Work", "One", "One"), items.map { it::class.simpleName })
        val work = items[2] as FeedItem.Work
        assertEquals(3, work.steps.size)
        assertEquals(res1, work.steps[0].result)
        assertEquals(2 to 7, work.first to work.last)
        assertEquals(12 to 3, work.added to work.removed)
        assertEquals(1, work.failed)
        assertNull(work.running)
        assertEquals("Ran 2 commands, edited a file", workSummary(work.steps))
    }

    @Test
    fun narrationIsWordsWithWorkAfterThemAndTheAnswerIsTheLast() {
        val items = feedItems(listOf(prompt, say1, bash1, res1, answer, turn))
        val words = items.filterIsInstance<FeedItem.One>().filter { it.row.kind == "assistant" }
        assertTrue(words[0].narration && !words[0].answer)
        assertTrue(!words[1].narration && words[1].answer)
        val turnLine = items.last() as FeedItem.One
        assertNull("no edits, no diff chip", turnLine.turnDiff)
    }

    @Test
    fun aTurnLineCarriesItsEditsForTheDiffChip() {
        val items = feedItems(listOf(prompt, edit, res2, answer, turn, r("user", "and again"), edit, res2, turn))
        val turns = items.filterIsInstance<FeedItem.One>().filter { it.row.kind == "turn" }
        assertEquals(12 to 3, turns[0].turnDiff)
        assertEquals("counted per turn", 12 to 3, turns[1].turnDiff)
    }

    @Test
    fun parallelCallsPairWithTheirResults() {
        val a = r("tool", "Read a.kt", "a", "Read")
        val b = r("tool", "Read b.kt", "b", "Read")
        val ra = r("result", "done", "a", glyph = "✓")
        val work = feedItems(listOf(a, b, ra)).single() as FeedItem.Work
        assertEquals(ra, work.steps[0].result)
        assertNull(work.steps[1].result)
        assertEquals(b, work.running?.call)
        assertEquals("Read 2 files", workSummary(work.steps))
    }

    @Test
    fun agentsQuestionsAndCommandsStayThemselves() {
        val agentCall = r("tool", "Delegated Map the config", "g1", "Agent", agent = AgentCall("Explore", "Map the config", "haiku", true))
        val agentDone = r("result", "done", "g1", glyph = "✓")
        val question = r("question", "Which size?", "q1")
        val answered = r("result", "Answered: Large", "q1", glyph = "✓")
        val command = r("command", "/effort high", "cmd:1")
        val lateReply = r("result", "Set effort level", "cmd:1", glyph = "✓")
        val items = feedItems(listOf(agentCall, agentDone, question, answered, command, lateReply))
        assertEquals("the agent's own result is left to its card", 5, items.size)
        assertTrue(items.all { it is FeedItem.One })
        assertFalse(items.any { (it as FeedItem.One).row === agentDone })
    }

    /** Rows can come between a call and its result; the pair is one step all the same, never left running. */
    @Test
    fun aResultFindsItsCallAcrossOtherRows() {
        val send = r("tool", "Sent a file", "s1", "SendUserFile")
        val picture = r("image", "Image Claude looked at")
        val sent = r("result", "done", "s1", glyph = "✓")
        val bash = r("tool", "Ran sleep 60", "b1", "Bash")
        val queued = r("queued", "and then deploy")
        val slept = r("result", "done", "b1", glyph = "✓")
        val items = feedItems(listOf(prompt, send, picture, sent, bash, queued, slept, answer, turn))
        assertEquals(listOf("One", "Work", "One", "Work", "One", "One", "One"), items.map { it::class.simpleName })
        val works = items.filterIsInstance<FeedItem.Work>()
        assertEquals(sent, works[0].steps.single().result)
        assertEquals(slept, works[1].steps.single().result)
        assertTrue(works.all { it.running == null })
        // Words before a message queued mid-turn are not the turn's answer.
        val words = items.filterIsInstance<FeedItem.One>().filter { it.row.kind == "assistant" }
        assertTrue(words.single().answer)
    }

    /** A queued message lands mid-turn: the turn's edits still add up across it. */
    @Test
    fun aQueuedMessageDoesNotSplitTheTurn() {
        val items = feedItems(listOf(prompt, edit, res2, r("queued", "also the docs"), say1, bash1, res1, answer, turn))
        val turnLine = items.last() as FeedItem.One
        assertEquals(12 to 3, turnLine.turnDiff)
        val narration = items.filterIsInstance<FeedItem.One>().first { it.row === say1 }
        assertTrue(narration.narration)
    }

    /** A step opens titled by what it did, the command above the output. */
    @Test
    fun aStepOpensWithItsCommand() {
        val call = Row(kind = "tool", glyph = "▸", text = "Ran npm test", metric = "", detail = "npm test -- --watch=false", truncated = false, toolId = "x", tool = "Bash")
        val out = Row(kind = "result", glyph = "✗", text = "failed", metric = "1 failing", detail = "1 failing\n  at app.test.ts:4", truncated = false, toolId = "x")
        val open = WorkStep(call, out).detailRow()
        assertEquals("Ran npm test", open.text)
        assertEquals("$ npm test -- --watch=false\n\n1 failing\n  at app.test.ts:4", open.detail)
        assertEquals("a result with nothing to show opens nothing", "", WorkStep(call.copy(detail = ""), out.copy(detail = "")).detailRow().detail)
    }

    @Test
    fun summariesSayEachKindOnce() {
        fun steps(vararg tools: String) = tools.mapIndexed { i, t -> WorkStep(r("tool", "x", "$i", t), null) }
        assertEquals("Created a file, ran a command", workSummary(steps("Write", "Bash")))
        assertEquals("Searched 2 times, fetched a page, used a tool", workSummary(steps("Grep", "Glob", "WebFetch", "Mystery")))
        // an older daemon names no tool: the verb says it
        assertEquals("Ran a command, made 2 edits", workSummary(listOf(
            WorkStep(r("tool", "Ran ls"), null), WorkStep(r("tool", "Edited a.kt"), null), WorkStep(r("tool", "Edited b.kt"), null))))
    }
}
