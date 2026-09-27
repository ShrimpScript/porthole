package dev.shrimpscript.porthole

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class UpdaterTest {
    @Test fun `dotted versions compare numerically`() {
        assertTrue(Updater.isNewer("0.9.0", "0.8.2"))
        assertTrue(Updater.isNewer("0.10.0", "0.9.1"))
        assertTrue(Updater.isNewer("1.0", "0.99.99"))
        assertFalse(Updater.isNewer("0.8.2", "0.8.2"))
        assertFalse(Updater.isNewer("0.8.1", "0.8.2"))
        assertFalse(Updater.isNewer("0.9", "0.9.0"))
    }

    /** A one-thread HTTP server on loopback: each request is counted, then [answer]ed by path. */
    private class Local(val answer: (path: String) -> String) : AutoCloseable {
        val hits = AtomicInteger()
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val base = "http://127.0.0.1:${socket.localPort}"

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val s = runCatching { socket.accept() }.getOrNull() ?: break
                    runCatching {
                        s.use {
                            it.soTimeout = 5_000
                            val reader = it.getInputStream().bufferedReader()
                            val line = reader.readLine() ?: return@use
                            while (reader.readLine()?.isNotEmpty() == true) Unit
                            hits.incrementAndGet()
                            it.getOutputStream().write(answer(line.split(' ')[1]).toByteArray())
                        }
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    private fun redirect(to: String) = "HTTP/1.1 302 Found\r\nLocation: $to\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    private val apk = "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\napk"

    private fun client(guard: Updater.GitHubOnly) = OkHttpClient.Builder().addNetworkInterceptor(guard).build()

    private fun fetch(client: OkHttpClient, url: String): Result<String> =
        runCatching { client.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() } }

    @Test fun `the release download client checks every hop`() {
        assertTrue(Updater.https.networkInterceptors.any { it is Updater.GitHubOnly })
    }

    @Test fun `a redirect off the trusted host is refused before the next host is contacted`() {
        Local { apk }.use { elsewhere ->
            Local { redirect(elsewhere.base + "/porthole.apk") }.use { github ->
                val res = fetch(client(Updater.GitHubOnly { it.startsWith(github.base) }), github.base + "/latest")
                assertTrue(res.exceptionOrNull() is IOException)
                assertEquals(1, github.hits.get())
                assertEquals(0, elsewhere.hits.get())
            }
        }
    }

    @Test fun `a redirect that stays on the trusted host is followed`() {
        lateinit var github: Local
        github = Local { path -> if (path == "/porthole.apk") apk else redirect(github.base + "/porthole.apk") }
        github.use {
            assertEquals("apk", fetch(client(Updater.GitHubOnly { u -> u.startsWith(it.base) }), it.base + "/latest").getOrThrow())
            assertEquals(2, it.hits.get())
        }
    }

    @Test fun `by default only GitHub over HTTPS gets a request`() {
        Local { apk }.use { local ->
            assertTrue(fetch(client(Updater.GitHubOnly()), local.base + "/porthole.apk").exceptionOrNull() is IOException)
            assertEquals(0, local.hits.get())
        }
    }
}
