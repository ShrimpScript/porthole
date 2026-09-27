package dev.shrimpscript.porthole

import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.net.Socket

/**
 * Launches the real activity against a dev daemon on 127.0.0.1:8737 and holds it there
 * while the connection goes live - the moment a crash-on-connect would close the app. It
 * runs under Robolectric, so no emulator or device is needed, and it fails with a stack
 * trace rather than a guess. It also proves the crash report reaches the computer.
 *
 * Skipped unless the dev daemon is listening:
 *   cd daemon && go build -o portholed ./cmd/portholed && cp portholed /tmp/portholed-emu
 *   PORTHOLE_SOCK=/tmp/porthole-emu.sock PORTHOLE_STATE_DIR=/tmp/porthole-emu-state \
 *     /tmp/portholed-emu serve -dev-listen 127.0.0.1:8737 &
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class AppLaunchTest {
    private val stateDir = File("/tmp/porthole-emu-state")

    private fun daemonUp(): Boolean = runCatching { Socket("127.0.0.1", 8737).close() }.isSuccess

    private fun pairCode(): String? {
        val bin = File("/tmp/portholed-emu")
        if (!bin.canExecute()) return null
        val p = ProcessBuilder(bin.path, "pair", "-no-qr")
            .apply { environment()["PORTHOLE_SOCK"] = "/tmp/porthole-emu.sock" }
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return Regex("[0-9]{3} ?[0-9]{3}").find(out)?.value?.replace(" ", "")
    }

    @Test
    fun survivesALiveConnectionAndSendsTheLastCrash() {
        assumeTrue("dev daemon not listening on 127.0.0.1:8737", daemonUp())
        val code = pairCode()
        assumeTrue("could not get a pairing code", code != null)

        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        // A phone that has already paired, with a crash waiting to be told: the state the
        // report describes.
        app.getSharedPreferences("porthole", android.content.Context.MODE_PRIVATE)
            .edit().putString("host", "127.0.0.1:8737").putBoolean("paired", true).putBoolean("tour_seen", true).apply()
        val marker = "probe-marker-" + System.currentTimeMillis()
        File(app.filesDir, "last-crash.txt").writeText("Porthole test\n$marker\n")

        val intent = Intent(app, MainActivity::class.java)
            .putExtra("host", "127.0.0.1:8737")
            .putExtra("pairCode", code)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()

        val deadline = System.currentTimeMillis() + 20_000
        var kept: File? = null
        var live = false
        while (System.currentTimeMillis() < deadline && kept == null) {
            // In bounded steps: the app animates forever by design, so an unbounded idle()
            // would run frames for minutes before this loop got to look again.
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(100)
            live = ClientHolder.client?.isLive() == true
            kept = File(stateDir, "crashes").listFiles()?.firstOrNull { it.readText().contains(marker) }
        }
        println("PROBE: live=$live sessions=${ClientHolder.client?.sessions?.value?.size} kept=${kept?.name}")
        assertTrue("the socket never went live", live)
        assertTrue("the computer did not keep the report", kept != null)
        assertTrue(
            "the sent report should have been cleared",
            CrashReporter.pending(app)?.contains(marker) != true,
        )
        kept?.delete()
        // Stop the recomposer: left running, Robolectric spins frames until the heap goes.
        runCatching { controller.pause().stop().destroy() }
    }
}
