package dev.shrimpscript.porthole.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType
import kotlinx.coroutines.delay

/**
 * Shared primitives. Every touch target is at least 44dp and no text is below 12sp -
 * both are app-wide floors, enforced here so screens cannot quietly undercut them.
 */

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = Porthole.colors
    val interaction = remember { MutableInteractionSource() }
    // The bright capsule (DESIGN v3 §4a): the one main action on a screen.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .pressScale(interaction)
            .then(if (enabled) Modifier.bright() else Modifier.background(c.raised, PortholeShape.pill))
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = PortholeType.body.copy(fontWeight = FontWeight.SemiBold),
            // Text on the bright capsule is near-black, never white.
            color = if (enabled) c.onAccent else c.faint,
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 20.dp),
        )
    }
}

/**
 * The secondary action: a raised capsule on a plain ground, or glass where it floats over
 * content ([glass]).
 */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, glass: Boolean = false) {
    val c = Porthole.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .pressScale(interaction)
            // A filled capsule, raised by value: an outline-only button is the hairline box of DESIGN §0.
            .then(if (glass) Modifier.glass(PortholeShape.pill) else Modifier.background(c.text.copy(alpha = if (c.isLight) 0.08f else 0.10f), PortholeShape.pill))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PortholeType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.padding(14.dp))
    }
}

/** A 44dp icon target; [glass] sets it in a glass circle, for controls that float over content. */
@Composable
fun IconTarget(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = Porthole.colors.muted,
    glass: Boolean = false,
) {
    if (glass) {
        GlassCircle(icon, contentDescription, onClick, modifier, tint = Porthole.colors.text)
        return
    }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(44.dp)
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    numeric: Boolean = false,
    textStyle: TextStyle? = null,
    center: Boolean = false,
) {
    val c = Porthole.colors
    val style = (textStyle ?: if (mono) PortholeType.mono else PortholeType.body)
        .copy(color = c.text, textAlign = if (center) TextAlign.Center else TextAlign.Start)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .background(c.raised, PortholeShape.control)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = if (center) Alignment.Center else Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                placeholder,
                style = style.copy(color = c.faint),
                modifier = (if (center) Modifier.fillMaxWidth() else Modifier).clearAndSetSemantics {},
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = style,
            singleLine = true,
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Uri
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = placeholder },
        )
    }
}

/**
 * A command to run on the computer, with copy. The one place mono appears outside the
 * terminal, and always something real to paste.
 */
@Composable
fun CommandBlock(command: String, label: String? = null, modifier: Modifier = Modifier) {
    val c = Porthole.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    Column(modifier.fillMaxWidth()) {
        if (label != null) {
            Text(label, style = PortholeType.meta, color = c.faint, modifier = Modifier.padding(bottom = 6.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.deep, PortholeShape.control)
                .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(command, style = PortholeType.mono, color = c.text, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .defaultMinSize(minHeight = 44.dp, minWidth = 44.dp)
                    .clickable { clipboard.setText(AnnotatedString(command)); copied = true }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (copied) "copied" else "copy", style = PortholeType.secondary, color = if (copied) c.ok else c.muted)
            }
        }
    }
}

@Composable
fun ScreenScaffold(
    title: String,
    body: String? = null,
    ring: RingState? = null,
    content: @Composable () -> Unit,
) {
    val c = Porthole.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (ring != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ring(state = ring, size = 34.dp, strokeWidth = 3.dp)
            }
        }
        Text(title, style = Porthole.type.display, color = c.text)
        if (body != null) {
            Text(body, style = PortholeType.body, color = c.muted)
        }
        content()
    }
}

/** A row of the consent list: what this pairing actually grants. */
@Composable
fun ScopeRow(icon: String, name: String, access: String) {
    val c = Porthole.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(c.raised, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, style = PortholeType.rowTitle, color = c.accent)
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = PortholeType.rowTitle, color = c.text)
            Text(access, style = PortholeType.secondary, color = c.muted)
        }
    }
}

/** A compact action for a list row or banner; the full-width buttons would starve the text. */
@Composable
fun Pill(text: String, filled: Boolean, onClick: () -> Unit) {
    val c = Porthole.colors
    val interaction = remember { MutableInteractionSource() }
    // The visible pill is small; the touch target is the 44dp minimum around it.
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .pressScale(interaction)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = PortholeType.meta,
            color = if (filled) c.onAccent else c.muted,
            modifier = Modifier
                .then(if (filled) Modifier.bright(glow = false) else Modifier.clip(PortholeShape.pill).background(c.raised))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

