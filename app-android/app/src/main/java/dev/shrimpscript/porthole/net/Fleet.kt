package dev.shrimpscript.porthole.net

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One computer the phone is paired with: one daemon, one tailnet host. The id is the
 * address the person typed or scanned (a MagicDNS name or a 100.x address); the name is
 * what the daemon calls itself in its hello; the label is the person's own, if set.
 */
data class Machine(val id: String, val host: String, val name: String = "", val label: String = "", val addedAt: Long = 0L) {
    /** What the list and notifications call it. */
    val shown: String get() = label.ifBlank { name.ifBlank { host } }
}

/**
 * The machines on disk: `machines.json` in the app's files directory. The first entry
 * is migrated from the single-machine prefs (`host` + `paired`) so an existing pairing
 * survives the update; the prefs remain the source of truth for that first machine, and
 * further computers are added from Settings > Add a computer.
 */
class MachineStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "machines.json"))

    fun load(): List<Machine> {
        val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Machine(o.optString("id"), o.optString("host"), o.optString("name"), o.optString("label"), o.optLong("addedAt"))
            }.filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun save(machines: List<Machine>) {
        val arr = JSONArray()
        machines.forEach { m ->
            arr.put(JSONObject().put("id", m.id).put("host", m.host).put("name", m.name).put("label", m.label).put("addedAt", m.addedAt))
        }
        file.writeText(arr.toString())
    }

    /**
     * Mirrors the single-machine prefs into the store: the paired host becomes the first
     * machine, an existing entry for it keeps its label and name, and an unpaired app
     * has no machines. Idempotent; called whenever the prefs may have changed.
     */
    fun mirrorPrefs(host: String, paired: Boolean, name: String = ""): List<Machine> {
        val current = load()
        val next = if (!paired || host.isBlank()) {
            emptyList()
        } else {
            val existing = current.firstOrNull { it.id == host }
            listOf(
                existing?.copy(name = name.ifBlank { existing.name })
                    ?: Machine(id = host, host = host, name = name, addedAt = System.currentTimeMillis())
            ) + current.filter { it.id != host }
        }
        if (next != current) save(next)
        return next
    }
}

/** A pending permission request and the machine it must be answered on. */
data class FleetApproval(val machineId: String, val approval: Approval)

/**
 * One client per machine, and the merged view a phone with several computers needs:
 * every session with its machine attached. With one machine nothing changes: `primary`
 * is that machine's client and the merged list is its list.
 */
class Fleet(scope: CoroutineScope, private val store: MachineStore) {
    private val _machines = MutableStateFlow(store.load())
    val machines: StateFlow<List<Machine>> = _machines

    private val clients = LinkedHashMap<String, PortholeClient>()

    /** The first machine's client: the single-machine app's one and only, whatever its id. */
    val primary: PortholeClient = PortholeClient()

    /** Where clients keep downloaded clips; applied to every client the fleet creates. */
    var cacheDir: java.io.File? = null
        set(value) { field = value; primary.cacheDir = value; clients.values.forEach { it.cacheDir = value } }

    /**
     * Where each computer's sessions are kept on the phone (drafts, recent feeds, the last
     * list): one directory per computer under this. Set by the activity.
     */
    var storeRoot: java.io.File? = null
        @Synchronized set(value) { field = value; syncStores() }

    /** Gives every client the store of its own computer; the primary's follows the first machine. */
    @Synchronized
    private fun syncStores() {
        val root = storeRoot ?: return
        val first = _machines.value.firstOrNull()?.id
        if (first != null && primary.store?.dir != SessionStore.forMachine(root, first).dir) primary.store = SessionStore.forMachine(root, first)
        clients.forEach { (id, c) -> if (c.store == null) c.store = SessionStore.forMachine(root, id) }
    }

