package dev.shrimpscript.porthole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType

/**
 * Whether a drop that is still being retried should replace the screen with the failure
 * card. A session never is: its feed, its draft and whatever was open stay, under a
 * reconnecting bar that offers the card's tools. The list stays too while it has rows to
 * show. Anything else (the welcome screen, an empty list) gets the card after three tries,
 * about seven seconds, so a computer that is really gone does not hide.
 */
fun failureCardOnRetry(attempt: Int, inSession: Boolean, onList: Boolean, listHasRows: Boolean): Boolean = when {
    attempt < 3 -> false
    inSession -> false
    onList -> !listHasRows
    else -> true
}

/**
 * The connection is down and being retried: one quiet line under the header. What is on
 * screen stays as it was; [savedAt], when set, says the feed is the phone's saved copy.
 * After a few tries [onOptions] offers the failure card (the SSH failsafe, restarting the
 * daemon) for someone who wants to do more than wait.
 */
@Composable
fun ReconnectBar(text: String, savedAt: Long = 0L, onOptions: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val c = Porthole.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(c.surface, PortholeShape.control)
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spinner(color = c.warn, background = c.surface)
        Column(Modifier.weight(1f)) {
            Text(text, style = PortholeType.secondary, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (savedAt > 0L) {
                Text("Showing the copy saved at ${clockTime(java.time.Instant.ofEpochMilli(savedAt).toString())}",
                    style = PortholeType.meta, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onOptions != null) {
            Text(
                "Options", style = PortholeType.secondary, color = c.accent,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onOptions)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}
