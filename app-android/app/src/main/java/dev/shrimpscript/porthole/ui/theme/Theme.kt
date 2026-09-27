package dev.shrimpscript.porthole.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shrimpscript.porthole.R

/**
 * The design tokens, as code. Values here are decisions, not preferences: change them
 * deliberately, here and nowhere else.
 *
 * One palette at a time, and chat and terminal share its ground so that swiping between
 * them never flashes a different theme. The Porthole palette is the brand; the Claude
 * palettes reproduce claude.ai and the Claude app (Anthropic's published swatches, plus
 * grounds sampled from the app's screens).
 */
@Immutable
data class PortholeColors(
    val ground: Color = Color(0xFF07100F),
    val surface: Color = Color(0xFF0E1A19),
    val raised: Color = Color(0xFF162523),
    val edge: Color = Color(0xFF22322F),
    val text: Color = Color(0xFFE8F0EE),
    val muted: Color = Color(0xFF8FA3A0),
    val faint: Color = Color(0xFF7B908C),   // lifted from #5F726F to clear AA on `raised`
    val accent: Color = Color(0xFF3FD4C0),
    val warn: Color = Color(0xFFE8B54A),
    val bad: Color = Color(0xFFF2685E),
    val ok: Color = Color(0xFF5FCF80),
    /** Text on a filled accent/warn/bad surface. Porthole: `ground` - white on bad is 2.62. */
    val onAccent: Color = Color(0xFF07100F),
    /** The deepest ground: the terminal pane and full-screen media viewers. */
    val deep: Color = Color(0xFF050B0A),
    /** True when the ground is light, so system bar icons and the terminal default go dark. */
    val isLight: Boolean = false,
) {
    /** The modal scrim: 80% ground. */
    val scrim: Color get() = ground.copy(alpha = 0.8f)

    companion object {
        val Porthole = PortholeColors()

        /**
         * Claude, dark. Ground and surfaces from the Claude app's dark appearance; accent
         * is Anthropic's `clay`; text is `ivory-light`; muted text `cloud-dark`.
         */
        val ClaudeDark = PortholeColors(
            ground = Color(0xFF1F1E1D),
            surface = Color(0xFF262624),
            raised = Color(0xFF30302E),
            edge = Color(0xFF3D3D3A),
            text = Color(0xFFFAF9F5),
            muted = Color(0xFFB0AEA5),
            faint = Color(0xFF87867F),
            accent = Color(0xFFD97757),
            warn = Color(0xFFD4A27F),
            bad = Color(0xFFE5807A),
            ok = Color(0xFF9DB07A),
            onAccent = Color(0xFFFAF9F5),
            deep = Color(0xFF141413),
            isLight = false,
        )

        /**
         * Claude, light. `ivory-light` ground, `ivory-medium` cards, `ivory-dark` edges,
         * `slate-dark` text, `slate-light`/`cloud-dark` muted. Accent text uses the darker
         * `accent` swatch (#C6613F) that anthropic.com uses for links, since clay on ivory
         * is only 2.9:1; filled buttons still use clay with ivory on top, as the app does.
         */
        /**
         * Gemini, dark. Google's own Material tokens as gemini.google.com ships them:
         * surface #131314, containers #1E1F20 / #282A2C, outline #444746, on-surface
         * #E3E3E3 / #C4C7C5, primary #A8C7FA with #062E6F on it. Error is M3 error 80.
         */
        val GeminiDark = PortholeColors(
            ground = Color(0xFF131314),
            surface = Color(0xFF1E1F20),
            raised = Color(0xFF282A2C),
            edge = Color(0xFF444746),
            text = Color(0xFFE3E3E3),
            muted = Color(0xFFC4C7C5),
            faint = Color(0xFF8E918F),
            accent = Color(0xFFA8C7FA),
            warn = Color(0xFFE6C36A),
            bad = Color(0xFFF2B8B5),
            ok = Color(0xFF6DD58C),
            onAccent = Color(0xFF062E6F),
            deep = Color(0xFF0E0E0F),
            isLight = false,
        )

        /**
         * Gemini, light. White chat ground, #F0F4F9 surfaces and #E9EEF6 bubbles (the
         * app's home and composer), #1F1F1F / #444746 text, primary #0B57D0 with white on
         * it. Green is Google's 800 rather than the app's 700, which is 4.2:1 on white.
         */
        val GeminiLight = PortholeColors(
            ground = Color(0xFFFFFFFF),
            surface = Color(0xFFF0F4F9),
            raised = Color(0xFFE9EEF6),
            edge = Color(0xFFC4C7C5),
            text = Color(0xFF1F1F1F),
            muted = Color(0xFF444746),
            faint = Color(0xFF5F6368),
            accent = Color(0xFF0B57D0),
            warn = Color(0xFF8A5A00),
            bad = Color(0xFFB3261E),
            ok = Color(0xFF137333),
            onAccent = Color(0xFFFFFFFF),
            deep = Color(0xFFFFFFFF),
            isLight = true,
        )

        val ClaudeLight = PortholeColors(
            ground = Color(0xFFFAF9F5),
            surface = Color(0xFFF0EEE6),
            raised = Color(0xFFE8E6DC),
            edge = Color(0xFFD1CFC5),
            text = Color(0xFF141413),
            muted = Color(0xFF5E5D59),
            faint = Color(0xFF6E6D67),
            accent = Color(0xFFC6613F),
            warn = Color(0xFFA8712E),
            bad = Color(0xFFB4433A),
            ok = Color(0xFF5F7A3D),
            onAccent = Color(0xFFFAF9F5),
            deep = Color(0xFFFFFFFF),
            isLight = true,
        )
    }
}