    /** The client for a machine: the primary for the first machine, its own for any other. */
    @Synchronized
    fun client(machineId: String): PortholeClient {
        val first = _machines.value.firstOrNull()?.id
        if (first == null || first == machineId) return primary
        return clients.getOrPut(machineId) {
            PortholeClient().also { c -> c.cacheDir = cacheDir; storeRoot?.let { c.store = SessionStore.forMachine(it, machineId) } }
        }
    }

    /** Open the sockets of every computer but the first (whose connect the activity owns). */
    @Synchronized
    fun connectAll() {
        _machines.value.drop(1).forEach { m ->
            val c = client(m.id)
            if (c.connection.value is Connection.Idle || c.connection.value is Connection.Failed) c.connect(m.host)
        }
    }

    /** The network is back: every retry loop may go now. */
    @Synchronized
    fun retryAll() { clients.values.forEach { it.retryNow() } }

    /** Ask every live computer for its list. */
    @Synchronized
    fun refreshAll() {
        primary.refreshSessions()
        clients.values.forEach { if (it.isLive()) it.refreshSessions() }
    }

    /** The client a session lives on: its machine's, or the primary for an untagged row. */
    fun clientFor(session: SessionInfo): PortholeClient =
        if (session.machineId.isBlank()) primary else client(session.machineId)

    /** The client for a session id the list knows, for a reply that arrives outside the UI. */
    fun clientForSession(sessionId: String): PortholeClient? =
        sessions.value.firstOrNull { it.id == sessionId }?.let { clientFor(it) }

    /** The address a session's machine is reached at (for links the phone opens itself). */
    fun hostOf(session: SessionInfo): String? =
        if (session.machineId.isBlank()) null else machines.value.firstOrNull { it.id == session.machineId }?.host

    /** A client for a pairing in progress; adopted by [add] once the daemon says hello. */
    fun newPairingClient(): PortholeClient = PortholeClient()

    /**
     * A second (or later) computer, paired: its client is kept under its id. False when
     * that id is already a machine (the same address typed again); the caller's client is
     * closed, since the daemon would otherwise hold a second device for one phone.
     */
    @Synchronized
    fun add(machine: Machine, client: PortholeClient): Boolean {
        val current = _machines.value
        if (current.isEmpty() || current.any { it.id == machine.id }) {
            client.disconnect()
            return false
        }
        client.cacheDir = cacheDir
        storeRoot?.let { client.store = SessionStore.forMachine(it, machine.id) }
        clients[machine.id] = client
        val next = current + machine
        store.save(next)
        _machines.value = next
        return true
    }

    /** Forget a computer other than the first; its socket closes. */
    @Synchronized
    fun remove(machineId: String) {
        val current = _machines.value
        if (current.firstOrNull()?.id == machineId) return // the first is unpaired through prefs
        clients.remove(machineId)?.let { it.disconnect(); it.store?.clear(); it.store = null }
        val next = current.filter { it.id != machineId }
        store.save(next)
        _machines.value = next
    }

