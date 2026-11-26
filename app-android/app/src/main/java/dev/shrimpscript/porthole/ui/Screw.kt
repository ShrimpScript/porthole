package dev.shrimpscript.porthole.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shrimpscript.porthole.ui.theme.Porthole

/*
 * The working icon, by its old name: since v3 it is the porthole's bolts at work (Bolts.kt),
 * which replaced the engine room's turning screw (2026-09-28 to 2026-11-26).
 */

/** Something is under way. [mode] picks the movement: a tool running, or Claude thinking. */
@Composable
fun Spinner(
    modifier: Modifier = Modifier,
    color: Color = Porthole.colors.text,
    size: Dp = 16.dp,
    mode: BoltsMode = BoltsMode.Running,
) = Bolts(mode, modifier, size, color)

/** The bolts at rest, for a finished turn ("Worked for 41s"). */
@Composable
fun ScrewMark(modifier: Modifier = Modifier, color: Color = Porthole.colors.faint) = BoltsStill(modifier, 14.dp, color)
