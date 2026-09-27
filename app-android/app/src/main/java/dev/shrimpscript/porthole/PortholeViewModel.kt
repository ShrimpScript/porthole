package dev.shrimpscript.porthole

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.shrimpscript.porthole.net.Fleet
import dev.shrimpscript.porthole.net.MachineStore
import dev.shrimpscript.porthole.net.PortholeClient
import dev.shrimpscript.porthole.net.Row
import dev.shrimpscript.porthole.net.WorkingEvent
import androidx.glance.appwidget.updateAll
import dev.shrimpscript.porthole.net.SshFailsafe
import kotlinx.coroutines.launch

/**
 * Owns the daemon connection.
 *
 * The client must NOT be `remember`-scoped: an activity recreation - a rotation, a
 * density change, a configuration update - would build a second client and open a
 * second socket while the first lingered. A ViewModel survives those, so there is
 * exactly one connection per session of use.
 */
class PortholeViewModel(app: Application) : AndroidViewModel(app) {
    /** The paired computers and one client each; the primary is the first machine's. */
    val fleet = Fleet(viewModelScope, MachineStore(app))
    val client = fleet.primary

    /**
     * The SSH failsafe's own terminal. It used to share the session terminal, and then a
     * shell opened from Settings came up showing whatever session had been on screen -
     * or kept receiving it, while the daemon was still streaming.
     */
    val sshTerminal = dev.shrimpscript.porthole.terminal.TerminalEmulator(80, 24)
    val sshRevision = kotlinx.coroutines.flow.MutableStateFlow(0)
    val sshClosed = kotlinx.coroutines.flow.MutableStateFlow(false)
    val ssh = SshFailsafe(
        emulator = sshTerminal,
        onRevision = { sshRevision.value = sshRevision.value + 1 },
        onClosed = { sshClosed.value = true },
    )

    /** The session the phone is attached to, for notifications. Changing it re-primes. */
    @Volatile var attachedId: String = ""
        set(value) { field = value; primed = false; lastTurn = null }
    @Volatile var attachedTitle: String = ""
    /** Whether the foreground service has been started for the current connection. */
    @Volatile var serviceUp: Boolean = false
    /** When the current run of failed reconnects began; 0 while connected. */
    @Volatile var retryingSince: Long = 0L

    /** Working chips by session id, and when each session's last "Done" was posted. */
    private val chips = java.util.concurrent.ConcurrentHashMap<String, WorkingEvent>()
    /** Questions notified per session, so a re-broadcast of the same question does not ring twice. */
    private val asked = mutableMapOf<String, String>()
    private val doneAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @Volatile private var primed = false
    @Volatile private var lastTurn: Row? = null

