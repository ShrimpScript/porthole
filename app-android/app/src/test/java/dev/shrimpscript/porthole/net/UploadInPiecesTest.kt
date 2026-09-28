package dev.shrimpscript.porthole.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import kotlin.concurrent.thread

/**
 * The real client against a bare WebSocket server: a file goes as numbered pieces, then
 * the prompt that names it, and a message typed after it arrives after it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UploadInPiecesTest {
    private class TinyDaemon(caps: List<String>) {
        val server = ServerSocket(0)
        val received: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())

        init {
            thread(isDaemon = true) {
                val s = server.accept()
                val input = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()
                this.out = out
                // The upgrade request, up to its blank line.
                var key = ""
                val line = StringBuilder()
                while (true) {
                    val c = input.read()
                    if (c < 0) return@thread
                    if (c == '\n'.code) {
                        val l = line.toString().trim()
                        if (l.isEmpty()) break
                        if (l.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) key = l.substringAfter(':').trim()
                        line.clear()
                    } else line.append(c.toChar())
                }
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                val hello = JSONObject().put("v", 1).put("type", "daemon.hello").put("daemon_version", "test")
                    .put("host", "desk").put("os", "linux").put("caps", org.json.JSONArray(caps)).toString()
                writeFrame(out, 0x1, hello.toByteArray())
                while (true) {
                    val b0 = input.read()
                    if (b0 < 0) return@thread
                    val b1 = input.read()
                    var len = (b1 and 0x7f).toLong()
                    if (len == 126L) len = input.readUnsignedShort().toLong()
                    else if (len == 127L) len = input.readLong()
                    val mask = ByteArray(4).also { input.readFully(it) }
                    val payload = ByteArray(len.toInt()).also { input.readFully(it) }
                    for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    when (b0 and 0x0f) {
                        0x1 -> received += JSONObject(String(payload))
                        0x9 -> writeFrame(out, 0xA, payload)
                        0x8 -> return@thread
                    }
                }
            }
        }

        @Volatile var out: OutputStream? = null
        fun say(o: JSONObject) = synchronized(this) { writeFrame(out!!, 0x1, o.toString().toByteArray()) }

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
        val until = System.currentTimeMillis() + 10_000
        while (!ok()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun aFileGoesInPiecesAndWhatFollowsItStaysBehindIt() {
        val daemon = TinyDaemon(listOf("prompt", "upload", "attach"))
        val client = PortholeClient()
        client.connect("127.0.0.1:${daemon.server.localPort}")
        waitUntil("the hello") { client.isLive() }

        val file = ByteArray(300_000) { (it % 251).toByte() }
        client.sendPrompt("s1", "Look at this", listOf(Attachment("trace.bin", "application/octet-stream", file)))
        client.sendPrompt("s1", "And then this")
        waitUntil("both prompts") { daemon.received.count { it.optString("type") == "prompt.send" } == 2 }

        val frames = synchronized(daemon.received) { daemon.received.toList() }
        val pieces = frames.filter { it.optString("type") == "upload.chunk" }
        assertEquals(3, pieces.size) // 300 000 bytes in 128 KB pieces
        assertEquals(listOf(0, 1, 2), pieces.map { it.getInt("seq") })
        assertEquals(listOf(false, false, true), pieces.map { it.getBoolean("last") })
        assertEquals("trace.bin", pieces[0].getString("name"))
        val id = pieces[0].getString("upload")
        assertTrue(pieces.all { it.getString("upload") == id })
        val rebuilt = pieces.flatMap { Base64.getDecoder().decode(it.getString("data")).toList() }.toByteArray()
        assertTrue(rebuilt.contentEquals(file))

        val order = frames.map { it.optString("type") + ":" + it.optString("text") }
        assertEquals(
            listOf("upload.chunk:", "upload.chunk:", "upload.chunk:", "prompt.send:Look at this", "prompt.send:And then this"),
            order,
        )
        val prompt = frames.first { it.optString("text") == "Look at this" }
        assertEquals(id, prompt.getJSONArray("uploads").getString(0))
        client.disconnect()
    }

    @Test
    fun aRefusalGivesTheMessageBackOnce() {
        val daemon = TinyDaemon(listOf("prompt", "upload", "attach"))
        val client = PortholeClient()
        client.connect("127.0.0.1:${daemon.server.localPort}")
        waitUntil("the hello") { client.isLive() }
        client.sendPrompt("s1", "Look at this", listOf(Attachment("a.txt", "text/plain", ByteArray(10))))
        waitUntil("the prompt") { daemon.received.any { it.optString("type") == "prompt.send" } }
        val id = daemon.received.first { it.optString("type") == "upload.chunk" }.getString("upload")
        daemon.say(JSONObject().put("v", 1).put("type", "error").put("code", "not_live").put("message", "not running").put("ref", id))
        daemon.say(JSONObject().put("v", 1).put("type", "error").put("code", "upload_failed").put("message", "again").put("ref", id))
        waitUntil("the message back") { client.failedSend.value != null }
        assertEquals("Look at this", client.failedSend.value!!.text)
        client.clearFailedSend()
        Thread.sleep(300)
        assertEquals(null, client.failedSend.value) // the second refusal does not bring it back again
        client.disconnect()
    }
}