/** Which family of palettes. */
enum class ThemeChoice(val pref: String) {
    Porthole("porthole"), Claude("claude"), Gemini("gemini");
    /** Whether this family has a light and a dark appearance. */
    val hasAppearance: Boolean get() = this != Porthole
    companion object { fun of(pref: String?) = entries.firstOrNull { it.pref == pref } ?: Porthole }
}

/** Light or dark for a family that has both. Porthole is dark only. */
enum class Appearance(val pref: String) {
    System("system"), Light("light"), Dark("dark");
    companion object { fun of(pref: String?) = entries.firstOrNull { it.pref == pref } ?: System }
}

fun paletteFor(choice: ThemeChoice, appearance: Appearance, systemDark: Boolean): PortholeColors =
    when (choice) {
        ThemeChoice.Porthole -> PortholeColors.Porthole
        ThemeChoice.Claude -> when (appearance) {
            Appearance.Light -> PortholeColors.ClaudeLight
            Appearance.Dark -> PortholeColors.ClaudeDark
            Appearance.System -> if (systemDark) PortholeColors.ClaudeDark else PortholeColors.ClaudeLight
        }
        ThemeChoice.Gemini -> when (appearance) {
            Appearance.Light -> PortholeColors.GeminiLight
            Appearance.Dark -> PortholeColors.GeminiDark
            Appearance.System -> if (systemDark) PortholeColors.GeminiDark else PortholeColors.GeminiLight
        }
    }

private fun lerp(a: PortholeColors, b: PortholeColors, t: Float): PortholeColors =
    if (t <= 0f) a else if (t >= 1f) b else PortholeColors(
        ground = androidx.compose.ui.graphics.lerp(a.ground, b.ground, t),
        surface = androidx.compose.ui.graphics.lerp(a.surface, b.surface, t),
        raised = androidx.compose.ui.graphics.lerp(a.raised, b.raised, t),
        edge = androidx.compose.ui.graphics.lerp(a.edge, b.edge, t),
        text = androidx.compose.ui.graphics.lerp(a.text, b.text, t),
        muted = androidx.compose.ui.graphics.lerp(a.muted, b.muted, t),
        faint = androidx.compose.ui.graphics.lerp(a.faint, b.faint, t),
        accent = androidx.compose.ui.graphics.lerp(a.accent, b.accent, t),
        warn = androidx.compose.ui.graphics.lerp(a.warn, b.warn, t),
        bad = androidx.compose.ui.graphics.lerp(a.bad, b.bad, t),
        ok = androidx.compose.ui.graphics.lerp(a.ok, b.ok, t),
        onAccent = androidx.compose.ui.graphics.lerp(a.onAccent, b.onAccent, t),
        deep = androidx.compose.ui.graphics.lerp(a.deep, b.deep, t),
        isLight = if (t < 0.5f) a.isLight else b.isLight,
    )

@Immutable
data class PortholeSpacing(
    val base: Int = 4,
    val screen: Int = 16,
    val rowInner: Int = 12,
    val rowGap: Int = 8,
    val touchTarget: Int = 44,
)

val LocalPortholeColors = staticCompositionLocalOf { PortholeColors() }
val LocalPortholeSpacing = staticCompositionLocalOf { PortholeSpacing() }

