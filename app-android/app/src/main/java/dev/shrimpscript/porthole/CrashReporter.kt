package dev.shrimpscript.porthole

import android.app.ActivityManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why the app died, kept on the phone and told to the person.
 *
 * An app that vanishes with no message leaves its owner guessing and its author blind.
 * So: the last crash is written to the app's own files, shown as a notification, and
 * offered to the computer on the next connection (the daemon writes it beside its own
 * state). Nothing leaves the pair of devices, as
 * ever; there is no crash service to send it to.
 */
object CrashReporter {
    private const val FILE = "last-crash.txt"
    private const val CHANNEL = "porthole.crash"
    private const val CAP = 24_000

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(app.filesDir, FILE).writeText(describe(app, thread, error)) }
            runCatching { tell(app, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last crash, if one is waiting to be reported, else null. */
    fun pending(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()?.ifBlank { null }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    private fun describe(context: Context, thread: Thread, error: Throwable): String = buildString {
        appendLine("Porthole ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) on ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("at ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())} on thread ${thread.name}")
        appendLine()
        appendLine(error.stackTraceToString())
        val ended = endings(context)
        if (ended.isNotBlank()) {
            appendLine()
            appendLine("earlier endings:")
            append(ended)
        }
    }.take(CAP)

    /**
     * How the previous processes ended, from Android's own record: this catches the
     * deaths a Java handler never sees - killed for memory, ANR, a native abort.
     */
    fun endings(context: Context): String = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@runCatching ""
        val am = context.getSystemService(ActivityManager::class.java) ?: return@runCatching ""
        am.getHistoricalProcessExitReasons(context.packageName, 0, 6).joinToString("\n") { info ->
            val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(info.timestamp))
            val reason = when (info.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                android.app.ApplicationExitInfo.REASON_ANR -> "not responding"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "signal"
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "user closed it"
                android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped it"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "too much of something"
                android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
                android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
                else -> "reason ${info.reason}"
            }
            "  $when_  $reason  ${info.description.orEmpty()}"
        }
    }.getOrDefault("")

    /** A notification the person can read without a computer, since the app is gone. */
    private fun tell(context: Context, error: Throwable) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(CHANNEL, "Crashes", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Shown when Porthole stops unexpectedly."
                }
            )
        }
        val head = error.stackTrace.take(4).joinToString("\n") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        val text = "${error::class.java.simpleName}: ${error.message.orEmpty().take(160)}"
        nm.notify(
            9021,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Porthole stopped")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\n$head\n\nOpen Porthole; it will send this to your computer."))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .build()
        )
    }
}
