package dev.shrimpscript.porthole.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

const val DEFAULT_PORT = 8737

/** Frame version this app understands. A newer daemon is reported, not crashed on. */
const val PROTOCOL_VERSION = 1

/** One row of the session feed. Mirrors the daemon's transcript.Row. */
data class Row(
    val kind: String,
    val glyph: String,
    val text: String,
    val metric: String,
    val detail: String,
    val truncated: Boolean,
    /** RFC 3339, as the transcript recorded it. Empty when the record had none. */
    val ts: String = "",
    /** Pairs a tool row with its result row; empty for other kinds. */
    val toolId: String = "",
    /** Where an image or video row's bytes live; fetched on demand and cached. */
    val imageRef: String = "",
    val media: String = "",
    /** A question row: what Claude asked and the choices, in the CLI's own order. */
    val questions: List<Question> = emptyList(),
)

/** One question from Claude's AskUserQuestion, as the CLI shows it: numbered options, then "Type something". */
data class Question(val header: String, val text: String, val multi: Boolean, val options: List<QuestionOption>)

data class QuestionOption(val label: String, val description: String)

/**
 * The CLI's question picker as it stands on screen: which question of the call is up,
 * its numbered options (ticked ones on a multi-select), the number of "Type something",
 * and whether this is the review screen before submitting. Every tap on it is one key
 * press the daemon makes; the next status frame shows what happened.
 */
data class ScreenQuestion(
    val header: String, val text: String, val multi: Boolean, val options: List<ScreenOption>,
    val typed: Int, val review: Boolean, val index: Int, val total: Int,
)

data class ScreenOption(val n: Int, val label: String, val description: String, val checked: Boolean)

/** One file git reports changed in the session's directory, with its diff against HEAD. */
data class ChangedFile(
    val path: String, val status: String, val added: Int, val removed: Int,
    val binary: Boolean, val diff: String, val truncated: Boolean,
    val from: String = "", val error: String = "",
)

/** The working tree of the session's repository, as `git status` and `git diff` report it. */
data class ChangesState(
    val sessionId: String, val root: String, val branch: String, val files: List<ChangedFile>,
    val added: Int, val removed: Int, val truncated: Boolean, val notRepo: Boolean,
    /** git failed on the computer; [files] is not an answer. Also set when the daemon refused. */
    val error: String = "",
)

/** A picture attached to a prompt from the phone. */
data class Attachment(val name: String, val media: String, val bytes: ByteArray)

/** A dev server listening on the computer's loopback. */
data class DevServer(val port: Int, val process: String, val name: String)

/** A dev server the daemon is currently sharing: local `upstream`, reachable on `port`. */
data class PreviewShare(val upstream: Int, val port: Int)

data class PreviewState(val servers: List<DevServer>, val active: List<PreviewShare>)

/** A turn finished in some session on the computer - any session, attached or not. */
data class TurnEvent(val sessionId: String, val title: String, val text: String)

/** The daemon started (or found already running) Claude Code for a session. */
data class StartedEvent(val sessionId: String, val tmux: String, val mode: String, val pane: String = "")

/** A session started or stopped working, or moved on to another tool. */
data class WorkingEvent(val sessionId: String, val title: String, val working: Boolean, val sinceMs: Long, val doing: String, val asking: String = "")

/** Token accounting summed over a session's assistant messages. */
data class Usage(
    val input: Long = 0, val output: Long = 0, val cacheRead: Long = 0,
    val cacheWrite: Long = 0, val thinking: Long = 0,
)

/**
 * What the transcript says about the session as a whole. Every field is derived from
 * records - the daemon keeps it running as the file grows.
 */
data class SessionState(
    val model: String,
    val permissionMode: String,
    val working: Boolean,
    val workingSince: String,
    val pendingTool: String,
    val turns: Int,
    val prompts: Int,
    val replies: Int,
    val usage: Usage,
    val lastContext: Long,
    val tools: Map<String, Int>,
    val firstTs: String,
    val lastTs: String,
    /** The CLI's own accounting from its cost-state records; zero when it wrote none. */
    val costUsd: Double = 0.0,
    val apiMs: Long = 0,
    val linesAdded: Long = 0,
    val linesRemoved: Long = 0,
)

/** What the CLI's own screen says right now, read from the tmux pane once a second. */
data class TuiStatus(
    val working: Boolean,
    val text: String,
    val elapsed: String,
    val tokens: String,
    val permissionMode: String,
    val interruptible: Boolean,
    /** The CLI's usage-limit line, when one is on screen. */
    val limitText: String = "",
    val limitResumeAt: String = "",
    val limitWaiting: Boolean = false,
    val limitEnter: Boolean = false,
    val limitStopped: Boolean = false,
    /** The question picker on the CLI's screen right now, if any. */
    val question: ScreenQuestion? = null,
    /** The effort level the CLI shows above its prompt ("xhigh"), "" when not on screen. */
    val effort: String = "",
    /** The CLI's saved default effort, for when the line is not on screen. */
    val effortDefault: String = "",
) {
    val limitHit: Boolean get() = limitText.isNotBlank()
}