private val Schibsted = FontFamily(
    Font(R.font.schibsted_grotesk_regular, FontWeight.Normal),
    Font(R.font.schibsted_grotesk_medium, FontWeight.Medium),
    Font(R.font.schibsted_grotesk_semibold, FontWeight.SemiBold),
)

/**
 * Iosevka Term is the terminal's face (bundled in res/font, subset to the ranges a
 * terminal needs, and loaded by TerminalView), chosen on a measurement: 500/1000 em
 * advance against 600 for JetBrains Mono, i.e. 60.8 columns at 13sp instead of 50.6.
 * Compose text outside the terminal (a pairing code, a command to copy) uses the system
 * monospace.
 */
val PortholeMono = FontFamily.Monospace

/**
 * Source Serif 4 stands in for the Claude app's serif (Tiempos Text / Anthropic Serif,
 * both proprietary): a transitional text face with the same colour on the page. Static
 * Regular and SemiBold instances at opsz 16, subset to Latin (OFL, 63KB each), and
 * renamed Porthole Serif because the licence reserves the name "Source" for unmodified
 * copies.
 */
private val SourceSerif = FontFamily(
    Font(R.font.source_serif4_regular, FontWeight.Normal),
    Font(R.font.source_serif4_semibold, FontWeight.SemiBold),
)

/**
 * The faces a theme is allowed to change. Chrome (rows, buttons, chips, meta) stays
 * Schibsted in every theme, as the Claude app keeps its own sans for chrome; what the
 * Claude theme swaps is the voice - Claude's replies and the big display lines.
 */
/**
 * Google Sans Flex is the Gemini app's own face, open under the OFL since 2025. Static
 * Regular and SemiBold instances at opsz 16, default width, grade and roundness, Latin
 * subset, 66KB each.
 */
private val GoogleSans = FontFamily(
    Font(R.font.google_sans_flex_regular, FontWeight.Normal),
    Font(R.font.google_sans_flex_semibold, FontWeight.SemiBold),
)

@Immutable
data class PortholeFonts(val prose: FontFamily, val display: FontFamily) {
    companion object {
        val Porthole = PortholeFonts(prose = Schibsted, display = Schibsted)
        val Claude = PortholeFonts(prose = SourceSerif, display = SourceSerif)
        val Gemini = PortholeFonts(prose = GoogleSans, display = GoogleSans)
    }
}

val LocalPortholeFonts = staticCompositionLocalOf { PortholeFonts.Porthole }

/** [PortholeType] re-cut in a theme's faces. Sizes and line heights never change. */
@Immutable
class PortholeTypeSet(f: PortholeFonts) {
    val display = PortholeType.display.copy(fontFamily = f.display)
    val title = PortholeType.title.copy(fontFamily = f.display)
    /** Assistant text: 16/24 in the serif reads like the Claude app; the sans stays 15/22. */
    val prose = if (f.prose == Schibsted) PortholeType.body
        else PortholeType.body.copy(fontFamily = f.prose, fontSize = 16.sp, lineHeight = 24.sp)
    val proseTitle = PortholeType.title.copy(fontFamily = f.prose)
    val proseHeading = PortholeType.rowTitle.copy(fontFamily = f.prose)
}

