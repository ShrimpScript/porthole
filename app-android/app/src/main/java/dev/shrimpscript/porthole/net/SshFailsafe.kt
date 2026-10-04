package dev.shrimpscript.porthole.net

import dev.shrimpscript.porthole.terminal.TerminalEmulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.AndroidConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthNone
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One thing to do to the shell. Keystrokes and resizes share a queue so they keep their
 * order: a resize that overtakes the keystrokes around it redraws at the wrong width.
 */
private sealed interface Op {
    data class Data(val text: String) : Op
    data class Resize(val cols: Int, val rows: Int) : Op
}

/**
 * The failsafe: a real shell when the daemon is not answering.
 *
 * This is the reason Porthole can be trusted away from a desk. If portholed is down, the
 * app still opens a terminal over Tailscale SSH and can restart the daemon from it - an
 * app whose recovery story is "walk back to your computer" is not a product.
 *
 * Where Tailscale SSH serves the computer, authentication is the tailnet's: it sets
 * NoClientAuth and identifies the peer by its WireGuard identity, so the client offers the
 * `none` method (verified against Tailscale SSH). Where a client mishandles that, Tailscale
 * accepts a username suffixed `+password`, which is the documented escape hatch.
 *
 * Elsewhere - a Mac running the Tailscale app, which has no SSH server, or Linux with its
 * own sshd - the phone signs in with its own key ([FailsafeKey]), which the daemon added to
 * authorized_keys while it was up.
 */
