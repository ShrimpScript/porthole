package dev.shrimpscript.porthole.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.R
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType

/*
 * The brand, as components.
 *
 * The mark is the ring (Ring.kt). The wordmark sets "Porthole" in the display face with
 * the ring standing in for the first "o" - the one place the mark and the name are the
 * same object. Tool marks name the things a person has to install (Tailscale, Claude
 * Code, tmux) with their own icons, tinted into our palette so they read as references,
 * not as our brand.
 */

/** "P◯rthole": the wordmark. Ring state is real - pass the live one where there is a socket. */
@Composable
fun Wordmark(
    modifier: Modifier = Modifier,
    style: TextStyle = Porthole.type.display,
    ring: RingState = RingState.Live,
    color: Color = Porthole.colors.text,
) {
    // The ring sits where the "o" would: sized to the x-height, stroke to the letter weight.
    val ringSize = (style.fontSize.value * 0.66f).dp
    val stroke = (style.fontSize.value * 0.085f).dp
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("P", style = style, color = color)
        Ring(state = ring, size = ringSize, strokeWidth = stroke, modifier = Modifier.padding(horizontal = 1.dp))
        Text("rthole", style = style, color = color)
    }
}

/** A tool's own mark in a raised tile. */
@Composable
fun ToolMark(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = Porthole.colors.text,
    contentDescription: String? = null,
) {
    Box(
        modifier
            .size(size)
            .background(Porthole.colors.raised, PortholeShape.control),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon), contentDescription = contentDescription, tint = tint,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** icon + name + one line. The rhythm of the "what you need" list and the setup steps. */
@Composable
fun ToolRow(
    @DrawableRes icon: Int,
    name: String,
    detail: String,
    modifier: Modifier = Modifier,
    tint: Color = Porthole.colors.text,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Porthole.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(c.surface, PortholeShape.card)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolMark(icon, tint = tint, contentDescription = name)
        Column(Modifier.weight(1f)) {
            Text(name, style = PortholeType.rowTitle, color = c.text)
            Text(detail, style = PortholeType.secondary, color = c.muted)
        }
        trailing?.invoke()
    }
}

object Brand {
    const val NAME = "Porthole"
    const val HEADLINE = "Run Claude Code on your computer, from your phone."
    const val SUB = "Over your own Tailscale network, with no Porthole server in between."
    val tailscale = R.drawable.ic_brand_tailscale
    val claudeCode = R.drawable.ic_brand_claudecode
    val tmux = R.drawable.ic_brand_tmux
    val github = R.drawable.ic_brand_github
}
