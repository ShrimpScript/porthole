package dev.shrimpscript.porthole.widget

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.shrimpscript.porthole.MainActivity

/**
 * One line in the quick settings: connected and how many sessions are working, or how
 * old the last view is. A tap opens the app. It reads the same snapshot as the widget.
 */
class PortholeTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val state = WidgetState.load(this)
        tile.label = "Porthole"
        tile.state = if (state?.fresh == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            state == null -> "Not connected"
            state.fresh -> if (state.working > 0) "${state.working} working" else "Connected"
            else -> "Last seen " + agoLabel(state.atMs)
        }
        tile.updateTile()
    }

    // The Intent overload is the only one below API 34; the PendingIntent branch covers the rest.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun agoLabel(ms: Long): String {
        val m = ((System.currentTimeMillis() - ms) / 60_000).coerceAtLeast(0)
        return when { m < 1 -> "just now"; m < 60 -> "${m}m ago"; else -> "${m / 60}h ago" }
    }
}