/** A tool call waiting on a decision from this phone. */
data class Approval(
    val toolUseId: String,
    val sessionId: String,
    val toolName: String,
    val command: String,
    val cwd: String,
    val expiresInSeconds: Int,
    val receivedAtMs: Long = System.currentTimeMillis(),
)

/** A Claude Code session as the daemon reports it. */
data class SessionInfo(
    val id: String,
    val title: String,
    val cwd: String,
    val branch: String,
    val lastActive: String,
    /** Claude Code is running in a tmux pane there: prompts can be typed to it. */
    val live: Boolean,
    /** A tmux session exists there at all: the terminal can attach. */
    val tmux: Boolean = live,
    val working: Boolean = false,
    val model: String = "",
    /** When the current turn started, epoch millis; 0 when not working or unknown. */
    val workingSince: Long = 0L,
    /** What it is doing right now, from the transcript ("Bash: ls -la", "Read App.tsx"). */
    val doing: String = "",
    /** The question Claude is waiting on the person to answer; "" when none. */
    val asking: String = "",
    /** The tmux session Claude runs in ("work", "0"); what tells two sessions in one directory apart. */
    val tmuxName: String = "",
    /** The tmux pane it runs in ("%7"), when the computer's Claude Code registers one. */
    val pane: String = "",
    /** Which computer, when the phone is paired with more than one; blank otherwise. */
    val machineId: String = "",
    val machine: String = "",
    /** The machine's socket is not live: this row is its last known state, not a live one. */
    val machineDown: Boolean = false,
    /** The socket's state as a word while [machineDown]: connecting, reconnecting, refused, not connected. */
    val machineState: String = "",
)

/**
 * An app build the phone can install: one published on the computer (a path there), or
 * a Porthole release from GitHub (an HTTPS URL).
 */
data class BuildInfo(val app: String, val version: String, val path: String, val size: Long, val versionCode: Int = 0) {
    /** "shopping-list" -> "Shopping list". */
    val name: String get() = app.replace('-', ' ').replaceFirstChar { it.uppercase() }
    val isPorthole: Boolean get() = app == "porthole"
    /** A release downloaded from GitHub rather than from the computer. */
    val remote: Boolean get() = path.startsWith("https://")
}

data class DaemonInfo(
    val version: String,
    val host: String,
    val os: String,
    val caps: List<String>,
    val deviceName: String,
    /** The account the SSH failsafe logs in as, learned while the daemon was up. */
    val sshUser: String,
    /** Claude Code's autoContinueAtUsageLimit, read from the computer's settings. */
    val autoContinue: Boolean = true,
    /**
     * The newest Porthole build published on the computer. Older apps updated from it;
     * this one ignores it and updates from GitHub releases instead.
     */
    val latestBuild: BuildInfo? = null,
    /** The newest build of every app published on the computer. */
    val builds: List<BuildInfo> = emptyList(),
)

/**
 * Why the connection dropped. The app shows a specific card per case, because "couldn't
 * connect" with no reason is the failure mode that sends people back to their desk.
 */
enum class Failure {
    None,
    NotPaired,      // needs a code from `portholed pair`
    BadCode,        // wrong or expired
    Revoked,        // removed from the allowlist
    NotTailnet,     // whois could not name this device
    NeedsReauth,    // the tailnet ACL is on "check" and wants a browser round trip
    VersionSkew,    // the daemon speaks a newer protocol than this app
    Unreachable,    // no route: Tailscale off, machine asleep, daemon stopped
}

/**
 * Codes that end the connection. Everything else - a session that went away, a PTY that
 * would not open - is one failed request on a socket that is still up, and telling
 * someone the computer is unreachable when it just answered would show a state that is
 * not true.
 */
private val FATAL = mapOf(
    "not_paired" to Failure.NotPaired,
    "bad_code" to Failure.BadCode,
    "revoked" to Failure.Revoked,
    "not_tailnet" to Failure.NotTailnet,
    "needs_reauth" to Failure.NeedsReauth,
    "version_skew" to Failure.VersionSkew,
)

sealed interface Connection {
    data object Idle : Connection
    data object Connecting : Connection
    data class Live(val daemon: DaemonInfo) : Connection
    /**
     * Reconnecting after a drop. [detail] carries the last real error so the failure card
     * can say why, once the drop has lasted long enough to be worth showing.
     */
    data class Retrying(val attempt: Int, val detail: String = "") : Connection
    data class Failed(val reason: Failure, val detail: String) : Connection
}

/**
 * The daemon connection.
 *
 * Deliberately not a general-purpose networking layer: one socket, one host, explicit
 * states. State here is only ever set from a real socket event, never optimistically -
 * a ring that says "connected" without a live socket would be a lie about the connection.
 */
/** RFC 3339 to epoch millis; 0 when blank or unparseable. */
fun parseIsoMs(iso: String): Long {
    if (iso.isBlank() || iso.startsWith("0001")) return 0L
    return runCatching { java.time.Instant.parse(iso).toEpochMilli() }
        .recoverCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
        .getOrDefault(0L)
}

class PortholeClient(private val http: OkHttpClient = defaultClient()) {

    private val _connection = MutableStateFlow<Connection>(Connection.Idle)
    val connection: StateFlow<Connection> = _connection

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions

