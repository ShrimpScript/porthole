package dev.shrimpscript.porthole

import dev.shrimpscript.porthole.net.Row
import dev.shrimpscript.porthole.net.TuiStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotifyPolicyTest {
    private fun turn(text: String, ts: String) =
        Row(kind = "turn", glyph = "*", text = text, metric = "", detail = "", truncated = false, ts = ts)

    private val first = turn("Worked for 1.8s", "2026-09-11T11:29:00Z")
    private val second = turn("Worked for 1.8s", "2026-09-11T12:40:00Z")

    @Test fun `a new turn while backgrounded notifies with its duration`() {
        val e = NotifyPolicy.turnFinished(first, second, primed = true, visible = false, enabled = true, session = "shop-api")
        assertEquals(NotifyPolicy.Event("shop-api", "Done · worked for 1.8s"), e)
    }

    @Test fun `before the backfill nothing is news`() {
        assertNull(NotifyPolicy.turnFinished(null, first, primed = false, visible = false, enabled = true, session = "s"))
    }

    @Test fun `the first turn of a fresh session notifies once primed`() {
        val e = NotifyPolicy.turnFinished(null, first, primed = true, visible = false, enabled = true, session = "new")
        assertEquals(NotifyPolicy.Event("new", "Done · worked for 1.8s"), e)
    }

    @Test fun `the same turn re-emitted does not repeat`() {
        assertNull(NotifyPolicy.turnFinished(first, first, primed = true, visible = false, enabled = true, session = "s"))
    }

    @Test fun `on screen, off, or detached stays quiet`() {
        assertNull(NotifyPolicy.turnFinished(first, second, primed = true, visible = true, enabled = true, session = "s"))
        assertNull(NotifyPolicy.turnFinished(first, second, primed = true, visible = false, enabled = false, session = "s"))
        assertNull(NotifyPolicy.turnFinished(first, second, primed = true, visible = false, enabled = true, session = ""))
    }

    private fun status(limit: String, at: String = "") =
        TuiStatus(working = false, text = "", elapsed = "", tokens = "", permissionMode = "", interruptible = false,
            limitText = limit, limitResumeAt = at)

    @Test fun `a limit that just hit notifies once, with the resume time when known`() {
        val e = NotifyPolicy.limitHit(false, status("You've hit your limit", "3:45pm"), visible = false, session = "s")
        assertEquals(NotifyPolicy.Event("s", "Usage limit reached · continuing at 3:45pm"), e)
        assertEquals("Usage limit reached", NotifyPolicy.limitHit(false, status("limit"), false, "s")!!.text)
        assertNull(NotifyPolicy.limitHit(true, status("limit"), visible = false, session = "s"))
    }

    @Test fun `no limit, on screen, or a null status is quiet`() {
        assertNull(NotifyPolicy.limitHit(false, status(""), visible = false, session = "s"))
        assertNull(NotifyPolicy.limitHit(false, status("limit"), visible = true, session = "s"))
        assertNull(NotifyPolicy.limitHit(false, null, visible = false, session = "s"))
    }

    @Test fun `an outage is reported in the unit a person would use`() {
        assertEquals("1 minute", humanGap(90_000))
        assertEquals("12 minutes", humanGap(12 * 60_000L))
        assertEquals("1 hour", humanGap(61 * 60_000L))
        assertEquals("5 hours", humanGap(5 * 60 * 60_000L))
        assertEquals("2 days", humanGap(50 * 60 * 60_000L))
    }
}
