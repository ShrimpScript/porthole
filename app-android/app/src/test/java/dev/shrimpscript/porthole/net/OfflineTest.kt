package dev.shrimpscript.porthole.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import kotlin.concurrent.thread

/**
 * The phone through a bad connection: the feed stays and merges instead of flashing,
 * messages stay in their box until the computer says it typed them, and what the phone
 * keeps (drafts, recent feeds, the list) comes back from its own storage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A daemon that takes connection after connection, answers as told, and can hang up. */
    private class FakeDaemon(caps: List<String>, val answer: (FakeDaemon, JSONObject) -> Unit = { _, _ -> }) {
        val server = ServerSocket(0)
        val received: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
        @Volatile var conn: Socket? = null
        @Volatile var out: OutputStream? = null
        @Volatile var connections = 0

        init {
            thread(isDaemon = true) {
                while (true) {
                    val s = runCatching { server.accept() }.getOrNull() ?: return@thread
                    connections++
                    serve(s, caps)
                }
            }
        }

        private fun serve(s: Socket, caps: List<String>) {
            val input = DataInputStream(s.getInputStream())
            val o = s.getOutputStream()
            var key = ""
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return
                if (c == '\n'.code) {
                    val l = line.toString().trim()
                    if (l.isEmpty()) break
                    if (l.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) key = l.substringAfter(':').trim()
                    line.clear()
                } else line.append(c.toChar())
            }
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            o.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
            conn = s
            out = o
            say(JSONObject().put("v", 1).put("type", "daemon.hello").put("daemon_version", "test")
                .put("host", "desk").put("os", "linux").put("caps", JSONArray(caps)))
            while (true) {
                val b0 = runCatching { input.read() }.getOrDefault(-1)
                if (b0 < 0) return
                val b1 = input.read()
                var len = (b1 and 0x7f).toLong()
                if (len == 126L) len = input.readUnsignedShort().toLong()
                else if (len == 127L) len = input.readLong()
                val mask = ByteArray(4).also { input.readFully(it) }
                val payload = ByteArray(len.toInt()).also { input.readFully(it) }
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (b0 and 0x0f) {
                    0x1 -> { val f = JSONObject(String(payload)); received += f; answer(this, f) }
                    0x9 -> synchronized(this) { writeFrame(o, 0xA, payload) }
                    0x8 -> return
                }
            }
        }

        fun say(o: JSONObject) = synchronized(this) { out?.let { writeFrame(it, 0x1, o.toString().toByteArray()) } }

        /** The tunnel dies: no close frame, the socket just goes. */
        fun hangUp() { runCatching { conn?.close() } }

        private fun writeFrame(out: OutputStream, op: Int, payload: ByteArray) {
            out.write(0x80 or op)
            when {
                payload.size < 126 -> out.write(payload.size)
                payload.size < 65536 -> { out.write(126); out.write(payload.size shr 8); out.write(payload.size and 0xff) }
                else -> { out.write(127); for (i in 7 downTo 0) out.write(((payload.size.toLong() shr (8 * i)) and 0xff).toInt()) }
            }
            out.write(payload)
            out.flush()
        }
    }

    private fun waitUntil(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 15_000
        while (!ok()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun row(kind: String, text: String, ts: String) = Row(kind = kind, glyph = "", text = text, metric = "", detail = "", truncated = false, ts = ts)

    private fun rowsFrame(session: String, rows: List<Row>, remaining: Int = 0) = JSONObject().put("v", 1).put("type", "session.rows")
        .put("session_id", session).put("remaining", remaining).put("rows", JSONArray().also { a -> rows.forEach { a.put(it.toJson()) } })
        .put("state", JSONObject().put("model", "claude-fable-5-1").put("working", false))

    private val r1 = row("user", "look at the build", "2026-10-01T08:00:00Z")
    private val r2 = row("assistant", "It fails on the linker step.", "2026-10-01T08:00:10Z")
    private val r3 = row("turn", "Worked for 9s", "2026-10-01T08:00:11Z")
    private val r4 = row("user", "fix it", "2026-10-01T08:02:00Z")
    private val r5 = row("assistant", "Fixed: the flag was missing.", "2026-10-01T08:02:30Z")

    /** The tunnel drops and comes back: the feed is never empty in between, and what is new merges in. */
    @Test
    fun aReconnectKeepsTheFeedAndAddsWhatIsNew() {
        var backfill = listOf(r1, r2, r3)
        val daemon = FakeDaemon(listOf("prompt", "prompt_ack")) { d, f ->
            if (f.optString("type") == "session.attach") d.say(rowsFrame("s1", backfill))
        }
        val client = PortholeClient()
        val seen = Collections.synchronizedList(mutableListOf<Int>())
        val watch = CoroutineScope(Dispatchers.Unconfined)
        watch.launch { client.rows.collect { seen += it.size } }
        client.connect("127.0.0.1:${daemon.server.localPort}")
        waitUntil("the hello") { client.isLive() }
        client.attach("s1")
        waitUntil("the backfill") { client.rows.value.size == 3 }
        val epoch = client.loadedEpoch.value

        backfill = listOf(r2, r3, r4, r5) // the transcript went on while the phone was away
        daemon.hangUp()
        waitUntil("the drop") { !client.isLive() }
        assertEquals("the feed stays through the drop", 3, client.rows.value.size)
        waitUntil("the reconnect") { client.isLive() }
        client.attach("s1") // what the activity does on every Live
        assertEquals("a re-attach keeps the feed on screen", 3, client.rows.value.size)
        assertEquals("s1", client.attached.value)
        waitUntil("the merge") { client.rows.value.size == 5 }

        assertEquals(listOf(r1, r2, r3, r4, r5).map { it.text }, client.rows.value.map { it.text })
        assertEquals("a merge is not a fresh load: no jump to the end", epoch, client.loadedEpoch.value)
        val afterFirstFill = seen.dropWhile { it == 0 }
        assertFalse("the feed was emptied on the way: $seen", afterFirstFill.contains(0))
        watch.cancel()
        client.disconnect()
    }

    /** prompt_ack: the message is held until the computer says it typed it, then let go once. */
    @Test
    fun aMessageIsHeldUntilTheComputerTypedIt() {
        val daemon = FakeDaemon(listOf("prompt", "prompt_ack"))
        val client = PortholeClient()
        val got = Collections.synchronizedList(mutableListOf<PortholeClient.PendingPrompt>())
        val watch = CoroutineScope(Dispatchers.Unconfined)
        watch.launch { client.confirmed.collect { got += it } }
        client.connect("127.0.0.1:${daemon.server.localPort}")
        waitUntil("the hello") { client.isLive() }

        client.sendPrompt("s1", "run the tests")
        waitUntil("the prompt") { daemon.received.any { it.optString("type") == "prompt.send" } }
        val sent = daemon.received.first { it.optString("type") == "prompt.send" }
        val ref = sent.getString("ref")
        assertEquals("run the tests", client.sending.value[ref]?.text)

        daemon.say(JSONObject().put("v", 1).put("type", "prompt.sent").put("ref", ref))
        waitUntil("the confirmation") { client.sending.value.isEmpty() }
        waitUntil("the box let go") { got.size == 1 }
        assertEquals("run the tests", got[0].text)
        watch.cancel()
        client.disconnect()
    }

    /** Refused (Claude is not running there): it stays in the box, with the reason, and nothing is retried. */
    @Test
    fun aRefusedMessageStaysInItsBox() {
        val daemon = FakeDaemon(listOf("prompt", "prompt_ack"))
        val client = PortholeClient()
        client.connect("127.0.0.1:${daemon.server.localPort}")
        waitUntil("the hello") { client.isLive() }
        client.sendPrompt("s1", "deploy it")
        waitUntil("the prompt") { daemon.received.any { it.optString("type") == "prompt.send" } }
        val ref = daemon.received.first { it.optString("type") == "prompt.send" }.getString("ref")
        daemon.say(JSONObject().put("v", 1).put("type", "error").put("code", "not_live").put("message", "Claude Code isn't running in that session.").put("ref", ref))
        waitUntil("the refusal") { client.sending.value.isEmpty() }
        assertTrue(client.notice.value.orEmpty(), client.notice.value.orEmpty().contains("in the box to send again"))
        assertNull("nothing to give back: it never left the box", client.failedSend.value)
        Thread.sleep(300)
        assertEquals(1, daemon.received.count { it.optString("type") == "prompt.send" })
        client.disconnect()
    }

    /** The connection drops before the answer: the session's next feed settles whether it landed. */
    @Test
    fun aDropBeforeTheAnswerIsSettledByTheFeed() {
        var backfill = listOf(r1, r2, r3)
        val daemon = FakeDaemon(listOf("prompt", "prompt_ack")) { d, f ->
            if (f.optString("type") == "session.attach") d.say(rowsFrame("s1", backfill))
        }
        val client = PortholeClient()
        client.connect("127.0.0.1:${daemon.server.localPort}")
        waitUntil("the hello") { client.isLive() }
        client.attach("s1")
        waitUntil("the backfill") { client.rows.value.size == 3 }

        // Landed: typed, but the answer was lost with the connection.
        client.sendPrompt("s1", "fix it")
        waitUntil("the prompt") { daemon.received.any { it.optString("type") == "prompt.send" } }
        daemon.hangUp()
        waitUntil("unconfirmed") { client.sending.value.values.any { it.unconfirmed } }
        backfill = listOf(r2, r3, row("user", "fix it", java.time.Instant.now().toString()))
        waitUntil("the reconnect") { client.isLive() }
        client.attach("s1")
        waitUntil("settled as typed") { client.sending.value.isEmpty() }
        assertTrue(client.notice.value.orEmpty().isEmpty() || !client.notice.value!!.contains("dropped before"))

        // Not landed: the feed does not have it, so it stays in the box and says so.
        client.sendPrompt("s1", "and add a test")
        waitUntil("the second prompt") { daemon.received.count { it.optString("type") == "prompt.send" } == 2 }
        daemon.hangUp()
        waitUntil("unconfirmed again") { client.sending.value.values.any { it.unconfirmed } }
        waitUntil("the reconnect") { client.isLive() }
        client.attach("s1")
        waitUntil("settled as not typed") { client.sending.value.isEmpty() }
        assertTrue(client.notice.value.orEmpty(), client.notice.value.orEmpty().contains("in the box to send again"))
        client.disconnect()
    }

    /** Connected but no word about it: the box is given back after a while, and a later sight of it in the feed settles it. */
    @Test
    fun aMessageWithNoWordIsGivenBackThenSettled() {
        val daemon = FakeDaemon(listOf("prompt", "prompt_ack")) { d, f ->
            if (f.optString("type") == "session.attach") d.say(rowsFrame("s1", listOf(r1, r2, r3)))
        }
        val client = PortholeClient()
        client.ackTimeoutMs = 300
        val got = Collections.synchronizedList(mutableListOf<PortholeClient.PendingPrompt>())
        val watch = CoroutineScope(Dispatchers.Unconfined)
        watch.launch { client.confirmed.collect { got += it } }
        client.connect("127.0.0.1:${daemon.server.localPort}")
        waitUntil("the hello") { client.isLive() }
        client.attach("s1")
        waitUntil("the backfill") { client.rows.value.size == 3 }
        client.sendPrompt("s1", "ship it")
        waitUntil("given back") { client.sending.value.values.any { it.unconfirmed } }
        assertTrue(client.notice.value.orEmpty(), client.notice.value.orEmpty().contains("No word"))
        // A live row that is not it says nothing about it.
        daemon.say(JSONObject().put("v", 1).put("type", "session.event").put("session_id", "s1")
            .put("rows", JSONArray().put(row("assistant", "Working on it.", java.time.Instant.now().toString()).toJson())))
        Thread.sleep(200)
        assertEquals(1, client.sending.value.size)
        // Then it shows up: typed after all.
        daemon.say(JSONObject().put("v", 1).put("type", "session.event").put("session_id", "s1")
            .put("rows", JSONArray().put(row("user", "ship it", java.time.Instant.now().toString()).toJson())))
        waitUntil("settled") { client.sending.value.isEmpty() }
        waitUntil("the box let go") { got.size == 1 }
        watch.cancel()
        client.disconnect()
    }

    /**
     * After a drop, a window that starts after the send cannot say the message is missing:
     * it stays unsettled. A "!" line is found as the shell line it becomes, and the same
     * words sent before the message do not count as it.
     */
    @Test
    fun onlyAWindowThatReachesBackCanSayItIsMissing() {
        var backfill = listOf(r1, r2, r3)
        var remaining = 0
        val daemon = FakeDaemon(listOf("prompt", "prompt_ack")) { d, f ->
            if (f.optString("type") == "session.attach") d.say(rowsFrame("s1", backfill, remaining))
        }
        val client = PortholeClient()
        client.connect("127.0.0.1:${daemon.server.localPort}")
        waitUntil("the hello") { client.isLive() }
        client.attach("s1")
        waitUntil("the backfill") { client.rows.value.size == 3 }

        client.sendPrompt("s1", "!npm test")
        client.sendPrompt("s1", "look at the build") // the same words as r1, sent again
        waitUntil("both prompts") { daemon.received.count { it.optString("type") == "prompt.send" } == 2 }
        daemon.hangUp()
        waitUntil("unconfirmed") { client.sending.value.values.count { it.unconfirmed } == 2 }
        // Claude worked on: the window no longer reaches back to the send.
        val later = { s: Int -> java.time.Instant.now().plusSeconds(s.toLong()).toString() }
        backfill = listOf(row("event", "You ran ! npm test", later(1)), row("assistant", "Tests pass.", later(2)))
        remaining = 80
        waitUntil("the reconnect") { client.isLive() }
        client.attach("s1")
        waitUntil("the shell line settled") { client.sending.value.values.none { it.text == "!npm test" } }
        Thread.sleep(300)
        val left = client.sending.value.values.single()
        assertEquals("r1's words before the send are not it", "look at the build", left.text)
        assertTrue("still unsettled, not declared missing", left.unconfirmed)
        assertFalse(client.notice.value.orEmpty().contains("dropped before"))
        client.disconnect()
    }

    /** Forgotten: nothing still on its way may write the computer's folder back. */
    @Test
    fun aForgottenComputerStaysForgotten() {
        val dir = tmp.newFolder("gone")
        val store = SessionStore(dir)
        store.setDraft("s1", "x")
        store.flush()
        store.clear()
        store.saveSessions(JSONArray().put(JSONObject().put("id", "s1")))
        store.saveFeed("s1", listOf(r1), null, 0)
        store.setDraft("s1", "y")
        store.flush()
        assertFalse(dir.exists())
        assertEquals("", store.draft("s1"))
        assertNull(store.feed("s1"))
    }

    /** No connection at all: the saved copy of a session and the last list show at once. */
    @Test
    fun theSavedCopyShowsWithNoConnection() {
        val store = SessionStore(tmp.newFolder("desk"))
        store.saveFeed("s1", listOf(r1, r2, r3), JSONObject().put("model", "claude-fable-5-1"), remaining = 40)
        store.saveSessions(JSONArray().put(JSONObject().put("id", "s1").put("title", "Build fix").put("cwd", "/srv/app").put("live", true)))
        store.flush()

        val client = PortholeClient()
        client.store = store
        assertEquals("Build fix", client.sessions.value.single().title)
        client.attach("s1") // no socket: nothing goes, the copy shows
        assertEquals(listOf(r1, r2, r3).map { it.text }, client.rows.value.map { it.text })
        assertEquals("s1", client.attached.value)
        assertEquals(40, client.remaining.value)
        assertEquals("claude-fable-5-1", client.state.value?.model)
        assertTrue(client.savedCopy.value > 0L)
    }
}
