package dev.shrimpscript.porthole.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
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
 * A session opens out of its porthole: the new screen shows first in a circle round the
 * porthole, the circle grows until it covers the screen, and closing runs it back. Frames
 * from a paused clock; the strip is saved to build/reports/motion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApertureTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val listColour = Color(0xFF203040)
    private val sessionColour = Color(0xFFE0A030)

    private fun pixel(b: Bitmap, xDp: Float, yDp: Float): Int {
        val d = rule.activity.resources.displayMetrics.density
        return b.getPixel((xDp * d).toInt().coerceIn(0, b.width - 1), (yDp * d).toInt().coerceIn(0, b.height - 1))
    }

    private fun isSession(px: Int) = (px shr 16 and 0xff) > 0xB0 && (px shr 8 and 0xff) > 0x80 && (px and 0xff) < 0x60

    private fun frame(): Bitmap {
        val view = rule.activity.window.decorView
        val all = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(all))
        return all
    }

    @Test
    fun theSessionGrowsOutOfItsPortholeAndShrinksBackIn() {
        var open by mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PortholeTheme {
                val aperture = remember { Aperture().apply { key = "s1" } }
                CompositionLocalProvider(LocalAperture provides aperture) {
                    RouteTransition(target = open, forward = open, opensThrough = { from, to -> !from && to }) { isOpen ->
                        if (isOpen) Box(Modifier.fillMaxSize().background(sessionColour))
                        else Box(Modifier.fillMaxSize().background(listColour)) {
                            // the porthole, 60dp in from the left and 300dp down
                            Box(Modifier.offset(32.dp, 272.dp).size(56.dp).testTag("porthole").apertureSource("s1").background(Color.Black, CircleShape))
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(100)
        val node = rule.onNodeWithTag("porthole").fetchSemanticsNode()
        val d = rule.activity.resources.displayMetrics.density
        val px = (node.positionInWindow.x + node.size.width / 2f) / d
        val py = (node.positionInWindow.y + node.size.height / 2f) / d

        open = true
        rule.mainClock.advanceTimeByFrame()
        val frames = mutableListOf<Bitmap>()
        for (ms in listOf(16, 60, 120, 200, 400)) {
            rule.mainClock.advanceTimeBy(if (frames.isEmpty()) 16L else ms.toLong() - listOf(16, 60, 120, 200, 400)[frames.size - 1])
            frames += frame()
        }
        // Early: the session shows round the porthole, not yet in the far corner.
        assertTrue("session at the porthole early", isSession(pixel(frames[1], px, py)))
        assertTrue("the far corner still the list early", !isSession(pixel(frames[1], 400f, 860f)))
        // Done: the session covers everything.
        assertTrue("session in the far corner at the end", isSession(pixel(frames[4], 400f, 860f)))

        open = false
        rule.mainClock.advanceTimeByFrame()
        val back = mutableListOf<Bitmap>()
        for (k in 0 until 3) { rule.mainClock.advanceTimeBy(70); back += frame() }
        rule.mainClock.advanceTimeBy(400)
        back += frame()
        // Closing: the far corner is the list again before the porthole is.
        assertTrue("closing: far corner back to the list first", !isSession(pixel(back[1], 400f, 860f)))
        assertTrue("closed: the list everywhere", !isSession(pixel(back[3], px, py)))

        strip(frames + back)
    }

    @Test
    fun withAnimationsOffItSwitchesAtOnce() {
        android.provider.Settings.Global.putFloat(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f,
        )
        var open by mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PortholeTheme {
                val aperture = remember { Aperture().apply { key = "s1" } }
                CompositionLocalProvider(LocalAperture provides aperture) {
                    RouteTransition(target = open, forward = open, opensThrough = { from, to -> !from && to }) { isOpen ->
                        if (isOpen) Box(Modifier.fillMaxSize().background(sessionColour))
                        else Box(Modifier.fillMaxSize().background(listColour)) {
                            Box(Modifier.offset(32.dp, 272.dp).size(56.dp).apertureSource("s1"))
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        open = true
        // Frame by frame: the whole screen is one or the other, never a circle of one in the other.
        for (k in 1..8) {
            rule.mainClock.advanceTimeByFrame()
            val f = frame()
            assertEquals("frame $k: one screen, not a circle", isSession(pixel(f, 400f, 860f)), isSession(pixel(f, 60f, 300f)))
        }
        assertTrue("switched within eight frames", isSession(pixel(frame(), 400f, 860f)))
    }

    private fun strip(fs: List<Bitmap>) {
        val w = fs[0].width / 4; val h = fs[0].height / 4
        val out = Bitmap.createBitmap(w * fs.size, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        fs.forEachIndexed { i, b -> c.drawBitmap(Bitmap.createScaledBitmap(b, w, h, true), (i * w).toFloat(), 0f, null) }
        val dir = File("build/reports/motion").apply { mkdirs() }
        FileOutputStream(File(dir, "aperture.png")).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