    init {
        ClientHolder.client = client
        ClientHolder.fleet = fleet
        val ctx = app.applicationContext

        val prefs = ctx.getSharedPreferences("porthole", Context.MODE_PRIVATE)
        fleet.mirrorPrefs(prefs.getString("host", "").orEmpty(), prefs.getBoolean("paired", false))
        viewModelScope.launch {
            client.connection.collect { conn ->
                if (conn is dev.shrimpscript.porthole.net.Connection.Live) {
                    fleet.mirrorPrefs(prefs.getString("host", "").orEmpty(), true, conn.daemon.host)
                }
            }
        }
        // A second computer that refuses this phone (revoked there) is forgotten here, with
        // a word; the first computer's refusal is the failure screen's business.
        viewModelScope.launch {
            fleet.connections.collect { map ->
                val first = fleet.machines.value.firstOrNull()?.id
                map.forEach { (id, conn) ->
                    val refused = conn is dev.shrimpscript.porthole.net.Connection.Failed &&
                        (conn.reason == dev.shrimpscript.porthole.net.Failure.Revoked || conn.reason == dev.shrimpscript.porthole.net.Failure.NotPaired)
                    if (id != first && refused) {
                        val name = fleet.machines.value.firstOrNull { it.id == id }?.shown ?: id
                        fleet.remove(id)
                        Notifier.postPlain(ctx, "$name removed", "That computer refused this phone (${conn.detail}). Pair it again from Settings.")
                    }
                }
            }
        }
        // A backfill is the moment the feed is known: what it holds is history, not news.
        // From then on, a turn row that was not there before is a finished turn - which
        // includes the first turn of a session that had none when the phone attached.
        viewModelScope.launch {
            client.loadedEpoch.collect { epoch ->
                if (epoch == 0) return@collect
                lastTurn = client.rows.value.lastOrNull { it.kind == "turn" }
                primed = true
            }
        }
        viewModelScope.launch {
            client.rows.collect { rows ->
                if (rows.isEmpty()) { primed = false; return@collect } // cleared for an attach
                val turn = rows.lastOrNull { it.kind == "turn" }
                NotifyPolicy.turnFinished(
                    lastTurn, turn, primed, Foreground.visible,
                    prefs.getBoolean("notify_done", true) && !Mutes.muted(ctx, attachedId), sessionName(),
                )?.let { doneAt[attachedId] = System.currentTimeMillis(); Notifier.post(ctx, attachedId, it.title, it.text) }
                if (primed && turn != null) lastTurn = turn
            }
        }
        // Turns finishing in any other live session, as the daemon announces them. The
        // attached session is covered by the rows path above, so it is skipped here; the
        // others are always news, on screen or not, since no feed is showing them.
        viewModelScope.launch {
            fleet.turns.collect { ev ->
                if (ev.sessionId.isBlank() || ev.sessionId == attachedId) return@collect
                if (!prefs.getBoolean("notify_done", true) || Mutes.muted(ctx, ev.sessionId)) return@collect
                val took = ev.text.removePrefix("Worked for ").trim()
                doneAt[ev.sessionId] = System.currentTimeMillis()
                Notifier.post(ctx, ev.sessionId, ev.title.ifBlank { "Claude Code" }, if (took.isBlank()) "Done" else "Done \u00b7 worked for $took")
            }
        }
        // A working session is a chip in the status bar with a timer - unless it is the
        // session on screen, where the working strip already says it. A turn's "Done"
        // replaces the chip under the same id; a working=false without a Done (an
        // interrupt) clears it after a moment.
        fun showChips() {
            for ((id, ev) in chips) {
                if (id == attachedId && Foreground.visible) Notifier.cancel(ctx, id)
                else Notifier.postWorking(ctx, id, ev.title.ifBlank { "Claude Code" }, ev.doing, ev.sinceMs)
            }
        }
        viewModelScope.launch {
            fleet.working.collect { ev ->
                if (ev.sessionId.isBlank()) return@collect
                // A question waiting on the person: one notification while it waits, gone
                // the moment it is answered or the turn ends.
                if (ev.working && ev.asking.isNotBlank()) {
                    if (asked[ev.sessionId] != ev.asking && !(ev.sessionId == attachedId && Foreground.visible) &&
                        !Mutes.muted(ctx, ev.sessionId)
                    ) {
                        Notifier.postQuestion(ctx, ev.sessionId, ev.title.ifBlank { "Claude Code" }, ev.asking)
                    }
                    asked[ev.sessionId] = ev.asking
                } else if (asked.remove(ev.sessionId) != null) {
                    Notifier.cancelQuestion(ctx, ev.sessionId)
                }
                if (!prefs.getBoolean("notify_done", true) || Mutes.muted(ctx, ev.sessionId)) return@collect
                if (ev.working) {
                    chips[ev.sessionId] = ev
                    if (ev.sessionId == attachedId && Foreground.visible) return@collect
                    Notifier.postWorking(ctx, ev.sessionId, ev.title.ifBlank { "Claude Code" }, ev.asking.ifBlank { ev.doing }, ev.sinceMs)
                } else {
                    chips.remove(ev.sessionId)
                    launch {
                        kotlinx.coroutines.delay(3000)
                        if (ChipPolicy.mayCancel(doneAt[ev.sessionId], System.currentTimeMillis())) Notifier.cancel(ctx, ev.sessionId)
                    }
                }
            }
        }
        viewModelScope.launch { Foreground.state.collect { showChips() } }
        // The session list is the truth a chip must not outlive: a session that finished
        // while the phone was away sends no working-off frame, so reconcile here.
        viewModelScope.launch {
            fleet.sessions.collect { list ->
                val working = list.filter { it.working }.map { it.id }.toSet()
                for (id in ChipPolicy.stale(chips.keys, working)) {
                    chips.remove(id)
                    if (ChipPolicy.mayCancel(doneAt[id], System.currentTimeMillis())) Notifier.cancel(ctx, id)
                }
            }
        }
        // The widget and the quick tile read a snapshot of what the app last saw. Written on
        // every change to the list, the working state, or the connection.
        suspend fun writeSnapshot(list: List<dev.shrimpscript.porthole.net.SessionInfo>, conn: dev.shrimpscript.porthole.net.Connection) {
            val live = conn is dev.shrimpscript.porthole.net.Connection.Live
            if (!live && !prefs.getBoolean("paired", false)) {
                // Unpaired: nothing the home screen may keep showing.
                dev.shrimpscript.porthole.widget.WidgetState.clear(ctx)
                refreshHome()
                return
            }
            val machine = (conn as? dev.shrimpscript.porthole.net.Connection.Live)?.daemon?.host?.ifBlank { null } ?: prefs.getString("host", "").orEmpty()
            val prev = dev.shrimpscript.porthole.widget.WidgetState.load(ctx)
            val sessions = if (live || prev == null) list.map { s ->
                dev.shrimpscript.porthole.widget.WidgetSession(s.id, s.title, s.working, s.workingSince, s.doing)
            } else prev.sessions
            val at = if (live) System.currentTimeMillis() else prev?.atMs ?: 0L
            dev.shrimpscript.porthole.widget.WidgetState.save(ctx, dev.shrimpscript.porthole.widget.WidgetState(machine, live, at, sessions))
            refreshHome()
        }
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(fleet.sessions, client.connection) { list, conn -> list to conn }.collect { (list, conn) -> writeSnapshot(list, conn) }
        }
        // A live snapshot is re-stamped every minute. One that stops being re-stamped
        // belongs to a process that died, and the widget and tile treat it as history
        // (WidgetState.fresh) instead of saying "connected" until the app is next opened.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                val conn = client.connection.value
                if (conn is dev.shrimpscript.porthole.net.Connection.Live) writeSnapshot(fleet.sessions.value, conn)
            }
        }
        // No connection, no chips: an ongoing timer for a session we cannot see is a lie.
        // With several computers, only the sessions on machines that dropped lose theirs.
        viewModelScope.launch {
            fleet.connections.collect {
                val visible = fleet.sessions.value.filter { !it.machineDown }.map { it.id }.toSet()
                val anyLive = it.values.any { c -> c is dev.shrimpscript.porthole.net.Connection.Live } ||
                    client.connection.value is dev.shrimpscript.porthole.net.Connection.Live
                for (id in chips.keys.toList()) {
                    if (!anyLive || id !in visible) { chips.remove(id); Notifier.cancel(ctx, id) }
                }
            }
        }
        viewModelScope.launch {
            client.connection.collect { c ->
                if (c !is dev.shrimpscript.porthole.net.Connection.Live && fleet.machines.value.size <= 1) {
                    for (id in chips.keys.toList()) { chips.remove(id); Notifier.cancel(ctx, id) }
                }
            }
        }
        viewModelScope.launch {
            var wasLimit = false
            client.status.collect { st ->
                NotifyPolicy.limitHit(wasLimit, st, Foreground.visible, sessionName())
                    ?.let { Notifier.post(ctx, attachedId, it.title, it.text) }
                wasLimit = st?.limitHit == true
            }
        }
    }

    /** Redraw the widget and let the quick tile re-read the snapshot. */
    private suspend fun refreshHome() {
        val ctx = getApplication<Application>()
        runCatching { dev.shrimpscript.porthole.widget.SessionsWidget().updateAll(ctx) }
        runCatching { android.service.quicksettings.TileService.requestListeningState(ctx, android.content.ComponentName(ctx, dev.shrimpscript.porthole.widget.PortholeTile::class.java)) }
    }

    /** After unpairing: the forgotten machine's sessions come off the home screen now, not on the next connection. */
    fun clearWidget() {
        viewModelScope.launch {
            dev.shrimpscript.porthole.widget.WidgetState.clear(getApplication())
            refreshHome()
        }
    }

    private fun sessionName(): String =
        if (attachedId.isBlank()) "" else attachedTitle.ifBlank { "Claude Code" }

    override fun onCleared() {
        for (id in chips.keys.toList()) Notifier.cancel(getApplication(), id)
        ClientHolder.client = null
        ClientHolder.fleet = null
        ssh.close()
        client.disconnect()
        super.onCleared()
    }
}
