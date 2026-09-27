package dev.shrimpscript.porthole

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/**
 * Inline reply from a "Done" notification: the text goes to that session as a prompt
 * over the live connection, and the notification says so. If the app is not connected
 * any more, it says to open the app instead - never a silent drop.
 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(Notifier.EXTRA_OPEN_SESSION) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Claude Code"
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        // With several computers a session the list does not know cannot be routed; better
        // "open the app" than a prompt typed into the wrong machine.
        val fleet = ClientHolder.fleet
        val client = if (fleet != null && fleet.machines.value.size > 1) fleet.clientForSession(sessionId) else (fleet?.clientForSession(sessionId) ?: ClientHolder.client)
        if (client != null && client.isLive()) {
            client.sendPrompt(sessionId, text)
            Notifier.post(context, sessionId, title, "Sent \u00b7 $text", reply = false)
        } else {
            Notifier.post(context, sessionId, title, "Not connected. Open Porthole to send: $text", reply = false)
        }
    }

    companion object {
        const val KEY_TEXT = "reply"
        const val EXTRA_TITLE = "title"
    }
}
