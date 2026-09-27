package dev.shrimpscript.porthole.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.terminal.TerminalEmulator
import dev.shrimpscript.porthole.ui.theme.Appearance
import dev.shrimpscript.porthole.ui.theme.PortholeTheme
import dev.shrimpscript.porthole.ui.theme.ThemeChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** The screens the documentation shows, drawn from the real composables. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderDocsTest {
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

    @Test
    fun welcome() {
        rule.setContent { PortholeTheme { WelcomeScreen(onStart = {}, onHowItWorks = {}) } }
        save("welcome")
    }

    @Test
    fun needs() {
        rule.setContent { PortholeTheme { NeedsScreen(os = null, onOs = {}, onContinue = {}, onBack = {}) } }
        rule.onNodeWithText("What you'll need").assertIsDisplayed()
        // Nothing is assumed: the computer is chosen before anything else is shown for it.
        rule.onNodeWithText("Which computer runs Claude Code?").assertIsDisplayed()
        rule.onNodeWithText("Choose the computer").assertIsDisplayed()
        save("needs")
    }

    @Test
    fun needsWithAMac() {
        rule.setContent { PortholeTheme { NeedsScreen(os = ComputerOs.Mac, onOs = {}, onContinue = {}, onBack = {}) } }
        rule.onNodeWithText("Continue").assertIsDisplayed()
        save("needs-mac")
    }

    @Test
    fun pair() {
        rule.setContent { PortholeTheme { PairScreen(code = "915", onCodeChange = {}, host = "workstation", error = null, connecting = false, onPair = {}, onBack = {}, onScan = {}) } }
        rule.onNodeWithText("Pair with workstation").assertIsDisplayed()
        save("pair")
    }

    @Test
    fun consent() {
        rule.setContent { PortholeTheme { ConsentScreen(machine = "workstation", onCancel = {}, onAuthorize = {}, caps = listOf("sessions", "prompt", "terminal", "approvals", "upload", "capture", "preview", "start", "changes")) } }
        save("consent")
    }

    @Test
    fun settings() {
        rule.setContent {
            PortholeTheme {
                SettingsScreen(
                    host = "workstation", deviceName = "pixel", daemonVersion = "0.23.0", appVersion = "0.23.0",
                    theme = ThemeChoice.Porthole, appearance = Appearance.System, fontSp = 13f, onFontSp = {},
                    onGuide = {}, onSetup = {}, onUnpair = {}, onBack = {},
                )
            }
        }
        rule.onNodeWithText("Settings").assertIsDisplayed()
        save("settings")
    }

    @Test
    fun terminal() {
        // A believable CLI screen fed through the real VT parser: what the phone shows
        // is what the emulator drew from these bytes, nothing pasted.
        val esc = '\u001b'
        val em = TerminalEmulator(80, 24)
        val screen = buildString {
            append("$esc[2J$esc[H")
            append(" $esc[1mClaude Code$esc[0m v2.1.270\r\n")
            append(" Fable 5.1 with xhigh effort · Claude Pro\r\n")
            append(" /srv/proj · main\r\n\r\n")
            append("$esc[36m>$esc[0m Rename the helper and run the tests.\r\n\r\n")
            append("$esc[32m*$esc[0m Ran $esc[1m./gradlew test$esc[0m\r\n")
            append("     42 tests passed in 38s\r\n\r\n")
            append("$esc[32m*$esc[0m Done: renamed fetchAll to loadAll, 42 tests pass.\r\n\r\n")
            append("* Worked for 38s · done 10:02 AM\r\n")
            repeat(8) { append("\r\n") }
            append("--------------------------------------------------------------------------------\r\n")
            append("$esc[36m>$esc[0m \r\n")
            append("--------------------------------------------------------------------------------\r\n")
            append("  >> bypass permissions on (shift+tab to cycle) · <- for agents")
        }
        em.write(screen.toByteArray(Charsets.UTF_8))
        rule.setContent {
            PortholeTheme {
                TerminalBody(emulator = em, revision = 1, open = true, live = true, fontSp = 11f, onFontSp = {}, fit = true, onFit = {}, onSend = {}, onConnect = {})
            }
        }
        save("terminal")
    }

    @Test
    fun tour() {
        rule.setContent { PortholeTheme { TourScreen(onDone = {}, onSkip = {}) } }
        save("tour")
    }

    @Test
    fun claudeThemeSessions() {
        rule.setContent {
            PortholeTheme(choice = ThemeChoice.Claude, appearance = Appearance.Dark) {
                SessionsScreen(machine = "workstation", ring = RingState.Live, sessions = emptyList(), onSession = {}, onSettings = {}, onRefresh = {})
            }
        }
        save("theme-claude")
    }

    @Test
    fun settingsWithTwoComputers() {
        rule.setContent {
            PortholeTheme {
                SettingsScreen(
                    host = "workstation", deviceName = "pixel", daemonVersion = "0.23.1", appVersion = "0.23.1",
                    theme = ThemeChoice.Porthole, appearance = Appearance.System, fontSp = 13f, onFontSp = {},
                    onGuide = {}, onSetup = {}, onUnpair = {}, onBack = {},
                    machines = listOf(
                        dev.shrimpscript.porthole.net.Machine("workstation", "workstation", name = "workstation"),
                        dev.shrimpscript.porthole.net.Machine("192.0.2.9", "192.0.2.9", name = "laptop"),
                    ),
                    machineStates = mapOf("workstation" to "connected", "192.0.2.9" to "reconnecting"),
                )
            }
        }
        rule.onNodeWithText("Computers").assertIsDisplayed()
        rule.onNodeWithText("Add a computer").assertIsDisplayed()
        save("settings-two-computers")
    }

    // The onboarding steps and cards the public site replicates, drawn so the mock-ups
    // there have a real screen to be checked against.
    @Test
    fun tailscaleStep() {
        rule.setContent { PortholeTheme { TailscaleScreen(installed = true, onOpenStore = {}, onOpenApp = {}, onRecheck = {}, onContinue = {}, onBack = {}) } }
        save("tailscale")
    }

    @Test
    fun setupStep() {
        rule.setContent { PortholeTheme { SetupScreen(os = ComputerOs.Mac, onOs = {}, onContinue = {}, onBack = {}) } }
        rule.onNodeWithText("brew install shrimpscript/tap/porthole").assertExists()
        rule.onNodeWithText("Turn on Remote Login", substring = true).assertExists()
        rule.onNodeWithText("sudo tailscale up --ssh").assertDoesNotExist()
        save("setup")
    }

    @Test
    fun setupStepLinux() {
        rule.setContent { PortholeTheme { SetupScreen(os = ComputerOs.Linux, onOs = {}, onContinue = {}, onBack = {}) } }
        rule.onNodeWithText("sudo tailscale up --ssh").assertExists()
        rule.onNodeWithText("cd porthole && ./tools/install.sh").assertExists()
        rule.onNodeWithText("sudo loginctl enable-linger \$USER").assertExists()
        rule.onNodeWithText("Turn on Remote Login", substring = true).assertDoesNotExist()
        save("setup-linux")
    }

    @Test
    fun connectStep() {
        rule.setContent {
            PortholeTheme {
                ConnectScreen(host = "workstation", onHostChange = {}, checking = false, result = "portholed 0.26.0 is answering",
                    reachable = true, onCheck = {}, onContinue = {}, onBack = {})
            }
        }
        save("connect")
    }

    @Test
    fun failureCard() {
        rule.setContent {
            PortholeTheme {
                FailureScreen(reason = dev.shrimpscript.porthole.net.Failure.Unreachable, detail = "connection refused", machine = "workstation",
                    onRetry = {}, onRepair = {}, onOpenSsh = {}, onRestartDaemon = {}, retrying = true)
            }
        }
        save("failure")
    }

    @Test
    fun approvalCard() {
        val a = dev.shrimpscript.porthole.net.Approval(
            toolUseId = "toolu_01", sessionId = "s1", toolName = "Bash", command = "npm publish --access public",
            cwd = "/srv/app", expiresInSeconds = 120,
        )
        rule.setContent {
            PortholeTheme {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(dev.shrimpscript.porthole.ui.theme.Porthole.colors.ground)
                        .padding(16.dp)
                ) { ApprovalCardBody(a, remaining = 94, onAllow = {}, onDeny = {}) }
            }
        }
        save("approval")
    }
}
