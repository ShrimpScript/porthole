package dev.shrimpscript.porthole.ui

import android.graphics.BitmapFactory
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.shrimpscript.porthole.ui.theme.Porthole
import dev.shrimpscript.porthole.ui.theme.PortholeShape
import dev.shrimpscript.porthole.ui.theme.PortholeType
import java.io.File

/*
 * Pictures and clips in the feed: images Claude looked at, images the person sent,
 * files Claude sent, and captures of the desktop. Bytes arrive on demand from the daemon
 * and are decoded here at a bounded size; the viewer shows the full picture with pinch.
 */

/** Decode to at most [maxDim] on the long side; a 4000px screenshot must not be a 60MB bitmap. */
fun decodeBounded(bytes: ByteArray, maxDim: Int = 2048): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable
fun ImageRow(label: String, bytes: ByteArray?, onNeed: () -> Unit, onOpen: () -> Unit) {
    val c = Porthole.colors
    LaunchedEffect(bytes == null) { if (bytes == null) onNeed() }
    val bitmap = remember(bytes) { bytes?.let { decodeBounded(it, 1024) } }
    Column(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 360.dp)
            .background(c.surface, PortholeShape.card)
            .clip(PortholeShape.card)
            .clickable(enabled = bitmap != null) { onOpen() }
    ) {
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(), contentDescription = label,
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Image, contentDescription = null, tint = c.faint, modifier = Modifier.size(18.dp))
                    Text(if (bytes == null) "Loading image…" else "Could not decode this image", style = PortholeType.meta, color = c.faint)
                }
            }
        }
        Text(label, style = PortholeType.meta, color = c.faint, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
fun VideoRow(label: String, file: File?, onOpen: () -> Unit) {
    val c = Porthole.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface, PortholeShape.card)
            .clickable(enabled = file != null) { onOpen() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).background(c.raised, PortholeShape.control), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = c.accent)
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = PortholeType.secondary, color = c.text)
            Text(
                if (file != null) "${file.length() / 1024} KB · tap to play" else "Receiving…",
                style = PortholeType.meta, color = c.faint,
            )
        }
    }
}

/** Full-screen, pinch to zoom, drag to pan, tap the close mark to leave. */
@Composable
fun ImageViewer(bytes: ByteArray, label: String, onClose: () -> Unit) {
    val c = Porthole.colors
    val bitmap = remember(bytes) { decodeBounded(bytes, 4096) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(
        Modifier
            .fillMaxSize()
            .background(Porthole.colors.deep)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale > 1f) offset + pan else Offset.Zero
                }
            }
    ) {
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(), contentDescription = label,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                contentScale = ContentScale.Fit,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = PortholeType.secondary, color = c.muted, modifier = Modifier.weight(1f))
            IconTarget(Icons.Outlined.Close, "Close", onClose, tint = c.text)
        }
    }
}

@Composable
fun VideoViewer(file: File, label: String, onClose: () -> Unit) {
    val c = Porthole.colors
    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Porthole.colors.deep)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(top = 52.dp),
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoPath(file.path)
                    setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                    setOnPreparedListener { it.isLooping = false; start() }
                }
            },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = PortholeType.secondary, color = c.muted, modifier = Modifier.weight(1f))
            IconTarget(Icons.Outlined.Close, "Close", onClose, tint = c.text)
        }
    }
}

/** What a sent file is called on the phone: its own name, without the upload's time prefix. */
fun sentFileName(path: String): String = path.substringAfterLast('/').replaceFirst(Regex("^\\d{6}-"), "")

/**
 * The files a message carried, beside its bubble: pictures as small thumbnails (fetched
 * from the computer, tap to open), anything else as a chip with its name.
 */
@Composable
fun SentFiles(paths: List<String>, image: (String) -> ByteArray?, onNeed: (String) -> Unit, onOpen: (dev.shrimpscript.porthole.net.Row) -> Unit, gone: (String) -> Boolean = { false }) {
    val c = Porthole.colors
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.widthIn(max = 320.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        paths.forEach { path ->
            val name = sentFileName(path)
            val ref = "file:$path"
            if (dev.shrimpscript.porthole.net.isImagePath(path)) {
                SentThumb(image(ref), name, { onNeed(ref) }, gone = gone(ref)) {
                    onOpen(dev.shrimpscript.porthole.net.Row(kind = "image", glyph = "", text = name, metric = "", detail = "", truncated = false, imageRef = ref))
                }
            } else {
                Row(
                    Modifier
                        .background(c.surface, PortholeShape.control)
                        .border(1.dp, c.edge, PortholeShape.control)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
                    Text(name, style = PortholeType.meta, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                }
            }
        }
    }
}

/** A sent picture, small and rounded; a quiet tile while it loads. */
@Composable
fun SentThumb(bytes: ByteArray?, label: String, onNeed: () -> Unit, gone: Boolean = false, onOpen: () -> Unit) {
    val c = Porthole.colors
    LaunchedEffect(bytes == null, gone) { if (bytes == null && !gone) onNeed() }
    val bitmap = remember(bytes) { bytes?.let { decodeBounded(it, 512) } }
    Box(
        Modifier
            .size(96.dp)
            .clip(PortholeShape.control)
            .background(c.surface)
            .border(1.dp, c.edge, PortholeShape.control)
            .clickable(enabled = bitmap != null, onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = label, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (gone) Text("No longer\non the computer", style = PortholeType.meta, color = c.faint, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(6.dp))
        else Icon(Icons.Outlined.Image, contentDescription = label, tint = c.faint, modifier = Modifier.size(20.dp))
    }
}
