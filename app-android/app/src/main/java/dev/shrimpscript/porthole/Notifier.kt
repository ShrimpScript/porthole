package dev.shrimpscript.porthole

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.shrimpscript.porthole.net.Row
import dev.shrimpscript.porthole.net.TuiStatus

/**
 * Event notifications: a turn finished, a usage limit was hit. Posted only while the app
 * is not on screen (see [Foreground]); on screen, the feed already says it. Each names
 * the session and opens it when tapped. Distinct from ConnectionService's persistent,
 * low-priority "connected" notification.
 */
object Notifier {
    internal const val CHANNEL = "events"
    const val EXTRA_OPEN_SESSION = "openSession"

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun channel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Session events", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A turn finished, or Claude Code is waiting on a usage limit"
                }
            )
        }
    }

    fun post(context: Context, sessionId: String, title: String, text: String, reply: Boolean = true) {
        if (!canPost(context)) return
        channel(context)
        val open = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_SESSION, sessionId)
        val tap = PendingIntent.getActivity(
            context, sessionId.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(title).setContentText("Done").build()
            )
        if (reply) {
            // Answer from the shade: the text becomes the next prompt in that session.
            val input = androidx.core.app.RemoteInput.Builder(ReplyReceiver.KEY_TEXT).setLabel("Reply to Claude").build()
            val send = PendingIntent.getBroadcast(
                context, sessionId.hashCode(),
                Intent(context, ReplyReceiver::class.java)
                    .putExtra(EXTRA_OPEN_SESSION, sessionId)
                    .putExtra(ReplyReceiver.EXTRA_TITLE, title),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            b.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_launcher_monochrome, "Reply", send)
                    .addRemoteInput(input).setAllowGeneratedReplies(false).build()
            )
        }
        val n = b.build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(sessionId.hashCode() and 0x7fffffff, n)
        }
    }
}

/**
 * A session at work: an ongoing notification with a running timer, promoted to the
 * status-bar chip and the lock screen on Android 16 where the system allows. Same id as
 * the session's "Done", which replaces it when the turn ends.
 */
fun Notifier.postWorking(context: Context, sessionId: String, title: String, doing: String, sinceMs: Long) {
    // Every part of this is a platform call that a newer Android may refuse: the channel,
    // the progress style, the promotion to a status-bar chip. None of it is worth closing
    // the app over, so the whole build is guarded, not only the posting.
    runCatching { postWorkingOrThrow(context, sessionId, title, doing, sinceMs) }
        .onFailure { android.util.Log.w("Porthole", "working notification refused: $it") }
}

