package dev.shrimpscript.porthole.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A real fish, on a real pty, drawn by this emulator - the failsafe shell's situation,
 * where nothing sits between the shell and the phone's terminal. fish 4 asks the terminal
 * what it is and draws no prompt until it hears back. Runs on Linux where fish and
 * util-linux's `script` are installed; skipped elsewhere.
 */
class FishStartsTest {
    private fun fish(): String? = listOf("/usr/bin/fish", "/opt/homebrew/bin/fish", "/usr/local/bin/fish").firstOrNull { File(it).canExecute() }

    private class Shell(answer: Boolean) : AutoCloseable {
        val emulator = TerminalEmulator(80, 24)
        private val dir = Files.createTempDirectory("fishpty").toFile()
        private val proc: Process
        init {
            // No config and private mode: nothing of the person's own fish is read or written.
            val pb = ProcessBuilder("script", "-qfec", "stty cols 80 rows 24; exec fish --no-config --private -i", "/dev/null")
            pb.environment().apply {
                remove("TMUX"); remove("WAYLAND_DISPLAY"); remove("DISPLAY")
                put("TERM", "xterm-256color"); put("PORTHOLE_FAILSAFE", "1")
                put("XDG_CONFIG_HOME", File(dir, "config").path); put("XDG_DATA_HOME", File(dir, "data").path)
            }
            pb.redirectErrorStream(true)
            proc = pb.start()
            if (answer) emulator.onReply = { r -> send(r) }
            Thread {
                val buf = ByteArray(4096)
                val input = proc.inputStream
                while (true) {
                    val n = runCatching { input.read(buf) }.getOrDefault(-1)
                    if (n <= 0) break
                    emulator.write(buf, n)
                }
            }.apply { isDaemon = true }.start()
        }
        @Synchronized fun send(s: String) { runCatching { proc.outputStream.write(s.toByteArray()); proc.outputStream.flush() } }
        fun screen(): String = synchronized(emulator) {
            (0 until emulator.rows).joinToString("\n") { y ->
                String(IntArray(emulator.cols) { x -> emulator.buffer.chars[emulator.buffer.index(x, y)] }.map { if (it == 0) ' ' else it.toChar() }.toCharArray()).trimEnd()
            }
        }
        fun waitFor(ms: Long, test: (String) -> Boolean): Boolean {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) { if (test(screen())) return true; Thread.sleep(50) }
            return test(screen())
        }
        override fun close() { proc.destroyForcibly(); dir.deleteRecursively() }
    }

    @Test
    fun fishDrawsItsPromptAndTakesTypingOnlyWhenAnswered() {
        assumeTrue("fish is not installed", fish() != null)
        // util-linux's script: a Mac's BSD script takes its command differently.
        assumeTrue("needs Linux", System.getProperty("os.name").orEmpty().startsWith("Linux"))
        assumeTrue("script is not installed", File("/usr/bin/script").canExecute())
        Shell(answer = false).use { s ->
            assertFalse("unanswered, fish draws nothing for its first seconds:\n${s.screen()}", s.waitFor(3000) { it.contains(">") })
        }
        Shell(answer = true).use { s ->
            assertTrue("answered, the prompt comes at once:\n${s.screen()}", s.waitFor(4000) { it.contains(">") })
            s.send("echo porthole-\$status-ok\r")
            assertTrue("and what is typed runs:\n${s.screen()}", s.waitFor(4000) { scr -> scr.lines().any { it.trim() == "porthole-0-ok" } })
        }
    }
}
