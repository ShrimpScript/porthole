package dev.shrimpscript.porthole

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import dev.shrimpscript.porthole.ui.AccountSheet
import dev.shrimpscript.porthole.ui.LocalSharedTransition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.shrimpscript.porthole.net.Approval
import dev.shrimpscript.porthole.net.Connection
import dev.shrimpscript.porthole.net.Failure
import dev.shrimpscript.porthole.net.BuildInfo
import dev.shrimpscript.porthole.net.Machine
import dev.shrimpscript.porthole.net.PortholeClient
import dev.shrimpscript.porthole.net.SessionInfo
import dev.shrimpscript.porthole.ui.ApprovalOverlay
import dev.shrimpscript.porthole.ui.CLI_COMMANDS
import dev.shrimpscript.porthole.ui.failureCardOnRetry
import dev.shrimpscript.porthole.ui.ConnectScreen
import dev.shrimpscript.porthole.ui.ConsentScreen
import dev.shrimpscript.porthole.ui.ComputerOs
import dev.shrimpscript.porthole.ui.NewSessionSheet
import dev.shrimpscript.porthole.ui.projectsOf
import dev.shrimpscript.porthole.ui.shortPath
import kotlinx.coroutines.flow.first
import dev.shrimpscript.porthole.ui.FailsafeScreen
import dev.shrimpscript.porthole.ui.FailureScreen
import dev.shrimpscript.porthole.ui.LicencesScreen
import dev.shrimpscript.porthole.ui.NeedsScreen
import dev.shrimpscript.porthole.ui.PairScreen
import dev.shrimpscript.porthole.ui.RingState
import dev.shrimpscript.porthole.ui.RouteTransition
import dev.shrimpscript.porthole.ui.SessionScreen
import dev.shrimpscript.porthole.ui.SessionView
import dev.shrimpscript.porthole.ui.SessionsScreen
import dev.shrimpscript.porthole.ui.SettingsScreen
import dev.shrimpscript.porthole.ui.SetupScreen
import dev.shrimpscript.porthole.ui.TailscaleScreen
import dev.shrimpscript.porthole.ui.TourScreen
import dev.shrimpscript.porthole.ui.WelcomeScreen
import dev.shrimpscript.porthole.ui.theme.PortholeColors
import dev.shrimpscript.porthole.ui.theme.PortholeMotion
import dev.shrimpscript.porthole.ui.theme.Appearance
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeTheme
import dev.shrimpscript.porthole.ui.theme.ThemeChoice
import dev.shrimpscript.porthole.ui.theme.paletteFor
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity
import dev.shrimpscript.porthole.ui.theme.spec
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAILSCALE_PKG = "com.tailscale.ipn"
private const val PREFS = "porthole"
private const val SESSIONS_REFRESH_MS = 8_000L

/** Debug-only: an `am start -e sshPort N` extra points the failsafe at a stand-in SSH server. */
private var sshDebugPort: Int = 0

/** Debug-only: renders the usage-limit card in a named state, since a real limit cannot be provoked on demand. */
private var limitDemo: String? = null

/**
 * Screens, in the order a first run visits them. Forward/back for the transition is
 * simply whether the ordinal went up or down.
 */
private enum class Route {
    Welcome, Needs, Tailscale, Setup, Connect, Consent, Pair, Tour,
    Sessions, Session, Settings, SettingsSetup, SettingsTour, SettingsLicences, Failure, Failsafe,
}

private fun routeFromName(name: String?): Route? = when (name) {
    "welcome" -> Route.Welcome
    "needs" -> Route.Needs
    "tailscale" -> Route.Tailscale
    "setup" -> Route.Setup
    "connect" -> Route.Connect
    "consent" -> Route.Consent
    "pair" -> Route.Pair
    "tour" -> Route.Tour
    "sessions", "sessions-empty" -> Route.Sessions
    "session" -> Route.Session
    "settings" -> Route.Settings
    "licences" -> Route.SettingsLicences
    "failure" -> Route.Failure
    else -> null
}

/** The porthole opening at launch: quick to start, gentle to arrive. */
private const val APERTURE_MS = 640L

/** How long the background service keeps retrying an unreachable computer. */
private const val RETRY_GIVE_UP_MS = 30 * 60 * 1000L

/** A gap shorter than this is a blip between rooms; nothing is said about it. */
private const val OUTAGE_NOTE_MS = 2 * 60 * 1000L

/** "12 minutes", "3 hours" - the length of an outage, said the way a person would. */
internal fun humanGap(ms: Long): String {
    val mins = ms / 60_000L
    return when {
        mins < 60 -> "$mins minute" + if (mins == 1L) "" else "s"
        mins < 60 * 24 -> (mins / 60).let { "$it hour" + if (it == 1L) "" else "s" }
        else -> (mins / (60 * 24)).let { "$it day" + if (it == 1L) "" else "s" }
    }
}

/** A session a notification asked us to open; consumed by the composable. */
private val pendingOpen = androidx.compose.runtime.mutableStateOf<String?>(null)

/** A porthole://pair link the phone opened (camera, browser); consumed by the composable. */
private val pendingPair = androidx.compose.runtime.mutableStateOf<dev.shrimpscript.porthole.net.PairLink?>(null)

/** The chosen theme, held above the composition so the theme wraps the whole app. */
private val themeChoice = androidx.compose.runtime.mutableStateOf(ThemeChoice.Porthole)
private val themeAppearance = androidx.compose.runtime.mutableStateOf(Appearance.System)

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
class MainActivity : ComponentActivity() {
    private var launchAccent: Int = PortholeColors.Porthole.accent.toArgb()

