package com.vibecaster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.SubcomposeAsyncImage
import com.vibecaster.ui.theme.Cyan
import com.vibecaster.ui.theme.Pink
import com.vibecaster.ui.theme.Violet
import com.vibecaster.ui.theme.VioletDeep

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

/** Album artwork with a gradient + note fallback. */
@Composable
fun Artwork(
    model: String?,
    size: Dp,
    corner: Dp = 14.dp,
    modifier: Modifier = Modifier
) {
    val fallback: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(VioletDeep, Pink))),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.fillMaxSize(0.45f)
            )
        }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
    ) {
        SubcomposeAsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize(),
            loading = { fallback() },
            error = { fallback() }
        )
    }
}

/**
 * Custom compact slider. Replaces Material3's Slider, whose newer
 * tall-thumb design breaks the look of this app on recent Compose versions.
 */
@Composable
fun VibeSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    onChangeFinished: (() -> Unit)? = null,
    brush: Brush = Brush.horizontalGradient(listOf(VioletDeep, Pink))
) {
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(range) {
                detectTapGestures { offset ->
                    onChange(range.start + (offset.x / size.width).coerceIn(0f, 1f) * span)
                    onChangeFinished?.invoke()
                }
            }
            .pointerInput(range) {
                detectHorizontalDragGestures(
                    onDragEnd = { onChangeFinished?.invoke() },
                    onDragCancel = { onChangeFinished?.invoke() }
                ) { change, _ ->
                    change.consume()
                    onChange(range.start + (change.position.x / size.width).coerceIn(0f, 1f) * span)
                }
            }
    ) {
        val thumb = 16.dp
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f))
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(brush)
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = (maxWidth - thumb) * fraction)
                .size(thumb)
                .background(Color.White, CircleShape)
        )
    }
}

/**
 * The download ring, which doubles as the cancel button: tapping it stops the
 * download (queued or already transferring) and throws away the partial file.
 * progress < 0 = queued (waiting for its turn) — indeterminate ring.
 */
@Composable
fun DownloadRing(
    progress: Float,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val queued = progress < 0f
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(
                if (onCancel != null)
                    Modifier.clickable(onClick = onCancel)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (queued) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                strokeWidth = 2.dp,
                modifier = Modifier.size(24.dp)
            )
        } else {
            CircularProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                color = Cyan,
                trackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f),
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(24.dp)
            )
        }
        if (onCancel != null) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Cancel download",
                tint = if (queued) MaterialTheme.colorScheme.onSurfaceVariant else Cyan,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

/**
 * Ring + percentage for an in-progress download. Tap to cancel when
 * [onCancel] is supplied.
 */
@Composable
fun DownloadProgressBadge(
    progress: Float,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        DownloadRing(progress, onCancel)
        Text(
            if (progress < 0f) "queued" else "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = if (progress < 0f) MaterialTheme.colorScheme.onSurfaceVariant else Cyan
        )
    }
}

/** Dialog to pick an existing playlist or create a new one. */
@Composable
fun AddToPlaylistDialog(
    playlists: List<String>,
    onPick: (String) -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Playlists that ALREADY contain this song — shown with an "Added ✓" tag. */
    memberOf: Set<String> = emptySet()
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text("Add to playlist") },
        text = {
            Column {
                if (playlists.isEmpty()) {
                    Text(
                        "No playlists yet — create one below.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                playlists.forEach { name ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(name) }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(
                            name,
                            color = Violet,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (name in memberOf) {
                            Text(
                                "Added ✓",
                                color = Cyan,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("New playlist name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Violet,
                        cursorColor = Violet
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (newName.isNotBlank()) onCreateAndAdd(newName.trim()) }
            ) { Text("Create & add", color = Pink) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    )
}


/**
 * Orbit-styled confirmation dialog (delete/remove) — replaces the plain
 * Material alert so destructive prompts match the app's look.
 */
@Composable
fun OrbitConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    icon: ImageVector = Icons.Rounded.Delete,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            Brush.linearGradient(listOf(Pink, Color(0xFFEF4444))),
                            CircleShape
                        )
                ) {
                    Icon(icon, contentDescription = null, tint = Color.White,
                        modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(22.dp))
                Row {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) {
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = onConfirm,
                        shape = RoundedCornerShape(22.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Pink,
                            contentColor = Color(0xFF2A0A1C)
                        ),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) {
                        Text(confirmLabel, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
