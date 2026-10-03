package dev.shrimpscript.porthole.ui

import dev.shrimpscript.porthole.net.AgentInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class OrderAgentsTest {
    private fun a(id: String, parent: String = "", state: String = "done", started: Long = 0) =
        AgentInfo(id, "", "general-purpose", id, "", true, 1, parent, state, started, started, 0, "")

    @Test
    fun runningFirstThenTheTreeUnderEach() {
        val got = orderAgents(listOf(a("old", started = 1), a("run", state = "running", started = 2), a("child", parent = "run", started = 3)))
        assertEquals(listOf("run" to 0, "child" to 1, "old" to 0), got.map { it.first.id to it.second })
    }

    @Test
    fun noAgentIsLostToAMissingParentALoopOrItself() {
        val got = orderAgents(listOf(a("orphan", parent = "gone"), a("x", parent = "y"), a("y", parent = "x"), a("me", parent = "me")))
        assertEquals(setOf("orphan", "x", "y", "me"), got.map { it.first.id }.toSet())
        assertEquals(4, got.size)
        assertEquals(0, got.first { it.first.id == "orphan" }.second)   // a missing parent: top level, not indented
    }
}
