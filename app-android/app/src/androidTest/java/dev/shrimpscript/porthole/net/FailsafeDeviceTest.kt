package dev.shrimpscript.porthole.net

import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.schmizz.sshj.AndroidConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The failsafe key on real Android: sealed by the Keystore with BouncyCastle installed
 * in front of the platform's providers, as the app runs it, and signing in to a real
 * sshd. The sshd runs on the machine hosting the emulator (10.0.2.2 from inside it);
 * tools/device-failsafe-test.sh sets it up, since it must first learn this key.
 */
@RunWith(AndroidJUnit4::class)
class FailsafeDeviceTest {
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args: Bundle get() = InstrumentationRegistry.getArguments()
    private val prefs get() = ctx.getSharedPreferences("failsafe-device-test", Context.MODE_PRIVATE)

    /** Makes (or reads) the key and reports its public half for the harness. */
    @Test fun keystoreKeepsTheKey() {
        SshFailsafe.installCrypto()
        val key = FailsafeKey(prefs)
        val line = key.publicLine()
        assertNotNull("the sealed key did not open", key.provider())
        // A second holder over the same storage reads the same key back.
        assertEquals(line, FailsafeKey(prefs).publicLine())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("pubkey", line) })
    }

    @Test fun signsInToARealSshd() {
        val port = args.getString("sshPort")?.toIntOrNull()
        val user = args.getString("sshUser")
        assumeTrue("run through tools/device-failsafe-test.sh", port != null && user != null)
        SshFailsafe.installCrypto()
        SSHClient(AndroidConfig()).use { ssh ->
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connectTimeout = 10_000
            ssh.connect("10.0.2.2", port!!)
            SshFailsafe.authenticate(ssh, user!!, FailsafeKey(prefs).provider())
            ssh.startSession().use { s ->
                val cmd = s.exec("echo porthole-in")
                val out = cmd.inputStream.readBytes().toString(Charsets.UTF_8).trim()
                cmd.join(10, TimeUnit.SECONDS)
                assertEquals("porthole-in", out)
            }
        }
    }
}