/** The type scale. Nothing below 12sp, anywhere. */
object PortholeType {
    val display = TextStyle(fontFamily = Schibsted, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp)
    val title = TextStyle(fontFamily = Schibsted, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)
    val rowTitle = TextStyle(fontFamily = Schibsted, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp)
    val body = TextStyle(fontFamily = Schibsted, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val secondary = TextStyle(fontFamily = Schibsted, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val meta = TextStyle(fontFamily = Schibsted, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
    val mono = TextStyle(fontFamily = PortholeMono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
}

/** Radius: 14 cards, 10 controls, 8 key caps. Zero is reserved for the terminal pane. */
object PortholeShape {
    val card = RoundedCornerShape(14.dp)
    val control = RoundedCornerShape(10.dp)
    val key = RoundedCornerShape(8.dp)
    val pill = RoundedCornerShape(999.dp)
}

/**
 * Motion tokens. Calm and precise, but alive: every duration here is an
 * ease-out with no overshoot, and everything moves for a reason - to show what changed,
 * where something came from, or that the app heard the tap. The ring stays the only
 * decorative motion.
 *
 * Every animation in the app goes through [spec] or [motionEnabled], so a person who
 * turned animations off in system settings gets the same app, instantly.
 */
object PortholeMotion {
    /** Press feedback, toggles, colour changes. */
    const val FAST_MS = 120
    /** An element arriving: a feed row, a notice, a button state. */
    const val ENTER_MS = 200
    /** An element leaving. Exits are always quicker than entrances. */
    const val EXIT_MS = 140
    /** Moving between views: onboarding steps, feed <-> terminal, list -> session. */
    const val AXIS_MS = 260
    /** The big ones: the approval card, a sheet, the welcome choreography. */
    const val SLOW_MS = 360
    /** Gap between siblings entering together. Never more than five staggered items. */
    const val STAGGER_MS = 40
    /** How far an entering element travels, in dp. Small: it settles, it does not fly. */
    const val TRAVEL_DP = 12

    /** Gentle settle - the enter curve. cubic-bezier(0.16, 1, 0.3, 1). */
    val settle: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    /** Standard - moving something that is already on screen. cubic-bezier(0.4, 0, 0.2, 1). */
    val standard: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    /** Exit - quick and unremarkable. cubic-bezier(0.3, 0, 0.7, 0.4). */
    val leave: Easing = CubicBezierEasing(0.3f, 0f, 0.7f, 0.4f)

    // Kept for call sites that predate the token set above.
    const val ENTER_MS_LEGACY = 160
    const val AXIS_MS_LEGACY = 200
}

/**
 * True unless the person disabled animations in system settings. Read once per
 * composition root; with it false, every [spec] is a snap and the ring goes static.
 */
@Composable
fun motionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) != 0f
    }
}

/** A tween on the motion tokens, or an instant snap when animations are off. */
@Composable
fun <T> spec(
    durationMs: Int = PortholeMotion.ENTER_MS,
    easing: Easing = PortholeMotion.settle,
    delayMs: Int = 0,
): FiniteAnimationSpec<T> =
    if (motionEnabled()) tween(durationMs, delayMs, easing) else snap()

@Composable
fun PortholeTheme(
    choice: ThemeChoice = ThemeChoice.Porthole,
    appearance: Appearance = Appearance.System,
    content: @Composable () -> Unit,
) {
    val target = paletteFor(choice, appearance, isSystemInDarkTheme())
    // Switching themes cross-fades every token over SLOW_MS rather than cutting: the
    // picker card is the thing that moved, and the whole app follows it.
    val from = remember { mutableStateOf(target) }
    val shown = remember { mutableStateOf(target) }
    val blend = remember { Animatable(1f) }
    val on = motionEnabled()
    LaunchedEffect(target) {
        if (target == shown.value) return@LaunchedEffect
        from.value = lerp(from.value, shown.value, blend.value)
        shown.value = target
        blend.snapTo(0f)
        if (on) blend.animateTo(1f, tween(PortholeMotion.SLOW_MS, 0, PortholeMotion.standard))
        else blend.snapTo(1f)
    }
    val colors = lerp(from.value, shown.value, blend.value)
    val fonts = when (choice) {
        ThemeChoice.Claude -> PortholeFonts.Claude
        ThemeChoice.Gemini -> PortholeFonts.Gemini
        ThemeChoice.Porthole -> PortholeFonts.Porthole
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalPortholeColors provides colors,
        LocalPortholeSpacing provides PortholeSpacing(),
        LocalPortholeFonts provides fonts,
    ) {
        val scheme = if (colors.isLight) lightColorScheme(
                background = colors.ground,
                surface = colors.surface,
                onBackground = colors.text,
                onSurface = colors.text,
                primary = colors.accent,
                onPrimary = colors.onAccent,
                error = colors.bad,
            ) else darkColorScheme(
                background = colors.ground,
                surface = colors.surface,
                onBackground = colors.text,
                onSurface = colors.text,
                primary = colors.accent,
                onPrimary = colors.onAccent,
                error = colors.bad,
            )
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(
                bodyLarge = PortholeType.body,
                bodyMedium = PortholeType.secondary,
                titleLarge = PortholeType.title,
                labelSmall = PortholeType.meta,
            ),
            content = content,
        )
    }
}

/** Shorthand so screens read `Porthole.colors.accent` rather than a CompositionLocal. */
object Porthole {
    val colors: PortholeColors
        @Composable get() = LocalPortholeColors.current
    val fonts: PortholeFonts
        @Composable get() = LocalPortholeFonts.current
    /** The themed cuts of the scale. `remember`ed per font set; cheap to read anywhere. */
    val type: PortholeTypeSet
        @Composable get() { val f = LocalPortholeFonts.current; return remember(f) { PortholeTypeSet(f) } }
    val spacing: PortholeSpacing
        @Composable get() = LocalPortholeSpacing.current
}