    private val _rows = MutableStateFlow<List<Row>>(emptyList())
    val rows: StateFlow<List<Row>> = _rows

    /** Null until a backfill arrives, so the feed can tell "empty" from "not loaded". */
    private val _attached = MutableStateFlow<String?>(null)
    val attached: StateFlow<String?> = _attached

    /**
     * How many rows arrived as history. Rows past this index arrived live, which is what
     * the feed animates: history is just there, new events settle in.
     */
    private val _backfillCount = MutableStateFlow(0)
    val backfillCount: StateFlow<Int> = _backfillCount
    private val _earlierEpoch = MutableStateFlow(0)
    /** Bumped on every page of history, empty or not, so the feed can stop saying "Loading". */
    val earlierEpoch: StateFlow<Int> = _earlierEpoch
    private val _remaining = MutableStateFlow(0)
    /** Older transcript rows the daemon still holds beyond what the feed has. */
    val remaining: StateFlow<Int> = _remaining
    /** Rows in the feed that came from the transcript (captures are local and do not count). */
    private var fileRows = 0
    private val _loadedEpoch = MutableStateFlow(0)
    /** Bumped on every backfill, including an empty one, so "the feed is known" is observable. */
    val loadedEpoch: StateFlow<Int> = _loadedEpoch

    private val _state = MutableStateFlow<SessionState?>(null)
    val state: StateFlow<SessionState?> = _state

    private val _status = MutableStateFlow<TuiStatus?>(null)
    val status: StateFlow<TuiStatus?> = _status

    /** Image bytes by ref. Bounded: the newest 24 stay, older ones are fetched again. */
    private val _images = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val images: StateFlow<Map<String, ByteArray>> = _images
    private val inflight = mutableSetOf<String>()

    /** Recorded clips by ref, as files in the app's cache directory. */
    private val _clips = MutableStateFlow<Map<String, java.io.File>>(emptyMap())
    val clips: StateFlow<Map<String, java.io.File>> = _clips

    /** Where clips are written. Set by the activity; a client without it drops clips. */
    var cacheDir: java.io.File? = null

    /** The terminal model. Lives here so it survives recomposition and reconnects. */
    val terminal = dev.shrimpscript.porthole.terminal.TerminalEmulator(80, 24)

    private val _terminalRevision = MutableStateFlow(0)
    val terminalRevision: StateFlow<Int> = _terminalRevision

    private val _terminalOpen = MutableStateFlow(false)
    val terminalOpen: StateFlow<Boolean> = _terminalOpen
    /**
     * The session the terminal was opened for. A terminal is only ever shown for its own
     * session: open for another one and the screen would be the wrong session's.
     */
    private val _terminalSession = MutableStateFlow<String?>(null)
    val terminalSession: StateFlow<String?> = _terminalSession

    /**
     * The daemon's end of a terminal dies with the socket and with a new attach. Keeping
     * "open" after that froze the screen on its last frame while the feed moved on, since
     * nothing asked for a new terminal.
     */
    private fun terminalGone() {
        _terminalOpen.value = false
        _terminalSession.value = null
    }

    /** The last refused request, shown next to what failed rather than as a dead end. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    private val _changes = MutableStateFlow<ChangesState?>(null)
    /** The last answer to [getChanges]; null until asked, and cleared on attach. */
    val changes: StateFlow<ChangesState?> = _changes

    private val _started = kotlinx.coroutines.flow.MutableSharedFlow<StartedEvent>(extraBufferCapacity = 4)
    /** Answers to [startSession]. */
    val started: kotlinx.coroutines.flow.SharedFlow<StartedEvent> = _started

    private val _working = kotlinx.coroutines.flow.MutableSharedFlow<WorkingEvent>(extraBufferCapacity = 16)
    /** Working-state changes in every live session, as the daemon sees them. */
    val working: kotlinx.coroutines.flow.SharedFlow<WorkingEvent> = _working

    private val _turns = kotlinx.coroutines.flow.MutableSharedFlow<TurnEvent>(extraBufferCapacity = 8)
    /** Finished turns in every live session, as the daemon sees them. */
    val turns: kotlinx.coroutines.flow.SharedFlow<TurnEvent> = _turns

    private val _preview = MutableStateFlow<PreviewState?>(null)
    /** What the computer is listening on and which of it is shared; null until asked. */
    val preview: StateFlow<PreviewState?> = _preview
    private val _previewOpened = kotlinx.coroutines.flow.MutableSharedFlow<PreviewShare>(extraBufferCapacity = 4)
    /** Fires when a share is confirmed open, so the UI can hand the URL to the browser. */
    val previewOpened: kotlinx.coroutines.flow.SharedFlow<PreviewShare> = _previewOpened

    fun clearNotice() { _notice.value = null }

    private val _approval = MutableStateFlow<Approval?>(null)
    val approval: StateFlow<Approval?> = _approval

    private var socket: WebSocket? = null
    private var lastHost: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var retryJob: Job? = null
    private var attempt = 0