    /** Each machine's connection state, by id. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val connections: StateFlow<Map<String, Connection>> = machines.flatMapLatest { list ->
        if (list.isEmpty()) primary.connection.map { mapOf<String, Connection>() }
        else combine(list.map { m -> client(m.id).connection.map { m.id to it } }) { pairs -> pairs.toMap() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * Every machine's sessions, each row saying which machine it is on, newest first. A
     * machine whose socket is not live contributes its last list marked down, with the
     * socket's state as a word, so the rows stay visible but nothing on them claims to
     * be live.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val sessions: StateFlow<List<SessionInfo>> = machines.flatMapLatest { list ->
        if (list.size <= 1) {
            primary.sessions
        } else {
            val perMachine = list.map { m -> combine(client(m.id).sessions, client(m.id).connection) { s, c -> s to c } }
            combine(perMachine) { arrays -> mergeSessions(list, arrays.map { it.first }, arrays.map { it.second is Connection.Live }, arrays.map { stateWord(it.second) }) }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** A permission request waiting on any machine, with the machine to answer it on. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val approval: StateFlow<FleetApproval?> = machines.flatMapLatest { list ->
        if (list.size <= 1) {
            primary.approval.map { a -> a?.let { FleetApproval(list.firstOrNull()?.id ?: "", it) } }
        } else {
            combine(list.map { m -> client(m.id).approval.map { a -> a?.let { FleetApproval(m.id, it) } } }) { arr -> arr.firstOrNull { it != null } }
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Working-state changes from every machine; titles name the machine when there are several. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val working: SharedFlow<WorkingEvent> = machines.flatMapLatest { list ->
        if (list.size <= 1) primary.working
        else merge(*list.map { m -> client(m.id).working.map { it.copy(title = "${m.shown} · ${it.title.ifBlank { "Claude Code" }}") } }.toTypedArray())
    }.shareIn(scope, SharingStarted.Eagerly, replay = 0)

    /** Finished turns from every machine, titled the same way. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val turns: SharedFlow<TurnEvent> = machines.flatMapLatest { list ->
        if (list.size <= 1) primary.turns
        else merge(*list.map { m -> client(m.id).turns.map { it.copy(title = "${m.shown} · ${it.title.ifBlank { "Claude Code" }}") } }.toTypedArray())
    }.shareIn(scope, SharingStarted.Eagerly, replay = 0)

    /** Reflect a prefs change (pair, unpair, a hello naming the host) in the machine list. */
    @Synchronized
    fun mirrorPrefs(host: String, paired: Boolean, name: String = "") {
        if (!paired || host.isBlank()) {
            // Unpairing the first computer is leaving: the app returns to its welcome
            // screen, and the other computers' sockets close with it. What the phone kept
            // of their sessions goes too.
            clients.values.forEach { it.disconnect(); it.store?.clear(); it.store = null }
            clients.clear()
            primary.store?.clear()
            primary.store = null
            if (_machines.value.isNotEmpty()) {
                store.save(emptyList())
                _machines.value = emptyList()
            }
            return
        }
        val others = _machines.value.drop(1)
        val first = store.mirrorPrefs(host, true, name).firstOrNull()
        val next = listOfNotNull(first) + others.filter { it.id != first?.id }
        if (next != _machines.value) {
            store.save(next)
            _machines.value = next
        }
        syncStores()
    }

    companion object {
        /**
         * Tags each machine's sessions with the machine and merges them, newest first. A
         * machine list of one (or none) leaves the rows untagged: the single-machine app
         * shows no machine names.
         */
        fun mergeSessions(machines: List<Machine>, perMachine: List<List<SessionInfo>>, reachable: List<Boolean> = emptyList(), states: List<String> = emptyList()): List<SessionInfo> {
            val several = machines.size > 1
            val out = ArrayList<SessionInfo>()
            perMachine.forEachIndexed { i, list ->
                val m = machines.getOrNull(i)
                val up = reachable.getOrNull(i) ?: true
                list.forEach { s ->
                    var row = if (several && m != null) s.copy(machineId = m.id, machine = m.shown) else s
                    if (!up) row = row.copy(machineDown = true, machineState = states.getOrNull(i) ?: "unreachable", live = false, tmux = false, working = false, asking = "", waiting = false, waitingFor = "", doing = "")
                    out += row
                }
            }
            // One row per session per machine. A session resumed in a second pane used to
            // arrive twice, and two rows with one key is a crash in a lazy list.
            return out.sortedByDescending { it.lastActive }.distinctBy { it.machineId + "/" + it.id }
        }

        /** A socket state as the person reads it. */
        fun stateWord(c: Connection): String = when (c) {
            is Connection.Live -> "connected"
            is Connection.Connecting -> "connecting"
            is Connection.Retrying -> "reconnecting"
            is Connection.Failed -> "refused"
            else -> "not connected"
        }
    }
}
