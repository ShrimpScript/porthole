package dev.shrimpscript.porthole.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.shrimpscript.porthole.MainActivity
import dev.shrimpscript.porthole.Notifier
import dev.shrimpscript.porthole.ui.theme.Appearance
import dev.shrimpscript.porthole.ui.theme.PortholeColors
import dev.shrimpscript.porthole.ui.theme.ThemeChoice
import dev.shrimpscript.porthole.ui.theme.paletteFor

/**
 * The sessions list at a glance: name, what each is doing, and when. Working sessions
 * first. A tap on a row opens that session; the header opens the app. The widget cannot
 * hold the socket, so it shows the app's last view of the computer and says how old it is.
 */
class SessionsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(250.dp, 110.dp), DpSize(250.dp, 180.dp), DpSize(250.dp, 260.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetState.load(context)
        val prefs = context.getSharedPreferences("porthole", Context.MODE_PRIVATE)
        val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val palette = paletteFor(ThemeChoice.of(prefs.getString("theme", null)), Appearance.of(prefs.getString("appearance", null)), night)
        provideContent { Content(context, state, palette) }
    }

    @androidx.compose.runtime.Composable
    private fun Content(context: Context, state: WidgetState?, p: PortholeColors) {
        val rowsThatFit = ((LocalSize.current.height - 44.dp) / 30.dp).toInt().coerceIn(1, 6)
        val open = actionStartActivity(android.content.Intent(context, MainActivity::class.java))
        Column(
            GlanceModifier.fillMaxSize().background(ColorProvider(p.surface)).cornerRadius(16.dp).padding(12.dp),
        ) {
            Row(GlanceModifier.fillMaxWidth().clickable(open), verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.size(10.dp).cornerRadius(5.dp).background(ColorProvider(if (state?.fresh == true) p.accent else p.edge))) {}
                Spacer(GlanceModifier.width(8.dp))
                Text(state?.machine?.ifBlank { null } ?: "Porthole", style = TextStyle(color = ColorProvider(p.text), fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    when {
                        state == null -> "not connected"
                        state.fresh -> if (state.working > 0) "${state.working} working" else "connected"
                        else -> "as of " + clock(context, state.atMs)
                    },
                    style = TextStyle(color = ColorProvider(p.faint), fontSize = 12.sp), maxLines = 1,
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            val sessions = state?.sessions.orEmpty().sortedByDescending { it.working }.take(rowsThatFit)
            if (sessions.isEmpty()) {
                Text(
                    if (state == null) "Open Porthole to connect to your computer." else "No sessions on the computer.",
                    style = TextStyle(color = ColorProvider(p.muted), fontSize = 13.sp),
                )
            }
            sessions.forEach { s ->
                val openSession = actionStartActivity(
                    android.content.Intent(context, MainActivity::class.java)
                        .setFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(Notifier.EXTRA_OPEN_SESSION, s.id)
                )
                Row(GlanceModifier.fillMaxWidth().height(30.dp).clickable(openSession), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.title, style = TextStyle(color = ColorProvider(if (s.working) p.text else p.muted), fontSize = 13.sp, fontWeight = if (s.working) FontWeight.Medium else FontWeight.Normal), maxLines = 1)
                    Spacer(GlanceModifier.defaultWeight())
                    Text(
                        if (s.working) (s.doing.ifBlank { "working" }.take(28) + if (s.sinceMs > 0) " · " + since(s.sinceMs) else "") else "idle",
                        style = TextStyle(color = ColorProvider(if (s.working) p.accent else p.faint), fontSize = 12.sp), maxLines = 1,
                    )
                }
            }
        }
    }

    /** The widget is not redrawn as time passes, so a wall-clock stamp is the honest form. */
    private fun clock(context: Context, ms: Long): String =
        if (ms <= 0) "earlier" else android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(ms))

    private fun since(ms: Long): String {
        val m = ((System.currentTimeMillis() - ms) / 60_000).coerceAtLeast(0)
        return if (m < 1) "<1m" else if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m"
    }
}

class SessionsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SessionsWidget()
}
