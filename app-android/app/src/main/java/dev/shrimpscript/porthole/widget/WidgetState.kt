package dev.shrimpscript.porthole.widget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the widget and the quick tile show: the last session list the app saw, with
 * when it saw it and whether the connection was live at the time. Written by the view
 * model on every change; read by the widget, which cannot reach the socket. Staleness
 * is shown, never hidden - a list "as of 14 min ago" is honest, a live-looking one is not.
 */
data class WidgetSession(val id: String, val title: String, val working: Boolean, val sinceMs: Long, val doing: String)

data class WidgetState(val machine: String, val live: Boolean, val atMs: Long, val sessions: List<WidgetSession>) {
    val working: Int get() = sessions.count { it.working }

    /**
     * Live, and said so recently. The view model re-stamps a live snapshot every minute;
     * one that stopped being re-stamped belongs to a process that died (reboot, memory
     * pressure) and must not keep the home screen saying "connected".
     */
    val fresh: Boolean get() = live && System.currentTimeMillis() - atMs < FRESH_MS

    companion object {
        private const val PREFS = "widget"
        const val FRESH_MS = 150_000L

        /** After unpairing: the forgotten machine's sessions leave the home screen too. */
        fun clear(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("state").apply()
        }
        fun load(context: Context): WidgetState? {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("state", null) ?: return null
            return runCatching {
                val o = JSONObject(raw)
                val arr = o.optJSONArray("sessions") ?: JSONArray()
                WidgetState(
                    machine = o.optString("machine"), live = o.optBoolean("live"), atMs = o.optLong("at"),
                    sessions = (0 until arr.length()).map { i ->
                        val s = arr.getJSONObject(i)
                        WidgetSession(s.optString("id"), s.optString("title"), s.optBoolean("working"), s.optLong("since"), s.optString("doing"))
                    },
                )
            }.getOrNull()
        }

        fun save(context: Context, state: WidgetState) {
            val o = JSONObject().put("machine", state.machine).put("live", state.live).put("at", state.atMs)
                .put("sessions", JSONArray().apply {
                    state.sessions.forEach { s -> put(JSONObject().put("id", s.id).put("title", s.title).put("working", s.working).put("since", s.sinceMs).put("doing", s.doing)) }
                })
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("state", o.toString()).apply()
        }
    }
}