private fun Notifier.postWorkingOrThrow(context: Context, sessionId: String, title: String, doing: String, sinceMs: Long) {
    if (!canPost(context)) return
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    if (nm.getNotificationChannel(WORKING_CHANNEL) == null) {
        nm.createNotificationChannel(
            NotificationChannel(WORKING_CHANNEL, "Working", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A session is working; the timer counts the turn"
                setSound(null, null)
            }
        )
    }
    val open = Intent(context, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(EXTRA_OPEN_SESSION, sessionId)
    val tap = PendingIntent.getActivity(context, sessionId.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val b = NotificationCompat.Builder(context, WORKING_CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle(title)
        .setContentText(doing.ifBlank { "Working" })
        .setContentIntent(tap)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setWhen(if (sinceMs > 0) sinceMs else System.currentTimeMillis())
        .setUsesChronometer(true)
        .setShowWhen(true)
        .setStyle(NotificationCompat.ProgressStyle().setProgressIndeterminate(true))
        // A tool's input can carry anything; the lock screen gets the name and "Working".
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(
            NotificationCompat.Builder(context, WORKING_CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title).setContentText("Working").setOngoing(true).build()
        )
    runCatching { b.setRequestPromotedOngoing(true) } // Live Updates, where the system allows
    runCatching { nm.notify(sessionId.hashCode() and 0x7fffffff, b.build()) }
}

/**
 * Claude is waiting on the person: its own notification beside the working chip, since
 * a chip at low importance never wakes a pocket. Tap opens the session on the card.
 */
fun Notifier.postQuestion(context: Context, sessionId: String, title: String, question: String) {
    if (!canPost(context)) return
    channel(context)
    val open = Intent(context, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(EXTRA_OPEN_SESSION, sessionId)
    val tap = PendingIntent.getActivity(
        context, questionId(sessionId), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val n = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle("$title is asking you")
        .setContentText(question)
        .setStyle(NotificationCompat.BigTextStyle().bigText(question))
        .setContentIntent(tap)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(
            NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title).setContentText("Asking you a question").build()
        )
        .build()
    runCatching { context.getSystemService(NotificationManager::class.java)?.notify(questionId(sessionId), n) }
}

fun Notifier.cancelQuestion(context: Context, sessionId: String) {
    runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(questionId(sessionId)) }
}

private fun questionId(sessionId: String) = ("$sessionId:question").hashCode() and 0x7fffffff

fun Notifier.cancel(context: Context, sessionId: String) {
    runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(sessionId.hashCode() and 0x7fffffff) }
}

private const val WORKING_CHANNEL = "working"

/** A notice with no session behind it: tapping opens the app. */
fun Notifier.postPlain(context: Context, title: String, text: String) {
    if (!canPost(context)) return
    val open = Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val tap = PendingIntent.getActivity(context, title.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    if (nm.getNotificationChannel("events") == null) {
        nm.createNotificationChannel(NotificationChannel("events", "Session events", NotificationManager.IMPORTANCE_DEFAULT))
    }
    runCatching {
        nm.notify(title.hashCode() and 0x7fffffff, NotificationCompat.Builder(context, "events")
            .setSmallIcon(R.drawable.ic_launcher_monochrome).setContentTitle(title).setContentText(text)
            .setContentIntent(tap).setAutoCancel(true).build())
    }
}

/**
 * The decision half of event notifications, kept pure so it is testable without a device.
 * Only a change warrants a ping: a turn row that was not there before, a limit that was
 * not hit before. Nothing fires while the app is on screen, where the feed already says it.
 */
object NotifyPolicy {
    data class Event(val title: String, val text: String)

    /**
     * [primed] is true once the attached session's backfill has been seen, so history is
     * never news; after that any turn that differs from the last one seen - including the
     * first turn of a session that had none - is a finished turn.
     */
    fun turnFinished(prev: Row?, now: Row?, primed: Boolean, visible: Boolean, enabled: Boolean, session: String): Event? {
        if (!primed || now == null || now == prev) return null
        if (visible || !enabled || session.isBlank()) return null
        val took = now.text.removePrefix("Worked for ").trim()
        return Event(session, if (took.isBlank()) "Done" else "Done · worked for $took")
    }

    fun limitHit(wasHit: Boolean, now: TuiStatus?, visible: Boolean, session: String): Event? {
        val hit = now?.limitHit == true
        if (!hit || wasHit || visible || session.isBlank()) return null
        val at = now!!.limitResumeAt
        return Event(session, if (at.isNotBlank()) "Usage limit reached · continuing at $at" else "Usage limit reached")
    }
}

/** Whether an activity of ours is on screen. Set from MainActivity's lifecycle. */
/**
 * Sessions the person has asked not to hear from. A build loop that finishes a turn
 * every four minutes is not news the fifth time; the list still shows it and the feed
 * still fills, the status bar just stays quiet. Kept in the same preferences the rest
 * of the app uses, so a mute survives a restart.
 */
object Mutes {
    private const val KEY = "muted_sessions"

    private fun prefs(context: Context) =
        context.getSharedPreferences("porthole", Context.MODE_PRIVATE)

    fun of(context: Context): Set<String> =
        prefs(context).getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun muted(context: Context, sessionId: String): Boolean =
        sessionId.isNotBlank() && of(context).contains(sessionId)

    fun set(context: Context, sessionId: String, muted: Boolean): Set<String> {
        if (sessionId.isBlank()) return of(context)
        val next = of(context).toMutableSet()
        if (muted) next.add(sessionId) else next.remove(sessionId)
        prefs(context).edit().putStringSet(KEY, next).apply()
        return next
    }

    fun clear(context: Context) {
        prefs(context).edit().putStringSet(KEY, emptySet()).apply()
    }
}

object Foreground {
    val state = kotlinx.coroutines.flow.MutableStateFlow(false)
    var visible: Boolean
        get() = state.value
        set(value) { state.value = value }
}