    /**
     * Porthole is dark only; Claude and Gemini are light or dark by choice, or follow the
     * phone. Android 12+ keeps this per app and uses it for the starting window too.
     */
    fun applyNightMode(choice: ThemeChoice, appearance: Appearance) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val ui = getSystemService(android.app.UiModeManager::class.java) ?: return
        val mode = when {
            choice == ThemeChoice.Porthole -> android.app.UiModeManager.MODE_NIGHT_YES
            appearance == Appearance.Light -> android.app.UiModeManager.MODE_NIGHT_NO
            appearance == Appearance.Dark -> android.app.UiModeManager.MODE_NIGHT_YES
            else -> android.app.UiModeManager.MODE_NIGHT_AUTO
        }
        if (ui.nightMode != mode) runCatching { ui.setApplicationNightMode(mode) }
    }

    /**
     * The launch, second half. The system splash has drawn the ring (ic_splash_animated,
     * 900ms) with the wordmark beneath it. Once that finishes and the app has drawn,
     * an [ApertureView] takes the splash's place pixel for pixel and the ring opens:
     * the app is seen through the porthole as it widens past the screen. Animations
     * off: the splash simply goes.
     */
    private fun exitSplash(view: androidx.core.splashscreen.SplashScreenViewProvider) {
        // The launch animation is decoration. Nothing in it may close the app: the system
        // owns the splash view and can take its pieces away before this runs - on Android
        // 16 the icon view can already be gone by the time the ring has finished, and
        // reading it then throws.
        runCatching { openAperture(view) }.onFailure {
            android.util.Log.w("Porthole", "splash exit fell back to plain: $it")
            runCatching { view.remove() }
        }
    }

    private fun openAperture(view: androidx.core.splashscreen.SplashScreenViewProvider) {
        val scale = android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        // Below Android 12 the compat splash is a different view with different geometry:
        // the copy would not be pixel for pixel, so it fades instead.
        if (scale == 0f || android.os.Build.VERSION.SDK_INT < 31) {
            if (scale == 0f) view.remove()
            else view.view.animate().alpha(0f).setDuration(PortholeMotion.SLOW_MS.toLong()).withEndAction { view.remove() }.start()
            return
        }
        // iconAnimationStartMillis is wall-clock time (the platform's own timestamp base).
        val elapsed = System.currentTimeMillis() - view.iconAnimationStartMillis
        val remaining = (view.iconAnimationDurationMillis - elapsed).coerceIn(0L, 1000L)
        view.view.postDelayed({
            // Its own guard: this runs long after exitSplash returned, so the wrapper
            // there cannot catch it, and by now the activity may be finishing.
            runCatching { aperture(view) }.onFailure {
                android.util.Log.w("Porthole", "aperture fell back to a fade: $it")
                runCatching { fadeOutSplash(view) }
            }
        }, remaining)
    }

    /** The splash, gone quietly: the fallback whenever the opening cannot be drawn. */
    private fun fadeOutSplash(view: androidx.core.splashscreen.SplashScreenViewProvider) {
        view.view.animate().alpha(0f).setDuration(PortholeMotion.SLOW_MS.toLong())
            .withEndAction { runCatching { view.remove() } }.start()
    }

    private fun aperture(view: androidx.core.splashscreen.SplashScreenViewProvider) {
            // The system may have reclaimed the icon before the ring finished; without it
            // there is nothing to open from, so the splash simply fades.
            val icon = runCatching { view.iconView }.getOrNull()
            val decor = window.decorView as? android.view.ViewGroup
            if (icon == null || icon.width == 0 || decor == null || isFinishing || isDestroyed) {
                fadeOutSplash(view)
                return
            }
            val loc = IntArray(2).also { icon.getLocationInWindow(it) }
            // The system draws the 108-unit icon at 288dp, one and a half times the view
            // it reports (measured: a 140px ring on a 504px view). The ring is radius 20,
            // stroke 5, of those units.
            val unit = icon.width * 1.5f / 108f
            val cx = loc[0] + icon.width / 2f
            val cy = loc[1] + icon.height / 2f
            val ground = ApertureView.groundOf(view.view.background, PortholeColors.Porthole.ground.toArgb())
            // The wordmark the system placed: an ImageView beside the icon. Copied so it can fade.
            // The wordmark the system placed: a plain View with the branding drawable as
            // its background, beside the icon. Copied so it can go with the ground.
            val brandView = (view.view as? android.view.ViewGroup)?.let { g ->
                (0 until g.childCount).map { g.getChildAt(it) }.firstOrNull { it !== icon && it.background != null && it.width > 0 }
            }
            val brandBounds = brandView?.let { b ->
                val l = IntArray(2).also { b.getLocationInWindow(it) }
                android.graphics.Rect(l[0], l[1], l[0] + b.width, l[1] + b.height)
            }
            // A software copy of the wordmark: the system's is a bitmap drawable the
            // aperture's thread may not draw.
            val brandBitmap = brandView?.let { b ->
                runCatching {
                    val d = b.background.constantState?.newDrawable()?.mutate() ?: return@runCatching null
                    val bmp = android.graphics.Bitmap.createBitmap(b.width, b.height, android.graphics.Bitmap.Config.ARGB_8888)
                    d.setBounds(0, 0, b.width, b.height)
                    d.draw(android.graphics.Canvas(bmp))
                    bmp
                }.getOrNull()
            }
            val aperture = ApertureView(this, cx, cy, 20f * unit, 5f * unit, ground, PortholeColors.Porthole.accent.toArgb(), launchAccent, brandBitmap, brandBounds)
            // Swap once the aperture's first frame - its copy of the splash - is on
            // screen; hold for a beat while the app underneath settles (the porthole is
            // the view of it); then open. The opening draws on its own thread, so the
            // app's first, busiest frames cannot stall it.
            aperture.ready {
                view.remove()
                whenSmooth(maxWaitMs = 600) { aperture.open(APERTURE_MS) { decor.removeView(aperture) } }
            }
            decor.addView(aperture, android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    }

    /** Runs [block] after two consecutive frames under 24ms apart, or after [maxWaitMs]. */
    private fun whenSmooth(maxWaitMs: Long, block: () -> Unit) {
        val choreographer = android.view.Choreographer.getInstance()
        val start = android.os.SystemClock.uptimeMillis()
        var last = 0L
        var smooth = 0
        choreographer.postFrameCallback(object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val now = frameTimeNanos / 1_000_000
                if (last != 0L && now - last < 24) smooth++ else smooth = 0
                last = now
                if (smooth >= 2 || android.os.SystemClock.uptimeMillis() - start > maxWaitMs) block()
                else choreographer.postFrameCallback(this)
            }
        })
    }

    override fun onStart() { super.onStart(); Foreground.visible = true }
    override fun onStop() { Foreground.visible = false; super.onStop() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifier.EXTRA_OPEN_SESSION)?.let { pendingOpen.value = it }
        intent.dataString?.let { dev.shrimpscript.porthole.net.PairLink.parse(it) }?.let { pendingPair.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The saved theme decides the splash and window colours before the first frame,
        // so a light Claude session never opens on the dark Porthole ground.
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        themeChoice.value = ThemeChoice.of(prefs.getString("theme", null))
        themeAppearance.value = Appearance.of(prefs.getString("appearance", null))
        val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val palette = paletteFor(themeChoice.value, themeAppearance.value, systemDark)
        // The system draws the starting window from the manifest theme before any of this
        // runs, so the splash itself is always the Porthole ring on the Porthole ground.
        // What the saved theme can still decide is the window it hands over to, and how:
        // on a Claude palette the splash fades out over the new ground instead of cutting.
        launchAccent = palette.accent.toArgb()
        // The app's own night mode is what the system reads when it draws the splash, so
        // set it from the theme here (for the next launch) and whenever the theme changes.
        applyNightMode(themeChoice.value, themeAppearance.value)
        when (themeChoice.value) {
            ThemeChoice.Claude -> setTheme(if (palette.isLight) R.style.Theme_Porthole_Starting_Light else R.style.Theme_Porthole_Starting_ClaudeDark)
            ThemeChoice.Gemini -> setTheme(if (palette.isLight) R.style.Theme_Porthole_Starting_GeminiLight else R.style.Theme_Porthole_Starting_GeminiDark)
            ThemeChoice.Porthole -> setTheme(R.style.Theme_Porthole_Starting_PortholeDark)
        }
        val splash = installSplashScreen()
        splash.setOnExitAnimationListener { view -> exitSplash(view) }
        super.onCreate(savedInstanceState)
        intent?.getStringExtra(Notifier.EXTRA_OPEN_SESSION)?.let { pendingOpen.value = it }
        intent?.dataString?.let { dev.shrimpscript.porthole.net.PairLink.parse(it) }?.let { pendingPair.value = it }
        // Debug builds accept a starting route and host so tools/screenshots.sh can
        // capture each screen deterministically. Release builds ignore both: this is a
        // capture hook, never a way to show a state the app has not actually reached.
        val startRoute = if (BuildConfig.DEBUG) intent?.getStringExtra("startRoute") else null
        val presetHost = if (BuildConfig.DEBUG) intent?.getStringExtra("host") else null
        val pairCode = if (BuildConfig.DEBUG) intent?.getStringExtra("pairCode") else null
        val sessionId = if (BuildConfig.DEBUG) intent?.getStringExtra("sessionId") else null
        limitDemo = if (BuildConfig.DEBUG) intent?.getStringExtra("limitDemo") else null
        sshDebugPort = if (BuildConfig.DEBUG)
            intent?.getStringExtra("sshPort")?.toIntOrNull() ?: 0 else 0
        setContent {
            PortholeTheme(themeChoice.value, themeAppearance.value) {
                WindowChrome()
                SharedTransitionLayout {
                    CompositionLocalProvider(LocalSharedTransition provides this) {
                        PortholeApp(startRoute, presetHost, pairCode, sessionId)
                    }
                }
            }
        }
    }
}

private fun Context.tailscaleInstalled(): Boolean = try {
    packageManager.getPackageInfo(TAILSCALE_PKG, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}

/**
 * Keeps the window in step with the palette: background (what shows behind the IME and
 * on the launch frame) and whether the status/navigation bar icons draw dark.
 */
@Composable
private fun WindowChrome() {
    val view = LocalView.current
    val c = Porthole.colors
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(c.ground.toArgb()))
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = c.isLight
            isAppearanceLightNavigationBars = c.isLight
        }
    }
}