    /**
     * True between connect() and disconnect(): the app wants a socket, so a drop should
     * be retried rather than left on screen. Cleared on a fatal refusal - retrying a
     * revoked device just produces the same rejection every few seconds.
     */
    private var wanted = false

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)   // a websocket read has no deadline
            .pingInterval(20, TimeUnit.SECONDS)      // detect a dead tunnel promptly
            .retryOnConnectionFailure(true)
            .build()

        /** Accepts "host", "host:port" and a bare IPv6 literal. */
        fun normalizeHost(input: String): String {
            val h = input.trim().removePrefix("http://").removePrefix("ws://").trimEnd('/')
            if (h.isEmpty()) return h
            if (h.contains(':') && !h.startsWith("[") && h.count { it == ':' } > 1) return "[$h]"
            return h
        }

        fun hostPort(host: String): String =
            if (host.substringAfterLast(']').contains(':')) host else "$host:$DEFAULT_PORT"
    }

    /**
     * Is a daemon there? Used by the connect screen so the user learns the machine is
     * unreachable before being asked for a pairing code.
     */
    suspend fun probe(host: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val url = "http://${hostPort(normalizeHost(host))}/healthz"
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) error("daemon replied ${r.code}")
                val body = r.body?.string().orEmpty()
                // Older daemons answer "ok"; newer ones say what they can do.
                val caps = runCatching { JSONObject(body).optJSONArray("caps") }.getOrNull()
                buildList { for (i in 0 until (caps?.length() ?: 0)) add(caps!!.getString(i)) }
            }
        }
    }

    fun isLive(): Boolean = _connection.value is Connection.Live

    fun connect(host: String, pairingCode: String? = null) {
        disconnect()
        wanted = true
        attempt = 0
        lastHost = host
        connectInternal(host, pairingCode)
    }

    private fun connectInternal(host: String, pairingCode: String?) {
        // One socket at a time. An orphaned socket keeps its own listener alive and shows
        // up on the daemon as a second device.
        socket?.close(1000, null)
        socket = null
        _connection.value = Connection.Connecting
        val base = "ws://${hostPort(normalizeHost(host))}/ws"
        val url = if (pairingCode.isNullOrBlank()) base else "$base?code=${pairingCode.trim()}"
        socket = http.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    fun disconnect() {
        wanted = false
        retryJob?.cancel()
        retryJob = null
        socket?.close(1000, null)
        socket = null
    }

    /**
     * Retry now, resetting the backoff. Called when the phone gets a network back:
     * waiting out a 30-second backoff after walking into wifi is the kind of delay that
     * makes an app feel broken when it is merely patient.
     */
    fun retryNow() {
        if (lastHost == null) return
        // onAvailable fires once per network, so wifi and cellular coming back together
        // would otherwise open a socket each and leave the daemon holding orphans.
        if (_connection.value is Connection.Live || _connection.value is Connection.Connecting) return
        retryJob?.cancel()
        attempt = 0
        wanted = true
        reconnect()
    }

    /**
     * A phone loses its network constantly - a lift, a tunnel, walking out of wifi. The
     * connection coming back must not depend on someone noticing a failure screen and
     * tapping Retry, so drops are retried with a backoff that tops out at 30s.
     */
    private fun scheduleRetry(detail: String = "") {
        if (!wanted || retryJob?.isActive == true) return
        val host = lastHost ?: return
        attempt += 1
        val waitMs = minOf(30_000L, 1_000L shl minOf(attempt - 1, 5))
        _connection.value = Connection.Retrying(attempt, detail)
        retryJob = scope.launch {
            delay(waitMs)
            if (wanted) connectInternal(host, null)
        }
    }

    /**
     * Reconnect after an unexpected drop. Never reuses the pairing code: a code is
     * single-use, and retrying with a spent one would burn the attempt budget and
     * produce a confusing "wrong code" instead of "couldn't reach it".
     */
    fun reconnect() {
        val host = lastHost ?: return
        connect(host, null)
    }

    fun attach(sessionId: String) {
        _changes.value = null
        _rows.value = emptyList()
        _attached.value = null
        _backfillCount.value = 0
        _state.value = null
        _status.value = null
        send("""{"type":"session.attach","session_id":"$sessionId"}""")
    }

    fun detach() {
        terminalGone()
        _rows.value = emptyList()
        _attached.value = null
        _state.value = null
        _status.value = null
    }

    /** Enter or Escape, typed into the session. The daemon refuses anything else. */
    /**
     * Pick an option of the question Claude is asking: the daemon presses that number in
     * the CLI's picker. [text] goes with the "Type something" option: typed, then Enter.
     */
    fun answer(sessionId: String, option: Int, text: String = "", advance: Boolean = false, submit: Boolean = false) {
        send(JSONObject().put("type", "session.answer").put("session_id", sessionId).put("option", option).put("text", text)
            .put("advance", advance).put("submit", submit).toString())
    }

    fun sendKey(sessionId: String, key: String) {
        send(JSONObject().put("type", "session.key").put("session_id", sessionId).put("key", key).toString())
    }

    /** Esc at the desk: stops the current turn. */
    fun interrupt(sessionId: String) {
        send(JSONObject().put("type", "session.interrupt").put("session_id", sessionId).toString())
    }

    fun sendPrompt(sessionId: String, text: String, attachments: List<Attachment> = emptyList()) {
        val payload = JSONObject()
            .put("type", "prompt.send")
            .put("session_id", sessionId)
            .put("text", text)
        if (attachments.isNotEmpty()) {
            val arr = org.json.JSONArray()
            attachments.forEach { a ->
                arr.put(JSONObject().put("name", a.name).put("media", a.media)
                    .put("data", android.util.Base64.encodeToString(a.bytes, android.util.Base64.NO_WRAP)))
            }
            payload.put("attachments", arr)
        }
        send(payload.toString())
    }

    /** Ask for an image row's bytes once; the answer lands in [images]. */
    fun requestImage(sessionId: String, ref: String) {
        if (ref.isBlank() || _images.value.containsKey(ref)) return
        synchronized(inflight) { if (!inflight.add(ref)) return }
        send(JSONObject().put("type", "image.get").put("session_id", sessionId).put("ref", ref).toString())
    }

    /** Start Claude Code in this session's directory: "resume" this transcript, or "new". */
    fun startSession(sessionId: String, mode: String) {
        _notice.value = null
        send(JSONObject().put("type", "session.start").put("session_id", sessionId).put("mode", mode).toString())
    }

    /** Ask for the page of history before what the feed holds. */
    fun loadEarlier() = send(JSONObject().put("type", "session.earlier").put("before", fileRows).toString())

    /** Ask what git sees changed in the session's directory. Answered by a `changes` frame. */
    fun getChanges(sessionId: String) {
        send(JSONObject().put("type", "changes.get").put("session_id", sessionId).toString())
    }

    /** Tell the computer why the app died last time; the daemon keeps it beside its state. */
    fun report(text: String) {
        send(JSONObject().put("type", "client.report").put("text", text).toString())
    }

    fun previewList() = send("""{"type":"preview.list"}""")
    fun previewOpen(port: Int) = send(JSONObject().put("type", "preview.open").put("port", port).toString())
    fun previewClose(port: Int) = send(JSONObject().put("type", "preview.close").put("port", port).toString())

    fun captureStill() = send("""{"type":"capture.still"}""")
    /** One frame for the watch view; lands under the single key "live", never as a row. */
    fun captureLive() = send("""{"type":"capture.still","live":true}""")
    fun captureClip(seconds: Int) = send(JSONObject().put("type", "capture.clip").put("seconds", seconds).toString())

    private fun remember(ref: String, bytes: ByteArray) {
        val next = LinkedHashMap(_images.value)
        next[ref] = bytes
        while (next.size > 24) next.remove(next.keys.first())
        _images.value = next
        synchronized(inflight) { inflight.remove(ref) }
    }

    private fun nowIso(): String = java.time.OffsetDateTime.now().toString()

    fun refreshSessions() = send("""{"type":"session.list"}""")

    fun openTerminal(sessionId: String, cols: Int, rows: Int) {
        // A clean screen for this session: RIS, so the last session's frame never shows
        // under the new one while it attaches.
        terminal.write(byteArrayOf(0x1b, 'c'.code.toByte()))
        terminal.resize(cols, rows)
        _terminalOpen.value = false
        _terminalSession.value = sessionId
        _terminalRevision.value = _terminalRevision.value + 1
        val p = JSONObject()
            .put("type", "pty.open").put("session_id", sessionId)
            .put("cols", cols).put("rows", rows)
        send(p.toString())
    }

    /** Raw bytes to the terminal - keystrokes, not text. */
    fun sendKeys(data: String) {
        if (data.isEmpty()) return
        val p = JSONObject()
            .put("type", "pty.input")
            .put("data", android.util.Base64.encodeToString(
                data.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
        send(p.toString())
    }

    /** Lets the SSH failsafe drive the same emulator. */
    fun bumpTerminalRevision() {
        _terminalRevision.value = _terminalRevision.value + 1
        _terminalOpen.value = true
    }

    fun markTerminalClosed() {
        _terminalOpen.value = false
    }

    fun resizeTerminal(cols: Int, rows: Int) {
        val p = JSONObject().put("type", "pty.resize").put("cols", cols).put("rows", rows)
        send(p.toString())
    }

    /**
     * Walks the pane's own tmux history: lines > 0 further back, < 0 forward, 0 back to
     * the live screen. The desk sees the pane scrolled too - it is the same scrollback.
     */
    fun scrollTerminal(lines: Int) {
        send(JSONObject().put("type", "pty.scroll").put("lines", lines).toString())
    }

    /**
     * Answer a permission request. There is no third option and no "remember this":
     * every request is decided explicitly, which is the whole point of the feature.
     */
    fun decide(toolUseId: String, allow: Boolean, reason: String = "") {
        val payload = JSONObject()
            .put("type", "permission.decide")
            .put("tool_use_id", toolUseId)
            .put("decision", if (allow) "allow" else "deny")
            .put("reason", reason)
        send(payload.toString())
        _approval.value = null
    }

    /** Drop a request that expired locally without answering it. */
    fun expireApproval(toolUseId: String) {
        if (_approval.value?.toolUseId == toolUseId) _approval.value = null
    }

    private fun send(json: String) {
        socket?.send(json)
    }

    private fun parseQuestions(arr: org.json.JSONArray?): List<Question> = buildList {
        for (i in 0 until (arr?.length() ?: 0)) {
            val q = arr!!.getJSONObject(i)
            val opts = q.optJSONArray("options")
            add(Question(
                header = q.optString("header"), text = q.optString("text"), multi = q.optBoolean("multi"),
                options = buildList {
                    for (j in 0 until (opts?.length() ?: 0)) {
                        val o = opts!!.getJSONObject(j); add(QuestionOption(o.optString("label"), o.optString("description")))
                    }
                },
            ))
        }
    }

    private fun parseRows(arr: org.json.JSONArray?): List<Row> = buildList {
        for (i in 0 until (arr?.length() ?: 0)) {
            val r = arr!!.getJSONObject(i)
            add(
                Row(
                    kind = r.optString("kind"),
                    glyph = r.optString("glyph"),
                    text = r.optString("text"),
                    metric = r.optString("metric"),
                    detail = r.optString("detail"),
                    truncated = r.optBoolean("truncated"),
                    ts = r.optString("ts"),
                    toolId = r.optString("tool_id"),
                    imageRef = r.optString("image_ref"),
                    media = r.optString("media"),
                    questions = parseQuestions(r.optJSONArray("questions")),
                )
            )
        }
    }

    private fun parseState(o: JSONObject?): SessionState? {
        o ?: return null
        val u = o.optJSONObject("usage")
        val tools = mutableMapOf<String, Int>()
        o.optJSONObject("tools")?.let { t -> t.keys().forEach { k -> tools[k] = t.optInt(k) } }
        return SessionState(
            model = o.optString("model"),
            permissionMode = o.optString("permission_mode"),
            working = o.optBoolean("working"),
            workingSince = o.optString("working_since"),
            pendingTool = o.optString("pending_tool"),
            turns = o.optInt("turns"), prompts = o.optInt("prompts"), replies = o.optInt("replies"),
            usage = Usage(
                input = u?.optLong("input") ?: 0, output = u?.optLong("output") ?: 0,
                cacheRead = u?.optLong("cache_read") ?: 0, cacheWrite = u?.optLong("cache_write") ?: 0,
                thinking = u?.optLong("thinking") ?: 0,
            ),
            lastContext = o.optLong("last_context"),
            tools = tools,
            firstTs = o.optString("first_ts"), lastTs = o.optString("last_ts"),
            costUsd = o.optDouble("cost_usd", 0.0), apiMs = o.optLong("api_ms"),
            linesAdded = o.optLong("lines_added"), linesRemoved = o.optLong("lines_removed"),
        )
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
            // Unknown frame types are ignored on purpose: an older app against a newer
            // daemon degrades instead of crashing.
            when (obj.optString("type")) {
                "daemon.hello" -> {
                    // A daemon speaking a newer protocol is a real state, not a crash.
                    val v = obj.optInt("v", 1)
                    if (v > PROTOCOL_VERSION) {
                        _connection.value = Connection.Failed(
                            Failure.VersionSkew,
                            "This computer runs a newer Porthole daemon (protocol v$v).",
                        )
                        return
                    }
                    val caps = obj.optJSONArray("caps")
                    _connection.value = Connection.Live(
                        DaemonInfo(
                            version = obj.optString("daemon_version"),
                            host = obj.optString("host"),
                            os = obj.optString("os"),
                            caps = buildList {
                                for (i in 0 until (caps?.length() ?: 0)) add(caps!!.getString(i))
                            },
                            deviceName = obj.optString("device_name"),
                            sshUser = obj.optString("ssh_user"),
                            autoContinue = obj.optBoolean("auto_continue", true),
                            latestBuild = obj.optJSONObject("latest_build")?.let {
                                BuildInfo(it.optString("app").ifBlank { "porthole" }, it.optString("version"), it.optString("path"), it.optLong("size"), it.optInt("code"))
                            },
                            builds = obj.optJSONArray("builds").let { arr ->
                                buildList {
                                    for (i in 0 until (arr?.length() ?: 0)) {
                                        val o = arr!!.getJSONObject(i)
                                        add(BuildInfo(o.optString("app").ifBlank { "porthole" }, o.optString("version"), o.optString("path"), o.optLong("size"), o.optInt("code")))
                                    }
                                }
                            },
                        )
                    )
                }
                "session.list" -> {
                    val arr = obj.optJSONArray("sessions") ?: return
                    _sessions.value = buildList {
                        for (i in 0 until arr.length()) {
                            val s = arr.getJSONObject(i)
                            add(
                                SessionInfo(
                                    id = s.optString("id"),
                                    title = s.optString("title"),
                                    cwd = s.optString("cwd"),
                                    branch = s.optString("branch"),
                                    lastActive = s.optString("last_active"),
                                    live = s.optBoolean("live"),
                                    tmux = s.optBoolean("has_tmux", s.optBoolean("live")),
                                    working = s.optBoolean("working"),
                                    model = s.optString("model"),
                                    workingSince = parseIsoMs(s.optString("working_since")),
                                    doing = s.optString("doing"),
                                    asking = s.optString("asking"),
                                    tmuxName = s.optString("tmux"),
                                    pane = s.optString("pane"),
                                )
                            )
                        }
                    }
                }
                "session.rows" -> {
                    val rows = parseRows(obj.optJSONArray("rows"))
                    if (obj.optBoolean("earlier")) {
                        // A page of history from before what we hold: goes on top, never news.
                        _rows.value = rows + _rows.value
                        _backfillCount.value = _backfillCount.value + rows.size
                        fileRows += rows.size
                        _remaining.value = obj.optInt("remaining")
                        _earlierEpoch.value = _earlierEpoch.value + 1
                        return
                    }
                    _backfillCount.value = rows.size
                    fileRows = rows.size
                    _remaining.value = obj.optInt("remaining")
                    _rows.value = rows
                    _loadedEpoch.value = _loadedEpoch.value + 1
                    parseState(obj.optJSONObject("state"))?.let { _state.value = it }
                    _attached.value = obj.optString("session_id")
                }
                "session.event" -> {
                    val incoming = parseRows(obj.optJSONArray("rows"))
                    if (!obj.optBoolean("synthetic")) fileRows += incoming.size
                    if (incoming.isNotEmpty()) _rows.value = _rows.value + incoming
                    parseState(obj.optJSONObject("state"))?.let { _state.value = it }
                }
                "session.started" -> _started.tryEmit(
                    StartedEvent(obj.optString("session_id"), obj.optString("tmux"), obj.optString("mode"), obj.optString("pane"))
                )
                "session.working" -> _working.tryEmit(
                    WorkingEvent(obj.optString("session_id"), obj.optString("title"), obj.optBoolean("working"),
                        parseIsoMs(obj.optString("since")), obj.optString("doing"), asking = obj.optString("asking"))
                )
                "session.turn" -> _turns.tryEmit(
                    TurnEvent(obj.optString("session_id"), obj.optString("title"), obj.optString("text"))
                )
                "changes" -> {
                    val arr = obj.optJSONArray("files")
                    _changes.value = ChangesState(
                        sessionId = obj.optString("session_id"), root = obj.optString("root"), branch = obj.optString("branch"),
                        files = buildList {
                            for (i in 0 until (arr?.length() ?: 0)) {
                                val f = arr!!.getJSONObject(i)
                                add(ChangedFile(
                                    path = f.optString("path"), status = f.optString("status"), added = f.optInt("added"),
                                    removed = f.optInt("removed"), binary = f.optBoolean("binary"), diff = f.optString("diff"),
                                    truncated = f.optBoolean("truncated"), from = f.optString("from"), error = f.optString("error"),
                                ))
                            }
                        },
                        added = obj.optInt("added"), removed = obj.optInt("removed"),
                        truncated = obj.optBoolean("truncated"), notRepo = obj.optBoolean("not_repo"),
                        error = obj.optString("error"),
                    )
                }
                "session.status" -> {
                    _status.value = TuiStatus(
                        working = obj.optBoolean("working"),
                        text = obj.optString("text"),
                        elapsed = obj.optString("elapsed"),
                        tokens = obj.optString("tokens"),
                        permissionMode = obj.optString("permission_mode"),
                        interruptible = obj.optBoolean("interruptible"),
                        limitText = obj.optString("limit_text"),
                        limitResumeAt = obj.optString("limit_resume_at"),
                        limitWaiting = obj.optBoolean("limit_waiting"),
                        limitEnter = obj.optBoolean("limit_enter"),
                        limitStopped = obj.optBoolean("limit_stopped"),
                        effort = obj.optString("effort"),
                        effortDefault = obj.optString("effort_default"),
                        question = obj.optJSONObject("question")?.let { q ->
                            val opts = q.optJSONArray("options")
                            ScreenQuestion(
                                header = q.optString("header"), text = q.optString("text"), multi = q.optBoolean("multi"),
                                typed = q.optInt("typed"), review = q.optBoolean("review"), index = q.optInt("index"), total = q.optInt("total"),
                                options = buildList {
                                    for (j in 0 until (opts?.length() ?: 0)) {
                                        val o = opts!!.getJSONObject(j)
                                        add(ScreenOption(o.optInt("n"), o.optString("label"), o.optString("description"), o.optBoolean("checked")))
                                    }
                                },
                            )
                        },
                    )
                }
                "pty.data" -> {
                    val raw = android.util.Base64.decode(
                        obj.optString("data"), android.util.Base64.DEFAULT)
                    terminal.write(raw)
                    _terminalOpen.value = true
                    _terminalRevision.value = _terminalRevision.value + 1
                }
                "pty.size" -> {
                    // The daemon reports the desktop window's real grid; render that
                    // rather than the phone's viewport, or wide output gets clipped.
                    terminal.resize(obj.optInt("cols", 80), obj.optInt("rows", 24))
                    _terminalRevision.value = _terminalRevision.value + 1
                }
                "pty.closed" -> terminalGone()
                "preview.list" -> {
                    val sv = obj.optJSONArray("servers")
                    val ac = obj.optJSONArray("active")
                    _preview.value = PreviewState(
                        servers = buildList {
                            for (i in 0 until (sv?.length() ?: 0)) {
                                val o = sv!!.getJSONObject(i)
                                add(DevServer(o.optInt("port"), o.optString("process"), o.optString("name")))
                            }
                        },
                        active = buildList {
                            for (i in 0 until (ac?.length() ?: 0)) {
                                val o = ac!!.getJSONObject(i)
                                add(PreviewShare(o.optInt("upstream"), o.optInt("port")))
                            }
                        },
                    )
                }
                "preview.state" -> {
                    val share = PreviewShare(obj.optInt("upstream"), obj.optInt("port"))
                    val open = obj.optBoolean("open")
                    val cur = _preview.value ?: PreviewState(emptyList(), emptyList())
                    val rest = cur.active.filter { it.upstream != share.upstream }
                    _preview.value = cur.copy(active = if (open) rest + share else rest)
                    if (open) _previewOpened.tryEmit(share)
                }
                "image.data" -> {
                    val ref = obj.optString("ref")
                    val bytes = runCatching {
                        android.util.Base64.decode(obj.optString("data"), android.util.Base64.DEFAULT)
                    }.getOrNull() ?: return
                    remember(ref, bytes)
                    // A capture is not in the transcript; it becomes a local row so it
                    // sits in the feed where it was asked for.
                    if (ref.startsWith("capture:")) {
                        _rows.value = _rows.value + Row(
                            kind = "image", glyph = "", text = obj.optString("text").ifBlank { "Screen" },
                            metric = "", detail = "", truncated = false, ts = nowIso(),
                            imageRef = ref, media = obj.optString("media"),
                        )
                    }
                }
                "clip.data" -> {
                    val ref = obj.optString("ref")
                    val dir = cacheDir ?: return
                    val bytes = runCatching {
                        android.util.Base64.decode(obj.optString("data"), android.util.Base64.DEFAULT)
                    }.getOrNull() ?: return
                    val file = java.io.File(dir, ref.replace(':', '-') + ".mp4")
                    runCatching { file.writeBytes(bytes) }.getOrNull() ?: return
                    _clips.value = _clips.value + (ref to file)
                    _rows.value = _rows.value + Row(
                        kind = "video", glyph = "", text = obj.optString("text").ifBlank { "Screen recording" },
                        metric = "", detail = "", truncated = false, ts = nowIso(),
                        imageRef = ref, media = "video/mp4",
                    )
                }
                "permission.request" -> {
                    _approval.value = Approval(
                        toolUseId = obj.optString("tool_use_id"),
                        sessionId = obj.optString("session_id"),
                        toolName = obj.optString("tool_name"),
                        command = obj.optString("command"),
                        cwd = obj.optString("cwd"),
                        expiresInSeconds = obj.optInt("expires_in_seconds", 90),
                    )
                }
                "error" -> {
                    val code = obj.optString("code")
                    val message = obj.optString("message")
                    val fatal = FATAL[code]
                    if (fatal != null) {
                        // Retrying a revoked device just reproduces the rejection.
                        wanted = false
                        _connection.value = Connection.Failed(fatal, message)
                    } else {
                        // The socket is fine; one request was refused. Say so where it
                        // happened instead of throwing up a connection failure.
                        _notice.value = message.ifBlank { "the daemon refused that ($code)" }
                        if (code == "no_changes") {
                            _changes.value = ChangesState("", "", "", emptyList(), 0, 0, false, false, error = message.ifBlank { "git is not available on the computer" })
                        }
                        synchronized(inflight) { inflight.clear() }
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            terminalGone()
            // A refusal arrives as an HTTP response on the upgrade, so the body carries
            // the real reason rather than a generic socket error.
            val body = runCatching { response?.body?.string() }.getOrNull()
            if (body != null) {
                val obj = runCatching { JSONObject(body) }.getOrNull()
                if (obj != null && obj.optString("type") == "error") {
                    val reason = codeToFailure(obj.optString("code"))
                    if (reason != Failure.Unreachable) wanted = false
                    _connection.value = Connection.Failed(reason, obj.optString("message"))
                    if (wanted) scheduleRetry()
                    return
                }
            }
            // A transport error is the ordinary case on a phone. Do NOT publish Failed
            // first: scheduleRetry overwrites it in the same instant, StateFlow conflates
            // the pair, and the failure card is never seen at all. The reason rides along
            // with Retrying instead, and the UI decides when a drop has lasted long
            // enough to be worth showing.
            val why = t.message ?: "could not reach the daemon"
            if (wanted) {
                scheduleRetry(why)
            } else if (_connection.value !is Connection.Failed) {
                // A fatal refusal (revoked, not paired) arrives as an error frame and the
                // daemon then drops the socket; that drop must not rewrite the reason.
                _connection.value = Connection.Failed(Failure.Unreachable, why)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            terminalGone()
            if (_connection.value is Connection.Live) _connection.value = Connection.Idle
            // The daemon closing on us (a restart, a shutdown) is exactly the case the
            // retry loop exists for.
            if (wanted) scheduleRetry()
        }
    }

    // A refusal on the upgrade genuinely is a connection failure, so an unknown code
    // there falls back to Unreachable.
    private fun codeToFailure(code: String) = FATAL[code] ?: Failure.Unreachable
}
