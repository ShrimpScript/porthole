package dev.shrimpscript.porthole

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChipPolicyTest {
    @Test fun `a chip for a session the list says is idle is stale`() {
        assertEquals(setOf("b"), ChipPolicy.stale(setOf("a", "b"), setOf("a", "c")))
        assertEquals(setOf("a", "b"), ChipPolicy.stale(setOf("a", "b"), emptySet()))
    }

    @Test fun `a fresh Done is never cancelled by the working-off that follows it`() {
        assertFalse(ChipPolicy.mayCancel(doneAtMs = 10_000, nowMs = 13_000))
        assertTrue(ChipPolicy.mayCancel(doneAtMs = 10_000, nowMs = 20_000))
        assertTrue(ChipPolicy.mayCancel(doneAtMs = null, nowMs = 20_000))
    }
}
