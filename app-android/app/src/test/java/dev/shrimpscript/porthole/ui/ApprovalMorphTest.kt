package dev.shrimpscript.porthole.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.net.Approval
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * In its own session an approval grows out of the message box: the sheet starts as the box's
 * bounds and opens to its own, its words coming in late. Frames at set points of its
 * progress; the strip is saved to build/reports/motion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApprovalMorphTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun frame(): Bitmap {
        val view = rule.activity.window.decorView
        val all = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(all))
        return all
    }

    private var box = Rect.Zero

    @Test
    fun theSheetGrowsOutOfTheMessageBox() {
        var p by mutableFloatStateOf(0f)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PortholeTheme {
                BoxWithConstraints(Modifier.fillMaxSize().background(Porthole.colors.ground)) {
                    val d = LocalDensity.current
                    val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
                    // a message box 12dp in from the sides and the bottom, 104dp tall
                    box = with(d) { Rect(12.dp.toPx(), h - 116.dp.toPx(), w - 12.dp.toPx(), h - 12.dp.toPx()) }
                    ApprovalOverlay(
                        approval = Approval("t1", "s1", "Bash", "npm publish --access public", "/srv/app", 120),
                        onAllow = {}, onDeny = {}, onExpired = {},
                        from = box, progress = { p },
                    )
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        val frames = mutableListOf<Bitmap>()
        for (v in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            p = v
            rule.mainClock.advanceTimeByFrame()
            frames += frame()
        }
        strip(frames)
        // Window coordinates of the content: the box is in the root's.
        val at = IntArray(2).also { rule.activity.findViewById<android.view.View>(android.R.id.content).getLocationInWindow(it) }
        val x = (at[0] + box.center.x).toInt()
        // Above the box: no sheet there at the start; the sheet's glass and words at the end.
        val above = (at[1] + box.top - 200 * rule.activity.resources.displayMetrics.density).toInt()
        assertNotEquals("the sheet covers the space above the box once open", frames[0].getPixel(x, above), frames[4].getPixel(x, above))
        // The morph starts at the box: at nothing it is not drawn yet (it fades in over the
        // box, so the box cross-fades into it); a quarter of the way it covers the box.
        val inBox = (at[1] + box.center.y).toInt()
        val bare = frames[0].getPixel(x, at[1] + 40)
        assertEquals("not drawn at the very start", bare, frames[0].getPixel(x, inBox))
        assertNotEquals("over the box a quarter of the way", bare, frames[1].getPixel(x, inBox))
        assertEquals(frames[0].width, frames[4].width)
    }

    private fun strip(fs: List<Bitmap>) {
        val w = fs[0].width / 3; val h = fs[0].height / 3
        val out = Bitmap.createBitmap(w * fs.size, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        fs.forEachIndexed { i, b -> c.drawBitmap(Bitmap.createScaledBitmap(b, w, h, true), (i * w).toFloat(), 0f, null) }
        val dir = File("build/reports/motion").apply { mkdirs() }
        FileOutputStream(File(dir, "approval-morph.png")).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
