package dev.shrimpscript.porthole.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun row(kind: String, text: String, ts: String = "", imageRef: String = "") =
        Row(kind = kind, glyph = "", text = text, metric = "", detail = "", truncated = false, ts = ts, imageRef = imageRef)

    /** A draft outlives the store that wrote it: another start of the app reads it back. */
    @Test
    fun aDraftSurvivesTheApp() {
        val dir = tmp.newFolder("desk")
        val a = SessionStore(dir)
        a.setDraft("s1", "half a thought")
        a.setDraft("s2", "other session")
        a.flush()
        val b = SessionStore(dir)
        assertEquals("half a thought", b.draft("s1"))
        assertEquals("other session", b.draft("s2"))
        // A sent message's draft goes; a draft that changed since does not.
        b.clearDraftIf("s1", "half a thought")
        b.clearDraftIf("s2", "something else")
        b.flush()
        val c = SessionStore(dir)
        assertEquals("", c.draft("s1"))
        assertEquals("other session", c.draft("s2"))
    }

    /** Every kind of row comes back as it went in. */
    @Test
    fun aFeedComesBackWhole() {
        val store = SessionStore(tmp.newFolder("desk"))
        val rows = listOf(
            row("user", "go", "2026-10-03T08:00:00Z"),
            Row("tool", "▸", "Delegated Map the config", "", "the prompt", true, "2026-10-03T08:00:01Z", toolId = "t1",
                agent = AgentCall("Explore", "Map the config", "haiku", true)),
            Row("question", "?", "Which size?", "", "", false, "2026-10-03T08:00:02Z", toolId = "q1",
                questions = listOf(Question("Size", "Which size?", false, listOf(QuestionOption("Small", "fits a phone"))))),
            Row("command", "/", "/context", "", "", false, "2026-10-03T08:00:03Z", toolId = "cmd:c1",
                command = CommandInfo("context", output = "x", error = true, value = "v", saved = true, skill = true, auto = true,
                    context = ContextUsage("claude-opus-5-5", 27_100, 1_000_000, listOf(ContextPart("MCP tools", 137_800, "deferred"))),
                    compact = Compaction("auto", 970_000, 13_000, 145_000))),
        )
        store.saveFeed("s1", rows, JSONObject().put("model", "m"), remaining = 7)
        store.flush()
        val back = store.feed("s1")!!
        assertEquals(rows, back.rows)
        assertEquals(7, back.remaining)
        assertEquals("m", back.state?.optString("model"))
        assertTrue(back.savedAt > 0)
    }

    /** The last 200 rows are kept; the rest count as history still on the computer. */
    @Test
    fun aLongFeedKeepsItsTail() {
        val store = SessionStore(tmp.newFolder("desk"))
        store.saveFeed("s1", List(250) { row("assistant", "line $it", "2026-10-03T08:00:00Z") }, null, remaining = 10)
        store.flush()
        val back = store.feed("s1")!!
        assertEquals(SessionStore.KEEP_ROWS, back.rows.size)
        assertEquals("line 50", back.rows.first().text)
        assertEquals(60, back.remaining)
    }

    /** The most recently saved twenty sessions are kept; forgetting the computer removes them all. */
    @Test
    fun oldFeedsArePrunedAndForgettingClearsAll() {
        val dir = tmp.newFolder("desk")
        val store = SessionStore(dir)
        repeat(SessionStore.KEEP_SESSIONS + 3) { i ->
            store.saveFeed("s$i", listOf(row("user", "hi $i")), null, 0)
            store.flush()
            File(dir, "feeds/s$i.json").setLastModified(1_000_000L + i * 1000L)
        }
        store.saveFeed("s99", listOf(row("user", "newest")), null, 0)
        store.flush()
        assertNull(store.feed("s0"))
        assertTrue(store.feed("s99") != null)
        assertEquals(SessionStore.KEEP_SESSIONS, File(dir, "feeds").listFiles()!!.count { it.name.endsWith(".json") })
        store.setDraft("s99", "x")
        store.clear()
        store.flush()
        assertFalse(dir.exists())
        assertEquals("", store.draft("s99"))
    }

    @Test
    fun eachComputerHasItsOwnDirectory() {
        val root = tmp.newFolder("root")
        val a = SessionStore.forMachine(root, "workstation:8737")
        val b = SessionStore.forMachine(root, "[fd7a::1]:8737")
        assertTrue(a.dir != b.dir && a.dir.parentFile == root && b.dir.name.matches(Regex("[0-9a-f]{16}")))
        assertEquals(a.dir, SessionStore.forMachine(root, "workstation:8737").dir)
    }

    // ---- merging a backfill into what the phone holds ---------------------------------

    private val a = row("user", "a", "2026-10-03T08:00:00Z")
    private val b = row("assistant", "b", "2026-10-03T08:00:01Z")
    private val c = row("turn", "c", "2026-10-03T08:00:02Z")
    private val d = row("user", "d", "2026-10-03T08:01:00Z")
    private val e = row("assistant", "e", "2026-10-03T08:01:05Z")

    @Test
    fun theWindowMergesAfterWhatIsHeld() {
        val m = mergeBackfill(listOf(a, b, c), listOf(b, c, d, e))!!
        assertEquals(listOf(a, b, c, d, e), m.rows)
        assertEquals(1, m.keptBefore)
    }

    @Test
    fun aGapTooLongToBridgeReplaces() {
        assertNull(mergeBackfill(listOf(a, b), listOf(d, e)))
        assertNull(mergeBackfill(emptyList(), listOf(d)))
    }

    /** The window's first row may read differently live (a queued bubble): the window's version replaces it. */
    @Test
    fun aRowThatReadDifferentlyLiveIsReplaced() {
        val queuedLive = row("queued", "a", "2026-10-03T08:00:00Z")
        val m = mergeBackfill(listOf(queuedLive, b, c), listOf(a, b, c, d))!!
        assertEquals(listOf(a, b, c, d), m.rows)
        assertEquals(0, m.keptBefore)
    }

    /** Two identical rows in a row are not doubled, wherever the window starts. */
    @Test
    fun identicalRowsAreNotDoubled() {
        val x = row("result", "done", "2026-10-03T08:00:05Z")
        assertEquals(listOf(a, x, x, d), mergeBackfill(listOf(a, x, x), listOf(x, x, d))!!.rows)
        assertEquals(listOf(a, x, x, d), mergeBackfill(listOf(a, x, x), listOf(x, d))!!.rows)
    }

    /** A screen capture is the phone's own row: it stays where its time puts it, in any time zone. */
    @Test
    fun capturesStayInTheirPlace() {
        val shot = row("image", "Screen", "2026-10-03T10:00:30+02:00", imageRef = "capture:1") // 08:00:30Z
        val m = mergeBackfill(listOf(a, b, c, shot), listOf(c, d, e))!!
        assertEquals(listOf(a, b, c, shot, d, e), m.rows)
    }
}
