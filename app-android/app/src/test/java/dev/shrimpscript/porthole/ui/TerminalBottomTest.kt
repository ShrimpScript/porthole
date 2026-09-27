package dev.shrimpscript.porthole.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.shrimpscript.porthole.terminal.TerminalEmulator
import dev.shrimpscript.porthole.ui.theme.PortholeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * The terminal's last row sits at the bottom of the view, just above the keys: in fit
 * mode, where the desk's grid is shorter than the phone, and zoomed in, where it is
 * taller and pans. Checked on the pixels, and saved for a look.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalBottomTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun desk(): TerminalEmulator = TerminalEmulator(100, 30).apply {
        val lines = (1..29).joinToString("\r\n") { "line $it" } + "\r\n\u001b[7m BOTTOM ROW OF THE DESK \u001b[0m"
        val b = lines.toByteArray()
        write(b, b.size)
    }

    /** The lowest pixel row, in the terminal's area, that differs from the ground. */
    private fun lowestInk(name: String): Pair<Int, Int> {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File("build/reports/screens").mkdirs()
        FileOutputStream(File("build/reports/screens/$name.png")).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        // The key row is the last thing on screen; the terminal ends where it begins. Find
        // the reverse-video bar (bright pixels in a wide run) and report its bottom edge.
        var barBottom = -1
        // From just above the key row (about 64dp at the bottom) upwards; its labels are light too.
        for (y in bmp.height - 170 downTo 0) {
            var bright = 0
            for (x in 0 until bmp.width step 4) {
                val p = bmp.getPixel(x, y)
                val lum = ((p shr 16 and 0xff) + (p shr 8 and 0xff) + (p and 0xff)) / 3
                if (lum > 180) bright++
            }
            if (bright > 10) { barBottom = y; break }
        }
        return barBottom to bmp.height
    }

    @Test fun `fit mode puts the last row at the bottom`() {
        rule.setContent {
            PortholeTheme {
                TerminalBody(desk(), revision = 1, open = true, live = true, fontSp = 13f, onFontSp = {},
                    fit = true, onFit = {}, onSend = {}, onConnect = {}, modifier = Modifier.fillMaxSize())
            }
        }
        val (bar, h) = lowestInk("terminal-fit-bottom")
        // Above the key row (about 60dp = 158px at 420dpi), and within a few rows of it.
        assertTrue("the last row ends at $bar of $h", bar in (h - 260)..(h - 170))
    }

    @Test fun `zoomed in, the view starts at the bottom`() {
        rule.setContent {
            PortholeTheme {
                TerminalBody(desk(), revision = 1, open = true, live = true, fontSp = 22f, onFontSp = {},
                    fit = false, onFit = {}, onSend = {}, onConnect = {}, modifier = Modifier.fillMaxSize())
            }
        }
        val (bar, h) = lowestInk("terminal-zoom-bottom")
        assertTrue("the last row ends at $bar of $h", bar in (h - 260)..(h - 170))
    }
}
