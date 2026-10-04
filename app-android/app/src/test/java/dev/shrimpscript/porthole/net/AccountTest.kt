package dev.shrimpscript.porthole.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountTest {
    private val link = "https://claude.com/cai/oauth/authorize?code=true&client_id=c&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback&state=Xy9_kQ2-abc"

    /** The page's code ends with the link's own state: that, and only that, is picked up from the clipboard. */
    @Test
    fun theCodeIsRecognisedByItsState() {
        val w = SignIn.Waiting(link)
        assertEquals("Xy9_kQ2-abc", w.state)
        assertTrue(w.isCode("Zq81mPq0vV3xLr9tWc2#Xy9_kQ2-abc"))
        assertTrue("copied with a stray newline", w.isCode("  Zq81mPq0vV3xLr9tWc2#Xy9_kQ2-abc\n"))
        assertFalse("another sign-in's code", w.isCode("Zq81mPq0vV3xLr9tWc2#other-state"))
        assertFalse("the state alone", w.isCode("#Xy9_kQ2-abc"))
        assertFalse("a sentence that happens to end with it", w.isCode("my code is Zq81mPq0vV3x #Xy9_kQ2-abc"))
        assertFalse("a link with no state recognises nothing", SignIn.Waiting("https://claude.com/cai/oauth/authorize?code=true").isCode("abc#"))
    }

    @Test
    fun plansAreNamedAsClaudeNamesThem() {
        assertEquals("Max", ClaudeAccount(true, "a@b.c", "max").planName)
        assertEquals("Pro", ClaudeAccount(true, "a@b.c", "pro").planName)
        assertEquals("API billing", ClaudeAccount(true, "a@b.c", "", "console").planName)
        assertEquals("Ultra", ClaudeAccount(true, "a@b.c", "ultra").planName)
    }

    /** How a sign-in ended moves only the sign-in it is about. */
    @Test
    fun aResultMovesOnlyItsOwnSignIn() {
        val w = SignIn.Waiting(link, sending = true)
        val acct = ClaudeAccount(true, "other@example.com", "pro")
        assertEquals(SignIn.Done(acct), w.after(ok = true, retry = false, error = "", state = "Xy9_kQ2-abc", account = acct))
        assertEquals("another sign-in's result, resent on reconnect", w, w.after(ok = true, retry = false, error = "", state = "old-state", account = acct))
        assertEquals(w.copy(sending = false, error = "that is not a sign-in code"), w.after(false, true, "that is not a sign-in code", "Xy9_kQ2-abc", null))
        assertEquals(SignIn.Failed("no sign-in is waiting for a code - start again"), w.after(false, false, "no sign-in is waiting for a code - start again", "", null))
        assertEquals("starting: a stale result with a state is not this one", SignIn.Starting, SignIn.Starting.after(true, false, "", "old-state", acct))
        assertEquals(SignIn.Failed("Claude Code was not found on the computer"), SignIn.Starting.after(false, false, "Claude Code was not found on the computer", "", null))
        assertEquals("nothing under way: nothing moves", SignIn.Idle, SignIn.Idle.after(true, false, "", "Xy9_kQ2-abc", acct))
    }
}
