package dev.shrimpscript.porthole.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionInfoTest {
    private fun row(asking: String = "", waiting: Boolean = false, waitingFor: String = "", doing: String = "") =
        SessionInfo("s", "t", "/srv", "main", "", live = true, working = true, asking = asking, waiting = waiting, waitingFor = waitingFor, doing = doing)

    @Test
    fun aQuestionOrAWaitIsNeedsYou() {
        assertTrue(row(asking = "Which name?").needsYou)
        assertTrue(row(waiting = true, waitingFor = "permission prompt").needsYou)
        assertFalse(row(doing = "Bash: npm test").needsYou)
    }

    @Test
    fun aPermissionPromptNamesTheToolItHolds() {
        assertEquals("Bash: git push", row(waiting = true, waitingFor = "permission prompt", doing = "Bash: git push").waitingWhat)
        // an older CLI gives no reason: the held tool still says what it is
        assertEquals("Bash: git push", row(waiting = true, doing = "Bash: git push").waitingWhat)
        assertEquals("permission prompt", row(waiting = true, waitingFor = "permission prompt").waitingWhat)
    }

    @Test
    fun anyOtherWaitSaysClaudeCodesReason() {
        // the last tool call is not what an MCP input request or a sandbox request is about
        assertEquals("input needed", row(waiting = true, waitingFor = "input needed", doing = "Bash: ls").waitingWhat)
        assertEquals("sandbox request", row(waiting = true, waitingFor = "sandbox request").waitingWhat)
    }
}
