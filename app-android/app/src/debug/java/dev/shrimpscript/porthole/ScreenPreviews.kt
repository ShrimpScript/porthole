package dev.shrimpscript.porthole

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.net.Failure
import dev.shrimpscript.porthole.net.SessionInfo
import dev.shrimpscript.porthole.ui.ComputerOs
import dev.shrimpscript.porthole.ui.ConnectScreen
import dev.shrimpscript.porthole.ui.ConsentScreen
import dev.shrimpscript.porthole.ui.FailureScreen
import dev.shrimpscript.porthole.ui.NeedsScreen
import dev.shrimpscript.porthole.ui.PairScreen
import dev.shrimpscript.porthole.ui.Ring
import dev.shrimpscript.porthole.ui.RingState
import dev.shrimpscript.porthole.ui.SessionsScreen
import dev.shrimpscript.porthole.ui.SettingsScreen
import dev.shrimpscript.porthole.ui.SetupScreen
import dev.shrimpscript.porthole.ui.TailscaleScreen
import dev.shrimpscript.porthole.ui.TourScreen
import dev.shrimpscript.porthole.ui.WelcomeScreen
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeTheme
import dev.shrimpscript.porthole.ui.theme.PortholeType

/**
 * Preview fixtures. These render the real screens, not mock-ups, so a preview is the
 * shipped UI.
 *
 * Two device specs on purpose: 411dp is a typical current phone width and 360dp is the
 * narrowest width every layout must still hold.
 */
private const val PIXEL = "spec:width=411dp,height=914dp,dpi=420"
private const val SMALL = "spec:width=360dp,height=780dp,dpi=420"

private val demoSessions = listOf(
    SessionInfo("a", "Add dark mode to settings", "/srv/app", "main", "", true),
    SessionInfo("b", "Fix flaky login test", "/srv/api", "fix/login", "", true),
    SessionInfo("c", "Write the release notes", "/srv/docs", "main", "", false),
)

@Preview(name = "01 welcome", device = PIXEL)
@Composable
fun PreviewWelcome() = PortholeTheme { WelcomeScreen({}, {}) }

@Preview(name = "02 needs", device = PIXEL)
@Composable
fun PreviewNeeds() = PortholeTheme { NeedsScreen(ComputerOs.Mac, {}, {}, {}) }

@Preview(name = "03 tailscale missing", device = PIXEL)
@Composable
fun PreviewTailscale() = PortholeTheme { TailscaleScreen(false, {}, {}, {}, {}, {}) }

@Preview(name = "04 setup", device = PIXEL)
@Composable
fun PreviewSetup() = PortholeTheme { SetupScreen(ComputerOs.Mac, {}, {}, {}) }

@Preview(name = "05 connect", device = PIXEL)
@Composable
fun PreviewConnect() = PortholeTheme {
    ConnectScreen("workstation", {}, false, "Found Porthole on workstation", true, {}, {}, {})
}

@Preview(name = "06 consent", device = PIXEL)
@Composable
fun PreviewConsent() = PortholeTheme { ConsentScreen("workstation", {}, {}) }

@Preview(name = "07 pair", device = PIXEL)
@Composable
fun PreviewPair() = PortholeTheme { PairScreen("896", {}, "workstation", null, false, {}, {}) }

@Preview(name = "08 tour", device = PIXEL)
@Composable
fun PreviewTour() = PortholeTheme { TourScreen({}, {}) }

@Preview(name = "09 sessions", device = PIXEL)
@Composable
fun PreviewSessions() = PortholeTheme {
    SessionsScreen("workstation", RingState.Live, demoSessions, {}, {}, {})
}

@Preview(name = "10 sessions empty", device = PIXEL)
@Composable
fun PreviewSessionsEmpty() = PortholeTheme {
    SessionsScreen("workstation", RingState.Live, emptyList(), {}, {}, {})
}

@Preview(name = "11 settings", device = PIXEL)
@Composable
fun PreviewSettings() = PortholeTheme {
    SettingsScreen(host = "workstation", deviceName = "my-phone", daemonVersion = "0.9.2", appVersion = "0.18.1", autoContinue = true, notifyDone = true, onNotifyDone = {}, fontSp = 13f, onFontSp = {}, onGuide = {}, onSetup = {}, onUnpair = {}, onBack = {})
}

@Preview(name = "12 failure daemon down", device = PIXEL)
@Composable
fun PreviewFailure() = PortholeTheme {
    FailureScreen(Failure.Unreachable, "", "workstation", {}, {})
}

/** The signature component, every state, so a regression in it is visible at a glance. */
@Preview(name = "13 ring states", device = "spec:width=411dp,height=200dp,dpi=420")
@Composable
fun PreviewRingStates() = PortholeTheme {
    Column(
        Modifier
            .fillMaxSize()
            .background(Porthole.colors.ground)
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            RingState.entries.forEach { st ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Ring(state = st, size = 34.dp, strokeWidth = 3.dp)
                    Text(
                        st.name,
                        style = PortholeType.meta,
                        color = Porthole.colors.muted,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

/* ---- the 360dp floor: the screens most likely to break when space is tight ---- */

@Preview(name = "20 sessions 360dp", device = SMALL)
@Composable
fun PreviewSessions360() = PortholeTheme {
    SessionsScreen("workstation", RingState.Live, demoSessions, {}, {}, {})
}

@Preview(name = "21 setup 360dp", device = SMALL)
@Composable
fun PreviewSetup360() = PortholeTheme { SetupScreen(ComputerOs.Linux, {}, {}, {}) }

@Preview(name = "22 pair 360dp", device = SMALL)
@Composable
fun PreviewPair360() = PortholeTheme { PairScreen("896760", {}, "workstation", null, false, {}, {}) }

@Preview(name = "23 welcome 360dp", device = SMALL)
@Composable
fun PreviewWelcome360() = PortholeTheme { WelcomeScreen({}, {}) }
