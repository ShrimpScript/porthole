package dev.shrimpscript.porthole.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.shrimpscript.porthole.net.Attachment
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Files attached to a prompt: photos (re-encoded, see the composer) and, where the daemon
 * says it takes them, any file. A message carries at most [ATTACH_LIMIT] of them: the
 * daemon refuses a larger file, and the socket would refuse the message.
 */
const val ATTACH_LIMIT = 10 * 1024 * 1024

/** A size as a person reads it: "812 KB", "3.4 MB". */
fun formatBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${n / 1024} KB"
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", n / (1024.0 * 1024.0))
}

/**
 * Reads the file the system picker returned: its own name and type, and its bytes, which
 * must fit [ATTACH_LIMIT]. Runs off the main thread; a failure says why, in words for the
 * person who picked it.
 */
fun readAttachment(context: Context, uri: Uri): Result<Attachment> = runCatching {
    val cr = context.contentResolver
    var name = "file"
    var size = -1L
    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            if (!c.isNull(0)) name = c.getString(0)
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    if (size > ATTACH_LIMIT) error("$name is ${formatBytes(size)}. A message can carry ${formatBytes(ATTACH_LIMIT.toLong())}.")
    val stream = cr.openInputStream(uri) ?: error("$name could not be opened")
    // A file from the cloud may not say its size until it is read.
    val bytes = stream.use { readBounded(it, ATTACH_LIMIT) }
        ?: error("$name is larger than ${formatBytes(ATTACH_LIMIT.toLong())}, which is what a message can carry.")
    if (bytes.isEmpty()) error("$name is empty")
    Attachment(name, cr.getType(uri) ?: "application/octet-stream", bytes)
}

/** At most [limit] bytes of [input], or null when there are more: a picker's size can be unknown. */
fun readBounded(input: InputStream, limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) return out.toByteArray()
        if (out.size() + n > limit) return null
        out.write(buf, 0, n)
    }
}