@Composable
private fun PortholeApp(
    startRoute: String? = null,
    presetHost: String? = null,
    pairCode: String? = null,
    presetSession: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    // ViewModel-scoped, not remember-scoped: see PortholeViewModel.
    val vm = viewModel<PortholeViewModel>()
    val client = vm.client

    val savedHost = remember { prefs.getString("host", "") ?: "" }
    val paired = remember { prefs.getBoolean("paired", false) }
    var tourSeen by remember { mutableStateOf(prefs.getBoolean("tour_seen", false)) }

    var route by remember {
        mutableStateOf(
            routeFromName(startRoute)
                ?: if (paired && savedHost.isNotBlank()) Route.Sessions else Route.Welcome
        )
    }
    var prevRoute by remember { mutableStateOf(route) }
    val forward = route.ordinal >= prevRoute.ordinal
    SideEffect { prevRoute = route }

    var host by remember { mutableStateOf(presetHost ?: savedHost) }
    val demoEmpty = startRoute == "sessions-empty"
    var code by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<String?>(null) }
    var probeCaps by remember { mutableStateOf<List<String>>(emptyList()) }
    var reachable by remember { mutableStateOf(false) }
    var pairError by remember { mutableStateOf<String?>(null) }
    var tsInstalled by remember { mutableStateOf(context.tailscaleInstalled()) }
    var failsafeNote by remember { mutableStateOf<String?>(null) }
    // The session's last activity as of the *previous* visit, captured before opening
    // overwrites it: everything after it is what happened while the phone was away.
    var openedSeenAt by remember { mutableStateOf("") }
    var mutedIds by remember { mutableStateOf(Mutes.of(context)) }
    // The shell can be opened from the failure card or, on purpose, from Settings; Back
    // goes where it came from, and the header says which of the two it is.
    var failsafeFrom by remember { mutableStateOf(Route.Failure) }
    // Learned from the daemon while it was reachable and remembered, so the failsafe
    // knows who to log in as at the moment the daemon is gone.
    var sshUser by remember { mutableStateOf(prefs.getString("ssh_user", "") ?: "") }
    // How to restart the daemon on this computer, learned the same way: systemd on Linux,
    // launchd on a Mac.
    var restartCmd by remember { mutableStateOf(prefs.getString("restart_cmd", "") ?: "") }
    // Mac or Linux: chosen on "What you'll need", it decides which setup steps are shown.
    var computerOs by remember { mutableStateOf(ComputerOs.fromPref(prefs.getString("computer_os", null))) }
    fun chooseOs(o: ComputerOs) { computerOs = o; prefs.edit().putString("computer_os", o.pref).apply() }
    var keyNote by remember { mutableStateOf<String?>(null) }
    var fontSp by remember { mutableFloatStateOf(prefs.getFloat("term_font", 13f)) }
    var fit by remember { mutableStateOf(prefs.getBoolean("term_fit", true)) }
    var sessionView by remember { mutableStateOf(SessionView.Feed) }

    val connection by client.connection.collectAsState()
    // The first computer's outage, for the list's bar (as the open session's has its own below).
    var listDownSince by remember { mutableLongStateOf(0L) }
    var listAttempt by remember { mutableStateOf(0) }
    LaunchedEffect(connection) {
        when (val c = connection) {
            is Connection.Live -> { listDownSince = 0L; listAttempt = 0 }
            is Connection.Retrying -> { if (listDownSince == 0L) listDownSince = System.currentTimeMillis(); listAttempt = maxOf(listAttempt, c.attempt) }
            else -> {}
        }
    }
    var listDown by remember { mutableStateOf(false) }
    LaunchedEffect(listDownSince) {
        listDown = false
        if (listDownSince != 0L) { kotlinx.coroutines.delay(1_500); listDown = true }
    }
    val failsafe by client.failsafe.collectAsState()
    val claudeAccount by client.account.collectAsState()
    // The Claude account sheet, and the computer it signs in.
    var accountClient by remember { mutableStateOf<dev.shrimpscript.porthole.net.PortholeClient?>(null) }
    val failsafeKeyError by client.failsafeKeyError.collectAsState()
    val sessions by vm.fleet.sessions.collectAsState()
    val machines by vm.fleet.machines.collectAsState()
    val fleetConns by vm.fleet.connections.collectAsState()
    var openSession by remember { mutableStateOf<SessionInfo?>(null) }
    // The client behind the open session: its machine's, or the first computer's. Every
    // session-scoped flow below reads from it, so a session on a second computer shows
    // that computer's rows, status and changes.
    val active = openSession?.let { vm.fleet.clientFor(it) } ?: client
    val activeConn by key(active) { active.connection.collectAsState() }
    // A pairing in progress for another computer runs on its own client until the daemon
    // says hello; the first computer's socket is untouched throughout.
    var adding by remember { mutableStateOf<PortholeClient?>(null) }
    val pairing = adding ?: client
    val pairConn by pairing.connection.collectAsState()
    val rows by key(active) { active.rows.collectAsState() }
    val backfillCount by key(active) { active.backfillCount.collectAsState() }
    val approval by vm.fleet.approval.collectAsState()
    val termRevision by key(active) { active.terminalRevision.collectAsState() }
    val sshRevision by vm.sshRevision.collectAsState()
    val sshClosed by vm.sshClosed.collectAsState()
    val termOpen by key(active) { active.terminalOpen.collectAsState() }
    val termSession by key(active) { active.terminalSession.collectAsState() }
    val notice by key(active) { active.notice.collectAsState() }
    val attached by key(active) { active.attached.collectAsState() }
    val sessionState by key(active) { active.state.collectAsState() }
    val images by key(active) { active.images.collectAsState() }
    val preview by key(active) { active.preview.collectAsState() }
    val remaining by key(active) { active.remaining.collectAsState() }
    val earlierEpoch by key(active) { active.earlierEpoch.collectAsState() }
    val loadedEpoch by key(active) { active.loadedEpoch.collectAsState() }
    val changes by key(active) { active.changes.collectAsState() }
    val files by key(active) { active.files.collectAsState() }
    val failedSend by key(active) { active.failedSend.collectAsState() }
    var quickReplies by remember {
        mutableStateOf(prefs.getString("quick_replies", null)?.split("\n") ?: listOf("Continue", "Yes", "No", "Looks good"))
    }
    var previewPath by remember { mutableStateOf("/") }
    var seenStamps by remember { mutableStateOf(prefs.all.filterKeys { it.startsWith("seen_") }.mapKeys { it.key.removePrefix("seen_") }.mapValues { it.value.toString() }) }
    // A confirmed share opens in the browser, as a custom tab so Back returns here.
    val tabColor = Porthole.colors.ground.toArgb()
    LaunchedEffect(active) {
        active.previewOpened.collect { share ->
            // The computer's tailnet IP when the daemon names it: the browser may resolve
            // names with its own secure DNS, which never sees a MagicDNS name.
            val h = share.host.ifBlank { (openSession?.let { vm.fleet.hostOf(it) } ?: host).substringBeforeLast(':') }
                .let { if (it.contains(':') && !it.startsWith("[")) "[$it]" else it }
            val uri = Uri.parse("http://$h:${share.port}$previewPath")
            previewPath = "/"
            runCatching {
                androidx.browser.customtabs.CustomTabsIntent.Builder()
                    .setDefaultColorSchemeParams(
                        androidx.browser.customtabs.CustomTabColorSchemeParams.Builder().setToolbarColor(tabColor).build()
                    )
                    .build().launchUrl(context, uri)
            }.onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) } }
        }
    }
    val clips by key(active) { active.clips.collectAsState() }
    LaunchedEffect(Unit) {
        vm.fleet.cacheDir = context.cacheDir
        // Drafts, recent feeds and the last list, per computer, in the app's private files.
        vm.fleet.storeRoot = java.io.File(context.filesDir, "sessions")
    }
    val liveStatus by key(active) { active.status.collectAsState() }
    val sessionAgents by key(active) { active.agents.collectAsState() }
    val sendingNow by key(active) { active.sending.collectAsState() }
    val goneImages by key(active) { active.gone.collectAsState() }
    val savedCopy by key(active) { active.savedCopy.collectAsState() }
    // Since when the open session's computer has been unreachable (0 while connected), and
    // the latest try. Reconnecting alternates Retrying and Connecting; the bar follows the
    // outage, not each state, so it does not flicker with them.
    var downSince by remember(active) { mutableLongStateOf(0L) }
    var downAttempt by remember(active) { mutableStateOf(0) }
    LaunchedEffect(activeConn) {
        when (val c = activeConn) {
            is Connection.Live -> { downSince = 0L; downAttempt = 0 }
            // The most tries seen this outage: a network coming back restarts the count, and
            // Options should not vanish and come back with it.
            is Connection.Retrying -> { if (downSince == 0L) downSince = System.currentTimeMillis(); downAttempt = maxOf(downAttempt, c.attempt) }
            else -> {}
        }
    }
    // A blip of a second or two passes unremarked; a longer one gets the bar.
    var showDown by remember(active) { mutableStateOf(false) }
    LaunchedEffect(downSince) {
        showDown = false
        if (downSince != 0L) { kotlinx.coroutines.delay(1_500); showDown = true }
    }
    val tuiStatus = when (limitDemo) {
        "waiting" -> dev.shrimpscript.porthole.net.TuiStatus(false, "", "", "", "bypass permissions on", false,
            limitText = "Usage limit reached · continuing automatically at 3:45pm · esc to cancel", limitResumeAt = "3:45pm", limitWaiting = true)
        "enter" -> dev.shrimpscript.porthole.net.TuiStatus(false, "", "", "", "", false,
            limitText = "Your usage limit has reset · press enter to continue", limitEnter = true)
        "stopped" -> dev.shrimpscript.porthole.net.TuiStatus(false, "", "", "", "", false,
            limitText = "Automatic continue stopped after repeated usage-limit hits · /rate-limit-options to try again", limitStopped = true)
        "hit" -> dev.shrimpscript.porthole.net.TuiStatus(false, "", "", "", "", false,
            limitText = "You've hit your session limit · resets 3:45pm")
        else -> liveStatus
    }
    // New session from the list: the sheet, the folder being started ("machineId|cwd")
    // and what went wrong, if anything.
    var showNewSession by remember { mutableStateOf(false) }
    var newStart by remember { mutableStateOf<String?>(null) }
    var newNote by remember { mutableStateOf<String?>(null) }
    // Claude Code's "do you trust this folder", when the new session stopped on it.
    var newTrust by remember { mutableStateOf<dev.shrimpscript.porthole.net.TrustAsk?>(null) }
    // A session we asked the computer to start: poll the list until it is live, then re-attach.
    var startingId by remember { mutableStateOf("") }
    // The pane the daemon typed "claude" into; the new session registers there. Matching
    // by directory instead would attach to whichever session shares the folder.
    var startedPane by remember { mutableStateOf("") }
    LaunchedEffect(startingId) {
        val id = startingId
        if (id.isBlank()) return@LaunchedEffect
        val deadline = System.currentTimeMillis() + 40_000
        val poll = openSession?.let { vm.fleet.clientFor(it) } ?: client
        val machineId = openSession?.machineId.orEmpty()
        while (System.currentTimeMillis() < deadline) {
            poll.refreshSessions()
            kotlinx.coroutines.delay(2000)
            val pane = startedPane
            val now = poll.sessions.value.firstOrNull {
                it.id == id || (pane.isNotBlank() && it.pane == pane) || (pane.isBlank() && it.pane.isBlank() && it.cwd == openSession?.cwd)
            }
            if (now != null && now.live) {
                if (openSession?.id == id) {
                    // A new session gets a new id: what was typed in the box goes with it.
                    if (now.id != id) poll.store?.let { st ->
                        st.draft(id).takeIf { it.isNotBlank() }?.let { d -> st.setDraft(now.id, d); st.setDraft(id, "") }
                    }
                    openSession = now.copy(machineId = machineId, machine = openSession?.machine.orEmpty()); poll.attach(now.id)
                }
                break
            }
        }
        startingId = ""
        startedPane = ""
    }
    // A refused start (no tmux, directory gone) answers with a notice, not a live
    // session; the card must stop saying "Starting" the moment it arrives.
    LaunchedEffect(notice) { if (notice != null && startingId.isNotBlank()) startingId = "" }
    LaunchedEffect(active) {
        active.started.collect { ev ->
            if (ev.sessionId != startingId) return@collect
            startedPane = ev.pane
            if (ev.mode == "already") active.refreshSessions()
        }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // The first computer's client answers to a blank machine id, as its rows carry one.
    fun clientOf(machineId: String) = if (machineId.isBlank()) client else vm.fleet.client(machineId)

    fun openSession(s: SessionInfo) {
        openSession = s
        vm.attachedId = s.id
        vm.attachedTitle = s.title
        openedSeenAt = seenStamps[s.id].orEmpty()
        prefs.edit().putString("seen_" + s.id, s.lastActive).apply()
        seenStamps = seenStamps + (s.id to s.lastActive)
        vm.fleet.clientFor(s).attach(s.id)
        // Landscape means the phone was turned sideways to type, so a live session opens on its terminal.
        sessionView = if (landscape && s.tmux) SessionView.Terminal else SessionView.Feed
        route = Route.Session
    }

    // New session: ask the computer, learn the pane it typed `claude` into, then wait for
    // Claude Code to register there and open it. Only one start runs at a time, so the
    // next "new" answer from that computer is this one.
    LaunchedEffect(newStart) {
        val req = newStart ?: return@LaunchedEffect
        val machineId = req.substringBefore('|')
        val sc = clientOf(machineId)
        var ev: dev.shrimpscript.porthole.net.StartedEvent? = null
        // Listening before asking: the answer is not replayed to a late collector.
        val listen = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            ev = sc.started.first { it.mode == "new" && it.sessionId.isBlank() }
        }
        var asked: dev.shrimpscript.porthole.net.TrustAsk? = null
        val trustListen = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            sc.trustAsks.collect { asked = it }
        }
        sc.newSession(req.substringAfter('|'))
        val answerBy = System.currentTimeMillis() + 15_000
        while (ev == null && sc.notice.value == null && System.currentTimeMillis() < answerBy) delay(100)
        listen.cancel()
        val started = ev
        if (started == null) {
            newNote = sc.notice.value ?: "The computer did not answer. Try again when it is connected."
            newStart = null
            return@LaunchedEffect
        }
        var readyBy = System.currentTimeMillis() + 40_000
        try {
            while (System.currentTimeMillis() < readyBy) {
                // A folder Claude Code has not been used in asks first, before the session
                // shows up anywhere; while that question is on the phone, nothing times out.
                asked?.takeIf { it.pane == started.pane }?.let { a ->
                    asked = null
                    newTrust = a
                }
                if (newTrust != null) {
                    delay(300)
                    readyBy = System.currentTimeMillis() + 40_000
                    continue
                }
                sc.refreshSessions()
                delay(1500)
                val s = vm.fleet.sessions.value.firstOrNull { it.machineId == machineId && it.pane == started.pane && it.live }
                if (s != null) {
                    showNewSession = false
                    newStart = null
                    openSession(s)
                    return@LaunchedEffect
                }
            }
        } finally {
            trustListen.cancel()
        }
        newNote = "Claude Code has not started in tmux session ${started.tmux} yet. It may be asking something " +
            "there first; at the desk: tmux attach -t ${started.tmux}"
        newStart = null
    }

    fun cancelAdd() {
        adding?.disconnect()
        adding = null
        host = prefs.getString("host", "").orEmpty()
        code = ""
        pairError = null
    }

    // One shell, two doors. Opening it needs the daemon only for the user name, which was
    // learned while it was still answering and kept in preferences for exactly this moment.
    fun openAccount(on: dev.shrimpscript.porthole.net.PortholeClient) {
        accountClient = on
        on.forgetRestarts()
        on.startSignIn()
    }

    fun openFailsafeShell(from: Route) {
        vm.ssh.debugPort = sshDebugPort
        failsafeNote = "Opening a shell over SSH\u2026"
        // A fresh screen and scrollback for every shell: RIS resets the emulator.
        vm.sshTerminal.write(byteArrayOf(0x1b, 'c'.code.toByte()))
        vm.sshClosed.value = false
        scope.launch {
            val res = vm.ssh.open(this, host, sshUser, 80, 24)
            failsafeNote = if (res.isSuccess) null else "SSH failed: ${res.exceptionOrNull()?.message}"
            if (res.isSuccess) { failsafeFrom = from; route = Route.Failsafe }
        }
    }

    fun closeSession() {
        (openSession?.let { vm.fleet.clientFor(it) } ?: client).detach()
        openSession = null
        vm.attachedId = ""
        route = Route.Sessions
    }

    // A pairing link opened from the camera or a browser: everything typed, for us.

    val wantedPair by pendingPair

    // The link fills the form; the person presses Pair. Anything on the phone can fire a
    // VIEW intent at porthole://pair, so connecting on its say-so would let any app or QR
    // point the app at another computer without a word.
    var pairNote by remember { mutableStateOf<String?>(null) }
    // Where Back leads from a link's Pair screen: a paired app returns to its list, not
    // into the onboarding it finished long ago.
    var pairBackTo by remember { mutableStateOf<Route?>(null) }
    LaunchedEffect(wantedPair) {
        val link = wantedPair ?: return@LaunchedEffect
        pendingPair.value = null
        val current = prefs.getString("host", "").orEmpty()
        pairBackTo = if (prefs.getBoolean("paired", false)) Route.Sessions else null
        pairNote = if (prefs.getBoolean("paired", false) && current.isNotBlank() && current != link.host)
            "Filled in from a link. Pairing with ${link.host} replaces $current."
        else "Filled in from a link. Check the address, then Pair."
        host = link.host; code = link.code; pairError = null
        route = Route.Pair
    }

    // A notification tap names a session; open it once the list has it.
    val wanted by pendingOpen
    LaunchedEffect(wanted, sessions) {
        val id = wanted ?: return@LaunchedEffect
        val s = sessions.firstOrNull { it.id == id } ?: return@LaunchedEffect
        pendingOpen.value = null
        openSession(s)
    }

    // Android 13+ needs permission before any event notification can show. Asked once,
    // on the sessions list, where "Claude finished while you were away" first matters.
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(route) {
        if (route == Route.Sessions && android.os.Build.VERSION.SDK_INT >= 33 &&
            !Notifier.canPost(context) && !prefs.getBoolean("asked_notifications", false)
        ) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun openTailscale() {
        val launch = context.packageManager.getLaunchIntentForPackage(TAILSCALE_PKG)
        if (launch != null) context.startActivity(launch)
    }

    // Reconnect an already-paired device on launch. The debug-extras path is the same real
    // handshake - it supplies the code instead of a person typing it.
    LaunchedEffect(Unit) {
        when {
            !pairCode.isNullOrBlank() && !presetHost.isNullOrBlank() ->
                client.connect(presetHost, pairCode)
            paired && savedHost.isNotBlank() -> client.connect(savedHost)
        }
        vm.fleet.connectAll()
    }

    // Retry the moment the phone has a network again, rather than waiting out a backoff
    // that could be 30 seconds old.
    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { client.retryNow(); vm.fleet.retryAll() }
        }
        runCatching {
            cm?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb,
            )
        }
        onDispose { runCatching { cm?.unregisterNetworkCallback(cb) } }
    }

    // The sessions list is a snapshot; while someone is looking at it, keep it current.
    // A session going live at the desk should show up without a tap.
    LaunchedEffect(route, connection is Connection.Live) {
        if ((route == Route.Sessions || route == Route.Session) && connection is Connection.Live) {
            while (true) {
                delay(SESSIONS_REFRESH_MS)
                vm.fleet.refreshAll()
            }
        }
    }

    // Debug-extras path: open a named session once the list arrives.
    LaunchedEffect(sessions, connection is Connection.Live) {
        if (presetSession != null && openSession == null && connection is Connection.Live) {
            sessions.firstOrNull { it.id == presetSession }?.let { openSession(it) }
        }
    }

    // The open session's live/working/model facts come from the list, so keep them
    // current: Claude starting in that directory must enable the composer without a
    // trip back to the list. A restarted Claude gets a new id; match by directory then.
    LaunchedEffect(sessions) {
        val cur = openSession ?: return@LaunchedEffect
        val fresh = sessions.firstOrNull { it.id == cur.id } ?: sessions.firstOrNull { it.cwd == cur.cwd }
        // A row from a computer that cannot be reached says nothing about the session: it
        // stays as last seen, so a drop does not make it read as stopped mid-drop.
        if (fresh != null && !fresh.machineDown && fresh != cur) openSession = fresh.copy(id = cur.id)
    }

    // Switching to the terminal view attaches if the session is live and not attached yet.
    // Keyed on the terminal's own state too: a terminal that dies (a dropped socket, a
    // closed pane) is asked for again, rather than left showing its last frame.
    // Reopens are spaced out, so a terminal that closes the moment it opens cannot loop.
    var lastTermOpen by remember { mutableLongStateOf(0L) }
    LaunchedEffect(sessionView, route, openSession?.id, activeConn is Connection.Live, termOpen, termSession) {
        val s = openSession ?: return@LaunchedEffect
        if (route == Route.Session && sessionView == SessionView.Terminal && s.tmux &&
            termSession != s.id && activeConn is Connection.Live
        ) {
            val wait = 1500L - (System.currentTimeMillis() - lastTermOpen)
            if (wait > 0) kotlinx.coroutines.delay(wait)
            lastTermOpen = System.currentTimeMillis()
            active.openTerminal(s.id, 80, 24)
        }
    }
    // A computer forgotten while one of its sessions is open: the session goes with it.
    LaunchedEffect(machines) {
        val s = openSession
        if (s != null && s.machineId.isNotBlank() && machines.none { it.id == s.machineId } && route == Route.Session) closeSession()
    }

    // Turning the phone sideways mid-session opens the terminal; turning back does not
    // force it closed, which would yank the view out from under someone mid-command.
    LaunchedEffect(landscape) {
        if (landscape && route == Route.Session && openSession?.tmux == true) sessionView = SessionView.Terminal
    }

    // Pairing another computer: its own client says hello, the fleet adopts it, and the
    // form goes back to the first computer's address. A refusal shows on the Pair screen.
    LaunchedEffect(pairConn, adding) {
        val add = adding ?: return@LaunchedEffect
        when (val c = pairConn) {
            is Connection.Live -> {
                val ok = vm.fleet.add(Machine(id = host, host = host, name = c.daemon.host, addedAt = System.currentTimeMillis()), add)
                adding = null
                if (ok) {
                    host = prefs.getString("host", "").orEmpty()
                    code = ""
                    route = Route.Sessions
                } else {
                    pairError = "This phone is already paired with that computer."
                }
            }
            is Connection.Failed -> if (route == Route.Pair) pairError = c.detail
            else -> {}
        }
    }
    // A session on a second computer re-attaches when that computer comes back. The
    // first value is skipped: opening the session attached already.
    LaunchedEffect(active) {
        if (active === client) return@LaunchedEffect
        active.connection.drop(1).collect { c ->
            if (c is Connection.Live) openSession?.let { active.attach(it.id) }
        }
    }

    // The connection drives navigation: a live socket means sessions, a refusal means the
    // matching failure card. Never the other way round - the UI does not assume a state
    // the socket has not reported.
    // Keyed on the connection only: re-attaching on every list refresh would re-send
    // the backfill every eight seconds while a session is open.
    LaunchedEffect(connection) {
        when (val c = connection) {
            is Connection.Live -> {
                if (c.daemon.sshUser.isNotBlank() && c.daemon.sshUser != sshUser) {
                    sshUser = c.daemon.sshUser
                    prefs.edit().putString("ssh_user", sshUser).apply()
                }
                if (c.daemon.restart.isNotBlank() && c.daemon.restart != restartCmd) {
                    restartCmd = c.daemon.restart
                    prefs.edit().putString("restart_cmd", restartCmd).apply()
                }
                if (adding == null) {
                    prefs.edit().putString("host", host).putBoolean("paired", true).apply()
                    if (route == Route.Pair || route == Route.Consent) {
                        route = if (tourSeen) Route.Sessions else Route.Tour
                    }
                }
                // Recovery. A live socket makes the failure screen a lie, whichever route
                // back was taken - Retry, the restart action, or Tailscale returning.
                if (route == Route.Failure) {
                    route = if (openSession != null) Route.Session else Route.Sessions
                    failsafeNote = null
                }
                // The service starts only once a socket is actually live, so the
                // notification never claims a connection that does not exist.
                ConnectionService.update(context, (if (adding == null) host else prefs.getString("host", "").orEmpty()).ifBlank { "your computer" }, retrying = false)
                // The computer is the only place a phone's crash can be read later.
                CrashReporter.pending(context)?.let { report ->
                    client.report(report)
                    CrashReporter.clear(context)
                }
                // A drop long enough to have been noticed deserves the all-clear, so a
                // phone in a pocket learns the computer is back without being opened. Only
                // when the app is not on screen, where the ring already says it.
                val outage = if (vm.retryingSince == 0L) 0L else System.currentTimeMillis() - vm.retryingSince
                if (outage >= OUTAGE_NOTE_MS && !Foreground.visible) {
                    Notifier.postPlain(
                        context,
                        "Your computer is back",
                        "${host.ifBlank { "The computer" }} is answering again after ${humanGap(outage)}.",
                    )
                }
                vm.serviceUp = true
                vm.retryingSince = 0L
                // Re-attach after a reconnect, so a dropped tunnel does not silently
                // leave the feed frozen.
                openSession?.let { if (vm.fleet.clientFor(it) === client) client.attach(it.id) }
            }
            is Connection.Retrying -> {
                // The service stays up through a drop - it is what keeps the retry loop
                // alive when the app is in the background - but its line changes to say so.
                // After half an hour of nothing it stops and says so, rather than spend the
                // night polling a computer that is asleep.
                val now = System.currentTimeMillis()
                if (vm.retryingSince == 0L) vm.retryingSince = now
                if (vm.serviceUp && now - vm.retryingSince > RETRY_GIVE_UP_MS) {
                    ConnectionService.stop(context)
                    vm.serviceUp = false
                    Notifier.postPlain(context, "Porthole stopped trying", "The computer has been unreachable for 30 minutes. Open the app to reconnect.")
                } else if (vm.serviceUp) {
                    ConnectionService.update(context, host.ifBlank { "your computer" }, retrying = true)
                }
                // A blip while walking between rooms should not throw up a failure card;
                // a daemon that is actually gone must not hide behind a stale session
                // list. Three attempts is about seven seconds.
                if ((route == Route.Sessions || route == Route.Session || route == Route.Welcome) &&
                    failureCardOnRetry(c.attempt, inSession = route == Route.Session, onList = route == Route.Sessions, listHasRows = sessions.isNotEmpty())
                ) {
                    route = Route.Failure
                }
            }
            is Connection.Failed -> {
                ConnectionService.stop(context)
                vm.serviceUp = false
                if (route == Route.Pair && c.reason == Failure.BadCode) {
                    pairError = c.detail
                } else if (route == Route.Sessions || route == Route.Session || route == Route.Welcome) {
                    route = Route.Failure
                }
            }
            else -> Unit
        }
    }

    fun ringOf(c: Connection): RingState = when (c) {
        is Connection.Live -> RingState.Live
        is Connection.Connecting -> RingState.Connecting
        is Connection.Retrying -> RingState.Retrying
        is Connection.Failed -> RingState.Dropped
        else -> RingState.Idle
    }
    val activeDaemon = (activeConn as? Connection.Live)?.daemon
    val ring = when (connection) {
        is Connection.Live -> RingState.Live
        is Connection.Connecting -> RingState.Connecting
        is Connection.Retrying -> RingState.Retrying
        is Connection.Failed -> RingState.Dropped
        else -> RingState.Idle
    }
    val daemon = (connection as? Connection.Live)?.daemon
    // What the banner offers: a newer Porthole release from GitHub first, otherwise the
    // newest build of a project published on the computer, unless it was put away.
    // Builds of Porthole itself on the computer are not offered - Porthole updates from
    // its releases, so every install runs the same signed APK. Progress while the APK
    // streams in, a note on failure.
    var updateProgress by remember { mutableStateOf<Float?>(null) }
    var updateNote by remember { mutableStateOf<String?>(null) }
    var dismissedBuilds by remember { mutableStateOf(prefs.getStringSet("dismissed_builds", emptySet()) ?: emptySet()) }
    val updateScope = rememberCoroutineScope()
    var release by remember { mutableStateOf(Releases.remembered(prefs)) }
    var releaseChecking by remember { mutableStateOf(false) }
    var releaseNote by remember { mutableStateOf<String?>(null) }
    var releaseChecks by remember { mutableStateOf(Releases.enabled(prefs)) }
    fun checkRelease(force: Boolean) {
        if (!BuildConfig.SELF_UPDATE || releaseChecking) return
        if (!force && (!releaseChecks || !Releases.due(Releases.checkedAt(prefs), System.currentTimeMillis()))) return
        releaseChecking = true
        releaseNote = null
        updateScope.launch {
            runCatching { Releases.fetchLatest() }
                .onSuccess { r -> release = r; Releases.remember(prefs, r, System.currentTimeMillis()) }
                .onFailure { releaseNote = "Could not reach GitHub: ${it.message ?: "no answer"}" }
            releaseChecking = false
        }
    }
    // Once a day at most, when the app is opened or comes back to the screen.
    val onScreen by Foreground.state.collectAsState()
    LaunchedEffect(onScreen, releaseChecks) { if (onScreen) checkRelease(force = false) }
    val latestRelease = if (releaseChecks) Releases.offer(release, BuildConfig.VERSION_NAME) else null
    val offeredBuild = latestRelease?.takeIf { "${it.app}:${it.version}" !in dismissedBuilds }
        ?: daemon?.builds?.firstOrNull { !it.isPorthole && "${it.app}:${it.version}" !in dismissedBuilds }
    fun dismissBuild(b: BuildInfo) {
        dismissedBuilds = dismissedBuilds + "${b.app}:${b.version}"
        prefs.edit().putStringSet("dismissed_builds", dismissedBuilds).apply()
    }
    fun installBuild(b: BuildInfo) {
        if (!Updater.canInstall(context)) {
            // Android's one-time consent lives in system settings; come back and tap again.
            updateNote = "Allow Porthole to install apps, then tap again."
            Updater.openInstallSetting(context)
            return
        }
        if (updateProgress != null) return
        updateNote = null
        updateProgress = 0f
        updateScope.launch {
            runCatching {
                val dest = java.io.File(context.cacheDir, "builds/${b.app}-${b.version}.apk")
                Updater.download(Updater.source(host, b), dest) { done, total ->
                    updateProgress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                }
                Updater.install(context, dest)
                if (!b.isPorthole) dismissBuild(b) // offered once; a newer version brings it back
            }.onFailure { updateNote = "Could not download: ${it.message ?: "unknown error"}" }
            updateProgress = null
        }
    }
    fun startUpdate() { offeredBuild?.let { installBuild(it) } }

    // Hardware back walks the same graph the arrows do.
    BackHandler(enabled = route != Route.Welcome && route != Route.Sessions && route != Route.Failure) {
        route = when (route) {
            Route.Needs -> Route.Welcome
            Route.Tailscale -> Route.Needs
            Route.Setup -> Route.Tailscale
            Route.Connect -> if (adding != null) { cancelAdd(); Route.Settings } else Route.Setup
            Route.Consent -> Route.Connect
            Route.Pair -> { pairNote = null; (pairBackTo ?: Route.Consent).also { pairBackTo = null } }
            Route.Tour -> if (paired) Route.Sessions else Route.Welcome
            Route.Session -> { closeSession(); Route.Sessions }
            Route.Settings -> Route.Sessions
            Route.SettingsSetup, Route.SettingsTour, Route.SettingsLicences -> Route.Settings
            Route.Failsafe -> { vm.ssh.close(); failsafeFrom }
            else -> route
        }
    }

    // System-bar insets are applied once, here, so no screen can forget them and put
    // content under the status bar.
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        RouteTransition(target = route, forward = forward) { r ->
            when (r) {
                Route.Welcome -> WelcomeScreen(
                    onStart = { route = Route.Needs },
                    onHowItWorks = { route = Route.Tour },
                )

                Route.Needs -> NeedsScreen(
                    os = computerOs,
                    onOs = { chooseOs(it) },
                    onContinue = { route = if (tsInstalled) Route.Setup else Route.Tailscale },
                    onBack = { route = Route.Welcome },
                )

                Route.Tailscale -> TailscaleScreen(
                    installed = tsInstalled,
                    onOpenStore = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TAILSCALE_PKG"))
                            )
                        }.onFailure {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://play.google.com/store/apps/details?id=$TAILSCALE_PKG")
                                )
                            )
                        }
                    },
                    onOpenApp = { openTailscale() },
                    onRecheck = {
                        // The user asserts Tailscale is installed. Trust it and move on - the
                        // Connect screen's reachability check is the real validator.
                        tsInstalled = context.tailscaleInstalled()
                        route = Route.Setup
                    },
                    onContinue = { route = Route.Setup },
                    onBack = { route = Route.Needs },
                )

                Route.Setup -> SetupScreen(
                    os = computerOs ?: ComputerOs.Mac,
                    onOs = { chooseOs(it) },
                    onContinue = { route = Route.Connect },
                    onBack = { route = if (tsInstalled) Route.Needs else Route.Tailscale },
                )

                Route.Connect -> ConnectScreen(
                    host = host,
                    onHostChange = { host = it; reachable = false; probeResult = null },
                    checking = checking,
                    result = probeResult,
                    reachable = reachable,
                    onCheck = {
                        if (adding != null && machines.any { it.id == host || it.host == host }) {
                            probeResult = "This phone is already paired with that computer."
                            reachable = false
                            return@ConnectScreen
                        }
                        checking = true
                        scope.launch {
                            val res = pairing.probe(host)
                            checking = false
                            reachable = res.isSuccess
                            probeCaps = res.getOrNull() ?: emptyList()
                            probeResult = if (res.isSuccess) {
                                "Found Porthole on $host"
                            } else {
                                "No answer from $host. Is Tailscale connected and portholed running?"
                            }
                        }
                    },
                    onContinue = { route = Route.Consent },
                    onBack = { if (adding != null) { cancelAdd(); route = Route.Settings } else route = Route.Setup },
                )

                Route.Consent -> ConsentScreen(
                    machine = host,
                    onCancel = { route = Route.Connect },
                    onAuthorize = { route = Route.Pair },
                    caps = probeCaps,
                )

                Route.Pair -> PairScreen(
                    code = code,
                    onCodeChange = { code = it; pairError = null },
                    host = host,
                    error = pairError,
                    connecting = pairConn is Connection.Connecting,
                    onPair = { pairing.connect(host, code) },
                    onBack = { pairNote = null; route = pairBackTo ?: Route.Consent; pairBackTo = null },
                    note = pairNote,
                    onScan = {
                        // Google's code scanner: no camera permission, the module arrives
                        // through Play services. Where it cannot, the code is still typed.
                        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
                        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options).startScan()
                            .addOnSuccessListener { b ->
                                val link = b.rawValue?.let { dev.shrimpscript.porthole.net.PairLink.parse(it) }
                                if (link == null) pairError = "That is not a Porthole pairing code."
                                else { host = link.host; code = link.code; pairError = null; pairing.connect(link.host, link.code) }
                            }
                            .addOnFailureListener { pairError = "Scanning is not available on this phone. Type the code instead." }
                    },
                )

                Route.Tour, Route.SettingsTour -> TourScreen(
                    onDone = {
                        if (!tourSeen) { tourSeen = true; prefs.edit().putBoolean("tour_seen", true).apply() }
                        route = when {
                            r == Route.SettingsTour -> Route.Settings
                            connection is Connection.Live -> Route.Sessions
                            else -> Route.Welcome
                        }
                    },
                    onSkip = if (r == Route.Tour && connection is Connection.Live) {
                        { tourSeen = true; prefs.edit().putBoolean("tour_seen", true).apply(); route = Route.Sessions }
                    } else if (r == Route.Tour) {
                        { route = Route.Welcome }
                    } else null,
                )

                Route.Sessions -> {
                if (showNewSession) NewSessionSheet(
                    projects = projectsOf(sessions),
                    machines = if (machines.size > 1) machines.map { it.id to it.shown } else listOf("" to (daemon?.host?.ifBlank { null } ?: "this computer")),
                    starting = newStart,
                    note = newNote,
                    onStart = { machineId, cwd -> newNote = null; newStart = "$machineId|$cwd" },
                    onDismiss = {
                        showNewSession = false
                        newNote = null
                        // Closed while Claude Code asks about the folder: stop waiting; the
                        // question stays on the computer, for the desk.
                        if (newTrust != null) { newTrust = null; newStart = null }
                    },
                    trust = newTrust,
                    onTrust = { yes ->
                        val ask = newTrust
                        val machineId = newStart?.substringBefore('|').orEmpty()
                        newTrust = null
                        if (ask != null) clientOf(machineId).answerTrust(ask.pane, yes)
                        if (!yes) {
                            // Stops the wait; the computer quits Claude Code and removes the session.
                            newStart = null
                            newNote = "Cancelled. Nothing was started in ${shortPath(ask?.cwd.orEmpty())}."
                        }
                    },
                )
                SessionsScreen(
                    // One computer, unreachable: its last list stays, under the bar. Several
                    // say so per computer in the list itself.
                    reconnecting = if (machines.size <= 1 && listDown && connection !is Connection.Live && connection !is Connection.Failed)
                        "Reconnecting to ${daemon?.host?.ifBlank { null } ?: host.ifBlank { "your computer" }}…" else null,
                    onConnectionOptions = if (listAttempt >= 3) ({ route = Route.Failure }) else null,
                    machine = if (machines.size > 1) "${machines.size} computers" else daemon?.host?.ifBlank { null } ?: host.ifBlank { "your computer" },
                    computers = if (machines.size > 1) "${machines.count { fleetConns[it.id] is Connection.Live || (it.id == machines.first().id && connection is Connection.Live) }} of ${machines.size} connected" else "",
                    ring = ring,
                    // One computer, unreachable for a while: the rows stay where they are, each
                    // reading "as last seen", rather than claiming work nobody can see now.
                    sessions = when {
                        demoEmpty -> emptyList()
                        machines.size <= 1 && listDown && connection !is Connection.Live -> sessions.map { it.copy(machineDown = true, machineState = "reconnecting") }
                        else -> sessions
                    },
                    onSession = { openSession(it) },
                    onSettings = { failsafeNote = null; route = Route.Settings },
                    onRefresh = { vm.fleet.refreshAll() },
                    seen = seenStamps,
                    update = offeredBuild,
                    updateProgress = updateProgress,
                    updateNote = updateNote,
                    onUpdate = { startUpdate() },
                    onDismissUpdate = { offeredBuild?.let { dismissBuild(it) } },
                    onNewSession = if ("start" in (daemon?.caps ?: emptyList())) ({ newNote = null; showNewSession = true }) else null,
                )
                }

                Route.Session -> {
                    val s = openSession
                    if (s == null) {
                        route = Route.Sessions
                    } else {
                        val sc = vm.fleet.clientFor(s)
                        SessionScreen(
                            title = s.title,
                            branch = listOf(s.branch, if (s.live && s.tmuxName.isNotBlank()) "tmux ${s.tmuxName}" else "").filter { it.isNotBlank() }.joinToString(" · "),
                            ring = ringOf(activeConn),
                            live = s.live,
                            tmux = s.tmux,
                            canStart = "start" in (activeDaemon?.caps ?: emptyList()),
                            starting = startingId == s.id,
                            onStart = { mode -> startingId = s.id; sc.startSession(s.id, mode) },
                            rows = rows,
                            sharedKey = s.id,
                            backfillCount = backfillCount,
                            loaded = attached == s.id,
                            remaining = remaining,
                            earlierEpoch = earlierEpoch,
                            loadedEpoch = loadedEpoch,
                            onEarlier = { sc.loadEarlier() },
                            // A prompt is typed into the session's tmux window, so a session
                            // with no live terminal genuinely cannot receive one.
                            canSend = s.live && activeConn is Connection.Live,
                            onAnswer = { a -> sc.answer(s.id, a.option, a.text, a.advance, a.submit) },
                            changes = changes?.takeIf { it.sessionId == s.id },
                            onChangesRefresh = { sc.getChanges(s.id) },
                            files = files?.takeIf { it.sessionId == s.id },
                            failedSend = failedSend?.takeIf { it.sessionId == s.id },
                            onFailedShown = { sc.clearFailedSend() },
                            onFiles = { q -> sc.getFiles(s.id, q) },
                            quickReplies = quickReplies,
                            onQuickReplies = { quickReplies = it; prefs.edit().putString("quick_replies", it.joinToString("\n")).apply() },
                            onSend = { text ->
                                sc.sendPrompt(s.id, text)
                                // A slash command whose answer is printed by the CLI is
                                // only visible in the terminal, so go there.
                                val cmd = CLI_COMMANDS.firstOrNull { it.name == text.substringBefore(' ') }
                                if (cmd?.terminal == true) sessionView = SessionView.Terminal
                            },
                            onBack = { closeSession() },
                            view = sessionView,
                            onViewChange = { sessionView = it },
                            terminal = sc.terminal,
                            terminalRevision = termRevision,
                            terminalOpen = termOpen && termSession == s.id,
                            // 80x24 is a placeholder: the daemon reports the desktop's real
                            // size on the first frame and the emulator resizes to it.
                            onOpenTerminal = { sc.openTerminal(s.id, 80, 24) },
                            onTerminalKeys = { sc.sendKeys(it) },
                            fontSp = fontSp,
                            onFontSp = { fontSp = it; prefs.edit().putFloat("term_font", it).apply() },
                            fit = fit,
                            onFit = { fit = it; prefs.edit().putBoolean("term_fit", it).apply() },
                            notice = notice,
                            onDismissNotice = { sc.clearNotice() },
                            state = sessionState,
                            status = tuiStatus,
                            agents = sessionAgents,
                            initialDraft = sc.store?.draft(s.id).orEmpty(),
                            onDraft = { sc.store?.setDraft(s.id, it) },
                            pendingSends = sendingNow.values.filter { it.sessionId == s.id },
                            confirmedSends = sc.confirmed,
                            goneImages = goneImages,
                            returnedSends = sc.returned,
                            reconnecting = if (showDown && activeConn !is Connection.Live && activeConn !is Connection.Failed)
                                "Reconnecting to ${s.machine.ifBlank { activeDaemon?.host?.ifBlank { null } ?: host.ifBlank { "your computer" } }}…" else null,
                            savedCopyAt = savedCopy,
                            // The failure card is the first computer's (its retry, its SSH): offered
                            // for that computer's sessions only.
                            onConnectionOptions = if (downAttempt >= 3 && active === client) ({ route = Route.Failure }) else null,
                            onInterrupt = { sc.interrupt(s.id) },
                            onCommand = { cmd ->
                                sc.sendPrompt(s.id, cmd.name)
                                if (cmd.terminal) sessionView = SessionView.Terminal
                            },
                            onKey = { key -> sc.sendKey(s.id, key) },
                            autoContinue = activeDaemon?.autoContinue ?: true,
                            images = images,
                            clips = clips,
                            onNeedImage = { ref -> sc.requestImage(s.id, ref) },
                            caps = activeDaemon?.caps ?: emptyList(),
                            onSwitchAccount = if ("account" in (activeDaemon?.caps ?: emptyList())) ({ openAccount(sc) }) else null,
                            onCapture = { secs -> if (secs == 0) sc.captureStill() else sc.captureClip(secs) },
                            hostLabel = openSession?.let { vm.fleet.hostOf(it) } ?: host.substringBeforeLast(':'),
                            preview = preview,
                            onPreviewRefresh = { sc.previewList() },
                            onPreviewOpen = { previewPath = "/"; sc.previewOpen(it) },
                            onLocalLink = { port, path -> previewPath = path; sc.previewOpen(port) },
                            onPreviewClose = { sc.previewClose(it) },
                            onSendWithImages = { text, atts -> sc.sendPrompt(s.id, text, atts) },
                            lastSeen = openedSeenAt,
                            onTerminalScroll = { lines -> sc.scrollTerminal(lines) },
                            muted = s.id in mutedIds,
                            onMuted = { m ->
                                mutedIds = Mutes.set(context, s.id, m)
                                // Silence means now, not next time: whatever this session
                                // already put in the status bar goes with it.
                                if (m) { Notifier.cancel(context, s.id); Notifier.cancelQuestion(context, s.id) }
                            },
                        )
                    }
                }

                Route.Settings -> SettingsScreen(
                    quickReplies = quickReplies,
                    onQuickReplies = { quickReplies = it; prefs.edit().putString("quick_replies", it.joinToString("\n")).apply() },
                    host = host,
                    machines = machines,
                    machineStates = fleetConns.mapValues { dev.shrimpscript.porthole.net.Fleet.stateWord(it.value) },
                    onAddMachine = {
                        adding = vm.fleet.newPairingClient()
                        host = ""; code = ""; reachable = false; probeResult = null; pairError = null
                        route = Route.Connect
                    },
                    onForgetMachine = { id -> vm.fleet.remove(id) },
                    mutedCount = mutedIds.size,
                    onUnmuteAll = { Mutes.clear(context); mutedIds = emptySet() },
                    canAccount = daemon?.caps?.contains("account") == true,
                    account = claudeAccount,
                    onSwitchAccount = { openAccount(client) },
                    onSignOut = { client.signOut() },
                    onOpenShell = { openFailsafeShell(Route.Settings) },
                    sshNote = failsafeNote,
                    failsafe = failsafe,
                    canAddKey = daemon?.caps?.contains("ssh_key") == true,
                    daemonOs = daemon?.os ?: "",
                    keyNote = keyNote ?: failsafeKeyError,
                    onAddKey = {
                        keyNote = null
                        scope.launch {
                            // The first key is made in the Keystore, which can take a moment.
                            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { vm.failsafeKey.publicLine() } }
                                .onSuccess { client.setFailsafeKey(it) }
                                .onFailure { keyNote = "Could not make a key on this phone: ${it.message}" }
                        }
                    },
                    onRemoveKey = { keyNote = null; client.setFailsafeKey(null) },
                    deviceName = daemon?.deviceName ?: "",
                    daemonVersion = daemon?.version ?: "",
                    appVersion = BuildConfig.VERSION_NAME,
                    autoContinue = daemon?.autoContinue ?: true,
                    release = latestRelease,
                    releaseChecks = releaseChecks,
                    onReleaseChecks = { on -> releaseChecks = on; Releases.setEnabled(prefs, on); if (on) checkRelease(force = true) },
                    releaseCheckedAt = if (releaseChecking) -1L else Releases.checkedAt(prefs),
                    releaseNote = releaseNote,
                    onCheckRelease = { checkRelease(force = true) },
                    builds = daemon?.builds ?: emptyList(),
                    onUpdate = { route = Route.Sessions; latestRelease?.let { installBuild(it) } },
                    onInstall = { b -> route = Route.Sessions; installBuild(b) },
                    notifyDone = prefs.getBoolean("notify_done", true),
                    onNotifyDone = { prefs.edit().putBoolean("notify_done", it).apply() },
                    theme = themeChoice.value,
                    appearance = themeAppearance.value,
                    onTheme = { themeChoice.value = it; prefs.edit().putString("theme", it.pref).apply(); (context as? MainActivity)?.applyNightMode(it, themeAppearance.value) },
                    onAppearance = { themeAppearance.value = it; prefs.edit().putString("appearance", it.pref).apply(); (context as? MainActivity)?.applyNightMode(themeChoice.value, it) },
                    fontSp = fontSp,
                    onFontSp = { fontSp = it; prefs.edit().putFloat("term_font", it).apply() },
                    onGuide = { route = Route.SettingsTour },
                    onSetup = { route = Route.SettingsSetup },
                    onLicences = { route = Route.SettingsLicences },
                    onUnpair = {
                        client.disconnect()
                        ConnectionService.stop(context)
                        prefs.edit().clear().apply()
                        vm.fleet.mirrorPrefs("", false)
                        vm.clearWidget()
                        openSession = null
                        host = ""
                        code = ""
                        route = Route.Welcome
                    },
                    onBack = { route = Route.Sessions },
                )

                Route.SettingsSetup -> SetupScreen(
                    // A connected computer says what it is; setting up another starts from the last choice.
                    os = computerOs ?: daemon?.os?.let { ComputerOs.fromDaemon(it) } ?: ComputerOs.Mac,
                    onOs = { chooseOs(it) },
                    onContinue = { route = Route.Settings },
                    onBack = { route = Route.Settings },
                    fromSettings = true,
                )

                Route.SettingsLicences -> LicencesScreen(onBack = { route = Route.Settings })

                Route.Failure -> {
                    // The card is shown while retrying too, so it must read its reason from
                    // whichever state is current.
                    val retrying = connection as? Connection.Retrying
                    val failed = connection as? Connection.Failed
                    FailureScreen(
                        reason = failed?.reason ?: Failure.Unreachable,
                        detail = failed?.detail ?: retrying?.detail ?: "",
                        retrying = retrying != null,
                        machine = host.ifBlank { "your computer" },
                        onRetry = {
                            if (failed?.reason == Failure.NeedsReauth || failed?.reason == Failure.NotTailnet) openTailscale()
                            client.connect(host)
                        },
                        onRepair = { code = ""; pairError = null; route = Route.Pair },
                        busy = failsafeNote,
                        onOpenSsh = { openFailsafeShell(Route.Failure) },
                        onRestartDaemon = {
                            vm.ssh.debugPort = sshDebugPort
                            failsafeNote = "Restarting portholed over SSH…"
                            scope.launch {
                                val res = vm.ssh.runCommand(
                                    host, sshUser,
                                    // A daemon too old to say how falls back to the systemd way.
                                    restartCmd.ifBlank { "systemctl --user restart portholed || portholed serve >/dev/null 2>&1 &" },
                                )
                                failsafeNote = if (res.isSuccess) {
                                    client.connect(host)
                                    "Restarted. Reconnecting…"
                                } else {
                                    "Could not restart: ${res.exceptionOrNull()?.message}"
                                }
                            }
                        },
                    )
                }

                Route.Failsafe -> FailsafeScreen(
                    machine = host.ifBlank { "your computer" },
                    terminal = vm.sshTerminal,
                    revision = sshRevision,
                    onKeys = { vm.ssh.send(it) },
                    onResize = { cols, rows -> vm.ssh.resize(cols, rows) },
                    fontSp = fontSp,
                    closed = sshClosed,
                    onBack = { vm.ssh.close(); failsafeNote = null; route = failsafeFrom },
                    daemonDown = failsafeFrom != Route.Settings,
                )
            }
        }

        accountClient?.let { ac ->
            val signIn by ac.signIn.collectAsState()
            val restarts by ac.restarts.collectAsState()
            val acSessions by ac.sessions.collectAsState()
            val acConn by ac.connection.collectAsState()
            AccountSheet(
                machine = (acConn as? Connection.Live)?.daemon?.host?.ifBlank { null } ?: host.ifBlank { "the computer" },
                signIn = signIn,
                sessions = acSessions,
                restarts = restarts,
                onCode = { ac.sendSignInCode(it) },
                onRetry = { ac.startSignIn() },
                // A session waiting out a usage limit is told to stop waiting by the
                // computer, when its restart comes up.
                onRestart = { ids -> ac.restartSessions(ids) },
                onCancelRestart = { ac.cancelRestart(it) },
                onClose = { ac.closeSignIn(); accountClient = null },
            )
        }

        // An approval can arrive on any screen - it is the reason the app exists, so it
        // is drawn above whatever route is showing rather than only inside a session.
        // The last one is remembered so the card can fade out with its content intact.
        var shown by remember { mutableStateOf<dev.shrimpscript.porthole.net.FleetApproval?>(null) }
        approval?.let { shown = it }
        AnimatedVisibility(
            visible = approval != null,
            enter = fadeIn(spec(PortholeMotion.SLOW_MS)),
            exit = fadeOut(spec(PortholeMotion.EXIT_MS, PortholeMotion.leave)),
        ) {
            shown?.let { fa ->
                val a = fa.approval
                val ac = vm.fleet.client(fa.machineId)
                ApprovalOverlay(
                    approval = a,
                    onAllow = { ac.decide(a.toolUseId, allow = true) },
                    onDeny = { ac.decide(a.toolUseId, allow = false, reason = "Denied from phone") },
                    onExpired = { ac.expireApproval(a.toolUseId) },
                )
            }
        }
    }
}
