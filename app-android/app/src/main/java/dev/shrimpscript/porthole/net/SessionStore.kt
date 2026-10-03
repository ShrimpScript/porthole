package dev.shrimpscript.porthole.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * What the phone keeps of one computer's sessions, in the app's private storage: each
 * session's unsent draft, a copy of its recent feed, and the last session list. A session
 * opens at once from its copy and stays readable while the computer cannot be reached; a
 * draft survives a dropped connection, the app being closed, and the phone restarting.
 * One directory per computer: forgetting the computer deletes it, and so does uninstalling.
 *
 * Reads happen on the caller's thread (small files, read once per open); writes go to one
 * background thread, each written whole to a temporary file and renamed into place, so a
 * kill mid-write leaves the previous copy rather than half of a new one.
 */
class SessionStore(val dir: File) {
    companion object {
        /** Rows kept per session: a long scroll back, not the whole transcript. */
        const val KEEP_ROWS = 200
        /** Sessions whose feed is kept, the most recently saved first. */
        const val KEEP_SESSIONS = 20

        /** A computer's directory under [root]: its id hashed, since ids are addresses. */
        fun forMachine(root: File, machineId: String): SessionStore {
            val hash = MessageDigest.getInstance("SHA-256").digest(machineId.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)
            return SessionStore(File(root, hash))
        }

        private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
    }

    /** A session's saved feed: its rows, its last state frame, and how many rows came before them. */
    data class Feed(val rows: List<Row>, val state: JSONObject?, val remaining: Int, val savedAt: Long)

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "porthole-store").apply { isDaemon = true } }
    private val drafts = ConcurrentHashMap<String, String>()
    /** Drafts changed and not written yet: one write per burst of typing, not one per key. */
    private val dirty = ConcurrentHashMap.newKeySet<String>()
    /** Set by [clear]: the computer was forgotten, and nothing may write its files again. */
    @Volatile private var cleared = false

    // ---- drafts ------------------------------------------------------------------------

    fun draft(sessionId: String): String = if (cleared) "" else drafts.getOrPut(sessionId) {
        runCatching { File(dir, "drafts/${safe(sessionId)}.txt").readText() }.getOrDefault("")
    }

    fun setDraft(sessionId: String, text: String) {
        if (cleared || drafts[sessionId] == text) return
        drafts[sessionId] = text
        writeDraftSoon(sessionId)
    }

    private fun writeDraftSoon(sessionId: String) {
        if (!dirty.add(sessionId)) return // a write is already queued; it takes the latest text
        io.execute {
            dirty.remove(sessionId)
            val now = drafts[sessionId] ?: return@execute
            val f = File(dir, "drafts/${safe(sessionId)}.txt")
            if (now.isEmpty()) f.delete() else writeAtomically(f, now)
        }
    }

    /** Clears a draft that still holds exactly [text]: a sent message, not whatever replaced it. */
    fun clearDraftIf(sessionId: String, text: String) {
        val cur = draft(sessionId)
        // Only if it has not changed in between: typing on another thread wins.
        if (cur.trim() == text.trim() && drafts.replace(sessionId, cur, "")) writeDraftSoon(sessionId)
    }

    // ---- feeds -------------------------------------------------------------------------

    fun feed(sessionId: String): Feed? = if (cleared) null else runCatching {
        val o = JSONObject(File(dir, "feeds/${safe(sessionId)}.json").readText())
        val arr = o.optJSONArray("rows") ?: JSONArray()
        Feed(
            rows = List(arr.length()) { parseRow(arr.getJSONObject(it)) },
            state = o.optJSONObject("state"),
            remaining = o.optInt("remaining"),
            savedAt = o.optLong("saved_at"),
        )
    }.getOrNull()?.takeIf { it.rows.isNotEmpty() }

    /** Keeps the last [KEEP_ROWS] of [rows]; the ones left out count toward [remaining]. */
    fun saveFeed(sessionId: String, rows: List<Row>, state: JSONObject?, remaining: Int) {
        if (rows.isEmpty() || cleared) return
        val kept = rows.takeLast(KEEP_ROWS)
        val stateText = state?.toString()
        val savedAt = System.currentTimeMillis()
        // Rows are immutable; the JSON is built here, off the caller's (often the main) thread.
        io.execute {
            val body = JSONObject()
                .put("rows", JSONArray().also { a -> kept.forEach { a.put(it.toJson()) } })
                .put("remaining", remaining + (rows.size - kept.size))
                .put("saved_at", savedAt)
            if (stateText != null) body.put("state", JSONObject(stateText))
            writeAtomically(File(dir, "feeds/${safe(sessionId)}.json"), body.toString())
            prune()
        }
    }

    private fun prune() {
        val files = File(dir, "feeds").listFiles { f -> f.name.endsWith(".json") } ?: return
        files.sortedByDescending { it.lastModified() }.drop(KEEP_SESSIONS).forEach { it.delete() }
    }

    // ---- the session list --------------------------------------------------------------

    /** The last "sessions" array the computer sent, as it sent it. */
    fun sessions(): JSONArray? = if (cleared) null else runCatching { JSONArray(File(dir, "sessions.json").readText()) }.getOrNull()

    fun saveSessions(arr: JSONArray) {
        if (cleared) return
        val text = arr.toString()
        io.execute { writeAtomically(File(dir, "sessions.json"), text) }
    }

    /** Forget everything kept for this computer. */
    fun clear() {
        cleared = true
        drafts.clear()
        io.execute { dir.deleteRecursively() }
    }

    /** Waits for the writes queued so far; for tests. */
    fun flush() {
        io.submit {}.get()
    }

    private fun writeAtomically(f: File, text: String) {
        if (cleared) return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }
}
