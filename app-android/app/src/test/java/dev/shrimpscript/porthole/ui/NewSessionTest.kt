package dev.shrimpscript.porthole.ui

import dev.shrimpscript.porthole.net.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class NewSessionTest {
    private fun s(id: String, cwd: String, at: String, live: Boolean = false, machine: String = "", down: Boolean = false) =
        SessionInfo(id = id, title = id, cwd = cwd, branch = "", lastActive = at, live = live, machineId = machine, machine = machine, machineDown = down)

    @Test fun `one row per folder and computer, newest first, with what runs there`() {
        val list = projectsOf(listOf(
            s("a1", "/home/dev/app", "2026-09-26T10:00:00Z", live = true),
            s("a2", "/home/dev/app", "2026-09-26T12:00:00Z", live = true),
            s("b", "/home/dev/site", "2026-09-26T11:00:00-07:00"), // 18:00Z: the newest
            s("c", "/Users/dev/app", "2026-09-25T09:00:00Z", machine = "mac"),
            s("d", "/srv/gone", "2026-09-27T00:00:00Z", machine = "far", down = true),
            s("e", "", "2026-09-27T00:00:00Z"),
        ))
        assertEquals(listOf("/home/dev/site", "/home/dev/app", "/Users/dev/app"), list.map { it.cwd })
        assertEquals(listOf(0, 2, 0), list.map { it.running })
        assertEquals(listOf("site", "app", "app"), list.map { it.name })
        assertEquals("mac", list[2].machineId)
    }

    @Test fun `home is shortened on Linux and on a Mac, and nothing else is`() {
        assertEquals("~/code/app", shortPath("/home/dev/code/app"))
        assertEquals("~/code/app", shortPath("/Users/dev/code/app"))
        assertEquals("~", shortPath("/Users/dev"))
        assertEquals("/srv/app", shortPath("/srv/app"))
        assertEquals("/home2/dev/app", shortPath("/home2/dev/app"))
    }
}
