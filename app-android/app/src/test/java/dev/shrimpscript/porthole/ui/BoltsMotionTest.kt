package dev.shrimpscript.porthole.ui

import android.graphics.Bitmap
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeTheme
import org.junit.Assert.assertEquals
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
 * The working sign moves, by state, and holds still when animations are off. Frames come
 * from a paused clock stepped by hand, so each is the same every run; the strip they make
 * is saved to build/reports/motion to compare against the clips the choreography was
 * picked from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoltsMotionTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val times = listOf(0.5f, 1.0f, 1.5f, 2.2f, 2.9f, 3.6f, 4.3f, 5.6f, 6.6f, 7.6f)

    private fun show() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PortholeTheme {
                Row(
                    Modifier.background(Porthole.colors.ground).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Bolts(BoltsMode.Running, Modifier.testTag("running"), size = 96.dp)
                    Bolts(BoltsMode.Thinking, Modifier.testTag("thinking"), size = 96.dp)
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
    }

    /** The two signs as they are drawn now. */
    private fun frame(): Pair<Bitmap, Bitmap> {
        val view = rule.activity.window.decorView
        val all = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(all))
        fun crop(tag: String): Bitmap {
            val n = rule.onNodeWithTag(tag).fetchSemanticsNode()
            val p = n.positionInWindow
            return Bitmap.createBitmap(all, p.x.toInt(), p.y.toInt(), n.size.width, n.size.height)
        }
        return crop("running") to crop("thinking")
    }

    /** How many pixels two same-sized frames disagree on, by more than a rounding step. */
    private fun differ(a: Bitmap, b: Bitmap): Int {
        var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            val d = maxOf(
                kotlin.math.abs((p shr 16 and 0xff) - (q shr 16 and 0xff)),
                kotlin.math.abs((p shr 8 and 0xff) - (q shr 8 and 0xff)),
                kotlin.math.abs((p and 0xff) - (q and 0xff)),
            )
            if (d > 24) n++
        }
        return n
    }

    private fun frames(): List<Pair<Bitmap, Bitmap>> {
        var at = 0f
        return times.map { t ->
            rule.mainClock.advanceTimeBy(((t - at) * 1000).toLong())
            at = t
            frame()
        }
    }

    @Test
    fun theBoltsMoveAndEachStateMovesItsOwnWay() {
        show()
        val fs = frames()
        // A tool running: half a second on, the light has moved on round the ring.
        assertTrue("Orbit should move between 0.5s and 1.0s", differ(fs[0].first, fs[1].first) > 400)
        assertTrue("Tide should move between 0.5s and 1.0s", differ(fs[0].second, fs[1].second) > 400)
        // Thinking is not the same motion slowed: at 2.9s Orbit winds in while Tide still ripples.
        assertTrue("Orbit and Tide should differ at 2.9s", differ(fs[4].first, fs[4].second) > 400)
        strip(fs, "bolts-frames")
    }

    @Test
    fun withAnimationsOffTheBoltsHoldStill() {
        Settings.Global.putFloat(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 0f,
        )
        show()
        val fs = frames()
        for (k in 1 until fs.size) {
            assertEquals("still at ${times[k]}s", 0, differ(fs[0].first, fs[k].first))
            assertEquals("still at ${times[k]}s", 0, differ(fs[0].second, fs[k].second))
        }
    }

    /** Running across the top, thinking beneath, one column a moment. */
    private fun strip(fs: List<Pair<Bitmap, Bitmap>>, name: String) {
        val w = fs[0].first.width; val h = fs[0].first.height
        val out = Bitmap.createBitmap(w * fs.size, h * 2, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        fs.forEachIndexed { i, (r, t) ->
            c.drawBitmap(r, (i * w).toFloat(), 0f, null)
            c.drawBitmap(t, (i * w).toFloat(), h.toFloat(), null)
        }
        val dir = File("build/reports/motion").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
