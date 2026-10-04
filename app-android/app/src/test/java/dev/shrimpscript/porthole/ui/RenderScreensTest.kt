package dev.shrimpscript.porthole.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.shrimpscript.porthole.net.ChangedFile
import dev.shrimpscript.porthole.net.ChangesState
import dev.shrimpscript.porthole.net.Question
import dev.shrimpscript.porthole.net.QuestionOption
import dev.shrimpscript.porthole.net.Row
import dev.shrimpscript.porthole.net.ScreenOption
import dev.shrimpscript.porthole.net.ScreenQuestion
import dev.shrimpscript.porthole.net.SessionInfo
import dev.shrimpscript.porthole.net.SessionState
import dev.shrimpscript.porthole.net.TuiStatus
import dev.shrimpscript.porthole.net.Usage
import dev.shrimpscript.porthole.terminal.TerminalEmulator
import dev.shrimpscript.porthole.ui.theme.PortholeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Screens drawn on the JVM and saved as PNGs under build/reports/screens. Each test also
 * asserts the words that must be on screen, so a render that silently drops a section
 * fails rather than just looking different.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderScreensTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /**
     * The activity's view tree drawn into a bitmap by hand. Compose's own capture waits
     * for a window draw that Robolectric never schedules; View.draw on the decor view
     * paints the same tree synchronously.
     */
    private fun save(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val w = view.width.takeIf { it > 0 } ?: 1080
        val h = view.height.takeIf { it > 0 } ?: 2340
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val dir = File("build/reports/screens").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun sessionsListWithNeedsYouLiveAndRecent() {
        val now = System.currentTimeMillis()
        val sessions = listOf(
            SessionInfo("q1", "Add dark mode to settings", "/srv/app", "main", "2026-09-14T10:00:00Z", live = true, working = true,
                model = "claude-fable-5-1", workingSince = now - 65_000, doing = "AskUserQuestion", asking = "Which colour?", tmuxName = "0", pane = "%0"),
            // Stopped on a permission prompt: no question in the transcript, only the CLI's "waiting".
            SessionInfo("p1", "Ship the release branch", "/srv/shop", "release/2.4", "2026-09-14T09:59:00Z", live = true, working = true,
                model = "claude-fable-5-1", workingSince = now - 20 * 60_000, doing = "Bash: git push --force origin main", waiting = true, waitingFor = "permission prompt", tmuxName = "2", pane = "%2"),
            SessionInfo("w1", "Fix flaky login test", "/srv/api", "HEAD", "2026-09-14T09:58:00Z", live = true, working = true,
                model = "claude-fable-5-1", workingSince = now - 12_000, doing = "Bash: ./gradlew test", tmuxName = "1", pane = "%1"),
            SessionInfo("l1", "Migrate to Postgres 16", "/srv/api", "main", "2026-09-14T09:30:00Z", live = true, working = false,
                model = "claude-fable-5-1", tmuxName = "api", pane = "%12"),
            SessionInfo("r1", "Write the release notes", "/srv/docs", "master", "2026-09-12T18:00:00Z", live = false, tmux = false, model = "claude-opus-5"),
        )
        rule.setContent {
            PortholeTheme {
                SessionsScreen(machine = "workstation", ring = RingState.Live, sessions = sessions, onSession = {}, onSettings = {}, onRefresh = {})
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Needs you").assertIsDisplayed()
        // The state on its own line, the details beneath it.
        rule.onNodeWithText("asking you: Which colour?").assertIsDisplayed()
        rule.onNodeWithText("main · tmux 0 · Fable 5.1").assertIsDisplayed()
        // The one held on a permission prompt is under Needs you too, above Live, saying what it wants to run.
        val held = rule.onNodeWithText("waiting for you: Bash: git push --force origin main").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText("release/2.4 · tmux 2 · Fable 5.1").assertIsDisplayed()
        val liveLabel = rule.onNodeWithText("Live").fetchSemanticsNode().boundsInRoot
        assertTrue("the waiting session sits under Needs you, above Live", held.bottom <= liveLabel.top)
        rule.onNodeWithText("Recent").assertIsDisplayed()
        save("sessions")
    }

    private fun row(kind: String, text: String, ts: String, toolId: String = "", questions: List<Question> = emptyList()) =
        Row(kind = kind, glyph = "", text = text, metric = "", detail = "", truncated = false, ts = ts, toolId = toolId, questions = questions)

    private fun state(working: Boolean) = SessionState(
        model = "claude-fable-5-1", permissionMode = "bypassPermissions", working = working, workingSince = java.time.Instant.now().minusSeconds(49).toString(),
        pendingTool = if (working) "AskUserQuestion" else "", turns = 3, prompts = 3, replies = 3, usage = Usage(1200, 300, 8000, 0),
        lastContext = 9500, tools = mapOf("Bash" to 4), firstTs = "2026-09-14T09:40:00Z", lastTs = "2026-09-14T10:00:00Z",
    )

    @Test
    fun sessionScreenWithALiveQuestionAndHistory() {
        val rows = listOf(
            row("user", "Make a poster for the launch. Ask me what you need to know first.", "2026-09-14T09:58:00Z"),
            row("question", "Which size?", "2026-09-14T09:58:10Z", toolId = "t1",
                questions = listOf(Question("Size", "Which size?", false, listOf(QuestionOption("Small", "fits a phone"), QuestionOption("Large", "fits a desk"))))),
            row("result", "Answered: Large", "2026-09-14T09:58:20Z", toolId = "t1"),
            row("assistant", "Large it is. One more thing before I go on.", "2026-09-14T09:58:22Z"),
            row("turn", "Worked for 3.5s", "2026-09-14T09:58:23Z"),
            row("user", "Go on.", "2026-09-14T09:59:00Z"),
        )
        val screenQ = ScreenQuestion(
            header = "Colour", text = "Which colour?", multi = false,
            options = listOf(ScreenOption(1, "Red", "warm", false), ScreenOption(2, "Blue", "cool", false)),
            typed = 3, review = false, index = 1, total = 1,
        )
        rule.setContent {
            PortholeTheme {
                SessionScreen(
                    title = "Launch poster", branch = "main · tmux work", ring = RingState.Live, live = true,
                    rows = rows, backfillCount = rows.size, loaded = true, canSend = true, onSend = {}, onBack = {},
                    view = SessionView.Feed, onViewChange = {}, terminal = TerminalEmulator(80, 24), terminalRevision = 0,
                    terminalOpen = false, onOpenTerminal = {}, onTerminalKeys = {}, fontSp = 13f, onFontSp = {}, fit = true, onFit = {},
                    notice = null, onDismissNotice = {}, state = state(working = true),
                    status = TuiStatus(working = true, text = "", elapsed = "", tokens = "", permissionMode = "bypass permissions on", interruptible = false, question = screenQ),
                    caps = listOf("changes", "preview"), quickReplies = listOf("Continue", "Yes", "No", "Looks good"),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Claude is asking you").assertIsDisplayed()
        rule.onNodeWithText("Which colour?").assertIsDisplayed()
        rule.onNodeWithText("1. Red").assertIsDisplayed()
        rule.onNodeWithText("Type something").assertIsDisplayed()
        rule.onNodeWithText("Answered: Large").assertIsDisplayed()
        save("session-question")
    }

    /** The question the ads show: a real decision mid-task, answered with a tap. */
    @Test
    fun sessionScreenAsksWhichTableName() {
        val rows = listOf(
            row("user", "Move orders older than two years out of the orders table. Ask me anything you need first.", "2026-09-14T09:52:00Z"),
            row("assistant", "I'll copy them to a new table, check the counts match, then delete them from orders.", "2026-09-14T09:52:40Z"),
            row("turn", "Worked for 41s", "2026-09-14T09:53:21Z"),
            row("assistant", "The copy script is ready. One question before I run it.", "2026-09-14T09:59:10Z"),
        )
        val screenQ = ScreenQuestion(
            header = "Table", text = "Which name for the new table?", multi = false,
            options = listOf(ScreenOption(1, "orders_archive", "like the other archive tables", false), ScreenOption(2, "orders_2024", "one table per year", false)),
            typed = 3, review = false, index = 1, total = 1,
        )
        rule.setContent {
            PortholeTheme {
                SessionScreen(
                    title = "Orders cleanup", branch = "main · tmux work", ring = RingState.Live, live = true,
                    rows = rows, backfillCount = rows.size, loaded = true, canSend = true, onSend = {}, onBack = {},
                    view = SessionView.Feed, onViewChange = {}, terminal = TerminalEmulator(80, 24), terminalRevision = 0,
                    terminalOpen = false, onOpenTerminal = {}, onTerminalKeys = {}, fontSp = 13f, onFontSp = {}, fit = true, onFit = {},
                    notice = null, onDismissNotice = {}, state = state(working = true),
                    status = TuiStatus(working = true, text = "", elapsed = "", tokens = "", permissionMode = "bypass permissions on", interruptible = false, question = screenQ),
                    caps = listOf("changes", "preview"), quickReplies = listOf("Continue", "Yes", "No", "Looks good"),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Which name for the new table?").assertIsDisplayed()
        rule.onNodeWithText("1. orders_archive").assertIsDisplayed()
        save("table-question")
    }

    @Test
    fun sessionScreenIdleShowsQuickReplies() {
        val rows = listOf(
            row("user", "Rename the helper and run the tests.", "2026-09-14T09:58:00Z"),
            row("assistant", "Done: renamed `fetchAll` to `loadAll`, 42 tests pass.", "2026-09-14T09:58:40Z"),
            row("turn", "Worked for 38s", "2026-09-14T09:58:41Z"),
        )
        rule.setContent {
            PortholeTheme {
                SessionScreen(
                    title = "Add dark mode to settings", branch = "main · tmux 0", ring = RingState.Live, live = true,
                    rows = rows, backfillCount = rows.size, loaded = true, canSend = true, onSend = {}, onBack = {},
                    view = SessionView.Feed, onViewChange = {}, terminal = TerminalEmulator(80, 24), terminalRevision = 0,
                    terminalOpen = false, onOpenTerminal = {}, onTerminalKeys = {}, fontSp = 13f, onFontSp = {}, fit = true, onFit = {},
                    notice = null, onDismissNotice = {}, state = state(working = false),
                    status = TuiStatus(working = false, text = "", elapsed = "", tokens = "", permissionMode = "bypass permissions on", interruptible = false),
                    caps = listOf("changes"), quickReplies = listOf("Continue", "Yes", "No", "Looks good"),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Continue").assertIsDisplayed()
        rule.onNodeWithText("Looks good").assertIsDisplayed()
        save("session-idle")
    }

    /**
     * Typing folds the photo and command buttons into one and puts the quick replies away,
     * so the field gets the width and the feed keeps the height.
     */
    @Test
    fun theMessageBoxIsOneBox() {
        val rows = listOf(
            row("user", "Rename the helper and run the tests.", "2026-09-14T09:58:00Z"),
            row("assistant", "Done: renamed `fetchAll` to `loadAll`, 42 tests pass.", "2026-09-14T09:58:40Z"),
            row("turn", "Worked for 38s", "2026-09-14T09:58:41Z"),
        )
        rule.setContent {
            PortholeTheme {
                SessionScreen(
                    title = "Add dark mode to settings", branch = "main · tmux 0", ring = RingState.Live, live = true,
                    rows = rows, backfillCount = rows.size, loaded = true, canSend = true, onSend = {}, onBack = {},
                    view = SessionView.Feed, onViewChange = {}, terminal = TerminalEmulator(80, 24), terminalRevision = 0,
                    terminalOpen = false, onOpenTerminal = {}, onTerminalKeys = {}, fontSp = 13f, onFontSp = {}, fit = true, onFit = {},
                    notice = null, onDismissNotice = {}, state = state(working = false),
                    status = TuiStatus(working = false, text = "", elapsed = "", tokens = "", permissionMode = "bypass permissions on", interruptible = false),
                    caps = listOf("changes", "upload", "attach"), quickReplies = listOf("Continue", "Yes", "No", "Looks good"),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Attach a photo or a file").assertIsDisplayed()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput(
            "Good. Now make the toggle remember its state across restarts, and add a test for it"
        )
        rule.waitForIdle()
        // One box: the words on top, attach and the model beneath them, whatever is typed.
        rule.onNodeWithContentDescription("Attach a photo or a file").assertIsDisplayed()
        rule.onNodeWithContentDescription("Model and effort: Fable 5.1").assertIsDisplayed()
        rule.onAllNodesWithText("Looks good").assertCountEquals(0)
        save("session-typing")
    }

    /**
     * An @ mention: typing it asks the daemon for the session's files, the list shows them
     * by name with the folder beneath, and a tap puts the path in the draft with a space.
     */
    @Test
    fun mentionOffersTheSessionsFiles() {
        val asked = mutableListOf<String>()
        var files by androidx.compose.runtime.mutableStateOf<dev.shrimpscript.porthole.net.FilesState?>(null)
        rule.setContent {
            PortholeTheme {
                SessionScreen(
                    title = "Add dark mode to settings", branch = "main · tmux 0", ring = RingState.Live, live = true,
                    rows = listOf(row("user", "Where is the settings screen?", "2026-09-28T09:58:00Z")),
                    backfillCount = 1, loaded = true, canSend = true, onSend = {}, onBack = {},
                    view = SessionView.Feed, onViewChange = {}, terminal = TerminalEmulator(80, 24), terminalRevision = 0,
                    terminalOpen = false, onOpenTerminal = {}, onTerminalKeys = {}, fontSp = 13f, onFontSp = {}, fit = true, onFit = {},
                    notice = null, onDismissNotice = {}, state = state(working = false),
                    status = TuiStatus(working = false, text = "", elapsed = "", tokens = "", permissionMode = "bypass permissions on", interruptible = false),
                    caps = listOf("files"),
                    files = files,
                    onFiles = { q ->
                        asked += q
                        files = dev.shrimpscript.porthole.net.FilesState("s", q, listOf(
                            "app/src/main/java/dev/app/ui/SettingsScreen.kt",
                            "app/src/main/java/dev/app/ui/SettingsViewModel.kt",
                            "docs/settings.md",
                        ))
                    },
                )
            }
        }
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Tidy up @sett")
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        assertEquals("sett", asked.last())
        rule.onNodeWithText("SettingsScreen.kt").assertIsDisplayed()
        rule.onNodeWithText("docs").assertIsDisplayed()
        save("session-mention")
        rule.onNodeWithContentDescription("app/src/main/java/dev/app/ui/SettingsScreen.kt").performClick()
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction())
            .assert(androidx.compose.ui.test.hasText("Tidy up @app/src/main/java/dev/app/ui/SettingsScreen.kt "))
        rule.onAllNodesWithText("SettingsViewModel.kt").assertCountEquals(0) // the mention is finished
        // The cursor went to the end: the next letters follow the path, not land inside it.
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("now")
        rule.onNode(androidx.compose.ui.test.hasSetTextAction())
            .assert(androidx.compose.ui.test.hasText("Tidy up @app/src/main/java/dev/app/ui/SettingsScreen.kt now"))
    }

    /** A daemon that takes files offers a choice under the attach button: a photo or any file. */
    @Test
    fun attachOffersAPhotoOrAFile() {
        rule.setContent {
            PortholeTheme {
                SessionScreen(
                    title = "Add dark mode to settings", branch = "main · tmux 0", ring = RingState.Live, live = true,
                    rows = listOf(row("user", "Here is the crash report.", "2026-09-28T09:58:00Z")),
                    backfillCount = 1, loaded = true, canSend = true, onSend = {}, onBack = {},
                    view = SessionView.Feed, onViewChange = {}, terminal = TerminalEmulator(80, 24), terminalRevision = 0,
                    terminalOpen = false, onOpenTerminal = {}, onTerminalKeys = {}, fontSp = 13f, onFontSp = {}, fit = true, onFit = {},
                    notice = null, onDismissNotice = {}, state = state(working = false),
                    status = TuiStatus(working = false, text = "", elapsed = "", tokens = "", permissionMode = "bypass permissions on", interruptible = false),
                    caps = listOf("upload", "attach"),
                )
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("Attach a photo").assertCountEquals(0)
        rule.onNodeWithContentDescription("Attach a photo or a file").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Photo").assertIsDisplayed()
        rule.onNodeWithText("File").assertIsDisplayed()
        save("session-attach")
    }

    /** The pencil beside a live session's name opens a field; Rename sends the CLI's own /rename. */
    @Test
    fun renameFromTheDetailsSheet() {
        val sent = mutableListOf<String>()
        rule.setContent {
            PortholeTheme {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.fillMaxSize().background(dev.shrimpscript.porthole.ui.theme.Porthole.colors.surface).padding(20.dp)
                ) {
                    RenameTitle("Fix the flaky login test", canRename = true) { sent += "/rename $it" }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Rename this session").performClick()
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextReplacement("Login   work\n")
        save("session-rename")
        rule.onNodeWithText("Rename").performClick()
        rule.waitForIdle()
        assertEquals(listOf("/rename Login work"), sent)
        rule.onNodeWithText("Fix the flaky login test").assertIsDisplayed() // the list updates it, not the field
    }

    /**
     * An approval as the app shows it, over the session it came from, with a command long
     * enough to wrap: every character of it is on the card, the dangerous end included.
     * The frame is also the one the ads use, so they show the real card, not a drawing.
     */
    @Test
    fun approvalShowsTheWholeCommand() {
        val command = "npm run build && git checkout main && git reset --hard release/2.4 && git push --force origin main"
        val rows = listOf(
            row("user", "Ship the release branch to main.", "2026-09-27T14:12:00Z"),
            row("assistant", "The build passes. I'll move main to the release branch and push it.", "2026-09-27T14:13:10Z"),
        )
        rule.setContent {
            PortholeTheme {
                androidx.compose.foundation.layout.Box {
                    SessionScreen(
                        title = "Release 2.4", branch = "release/2.4 · tmux 1", ring = RingState.Live, live = true,
                        rows = rows, backfillCount = rows.size, loaded = true, canSend = true, onSend = {}, onBack = {},
                        view = SessionView.Feed, onViewChange = {}, terminal = TerminalEmulator(80, 24), terminalRevision = 0,
                        terminalOpen = false, onOpenTerminal = {}, onTerminalKeys = {}, fontSp = 13f, onFontSp = {}, fit = true, onFit = {},
                        notice = null, onDismissNotice = {}, state = state(working = true),
                        status = TuiStatus(working = true, text = "Waiting for approval", elapsed = "", tokens = "", permissionMode = "", interruptible = false),
                        caps = listOf("attach", "files"),
                    )
                    ApprovalOverlay(
                        dev.shrimpscript.porthole.net.Approval("toolu_ad", "s1", "Bash", command, "~/code/shop", expiresInSeconds = 48),
                        onAllow = {}, onDeny = {}, onExpired = {},
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        // Wrapped, not one line running off the card: the end is in view without scrolling.
        val text = rule.onNodeWithText(command, substring = true).fetchSemanticsNode().boundsInRoot
        val screen = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("the command runs off the card: ${text.width} of ${screen.width}", text.right <= screen.right && text.width < screen.width)
        save("approval-whole-command")
        // The countdown as it runs, second by second, for the film that shows this card.
        for (left in 46 downTo 45) {
            rule.mainClock.advanceTimeBy(1000)
            rule.waitForIdle()
            rule.onNodeWithText("Waiting · ${left}s left").assertIsDisplayed()
            save("approval-whole-command-$left")
        }
    }

    private fun agentRow(id: String, text: String, type: String, bg: Boolean, ts: String) = Row(
        kind = "tool", glyph = "▸", text = "Delegated $text", metric = "", detail = "", truncated = false, ts = ts, toolId = id,
        agent = dev.shrimpscript.porthole.net.AgentCall(type, text, "", bg),
    )

    private fun agent(id: String, tool: String, desc: String, state: String, startedAgo: Long, tools: Int, doing: String,
                      depth: Int = 1, parent: String = "", type: String = "general-purpose", model: String = "", bg: Boolean = true) =
        dev.shrimpscript.porthole.net.AgentInfo(
            id, tool, type, desc, model, bg, depth, parent, state,
            startedMs = System.currentTimeMillis() - startedAgo * 1000,
            lastActiveMs = System.currentTimeMillis() - (if (state == "running") 2 else startedAgo / 3) * 1000,
            tools = tools, doing = doing,
        )

    private val agentsFixture = listOf(
        agent("a1", "t1", "Find why the login test flakes", "running", 130, 12, "Bash: npm test -- login"),
        agent("a2", "t2", "Map the config loading", "done", 400, 23, "Read: config.ts", type = "Explore", model = "haiku", bg = false),
        agent("a3", "t9", "Check the retry helper", "running", 40, 4, "Grep: retryWithBackoff", depth = 2, parent = "a1", model = "sonnet"),
    )

    private val agentRows = listOf(
        row("user", "The login test fails about one run in five. Find out why, and check where config loads.", "2026-09-28T09:40:00Z"),
        row("assistant", "I'll look at both at once: one agent on the flaky test, one mapping the config.", "2026-09-28T09:40:20Z"),
        agentRow("t1", "Find why the login test flakes", "general-purpose", true, "2026-09-28T09:40:22Z"),
        agentRow("t2", "Map the config loading", "Explore", false, "2026-09-28T09:40:23Z"),
    )

    @androidx.compose.runtime.Composable
    private fun AgentsSession(working: Boolean, agents: List<dev.shrimpscript.porthole.net.AgentInfo>) {
        SessionScreen(
            title = "Login flake", branch = "main · tmux work", ring = RingState.Live, live = true,
            rows = agentRows, backfillCount = agentRows.size, loaded = true, canSend = true, onSend = {}, onBack = {},
            view = SessionView.Feed, onViewChange = {}, terminal = TerminalEmulator(80, 24), terminalRevision = 0,
            terminalOpen = false, onOpenTerminal = {}, onTerminalKeys = {}, fontSp = 13f, onFontSp = {}, fit = true, onFit = {},
            notice = null, onDismissNotice = {},
            state = state(working = working).copy(pendingTool = if (working) "Agent" else ""),
            status = TuiStatus(working = working, text = if (working) "Waiting on 1 agent…" else "", elapsed = "", tokens = "", permissionMode = "", interruptible = working),
            caps = listOf("attach", "files"), agents = agents,
        )
    }

    /** Work handed to agents: a card per Agent call with its agent's progress, and the strip's count. */
    @Test
    fun agentsAtWorkInTheFeed() {
        rule.setContent { PortholeTheme { AgentsSession(working = true, agents = agentsFixture) } }
        rule.waitForIdle()
        rule.onNodeWithText("Find why the login test flakes").assertIsDisplayed()
        rule.onNodeWithText("12 tools · Bash: npm test -- login", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Done in", substring = true).assertIsDisplayed()
        rule.onNodeWithText("2 agents").assertIsDisplayed()   // the running one and its own agent
        save("agents-feed")
    }

    /** The turn has ended but a background agent works on: a strip says so. */
    @Test
    fun backgroundAgentsOutliveTheTurn() {
        rule.setContent { PortholeTheme { AgentsSession(working = false, agents = agentsFixture) } }
        rule.waitForIdle()
        rule.onNodeWithText("2 agents are working").assertIsDisplayed()
        save("agents-background")
    }

    /** Every agent, the ones at work first, an agent's own agents under it. */
    @Test
    fun agentsSheetListsEveryAgent() {
        rule.setContent {
            PortholeTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(dev.shrimpscript.porthole.ui.theme.Porthole.colors.surface).padding(top = 24.dp)) {
                    AgentsList(agentsFixture, System.currentTimeMillis())
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("2 at work · 3 in this session").assertIsDisplayed()
        rule.onNodeWithText("Check the retry helper").assertIsDisplayed()
        rule.onNodeWithText("Explore · Haiku", substring = true).assertExists()
        save("agents-sheet")
    }

    private fun cmdRow(ts: String, cmd: dev.shrimpscript.porthole.net.CommandInfo, detail: String = "") = Row(
        kind = "command", glyph = "/", text = "/${cmd.name}", metric = "", detail = detail, truncated = false, ts = ts,
        toolId = "cmd:$ts", command = cmd,
    )

    private val contextFixture = dev.shrimpscript.porthole.net.ContextUsage(
        "claude-opus-5-5", 27_100, 1_000_000, listOf(
            dev.shrimpscript.porthole.net.ContextPart("System prompt", 2_400),
            dev.shrimpscript.porthole.net.ContextPart("System tools", 12_200),
            dev.shrimpscript.porthole.net.ContextPart("MCP tools", 137_800, "deferred"),
            dev.shrimpscript.porthole.net.ContextPart("Skills", 10_000),
            dev.shrimpscript.porthole.net.ContextPart("Memory files", 924),
            dev.shrimpscript.porthole.net.ContextPart("Messages", 1_300),
            dev.shrimpscript.porthole.net.ContextPart("Free space", 939_900, "free"),
            dev.shrimpscript.porthole.net.ContextPart("Autocompact buffer", 33_000, "buffer"),
        ),
    )

    @androidx.compose.runtime.Composable
    private fun CommandsSession(rows: List<Row>) {
        SessionScreen(
            title = "Release prep", branch = "main · tmux work", ring = RingState.Live, live = true,
            rows = rows, backfillCount = rows.size, loaded = true, canSend = true, onSend = {}, onBack = {},
            view = SessionView.Feed, onViewChange = {}, terminal = TerminalEmulator(80, 24), terminalRevision = 0,
            terminalOpen = false, onOpenTerminal = {}, onTerminalKeys = {}, fontSp = 13f, onFontSp = {}, fit = true, onFit = {},
            notice = null, onDismissNotice = {}, state = state(working = false).copy(pendingTool = ""),
            status = TuiStatus(working = false, text = "", elapsed = "", tokens = "", permissionMode = "", interruptible = false),
            caps = listOf("attach", "files"),
        )
    }

    /** Commands run at the desk: what each one set, not the CLI's echo. */
    @Test
    fun commandCardsInTheFeed() {
        val rows = listOf(
            cmdRow("2026-10-02T09:40:00Z", dev.shrimpscript.porthole.net.CommandInfo(
                "effort", args = "high", value = "high", saved = true,
                output = "Set effort level to high (saved as your default for new sessions): Comprehensive implementation with extensive testing and documentation",
            )),
            cmdRow("2026-10-02T09:41:00Z", dev.shrimpscript.porthole.net.CommandInfo(
                "model", value = "Fable 5.1", output = "Kept model as `Fable 5.1`",
            )),
            cmdRow("2026-10-02T09:42:00Z", dev.shrimpscript.porthole.net.CommandInfo(
                "rename", args = "release prep", value = "release prep", output = "Session renamed to: release prep",
            )),
            cmdRow("2026-10-02T09:43:00Z", dev.shrimpscript.porthole.net.CommandInfo("login", output = "Login interrupted", error = true), detail = "Login interrupted"),
            cmdRow("2026-10-02T09:44:00Z", dev.shrimpscript.porthole.net.CommandInfo("scope-and-clarify", args = "the release checklist", skill = true)),
        )
        rule.setContent { PortholeTheme { CommandsSession(rows) } }
        rule.waitForIdle()
        rule.onNodeWithText("/effort").assertIsDisplayed()
        rule.onNodeWithText("Also the default for new sessions").assertIsDisplayed()
        rule.onNodeWithText("Comprehensive implementation", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("Fable 5.1")[0].assertIsDisplayed()
        rule.onNodeWithText("Unchanged").assertIsDisplayed()
        rule.onNodeWithText("The session's new name").assertIsDisplayed()
        rule.onNodeWithText("Login interrupted").assertIsDisplayed()
        rule.onNodeWithText("Skill loaded").assertIsDisplayed()
        save("commands-feed")
    }

    /** /context as a card, and a compaction and a /clear as rules across the feed. */
    @Test
    fun contextCardAndCompactionInTheFeed() {
        val rows = listOf(
            row("user", "How full is the window?", "2026-10-02T09:39:00Z"),
            cmdRow("2026-10-02T09:40:00Z", dev.shrimpscript.porthole.net.CommandInfo("context", context = contextFixture)),
            cmdRow("2026-10-02T09:45:00Z", dev.shrimpscript.porthole.net.CommandInfo(
                "compact", compact = dev.shrimpscript.porthole.net.Compaction("auto", 970_287, 13_445, 145_410),
            )),
            row("assistant", "Picking up from the summary: the checklist is half done.", "2026-10-02T09:45:30Z"),
            cmdRow("2026-10-02T09:46:00Z", dev.shrimpscript.porthole.net.CommandInfo("workflow-authoring", skill = true, auto = true)),
            cmdRow("2026-10-02T09:50:00Z", dev.shrimpscript.porthole.net.CommandInfo("clear")),
        )
        rule.setContent { PortholeTheme { CommandsSession(rows) } }
        rule.waitForIdle()
        rule.onNodeWithText("27k of 1.0M").assertIsDisplayed()
        rule.onNodeWithText("3% · Opus 5.5").assertIsDisplayed()
        rule.onNodeWithText("33k at the end is held back for autocompact.").assertIsDisplayed()
        rule.onNodeWithText("System tools").assertIsDisplayed()
        rule.onNodeWithText("Loaded only when used: MCP tools 138k").assertIsDisplayed()
        rule.onNodeWithText("Compacted automatically · 970k → 13k tokens", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Conversation cleared", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Claude Code loaded the workflow-authoring skill").assertIsDisplayed()
        save("commands-context")
    }

    /** The session sheet's own /context section, drawn without the modal sheet. */
    @Test
    fun contextSectionInTheSheet() {
        rule.setContent {
            PortholeTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(dev.shrimpscript.porthole.ui.theme.Porthole.colors.surface).padding(20.dp)) {
                    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp)) {
                        ContextSection(cmdRow("2026-10-02T09:40:00Z", dev.shrimpscript.porthole.net.CommandInfo("context", context = contextFixture)), true) {}
                        ContextSection(null, true) {}
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("From /context at", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Measure again").assertIsDisplayed()
        rule.onNodeWithText("Run /context to see what fills the window.").assertIsDisplayed()
        save("commands-sheet-context")
    }

    @androidx.compose.runtime.Composable
    private fun DropSession(
        canSend: Boolean, draft: String, onDraft: (String) -> Unit = {}, reconnecting: String? = null,
        pendingSends: List<dev.shrimpscript.porthole.net.PortholeClient.PendingPrompt> = emptyList(),
    ) {
        SessionScreen(
            title = "Build fix", branch = "main · tmux work", ring = if (canSend) RingState.Live else RingState.Retrying, live = true,
            rows = agentRows.take(2), backfillCount = 2, loaded = true, canSend = canSend, onSend = {}, onBack = {},
            view = SessionView.Feed, onViewChange = {}, terminal = TerminalEmulator(80, 24), terminalRevision = 0,
            terminalOpen = false, onOpenTerminal = {}, onTerminalKeys = {}, fontSp = 13f, onFontSp = {}, fit = true, onFit = {},
            notice = null, onDismissNotice = {}, state = state(working = false).copy(pendingTool = ""),
            status = TuiStatus(working = false, text = "", elapsed = "", tokens = "", permissionMode = "", interruptible = false),
            caps = listOf("attach", "files", "prompt_ack"), sharedKey = "s1",
            initialDraft = draft, onDraft = onDraft, pendingSends = pendingSends,
            reconnecting = reconnecting, savedCopyAt = if (reconnecting != null) java.time.Instant.parse("2026-10-03T08:42:00Z").toEpochMilli() else 0L,
            onConnectionOptions = if (reconnecting != null) ({}) else null,
        )
    }

    /** A drop: the feed and the half-written message stay, the box still takes typing, and a bar says what is happening. */
    @Test
    fun aDropKeepsTheSessionAndTheDraft() {
        var kept = ""
        rule.setContent { PortholeTheme { DropSession(canSend = false, draft = "and then run the migration", onDraft = { kept = it }, reconnecting = "Reconnecting to workstation…") } }
        rule.waitForIdle()
        rule.onNodeWithText("Reconnecting to workstation…").assertIsDisplayed()
        rule.onNodeWithText("Showing the copy saved at", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Options").assertIsDisplayed()
        rule.onNodeWithText("The login test fails about one run in five", substring = true).assertExists()
        val box = rule.onNodeWithText("and then run the migration")
        box.assert(androidx.compose.ui.test.isEnabled())
        box.performTextInput(" first")
        rule.waitForIdle()
        assertTrue(kept, kept.contains("first"))
        save("offline-session")
    }

    /** Sent, not yet confirmed: the message stays in the box, read-only, and the send button turns. */
    @Test
    fun aMessageOnItsWayStaysInTheBox() {
        val p = dev.shrimpscript.porthole.net.PortholeClient.PendingPrompt("r1", "s1", "run the tests", System.currentTimeMillis())
        rule.setContent { PortholeTheme { DropSession(canSend = true, draft = "run the tests", pendingSends = listOf(p)) } }
        rule.waitForIdle()
        rule.onNodeWithText("run the tests").assertIsDisplayed()
        rule.onNodeWithContentDescription("Send").assertDoesNotExist()
        save("offline-sending")
    }

    /** One computer, unreachable: the list keeps its sections, each row as last seen, under the bar. */
    @Test
    fun theListStaysThroughADrop() {
        val now = System.currentTimeMillis()
        val rows = listOf(
            SessionInfo("d1", "Add dark mode to settings", "/srv/app", "main", "2026-10-03T08:40:00Z", live = true, working = true,
                model = "claude-fable-5-1", workingSince = now - 40_000, doing = "Bash: go test ./...", tmuxName = "0", pane = "%0"),
            SessionInfo("d2", "Speed up the CSV import", "/srv/importer", "main", "2026-10-03T07:00:00Z", live = false, model = "claude-sonnet-5"),
        ).map { it.copy(machineDown = true, machineState = "reconnecting") }
        rule.setContent {
            PortholeTheme {
                SessionsScreen(machine = "workstation", ring = RingState.Retrying, sessions = rows, onSession = {}, onSettings = {}, onRefresh = {},
                    reconnecting = "Reconnecting to workstation…", onConnectionOptions = {})
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Reconnecting to workstation…").assertIsDisplayed()
        rule.onNodeWithText("Live").assertIsDisplayed()
        rule.onNodeWithText("Recent").assertIsDisplayed()
        rule.onAllNodesWithText("as last seen").assertCountEquals(2)
        rule.onNodeWithText("main · tmux 0 · Fable 5.1").assertIsDisplayed()
        save("offline-list")
    }

    /** A message with files: the pictures as thumbnails and the file as a chip beside the bubble, no paths. */
    @Test
    fun sentFilesSitBesideTheBubble() {
        val png = java.io.ByteArrayOutputStream().also { out ->
            android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2A6B62.toInt()) }
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
        val shot = "/srv/u/.config/porthole/uploads/2026-10-03/175738-Screenshot_20261003-145616.png"
        val log = "/srv/u/.config/porthole/uploads/2026-10-03/175741-render.log"
        val old = "/srv/u/.config/porthole/uploads/2026-09-10/101010-gone.png"
        val rows = listOf(
            Row(kind = "user", glyph = "", text = "Check the render. Here are the stills.", metric = "", detail = "", truncated = false,
                ts = "2026-10-03T21:56:00Z", files = listOf(shot, log)),
            Row(kind = "image", glyph = "", text = "Image you sent", metric = "", detail = "", truncated = false, ts = "2026-10-03T21:57:00Z", imageRef = "u1:0"),
            Row(kind = "user", glyph = "", text = "", metric = "", detail = "", truncated = false, ts = "2026-10-03T21:58:00Z", files = listOf(log)),
            Row(kind = "queued", glyph = "", text = "", metric = "queued", detail = "", truncated = false, ts = "2026-10-03T21:59:00Z", files = listOf(old)),
        )
        rule.setContent {
            PortholeTheme {
                SessionScreen(
                    title = "Video editing", branch = "main · tmux 2", ring = RingState.Live, live = true,
                    rows = rows, backfillCount = rows.size, loaded = true, canSend = true, onSend = {}, onBack = {},
                    view = SessionView.Feed, onViewChange = {}, terminal = TerminalEmulator(80, 24), terminalRevision = 0,
                    terminalOpen = false, onOpenTerminal = {}, onTerminalKeys = {}, fontSp = 13f, onFontSp = {}, fit = true, onFit = {},
                    notice = null, onDismissNotice = {}, state = state(working = false).copy(pendingTool = ""),
                    status = TuiStatus(working = false, text = "", elapsed = "", tokens = "", permissionMode = "", interruptible = false),
                    caps = listOf("attach", "files"), images = mapOf("file:$shot" to png, "u1:0" to png), goneImages = setOf("file:$old"),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Check the render. Here are the stills.").assertIsDisplayed()
        rule.onAllNodesWithText("render.log").assertCountEquals(2)
        rule.onNodeWithContentDescription("Screenshot_20261003-145616.png").assertIsDisplayed()
        rule.onNodeWithContentDescription("Picture you sent").assertIsDisplayed()
        // A removed upload says so on its tile; the queued message shows its file too.
        rule.onNodeWithText("No longer\non the computer").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("uploads", substring = true).fetchSemanticsNodes().isEmpty())
        save("sent-files")
    }

    /** Claude is asking but the picker cannot be read off the screen: the terminal is offered, not nothing. */
    @Test
    fun aQuestionThatCannotBeReadOffersTheTerminal() {
        var view = SessionView.Feed
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PortholeTheme {
                SessionScreen(
                    title = "Porthole", branch = "HEAD · tmux work", ring = RingState.Live, live = true,
                    rows = agentRows.take(2), backfillCount = 2, loaded = true, canSend = true, onSend = {}, onBack = {},
                    view = SessionView.Feed, onViewChange = { view = it }, terminal = TerminalEmulator(80, 24), terminalRevision = 0,
                    terminalOpen = false, onOpenTerminal = {}, onTerminalKeys = {}, fontSp = 13f, onFontSp = {}, fit = true, onFit = {},
                    notice = null, onDismissNotice = {}, state = state(working = true).copy(asking = "How should a screenshot handle sleeping screens?"),
                    status = TuiStatus(working = true, text = "", elapsed = "", tokens = "", permissionMode = "", interruptible = true),
                    caps = listOf("attach", "files"),
                )
            }
        }
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
        rule.onNodeWithText("How should a screenshot handle sleeping screens?").assertIsDisplayed()
        rule.onNodeWithText("Answer in the terminal").performClick()
        assertEquals(SessionView.Terminal, view)
        save("asking-unreadable")
    }

    private fun tool(id: String, text: String, tool: String, ts: String, diff: dev.shrimpscript.porthole.net.LineDiff? = null) = Row(
        kind = "tool", glyph = "▸", text = text, metric = "", detail = "", truncated = false, ts = ts, toolId = id, tool = tool, diff = diff,
    )
    private fun result(id: String, ok: Boolean, ts: String, diff: dev.shrimpscript.porthole.net.LineDiff? = null, why: String = "") = Row(
        kind = "result", glyph = if (ok) "✓" else "✗", text = if (ok) "done" else "failed", metric = why, detail = why, truncated = false, ts = ts, toolId = id, diff = diff,
    )
    private val busyTurn = listOf(
        row("user", "The render hangs at 90 minutes. Find out why and fix it.", "2026-10-03T21:50:00Z"),
        row("assistant", "Checking whether a render worker was killed for memory.", "2026-10-03T21:50:10Z"),
        tool("t1", "Ran journalctl -k --since -2h | grep -i oom", "Bash", "2026-10-03T21:50:12Z"),
        result("t1", true, "2026-10-03T21:50:13Z"),
        tool("t2", "Edited render.py", "Edit", "2026-10-03T21:51:00Z"),
        result("t2", true, "2026-10-03T21:51:01Z", dev.shrimpscript.porthole.net.LineDiff(12, 3)),
        tool("t3", "Ran python render.py --workers 3", "Bash", "2026-10-03T21:51:05Z"),
        result("t3", false, "2026-10-03T21:55:15Z", why = "Exit code 1: worker 2 killed (signal 9)"),
        row("assistant", "One of four workers ran out of memory and the renderer waited instead of failing. I fixed it so a killed worker's section is rendered again.", "2026-10-03T21:56:00Z"),
        row("turn", "Worked for 6m 2s", "2026-10-03T21:56:02Z"),
    )

    @androidx.compose.runtime.Composable
    private fun BusySession(rows: List<Row>, working: Boolean, onViewChange: (SessionView) -> Unit = {}) {
        SessionScreen(
            title = "Video editing", branch = "main · tmux 2", ring = RingState.Live, live = true,
            rows = rows, backfillCount = rows.size, loaded = true, canSend = true, onSend = {}, onBack = {},
            view = SessionView.Feed, onViewChange = onViewChange, terminal = TerminalEmulator(80, 24), terminalRevision = 0,
            terminalOpen = false, onOpenTerminal = {}, onTerminalKeys = {}, fontSp = 13f, onFontSp = {}, fit = true, onFit = {},
            notice = null, onDismissNotice = {}, state = state(working = working).copy(pendingTool = if (working) "Bash" else "", model = "claude-opus-5-5"),
            status = TuiStatus(working = working, text = if (working) "Rendering…" else "", elapsed = "", tokens = "", permissionMode = "", interruptible = working, effort = "high"),
            caps = listOf("attach", "files", "changes"),
        )
    }

    /** A busy turn: one work line for the stretch of tools, the narration quiet, the answer with its actions, the turn's diff. */
    @Test
    fun aBusyTurnReadsAsOneWorkLine() {
        var view = SessionView.Feed
        rule.setContent { PortholeTheme { BusySession(busyTurn, working = false) { view = it } } }
        rule.waitForIdle()
        rule.onNodeWithText("Ran 2 commands, edited a file").assertIsDisplayed()
        rule.onNodeWithText("1 failed").assertIsDisplayed()
        rule.onAllNodesWithText("Ran journalctl", substring = true).assertCountEquals(0)
        rule.onNodeWithText("Checking whether a render worker was killed for memory.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Copy the answer").assertIsDisplayed()
        rule.onNodeWithContentDescription("Read the answer aloud").assertIsDisplayed()
        rule.onNodeWithText("Diff").assertIsDisplayed()
        rule.onNodeWithContentDescription("Model and effort: Opus 5.5 · high").assertIsDisplayed()
        save("redesign-feed")
        rule.onNodeWithContentDescription("Open the terminal").performClick()
        assertEquals(SessionView.Terminal, view)
    }

    /** While a step runs: the stretch so far on one line, the running step beneath it with the screw and its time. */
    @Test
    fun aRunningStepIsItsOwnLine() {
        rule.setContent { PortholeTheme { BusySession(busyTurn.take(7), working = true) } }
        rule.waitForIdle()
        rule.onNodeWithText("Ran a command, edited a file").assertIsDisplayed()
        rule.onNodeWithText("Ran python render.py --workers 3").assertIsDisplayed()
        save("redesign-running")
    }

    /** The sheet behind a work line: every step, joined by a line, with its lines or its time. */
    @Test
    fun aWorkLinesStepsInOrder() {
        val work = feedItems(busyTurn).filterIsInstance<FeedItem.Work>().single()
        rule.setContent {
            PortholeTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(dev.shrimpscript.porthole.ui.theme.Porthole.colors.surface).padding(top = 24.dp)) {
                    WorkSteps(work, working = false, took = { "1.2s" }, onStep = {})
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("journalctl -k --since -2h | grep -i oom").assertIsDisplayed()
        rule.onNodeWithText("+12").assertIsDisplayed()
        rule.onNodeWithText("failed").assertIsDisplayed()
        save("redesign-sheet")
    }

    /** The working icon, the screw: sizes, a turn in eighths, on the ground and on a card, and at rest. */
    @Test
    fun screwAtEverySizeAndThroughATurn() {
        rule.setContent {
            PortholeTheme {
                val c = dev.shrimpscript.porthole.ui.theme.Porthole.colors
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.background(c.ground).padding(16.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
                ) {
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                        for (s in listOf(16, 20, 32, 48)) ScrewAt(0.8f, trails = true, size = s.dp)
                        ScrewAt(0.8f, trails = true, size = 160.dp)
                    }
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                        for (k in 0 until 8) ScrewAt(k * 2f * Math.PI.toFloat() / 24f, trails = true, size = 48.dp)
                    }
                    androidx.compose.foundation.layout.Row(
                        androidx.compose.ui.Modifier.background(c.surface).padding(12.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Spinner(background = c.surface)
                        androidx.compose.material3.Text("Running Bash…", color = c.text)
                        ScrewMark(background = c.surface)
                        androidx.compose.material3.Text("Worked for 41s", color = c.faint)
                    }
                }
            }
        }
        rule.waitForIdle()
        save("screw")
    }

    /** The same session a moment after Allow: the card has gone and the turn runs on. */
    @Test
    fun approvalAllowedTheTurnRunsOn() {
        val rows = listOf(
            row("user", "Ship the release branch to main.", "2026-09-27T14:12:00Z"),
            row("assistant", "The build passes. I'll move main to the release branch and push it.", "2026-09-27T14:13:10Z"),
        )
        rule.setContent {
            PortholeTheme {
                SessionScreen(
                    title = "Release 2.4", branch = "release/2.4 · tmux 1", ring = RingState.Live, live = true,
                    rows = rows, backfillCount = rows.size, loaded = true, canSend = true, onSend = {}, onBack = {},
                    view = SessionView.Feed, onViewChange = {}, terminal = TerminalEmulator(80, 24), terminalRevision = 0,
                    terminalOpen = false, onOpenTerminal = {}, onTerminalKeys = {}, fontSp = 13f, onFontSp = {}, fit = true, onFit = {},
                    notice = null, onDismissNotice = {},
                    // the allowed command is what runs now
                    state = state(working = true).copy(pendingTool = "Bash", workingSince = java.time.Instant.now().minusSeconds(75).toString()),
                    status = TuiStatus(working = true, text = "", elapsed = "", tokens = "", permissionMode = "", interruptible = true),
                    caps = listOf("attach", "files"),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Claude wants to run a command").assertDoesNotExist()
        rule.onNodeWithText("Running Bash", substring = true).assertIsDisplayed()
        save("approval-allowed")
    }

    private fun changesFixture(): ChangesState {
        val diff = """diff --git a/notes.txt b/notes.txt
index 4cb29ea..8b1d0e1 100644
--- a/notes.txt
+++ b/notes.txt
@@ -1,3 +1,4 @@
 one
-two
+2
 three
+four
""".trimIndent()
        val state = ChangesState(
            sessionId = "s", root = "/srv/proj", branch = "main",
            files = listOf(
                ChangedFile("notes.txt", " M", 2, 1, false, diff, false),
                ChangedFile("app/src/main/java/dev/example/VeryLongFileNameForTheList.kt", "A ", 40, 0, false, "", false),
                ChangedFile("added.txt", "??", 1, 0, false, "", false),
                ChangedFile("gone.txt", " D", 0, 12, false, "", false),
                ChangedFile("pic.bin", " M", 0, 0, true, "", false),
            ),
            added = 43, removed = 13, truncated = false, notRepo = false,
        )
        return state
    }

    @Test
    fun changesListOfFiles() {
        val state = changesFixture()
        rule.setContent { PortholeTheme { Sheet { ChangesContent(state, connected = true, waited = false, openPath = null, onOpen = {}, onRefresh = {}) } } }
        rule.waitForIdle()
        rule.onNodeWithText("5 files · main · uncommitted").assertIsDisplayed()
        rule.onNodeWithText("binary").assertIsDisplayed()
        save("changes-list")
    }

    @Test
    fun changesDiffOfOneFile() {
        val state = changesFixture()
        rule.setContent { PortholeTheme { Sheet { ChangesContent(state, connected = true, waited = false, openPath = "notes.txt", onOpen = {}, onRefresh = {}) } } }
        rule.waitForIdle()
        rule.onNodeWithText("+four").assertIsDisplayed()
        save("changes-diff")
    }

    /** The sheet's own ground, which ModalBottomSheet paints in the app. */
    @androidx.compose.runtime.Composable
    private fun Sheet(content: @androidx.compose.runtime.Composable () -> Unit) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(dev.shrimpscript.porthole.ui.theme.Porthole.colors.surface)
        ) { content() }
    }

    @Test
    fun switchesShowCurrentModelEffortAndMode() {
        rule.setContent {
            PortholeTheme {
                Sheet {
                    SwitchesSection(
                        state = state(working = false),
                        status = TuiStatus(working = false, text = "", elapsed = "", tokens = "", permissionMode = "bypass permissions on", interruptible = false, effort = "xhigh"),
                        onSend = {}, onKey = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithText("Fable 5.1").assertCountEquals(2) // the current value and its chip
        rule.onAllNodesWithText("xhigh").assertCountEquals(2)
        rule.onNodeWithText("bypass permissions on").assertIsDisplayed()
        rule.onNodeWithText("Cycle (Shift-Tab)").assertIsDisplayed()
        save("switches")
    }

    @Test
    fun sessionsListFromTwoComputers() {
        val now = System.currentTimeMillis()
        val desk = listOf(
            SessionInfo("d1", "Add dark mode to settings", "/srv/app", "main", "2026-09-14T10:00:00Z", live = true, working = true,
                model = "claude-fable-5-1", workingSince = now - 40_000, doing = "Bash: go test ./...", tmuxName = "0", pane = "%0"),
        )
        val laptop = listOf(
            SessionInfo("l1", "Speed up the CSV import", "/srv/importer", "main", "2026-09-14T09:59:00Z", live = true, working = false,
                model = "claude-sonnet-5", tmuxName = "work", pane = "%3"),
            SessionInfo("l2", "Site copy", "/srv/site", "main", "2026-09-13T22:00:00Z", live = false, tmux = false, model = "claude-opus-5"),
        )
        val machines = listOf(dev.shrimpscript.porthole.net.Machine("desk", "desk", name = "workstation"), dev.shrimpscript.porthole.net.Machine("laptop", "laptop", name = "laptop"))
        val merged = dev.shrimpscript.porthole.net.Fleet.mergeSessions(machines, listOf(desk, laptop))
        rule.setContent {
            PortholeTheme {
                SessionsScreen(machine = "2 computers", ring = RingState.Live, sessions = merged, onSession = {}, onSettings = {}, onRefresh = {})
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("laptop · main · tmux work · Sonnet 5").assertIsDisplayed()
        save("sessions-two-machines")
    }

    @Test
    fun sessionsListWithAnUnreachableComputer() {
        val now = System.currentTimeMillis()
        val desk = listOf(SessionInfo("d1", "Add dark mode to settings", "/srv/app", "main", "2026-09-14T10:00:00Z", live = true, working = true,
            model = "claude-fable-5-1", workingSince = now - 40_000, doing = "Bash: go test ./...", tmuxName = "0", pane = "%0"))
        val laptop = listOf(SessionInfo("l1", "Speed up the CSV import", "/srv/importer", "main", "2026-09-14T09:59:00Z", live = true, working = true,
            model = "claude-sonnet-5", doing = "Edit App.kt", tmuxName = "work", pane = "%3"))
        val machines = listOf(dev.shrimpscript.porthole.net.Machine("desk", "desk", name = "workstation"), dev.shrimpscript.porthole.net.Machine("laptop", "laptop", name = "laptop"))
        val merged = dev.shrimpscript.porthole.net.Fleet.mergeSessions(machines, listOf(desk, laptop), reachable = listOf(true, false), states = listOf("connected", "reconnecting"))
        rule.setContent {
            PortholeTheme {
                SessionsScreen(machine = "2 computers", ring = RingState.Live, sessions = merged, onSession = {}, onSettings = {}, onRefresh = {}, computers = "1 of 2 connected")
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("laptop · reconnecting").assertIsDisplayed()
        rule.onNodeWithText("2 sessions · 1 of 2 connected").assertIsDisplayed()
        rule.onNodeWithText("as last seen").assertIsDisplayed()
        rule.onNodeWithText("laptop · main · Sonnet 5").assertIsDisplayed()
        save("sessions-unreachable")
    }

    /**
     * The shell is reachable from Settings, not only from the failure card: by the time
     * the daemon is dead, a first-time SSH login is the wrong thing to be discovering.
     */
    @Test
    fun settingsOffersAShellBeforeAnythingBreaks() {
        rule.setContent {
            PortholeTheme {
                SettingsScreen(
                    host = "workstation", deviceName = "pixel", daemonVersion = "0.24.4",
                    appVersion = "0.24.4", fontSp = 13f, onFontSp = {},
                    onGuide = {}, onSetup = {}, onUnpair = {}, onBack = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Open a shell over SSH").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("If Porthole cannot connect").assertIsDisplayed()
        save("settings-failsafe")
    }

    @Test
    fun failsafeShellSaysWhichKindItIs() {
        val term = TerminalEmulator(80, 24)
        term.write("dev@workstation ~> systemctl --user status portholed\r\n".toByteArray())
        rule.setContent {
            PortholeTheme {
                FailsafeScreen(machine = "workstation", terminal = term, revision = 1, onKeys = {}, onBack = {}, daemonDown = false)
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Failsafe shell \u2014 a plain SSH login, not portholed").assertIsDisplayed()
        save("failsafe-on-purpose")
    }

    /**
     * Coming back to a session that ran on without you: the line says how much is new
     * and how long you were gone, and the feed lands on it rather than at the bottom.
     */
    @Test
    fun sessionScreenMarksWhereYouLeftOff() {
        val away = java.time.Instant.now().minusSeconds(4 * 3600)
        fun at(minutesAfter: Long) = away.plusSeconds(minutesAfter * 60).toString()
        val rows = listOf(
            row("user", "Port the settings screen to the new theme.", at(-40)),
            row("assistant", "Done - the palette is now read from the theme object.", at(-35)),
            row("turn", "Worked for 2m 10s", at(-34)),
            row("user", "Now do the same for the session screen.", at(2)),
            row("tool", "Edited SessionScreen.kt", at(6), toolId = "t9"),
            row("result", "1 file changed, 24 insertions(+)", at(7), toolId = "t9"),
            row("assistant", "Both screens read their colours from the theme now.", at(9)),
            row("turn", "Worked for 4m 02s", at(10)),
        )
        rule.setContent {
            PortholeTheme {
                SessionScreen(
                    title = "Theme pass", branch = "main \u00b7 tmux 0", ring = RingState.Live, live = true,
                    rows = rows, backfillCount = rows.size, loaded = true, canSend = true, onSend = {}, onBack = {},
                    view = SessionView.Feed, onViewChange = {}, terminal = TerminalEmulator(80, 24), terminalRevision = 0,
                    terminalOpen = false, onOpenTerminal = {}, onTerminalKeys = {}, fontSp = 13f, onFontSp = {}, fit = true, onFit = {},
                    notice = null, onDismissNotice = {}, state = state(working = false),
                    lastSeen = away.toString(),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("1 turn since you left \u00b7 4h ago").assertIsDisplayed()
        save("session-since-you-left")
    }

    /** A session can be silenced without being hidden; the sheet says which it is. */
    @Test
    fun detailsSheetCanMuteOneSession() {
        rule.setContent {
            PortholeTheme {
                Sheet {
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier.padding(16.dp),
                    ) {
                        MuteSwitch(muted = true, onMuted = {})
                        Spacer(androidx.compose.ui.Modifier.height(12.dp))
                        MuteSwitch(muted = false, onMuted = {})
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("It still shows in the list and in Needs you \u2014 it just stays silent.").assertIsDisplayed()
        rule.onNodeWithText("Muted").assertIsDisplayed()
        rule.onNodeWithText("Tell me about this session").assertIsDisplayed()
        save("session-muted")
    }

    /** Porthole's own update comes from GitHub; a project's build from the computer. Both can be put away. */
    @Test
    fun bannersForARelease_andForAProjectBuild() {
        val release = dev.shrimpscript.porthole.Releases.offer(
            dev.shrimpscript.porthole.Releases.Release("9.9.0", "https://github.com/ShrimpScript/porthole/releases/download/v9.9.0/porthole-9.9.0.apk", 9_000_000L, ""),
            "0.0.1",
        )!!
        val project = dev.shrimpscript.porthole.net.BuildInfo("shopping-list", "1.4.0", "/builds/shopping-list-1.4.0.apk", 12_000_000L)
        rule.setContent {
            PortholeTheme {
                Sheet {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(top = 16.dp)) {
                        UpdateBanner(release, progress = null, note = null, onUpdate = {}, onDismiss = {})
                        UpdateBanner(project, progress = null, note = null, onUpdate = {}, onDismiss = {})
                        UpdateBanner(project, progress = 0.42f, note = null, onUpdate = {}, onDismiss = {})
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Porthole 9.9.0 is out").assertIsDisplayed()
        rule.onAllNodesWithText("Shopping list 1.4.0 is on the computer").assertCountEquals(2)
        rule.onNodeWithText("Update").assertIsDisplayed()
        rule.onAllNodesWithText("Not now").assertCountEquals(2)
        save("banners")
    }

    @Test
    fun settingsOffersTheNewRelease() {
        val release = dev.shrimpscript.porthole.Releases.offer(
            dev.shrimpscript.porthole.Releases.Release("9.9.0", "https://github.com/ShrimpScript/porthole/releases/download/v9.9.0/porthole-9.9.0.apk", 9_000_000L, ""),
            "0.0.1",
        )
        rule.setContent {
            PortholeTheme {
                SettingsScreen(
                    host = "workstation", deviceName = "pixel", daemonVersion = "0.26.0",
                    appVersion = "0.26.0", fontSp = 13f, onFontSp = {},
                    onGuide = {}, onSetup = {}, onUnpair = {}, onBack = {},
                    release = release, releaseCheckedAt = System.currentTimeMillis(),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Check GitHub for new versions").assertIsDisplayed()
        rule.onNodeWithText("9.9.0 is out \u00b7 you have 0.26.0").assertIsDisplayed()
        rule.onNodeWithText("Update Porthole").assertIsDisplayed()
        save("settings-updates")
    }

    /** Every bundled component with its licence; the texts are the APK's own copies. */
    @Test
    fun licencesListEveryComponentAndOpenItsText() {
        rule.setContent { PortholeTheme { LicencesScreen(onBack = {}) } }
        rule.waitForIdle()
        rule.onNodeWithText("Licences").assertIsDisplayed()
        rule.onNodeWithText("Schibsted Grotesk").assertIsDisplayed()
        // The list and the shipped files agree: nothing listed is missing, nothing shipped is unlisted.
        val shipped = rule.activity.assets.list("licenses")!!.toSet()
        assertEquals(shipped, Notices.mapNotNull { it.file }.toSet())
        save("licences")
        rule.onNodeWithText("Schibsted Grotesk").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("PREAMBLE\nThe goals of the Open Font License (OFL)", substring = true).assertExists()
        save("licences-open")
    }

    /**
     * A Mac has no Tailscale SSH, so the failsafe offers to add this phone's own key -
     * and says so when Remote Login is off, since the key is no use without it.
     */
    @Test
    fun settingsOffersAKeyOnAMac() {
        rule.setContent {
            PortholeTheme {
                SettingsScreen(
                    host = "studio", deviceName = "pixel", daemonVersion = "0.27.0",
                    appVersion = "0.27.0", fontSp = 13f, onFontSp = {},
                    onGuide = {}, onSetup = {}, onUnpair = {}, onBack = {},
                    failsafe = dev.shrimpscript.porthole.net.FailsafeState("", sshServer = false),
                    canAddKey = true, daemonOs = "darwin",
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Add this phone's key").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Remote Login is off", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Open a shell over SSH").assertDoesNotExist()
        save("settings-failsafe-mac")
    }

    @Test
    fun settingsWithTheKeyAdded() {
        rule.setContent {
            PortholeTheme {
                SettingsScreen(
                    host = "studio", deviceName = "pixel", daemonVersion = "0.27.0",
                    appVersion = "0.27.0", fontSp = 13f, onFontSp = {},
                    onGuide = {}, onSetup = {}, onUnpair = {}, onBack = {},
                    failsafe = dev.shrimpscript.porthole.net.FailsafeState("key", sshServer = true),
                    canAddKey = true, daemonOs = "darwin",
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Remove this phone's key").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Open a shell over SSH").assertIsDisplayed()
        rule.onNodeWithText("Remote Login is off", substring = true).assertDoesNotExist()
        save("settings-failsafe-key")
    }

    @Test
    fun settingsLinksToTheLicences() {
        rule.setContent {
            PortholeTheme {
                SettingsScreen(
                    host = "workstation", deviceName = "pixel", daemonVersion = "0.26.0",
                    appVersion = "0.26.0", fontSp = 13f, onFontSp = {},
                    onGuide = {}, onSetup = {}, onUnpair = {}, onBack = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Licences").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("no Porthole servers", substring = true).assertIsDisplayed()
        save("settings-this-app")
    }

    /**
     * The failsafe is a plain shell sized to the phone, and one finger walks back through
     * what it printed.
     */
    @Test
    fun failsafeFitsThePhoneAndScrollsBack() {
        val term = TerminalEmulator(80, 24)
        term.write(((1..120).joinToString("\r\n") { "build step $it ok" } + "\r\ndev@workstation ~> ").toByteArray())
        var grid = 0 to 0
        rule.setContent {
            PortholeTheme {
                FailsafeScreen(machine = "workstation", terminal = term, revision = 1, onKeys = {}, onBack = {},
                    daemonDown = false, onResize = { cols, rows -> grid = cols to rows })
            }
        }
        rule.waitForIdle()
        // Sized to the screen: the columns a 411dp phone holds at 13sp, not the 80 it opened with.
        assert(grid.first in 50..70) { "fit to ${grid.first} columns" }
        assert(term.cols == grid.first && term.rows == grid.second)
        save("failsafe-fit")
        rule.onRoot().performTouchInput { swipeDown(startY = centerY - 300f, endY = centerY + 300f) }
        rule.waitForIdle()
        rule.onNodeWithText("back to the prompt", substring = true).assertIsDisplayed()
        save("failsafe-scrolled")
    }
}
