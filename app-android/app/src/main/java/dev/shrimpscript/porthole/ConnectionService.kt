package dev.shrimpscript.porthole

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Keeps Porthole's socket alive while the app is backgrounded.
 *
 * With no backend there is no push, so this is the honest mechanism: a foreground
 * service with a visible, low-priority notification. The battery cost is real and is
 * stated in the notification rather than hidden - a service that pretends to be free
 * is how apps get uninstalled.
 */
class ConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android may refuse the promotion (a background start, a type it no longer
        // allows). The service is a convenience - it keeps the socket alive off screen -
        // so a refusal stops the service and leaves the app running, rather than taking
        // the process down with it.
        // A sticky restart after the process was reclaimed delivers no intent. The socket
        // is certainly not live then, so the line says reconnecting, never connected.
        val machine = intent?.getStringExtra(EXTRA_MACHINE) ?: lastMachine
        val retrying = intent?.getBooleanExtra(EXTRA_RETRYING, false) ?: true
        lastMachine = machine
        val promoted = runCatching { startForeground(NOTIFICATION_ID, buildNotification(machine, retrying)) }
        if (promoted.isFailure) {
            android.util.Log.w("Porthole", "foreground service refused: ${promoted.exceptionOrNull()}")
            runCatching { stopSelf(startId) }
            return START_NOT_STICKY
        }
        // START_STICKY: if Android reclaims the process, come back rather than leaving
        // the user silently disconnected from their machine.
        return START_STICKY
    }

    // Time limits apply to dataSync and mediaProcessing services; this one is specialUse
    // and has none. Should Android ever end it anyway, stop cleanly and say so rather
    // than let the process be killed with an exception.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onTimeout(startId: Int) = giveUp()
    override fun onTimeout(startId: Int, fgsType: Int) = giveUp()
    private fun giveUp() {
        Notifier.postPlain(this, "Porthole stopped watching", "Android ended the background connection. Open the app to reconnect.")
        stopSelf()
    }

    private fun buildNotification(machine: String, retrying: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Session connection",
                // LOW: no sound, no heads-up. It is a status line, not an alert.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while Porthole is connected to your computer."
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // The line says what is true right now. A drop keeps the service - and so the
        // retry loop - alive in the background; it must not keep saying "connected".
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(if (retrying) "Reconnecting to $machine" else "Connected to $machine")
            .setContentText(
                if (retrying) "The link dropped. Porthole keeps trying in the background."
                else "Watching for approvals. Uses battery while connected."
            )
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "porthole.connection"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_MACHINE = "machine"
        private const val EXTRA_RETRYING = "retrying"
        @Volatile private var lastMachine = "your computer"

        /** Start, or re-issue the notification for a state change (live <-> retrying). */
        fun update(context: Context, machine: String, retrying: Boolean) {
            val i = Intent(context, ConnectionService::class.java)
                .putExtra(EXTRA_MACHINE, machine)
                .putExtra(EXTRA_RETRYING, retrying)
            begin(context, i)
        }

        fun start(context: Context, machine: String) {
            val i = Intent(context, ConnectionService::class.java).putExtra(EXTRA_MACHINE, machine)
            begin(context, i)
        }

        /**
         * Starting a foreground service can throw on modern Android - a start judged to
         * be from the background, a service type the system has since restricted. None
         * of that is worth closing the app over: the phone keeps its live socket for as
         * long as it is on screen either way.
         */
        private fun begin(context: Context, i: Intent) {
            runCatching { context.startForegroundService(i) }
                .onFailure { android.util.Log.w("Porthole", "could not start the connection service: $it") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ConnectionService::class.java)) }
        }
    }
}
