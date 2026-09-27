package dev.shrimpscript.porthole.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType

/** How a modifier is currently held. */
enum class ModState { Off, Armed, Locked }

private fun ModState.next() = when (this) {
    ModState.Off -> ModState.Armed
    ModState.Armed -> ModState.Locked
    ModState.Locked -> ModState.Off
}

/**
 * Byte sequences for the keys the row sends. These are xterm conventions - getting them
 * wrong is the difference between a key row that works and one that only looks right.
 */
object Keys {
    const val ESC = "\u001B"
    fun csi(s: String) = "$ESC[$s"

    val UP = csi("A"); val DOWN = csi("B"); val RIGHT = csi("C"); val LEFT = csi("D")
    val HOME = csi("H"); val END = csi("F")
    val PGUP = csi("5~"); val PGDN = csi("6~")
    val DEL = csi("3~")
    val TAB = "\t"
    val SHIFT_TAB = csi("Z")     // what Claude Code cycles permission modes with
    val ENTER = "\r"
    val BACKSPACE = "\u007F"

    fun fn(n: Int): String = when (n) {
        1 -> "${ESC}OP"; 2 -> "${ESC}OQ"; 3 -> "${ESC}OR"; 4 -> "${ESC}OS"
        5 -> csi("15~"); 6 -> csi("17~"); 7 -> csi("18~"); 8 -> csi("19~")
        9 -> csi("20~"); 10 -> csi("21~"); 11 -> csi("23~"); 12 -> csi("24~")
        else -> ""
    }

    /** Ctrl-<letter> is the letter with the top three bits cleared. */
    fun ctrl(ch: Char): String {
        val c = ch.uppercaseChar()
        return if (c in '@'..'_') ((c.code - 0x40).toChar()).toString()
            else if (c == '?') "\u007F" else ch.toString()
    }

    /** Alt-<key> is ESC followed by the key. */
    fun alt(s: String) = ESC + s
}

private data class Key(val label: String, val send: String? = null, val action: String? = null)

/**
 * The key row.
 *
 * One compact scrollable row by default, expanding to a full panel on demand. Termius
 * ships eight rows permanently, which eats ~45% of the viewport; here the expanded panel
 * is capped at 40% and the default is a single row whose keys are Claude Code's, not a
 * generic sysadmin's.
 */
@Composable
fun KeyRow(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Porthole.colors
    val haptics = LocalHapticFeedback.current
    var ctrl by remember { mutableStateOf(ModState.Off) }
    var alt by remember { mutableStateOf(ModState.Off) }
    var expanded by remember { mutableStateOf(false) }

    // Sticky modifiers: tap arms for the next key, tap again locks, tap again clears.
    // Without this a phone simply cannot send Ctrl-C.
    fun emit(raw: String) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        var out = raw
        if (ctrl != ModState.Off && raw.length == 1) out = Keys.ctrl(raw[0])
        if (alt != ModState.Off) out = Keys.alt(out)
        onSend(out)
        if (ctrl == ModState.Armed) ctrl = ModState.Off
        if (alt == ModState.Armed) alt = ModState.Off
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(c.surface)
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 1.dp).background(c.edge).size(1.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Cap("Esc") { emit(Keys.ESC) }
            Cap("Tab") { emit(Keys.TAB) }
            Cap("⇧Tab") { emit(Keys.SHIFT_TAB) }
            ModCap("Ctrl", ctrl) { ctrl = ctrl.next() }
            ModCap("Alt", alt) { alt = alt.next() }
            Cap("^C") { onSend(Keys.ctrl('C')) }
            Cap("/") { emit("/") }
            Cap("@") { emit("@") }
            Cap("!") { emit("!") }
            Cap("←") { emit(Keys.LEFT) }
            Cap("↑") { emit(Keys.UP) }
            Cap("↓") { emit(Keys.DOWN) }
            Cap("→") { emit(Keys.RIGHT) }
            Cap("^B") { onSend(Keys.ctrl('B')) }   // tmux prefix
            Cap(if (expanded) "⌄" else "⋯") { expanded = !expanded }
        }

        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // The expanded panel never exceeds 40% of the viewport, so the terminal stays readable above it.
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CapRow(listOf(
                    Key("Home", Keys.HOME), Key("End", Keys.END),
                    Key("PgUp", Keys.PGUP), Key("PgDn", Keys.PGDN),
                    Key("Del", Keys.DEL), Key("⌫", Keys.BACKSPACE), Key("↵", Keys.ENTER),
                ), ::emit)
                CapRow((1..6).map { Key("F$it", Keys.fn(it)) }, ::emit)
                CapRow((7..12).map { Key("F$it", Keys.fn(it)) }, ::emit)
                CapRow(listOf("|", "\\", "~", "`", "-", "_", "=", "+").map { Key(it, it) }, ::emit)
                CapRow(listOf("{", "}", "[", "]", "(", ")", "<", ">").map { Key(it, it) }, ::emit)
                // The one genuinely good idea from Termius, at a fraction of the size.
                CapRow(listOf("^A", "^D", "^E", "^K", "^L", "^R", "^U", "^W", "^Z").map {
                    Key(it, Keys.ctrl(it[1]))
                }, ::emit)
            }
        }
    }
}

@Composable
private fun CapRow(keys: List<Key>, emit: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        keys.forEach { k -> Cap(k.label) { k.send?.let(emit) } }
    }
}

@Composable
private fun Cap(label: String, onClick: () -> Unit) {
    val c = Porthole.colors
    Box(
        Modifier
            // 40dp wide x 44dp tall: 44dp is the app's minimum touch target.
            .heightIn(min = 44.dp)
            .background(c.raised, PortholeShape.key)
            .border(1.dp, c.edge, PortholeShape.key)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = PortholeType.mono, color = c.muted, maxLines = 1)
    }
}

@Composable
private fun ModCap(label: String, state: ModState, onClick: () -> Unit) {
    val c = Porthole.colors
    val bg = if (state == ModState.Off) c.raised else c.accent
    val fg = if (state == ModState.Off) c.muted else c.onAccent
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .background(bg, PortholeShape.key)
            .border(1.dp, if (state == ModState.Off) c.edge else c.accent, PortholeShape.key)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Armed shows the label; locked underlines it, so the two states are never
        // confused - a locked Ctrl that looks armed sends garbage for the rest of a
        // session.
        Text(
            if (state == ModState.Locked) "$label•" else label,
            style = PortholeType.mono, color = fg, maxLines = 1,
        )
    }
}