class SshFailsafe(
    private val emulator: TerminalEmulator,
    private val onRevision: () -> Unit,
    private val onClosed: (String?) -> Unit,
    /** This phone's failsafe key, when it has one. */
    private val key: () -> KeyProvider? = { null },
) {
    /**
     * Debug-only SSH port override, for reaching a stand-in NoClientAuth server.
     * Release builds always use 22.
     */
    var debugPort: Int = 0

    companion object {
        /** What the failsafe runs: the user's login shell, with PORTHOLE_FAILSAFE=1 set. */
        const val FAILSAFE_SHELL = "exec env PORTHOLE_FAILSAFE=1 \"\$SHELL\" -l"

        /**
         * Android ships a cut-down "BC" provider that sshj cannot use, and leaving it in
         * place fails the key exchange with a bare EOF - no useful error, just a dropped
         * handshake. Replacing it with the full BouncyCastle must happen before any
         * SSHClient is constructed.
         */
        private var providerReady = false

        /**
         * Signs in: `none` for Tailscale SSH, then this phone's key if it has one. Without
         * a key, Tailscale's `+password` form is the last try; with one, the server is an
         * ordinary sshd, where that name means nothing.
         */
        fun authenticate(ssh: SSHClient, user: String, key: KeyProvider?) {
            val methods = buildList<AuthMethod> {
                add(AuthNone())
                if (key != null) add(AuthPublickey(key))
            }
            try {
                ssh.auth(user, methods)
            } catch (e: UserAuthException) {
                if (key != null) throw UserAuthException(
                    "the computer did not accept this phone's key - add it again from Settings while Porthole is connected",
                    e,
                )
                try {
                    ssh.auth("$user+password", AuthPassword(PasswordUtils.createOneOff("porthole".toCharArray())))
                } catch (e2: Exception) {
                    // Not Tailscale SSH, and no key to offer: an ordinary sshd, most likely
                    // a Mac's Remote Login.
                    throw UserAuthException(
                        "the computer did not let this phone in over SSH - if it is not using Tailscale SSH " +
                            "(a Mac never is), add this phone's key in Settings while Porthole is connected",
                        e2,
                    )
                }
            }
        }

        @Synchronized
        fun installCrypto() {
            if (providerReady) return
            runCatching {
                java.security.Security.removeProvider("BC")
                java.security.Security.insertProviderAt(
                    org.bouncycastle.jce.provider.BouncyCastleProvider(), 1,
                )
            }
            providerReady = true
        }
    }
    private var client: SSHClient? = null
    private var session: Session? = null
    private var shell: net.schmizz.sshj.connection.channel.direct.SessionChannel? = null
    /**
     * Everything written to the shell goes through here.
     *
     * sshj writes straight to the socket, so this must never happen on the main thread.
     * Android answers with NetworkOnMainThreadException, which carries no message and so
     * reads as "nothing happened", and the half-written packet leaves sshj's channel
     * buffer at a negative position - after which every later keystroke dies with an
     * ArrayIndexOutOfBounds. Typing looked like a broken IME for exactly this reason.
     * A single consumer keeps writes off the main thread and in order.
     */
    private var outbox: Channel<Op>? = null
    private val running = AtomicBoolean(false)

    val isOpen: Boolean get() = running.get()

    /**
     * Splits "host", "host:port" and "[v6]:port". The daemon's port is not the SSH port,
     * so a host carrying :8737 must still reach SSH on 22.
     */
    private fun sshTarget(host: String): Pair<String, Int> {
        val h = PortholeClient.normalizeHost(host)
        if (debugPort > 0) return Pair(h.substringBeforeLast(':', h), debugPort)
        val afterBracket = h.substringAfterLast(']', h)
        return if (afterBracket.contains(':')) {
            val port = afterBracket.substringAfterLast(':').toIntOrNull()
            val name = h.substring(0, h.lastIndexOf(':'))
            // A daemon port is not an SSH port; only an explicit SSH port is honoured.
            if (port != null && port != DEFAULT_PORT) Pair(name, port) else Pair(name, 22)
        } else {
            Pair(h, 22)
        }
    }

    /** Opens a shell. [host] may carry a port; the SSH port is 22 unless one is given. */
    suspend fun open(
        scope: CoroutineScope,
        host: String,
        user: String,
        cols: Int,
        rows: Int,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        close()
        runCatching {
            installCrypto()
            val (hostOnly, port) = sshTarget(host)
            val ssh = SSHClient(AndroidConfig())
            // The tunnel already authenticates both ends with per-peer WireGuard keys, and
            // Tailscale SSH rotates its host key, so pinning it here would break on every
            // rotation while adding nothing over the transport's own guarantee.
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connectTimeout = 10_000
            ssh.connect(hostOnly, port)
            authenticate(ssh, user, key())

            val s = ssh.startSession()
            s.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap())
            // A login shell, marked. A shell that attaches to tmux (or anything else) on
            // every SSH login would turn the failsafe into that session; PORTHOLE_FAILSAFE
            // lets a login script tell this one apart and leave it plain. Set in the
            // command rather than as an SSH environment request, which servers refuse
            // unless they are configured to accept it.
            val sh = s.exec(FAILSAFE_SHELL) as net.schmizz.sshj.connection.channel.direct.SessionChannel

            client = ssh
            session = s
            shell = sh
            running.set(true)

            val box = Channel<Op>(Channel.UNLIMITED)
            outbox = box
            // A plain shell talks to this emulator directly, with no tmux in between to
            // answer what it asks the terminal: the emulator answers, in order with the keys.
            emulator.onReply = { r -> box.trySend(Op.Data(r)) }
            scope.launch(Dispatchers.IO) {
                for (op in box) {
                    runCatching {
                        when (op) {
                            is Op.Data -> {
                                sh.outputStream.write(op.text.toByteArray(Charsets.UTF_8))
                                sh.outputStream.flush()
                            }
                            is Op.Resize -> sh.changeWindowDimensions(op.cols, op.rows, 0, 0)
                        }
                    }
                }
            }

            scope.launch(Dispatchers.IO) {
                val buf = ByteArray(8192)
                try {
                    while (running.get()) {
                        val n = sh.inputStream.read(buf)
                        if (n <= 0) break
                        // A shell opened again since: this one's last bytes are not drawn on
                        // the new screen, nor answered into the new connection.
                        if (outbox !== box) break
                        emulator.write(buf, n)
                        onRevision()
                    }
                    onClosed(null)
                } catch (e: Exception) {
                    onClosed(e.message)
                } finally {
                    running.set(false)
                }
            }
            Unit
        }
    }

    fun send(data: String) {
        outbox?.trySend(Op.Data(data))
    }

    fun resize(cols: Int, rows: Int) {
        // Rotating the phone changes the column count; a shell that is not told keeps
        // drawing at the old width.
        outbox?.trySend(Op.Resize(cols, rows))
    }

    /**
     * Runs one command and returns its output. Used by "Restart daemon", which is what
     * turns the failsafe from a consolation prize into an actual repair.
     */
    suspend fun runCommand(host: String, user: String, command: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                installCrypto()
                val (hostOnly, port) = sshTarget(host)
                val ssh = SSHClient(AndroidConfig())
                ssh.addHostKeyVerifier(PromiscuousVerifier())
                ssh.connectTimeout = 10_000
                ssh.connect(hostOnly, port)
                authenticate(ssh, user, key())
                val s = ssh.startSession()
                val cmd = s.exec(command)
                val out = cmd.inputStream.readBytes().toString(Charsets.UTF_8)
                cmd.join()
                s.close()
                ssh.disconnect()
                out
            }
        }

    fun close() {
        running.set(false)
        emulator.onReply = null
        outbox?.close()
        outbox = null
        runCatching { session?.close() }
        runCatching { client?.disconnect() }
        shell = null
        session = null
        client = null
    }
}
