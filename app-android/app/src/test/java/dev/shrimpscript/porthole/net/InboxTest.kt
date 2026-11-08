package dev.shrimpscript.porthole.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class InboxTest {
    private val frame = """
        {"v":1,"type":"inbox","configured":true,"projects":[
          {"name":"harbor-api","dir":"/srv/harbor-api","live":true,"items":[
            {"id":"a1","file":".claude/progress.md","line":14,"text":"Which region: eu | us","options":["eu","us"],"default":"eu","since":"2026-03-02T00:00:00Z"},
            {"id":"a2","file":"ROADMAP.md","line":3,"text":"Name the release","since":"2026-03-01T00:00:00Z"}]},
          {"name":"empty","dir":"/srv/empty","live":false,"items":[]}
        ]}
    """.trimIndent()

    @Test
    fun parsesTheDaemonsFrame() {
        val inbox = Inbox.parse(JSONObject(frame))
        assertTrue(inbox.configured)
        assertEquals(listOf("harbor-api"), inbox.projects.map { it.name })
        val p = inbox.projects[0]
        assertTrue(p.live)
        assertEquals(InboxItem("a1", ".claude/progress.md", 14, "Which region: eu | us", listOf("eu", "us"), "eu", "2026-03-02T00:00:00Z"), p.items[0])
        assertEquals(emptyList<String>(), p.items[1].options)
        assertEquals(2, inbox.count)
    }

    @Test
    fun mergesComputers() {
        assertNull(Inbox.merge(listOf(Triple("m1", "desk", null))))
        val one = Inbox.parse(JSONObject(frame))
        val single = Inbox.merge(listOf(Triple("m1", "desk", one)))!!
        assertEquals("m1", single.projects[0].machineId)
        assertEquals("", single.projects[0].machine) // one computer: no name on every project
        val two = Inbox.merge(listOf(Triple("m1", "desk", one), Triple("m2", "laptop", Inbox(false, emptyList())), Triple("m3", "server", null)))!!
        assertTrue(two.configured)
        assertEquals("desk", two.projects[0].machine)
        assertEquals(2, two.count)
    }

    @Test
    fun parsesAnAnswer() {
        val a = InboxAnswered.parse(JSONObject("""{"type":"inbox.answered","item":"a1","decisions":"DECISIONS.md","session":"harbor-api"}"""))
        assertEquals(InboxAnswered("a1", "DECISIONS.md", "harbor-api", ""), a)
    }
}
