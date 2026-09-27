package dev.shrimpscript.porthole.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FailsafeKeyTest {
    /** Stands in for the Keystore, which Robolectric does not have; still not plaintext. */
    private object XorVault : Vault {
        override fun seal(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x5a).toByte() }
        override fun open(sealed: ByteArray) = seal(sealed)
    }

    private fun seed() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test fun `the public line is an OpenSSH Ed25519 key`() {
        val line = publicLineOf(seed())
        val (type, body) = line.split(" ")
        assertEquals("ssh-ed25519", type)
        val blob = Base64.getDecoder().decode(body)
        // string "ssh-ed25519", string key: 4 + 11 + 4 + 32 bytes.
        assertEquals(51, blob.size)
        assertEquals("ssh-ed25519", String(blob, 4, 11))
        assertEquals(32, blob[18].toInt())
    }

    @Test fun `the key is made once, kept sealed and can be forgotten`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("t", Context.MODE_PRIVATE)
        val key = FailsafeKey(prefs, XorVault)
        assertFalse(key.exists)
        assertNull(key.provider())
        val line = key.publicLine()
        assertTrue(key.exists)
        assertEquals(line, key.publicLine())
        assertNotNull(key.provider())
        // The seed is not in the preferences as it is.
        val sealed = prefs.getString("failsafe_key_sealed", "")!!
        assertFalse(prefs.all.values.any { it is String && it.contains(line.substringAfter(' ').take(8)) && it != line })
        assertTrue(sealed.isNotEmpty())
        key.forget()
        assertFalse(key.exists)
        assertNull(key.provider())
    }

    /**
     * The failsafe's own sign-in code against a real sshd, with the line the daemon writes:
     * a private sshd on a spare loopback port, its own host key and keys file.
     */
    @Test fun `a real sshd lets the phone's key in`() {
        val sshd = listOf("/usr/sbin/sshd", "/usr/bin/sshd").map(::File).firstOrNull { it.exists() }
        assumeTrue("no sshd here", sshd != null)
        assumeTrue("no ssh-keygen here", File("/usr/bin/ssh-keygen").exists())
        val dir = Files.createTempDirectory("porthole-sshd").toFile()
        try {
            val host = File(dir, "host")
            val gen = ProcessBuilder("/usr/bin/ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", host.path)
                .redirectErrorStream(true).start()
            assertTrue(gen.waitFor(20, TimeUnit.SECONDS) && gen.exitValue() == 0)

            val seed = seed()
            val keys = File(dir, "authorized_keys")
            keys.writeText("restrict,pty,from=\"127.0.0.1\" " + publicLineOf(seed).substringBeforeLast(' ') + " porthole:nTest\n")
            val port = ServerSocket(0).use { it.localPort }
            val cfg = File(dir, "sshd_config")
            cfg.writeText(
                """
                ListenAddress 127.0.0.1
                Port $port
                HostKey ${host.path}
                AuthorizedKeysFile ${keys.path}
                PidFile ${File(dir, "sshd.pid").path}
                StrictModes no
                PasswordAuthentication no
                KbdInteractiveAuthentication no
                """.trimIndent() + "\n",
            )
            val daemon = ProcessBuilder(sshd!!.path, "-D", "-e", "-f", cfg.path)
                .redirectErrorStream(true).redirectOutput(File(dir, "sshd.log")).start()
            try {
                waitFor(port)
                val user = System.getProperty("user.name")

                SSHClient(DefaultConfig()).use { ssh ->
                    ssh.addHostKeyVerifier(PromiscuousVerifier())
                    ssh.connect("127.0.0.1", port)
                    SshFailsafe.authenticate(ssh, user, keyPairOf(seed))
                    ssh.startSession().use { s ->
                        val cmd = s.exec("echo porthole-in")
                        val out = cmd.inputStream.readBytes().toString(Charsets.UTF_8).trim()
                        cmd.join(10, TimeUnit.SECONDS)
                        assertEquals("porthole-in", out)
                    }
                }

                // Another phone's key: refused, with a reason a person can act on.
                SSHClient(DefaultConfig()).use { ssh ->
                    ssh.addHostKeyVerifier(PromiscuousVerifier())
                    ssh.connect("127.0.0.1", port)
                    val err = runCatching { SshFailsafe.authenticate(ssh, user, keyPairOf(seed())) }.exceptionOrNull()
                    assertTrue("got $err", err is UserAuthException && err.message!!.contains("add it again"))
                }
            } finally {
                daemon.destroy()
                daemon.waitFor(5, TimeUnit.SECONDS)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun waitFor(port: Int) {
        repeat(50) {
            val up = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) } }.isSuccess
            if (up) return
            Thread.sleep(100)
        }
        error("sshd did not start")
    }
}
