package dev.shrimpscript.porthole.ui

import dev.shrimpscript.porthole.net.Row
import dev.shrimpscript.porthole.net.appendLive
import dev.shrimpscript.porthole.net.parseCommand
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommandsTest {
    @Test
    fun bareCommandsTheSheetAnswersOpenIt() {
        listOf("/model", "/effort", "/cost", "/stats", "/status", " /model ").forEach { assertTrue(it, opensSheet(it)) }
        // with an argument it is typed into the session; others still go to the CLI
        listOf("/model opus", "/effort high", "/context", "/usage", "/compact", "model").forEach { assertFalse(it, opensSheet(it)) }
    }

    /** The keys are the daemon's (transcript.Command's JSON tags). */
    @Test
    fun aCommandRowReadsAsTheDaemonWritesIt() {
        val cmd = parseCommand(JSONObject("""{"name":"context","args":"","output":"x","error":true,"value":"high","saved":true,"skill":true,
            "context":{"model":"claude-opus-5-5","used":27100,"total":1000000,"parts":[{"name":"MCP tools","tokens":137800,"kind":"deferred"},{"name":"Skills","tokens":10000}]},
            "compact":{"trigger":"auto","before":970287,"after":13445,"duration_ms":145410}}"""))!!
        assertEquals("context", cmd.name)
        assertTrue(cmd.error && cmd.saved && cmd.skill)
        assertEquals("high", cmd.value)
        assertEquals(27_100L, cmd.context!!.used)
        assertEquals("deferred", cmd.context!!.parts[0].kind)
        assertEquals("", cmd.context!!.parts[1].kind)
        assertEquals(145_410L, cmd.compact!!.durationMs)
        assertNull(parseCommand(null))
    }

    private fun r(kind: String, text: String) = Row(kind = kind, glyph = "", text = text, metric = "", detail = "", truncated = false)

    /** Typed while Claude was busy, then delivered in a later batch: one row, where the bubble was. */
    @Test
    fun aDeliveredMessageTakesItsQueuedBubblesPlace() {
        val feed = listOf(r("user", "go"), r("queued", "/effort high"), r("queued", "and check the logs"))
        val (out, folded) = appendLive(feed, listOf(r("assistant", "on it"), r("command", "/effort high"), r("user", "and check the logs")))
        assertEquals(2, folded)
        assertEquals(listOf("user", "command", "user", "assistant"), out.map { it.kind })
        // nothing queued: plain append
        val (plain, none) = appendLive(listOf(r("user", "go")), listOf(r("user", "go")))
        assertEquals(0, none)
        assertEquals(2, plain.size)
    }

    @Test
    fun compactionSaysHowMuchItFreed() {
        assertEquals("Compacted · 39k → 5.6k tokens", compactLine(dev.shrimpscript.porthole.net.Compaction("manual", 39_045, 5_594, 10_836)))
        assertEquals("Compacted automatically", compactLine(dev.shrimpscript.porthole.net.Compaction("auto", 0, 0, 0)))
    }
}
