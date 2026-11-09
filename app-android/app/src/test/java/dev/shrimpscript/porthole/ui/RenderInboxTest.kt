package dev.shrimpscript.porthole.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.shrimpscript.porthole.net.Inbox
import dev.shrimpscript.porthole.net.InboxAnswered
import dev.shrimpscript.porthole.net.InboxItem
import dev.shrimpscript.porthole.net.InboxProject
import dev.shrimpscript.porthole.net.SessionInfo
import dev.shrimpscript.porthole.ui.theme.PortholeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/** The inbox drawn on the JVM with made-up projects, saved beside the other screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderInboxTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(view.width.takeIf { it > 0 } ?: 1080, view.height.takeIf { it > 0 } ?: 2340, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val dir = File("build/reports/screens").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Moves the paused clock on and lets the views lay out, which a paused clock alone does not. */
    private fun step(ms: Long) {
        rule.mainClock.advanceTimeBy(ms)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        rule.mainClock.advanceTimeByFrame()
    }

    private fun ago(hours: Long) = Instant.now().minusSeconds(hours * 3600).toString()

    private val sample = Inbox(
        configured = true,
        projects = listOf(
            InboxProject(
                "harbor-api", "/srv/harbor-api", live = true,
                items = listOf(
                    InboxItem("a1", ".claude/progress.md", 14, "Which region for the database: Frankfurt | Virginia | Singapore",
                        options = listOf("Frankfurt", "Virginia", "Singapore"), suggested = "Frankfurt", since = ago(3),
                        question = "Which region for the database"),
                    InboxItem("a2", ".claude/progress.md", 15, "Approve the rate limits in docs/LIMITS.md before they ship to staging", since = ago(26)),
                ),
            ),
            InboxProject(
                "tidepool", "/srv/tidepool", live = false,
                items = listOf(
                    InboxItem("b1", "ROADMAP.md", 88, "Keep the free tier at 3 boards, or raise it to 5? [keep it at 3]",
                        suggested = "keep it at 3", since = ago(72), question = "Keep the free tier at 3 boards, or raise it to 5?"),
                    InboxItem("b2", ".agent/HANDOVER.md", 40, "Publish client 2.1", since = ago(24 * 9)),
                ),
            ),
            InboxProject(
                "lantern-site", "/srv/lantern-site", live = false,
                items = listOf(
                    InboxItem("c1", "docs/HANDOVER.md", 7, "The hero video autoplays muted on phones. Keep it, or show the still and a play button? Either way the page weight stays under 2 MB.",
                        since = ago(24 * 15)),
                ),
            ),
        ),
    )

    @Test
    fun inboxGroupedByProject() {
        val sent = mutableListOf<Pair<String, String>>()
        rule.setContent {
            PortholeTheme {
                var pending by remember { mutableStateOf<PendingAnswer?>(null) }
                InboxScreen(
                    inbox = sample, onBack = {}, onRefresh = {},
                    onAnswer = { _, item, text -> sent += item.id to text },
                    onDismiss = { _, _ -> },
                    answered = InboxAnswered("z9", "DECISIONS.md", "harbor-api", ""),
                    pending = pending, onPending = { pending = it },
                )
            }
        }
        rule.onNodeWithText("Inbox").assertIsDisplayed()
        rule.onNodeWithText("5 waiting on you · 3 projects").assertIsDisplayed()
        rule.onNodeWithText("harbor-api").assertIsDisplayed()
        rule.onNodeWithText("Which region for the database").assertIsDisplayed()
        rule.onNodeWithText(" · session running").assertIsDisplayed()
        rule.onNodeWithText("Recorded in DECISIONS.md, and told harbor-api.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Answer: Frankfurt, suggested").assertIsDisplayed()
        rule.onNodeWithText("Use the suggestion").assertIsDisplayed()
        rule.onNodeWithText("3h · .claude/progress.md:14").assertIsDisplayed()
        save("inbox")

        // A choice is held for Undo, then sent.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Virginia").performClick()
        step(500)
        rule.onNodeWithText("Answering: Virginia").assertIsDisplayed()
        rule.onNodeWithText("Undo").assertIsDisplayed()
        step(1500)
        save("inbox-answering")
        assertEquals(emptyList<Pair<String, String>>(), sent)
        step(INBOX_UNDO_MS.toLong())
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(listOf("a1" to "Virginia"), sent)
    }

    @Test
    fun undoKeepsTheAnswerBack() {
        val sent = mutableListOf<String>()
        var last: PendingAnswer? = PendingAnswer("", "", "x", "x")
        rule.setContent {
            PortholeTheme {
                var pending by remember { mutableStateOf<PendingAnswer?>(null) }
                InboxScreen(
                    inbox = sample, onBack = {}, onRefresh = {},
                    onAnswer = { _, _, text -> sent += text }, onDismiss = { _, _ -> },
                    pending = pending, onPending = { pending = it; last = it },
                )
            }
        }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Singapore").performClick()
        step(500)
        rule.onNodeWithText("Undo").performClick()
        step(INBOX_UNDO_MS * 2L)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertNull(last)
        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun typedAnswer() {
        val sent = mutableListOf<Pair<String, String>>()
        rule.setContent {
            PortholeTheme {
                var pending by remember { mutableStateOf<PendingAnswer?>(null) }
                InboxScreen(
                    inbox = sample.copy(projects = sample.projects.take(1)), onBack = {}, onRefresh = {},
                    onAnswer = { _, item, text -> sent += item.id to text }, onDismiss = { _, _ -> },
                    pending = pending, onPending = { pending = it },
                )
            }
        }
        rule.onNodeWithText("Write an answer").performClick()
        rule.onNodeWithContentDescription("Your answer").performTextInput("Approved, but halve the burst limit")
        save("inbox-typing")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Answer").performClick()
        step(INBOX_UNDO_MS + 500L)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(listOf("a2" to "Approved, but halve the burst limit"), sent)
    }

    @Test
    fun aSecondTapSendsTheFirstAndCardsScopeToTheirProject() {
        val sent = mutableListOf<Triple<String, String, String>>()
        // The same question id in two projects: two cards, answered apart.
        val twin = sample.copy(projects = listOf(
            sample.projects[0],
            sample.projects[0].copy(name = "harbor-api-copy", dir = "/srv/harbor-api-copy", live = false, items = sample.projects[0].items.take(1)),
        ))
        rule.setContent {
            PortholeTheme {
                var pending by remember { mutableStateOf<PendingAnswer?>(null) }
                InboxScreen(
                    inbox = twin, onBack = {}, onRefresh = {},
                    onAnswer = { p, item, text -> sent += Triple(p.dir, item.id, text) }, onDismiss = { _, _ -> },
                    pending = pending, onPending = { pending = it },
                    sending = mapOf(inboxKey("", "/srv/harbor-api", "a2") to "Approved"),
                )
            }
        }
        rule.onNodeWithText("Sending: Approved").assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        rule.onAllNodesWithText("Virginia")[0].performClick()
        step(500)
        rule.onAllNodesWithText("Answering: Virginia").assertCountEquals(1)
        rule.onNodeWithText("Singapore").performClick() // the first card shows its pending answer now
        step(500)
        assertEquals(listOf(Triple("/srv/harbor-api", "a1", "Virginia")), sent)
        step(INBOX_UNDO_MS.toLong())
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(Triple("/srv/harbor-api-copy", "a1", "Singapore"), sent.last())
    }

    @Test
    fun aRefusedSendIsSaid() {
        rule.setContent {
            PortholeTheme {
                InboxScreen(inbox = sample, onBack = {}, onRefresh = {}, onAnswer = { _, _, _ -> }, onDismiss = { _, _ -> },
                    failed = "Not sent: the computer is not connected. Your answer \u201cVirginia\u201d was not recorded.")
            }
        }
        rule.onNodeWithText("Not sent: the computer is not connected. Your answer \u201cVirginia\u201d was not recorded.").assertIsDisplayed()
    }

    @Test
    fun inboxNotSetUpAndEmpty() {
        var inbox by mutableStateOf(Inbox(configured = false, projects = emptyList()))
        rule.setContent {
            PortholeTheme { InboxScreen(inbox = inbox, onBack = {}, onRefresh = {}, onAnswer = { _, _, _ -> }, onDismiss = { _, _ -> }) }
        }
        rule.onNodeWithText("Which folders hold your projects?").assertIsDisplayed()
        rule.onNodeWithText("portholed inbox add ~/code").assertIsDisplayed()
        save("inbox-setup")
        inbox = Inbox(configured = true, projects = emptyList())
        rule.onNodeWithText("Nothing is waiting on you.").assertIsDisplayed()
    }

    @Test
    fun sessionsHeaderCountsTheInbox() {
        rule.setContent {
            PortholeTheme {
                SessionsScreen(
                    machine = "workstation", ring = RingState.Live,
                    sessions = listOf(SessionInfo("l1", "Migrate to Postgres 16", "/srv/api", "main", "2026-09-14T09:30:00Z", live = true, tmuxName = "api", pane = "%12")),
                    onSession = {}, onSettings = {}, onRefresh = {},
                    onInbox = {}, inboxCount = 5,
                )
            }
        }
        rule.onNodeWithContentDescription("Inbox, 5 waiting on you").assertIsDisplayed()
        save("sessions-inbox")
    }
}
