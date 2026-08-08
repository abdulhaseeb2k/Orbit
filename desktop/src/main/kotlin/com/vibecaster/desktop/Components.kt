package com.vibecaster.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vibecaster.data.Track
import com.vibecaster.data.matchKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.Collections
import javax.imageio.ImageIO

/** While any text field is focused, global shortcuts (Space etc.) stay off. */
object KeyGuard { @Volatile var textFieldFocused = false }

/** Attach to any text field so global shortcuts pause while typing in it. */
fun Modifier.onKeyGuard(): Modifier =
    this.then(Modifier.onFocusChanged { KeyGuard.textFieldFocused = it.isFocused })

fun sourceBadgeOf(t: Track): String? = when {
    t.fromYouTube -> "YT"
    t.sourceUrl?.startsWith("audius:") == true -> "AUD"
    else -> null
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private val artCache = Collections.synchronizedMap(HashMap<String, ImageBitmap?>())

/** Loads (and caches) artwork as an ImageBitmap — usable anywhere in the UI. */
@Composable
fun rememberArtworkBitmap(model: String?): ImageBitmap? {
    var bmp by remember(model) { mutableStateOf(artCache[model]) }
    LaunchedEffect(model) {
        if (model != null && !artCache.containsKey(model)) {
            bmp = withContext(Dispatchers.IO) {
                try {
                    val img = when {
                        model.startsWith("http") -> ImageIO.read(URL(model))
                        else -> ImageIO.read(File(model.removePrefix("file://")))
                    }
                    img?.toComposeImageBitmap()
                } catch (_: Exception) { null }
            }.also { artCache[model] = it }
        }
    }
    return bmp
}

/** Album artwork with gradient + note fallback (mirrors Android Artwork()). */
@Composable
fun Artwork(model: String?, size: Dp, corner: Dp = 14.dp, modifier: Modifier = Modifier) {
    val bmp = rememberArtworkBitmap(model)
    Box(modifier.size(size).clip(RoundedCornerShape(corner))) {
        val b = bmp
        if (b != null) {
            Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(VioletDeep, Pink))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.fillMaxSize(0.45f))
            }
        }
    }
}

@Composable
fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    downloaded: Boolean,
    downloadProgress: Float?,
    onClick: () -> Unit,
    onDownload: (() -> Unit)?,
    onCancelDownload: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
    sourceBadge: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isCurrent) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(track.artworkUri, 44.dp, 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isCurrent) Violet else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (sourceBadge != null) {
            Box(
                Modifier.padding(end = 8.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (sourceBadge == "YT") Color(0x33F87171) else Color(0x3322D3EE))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(sourceBadge, style = MaterialTheme.typography.labelSmall,
                    color = if (sourceBadge == "YT") Color(0xFFF87171) else Cyan)
            }
        }
        if (track.durationMs > 0) {
            Text(formatTime(track.durationMs), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onAddToPlaylist != null) {
            IconButton(onClick = onAddToPlaylist) {
                Icon(Icons.Rounded.PlaylistAdd, "Add to playlist",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when {
            downloadProgress != null -> {
                // The ring IS the cancel button — click it to stop a queued or
                // in-flight download and bin the partial file.
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .then(
                            if (onCancelDownload != null)
                                Modifier.clickable(onClick = onCancelDownload)
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (downloadProgress < 0f) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            strokeWidth = 2.dp, modifier = Modifier.size(22.dp)
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = { downloadProgress },
                            color = Cyan, strokeWidth = 2.dp, modifier = Modifier.size(22.dp)
                        )
                    }
                    if (onCancelDownload != null) {
                        Icon(
                            Icons.Rounded.Close, "Cancel download",
                            tint = if (downloadProgress < 0f)
                                MaterialTheme.colorScheme.onSurfaceVariant else Cyan,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
            }
            downloaded -> Icon(Icons.Rounded.CheckCircle, "Downloaded", tint = Cyan,
                modifier = Modifier.size(22.dp))
            onDownload != null -> IconButton(onClick = onDownload) {
                Icon(Icons.Rounded.Download, "Download", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Violet,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

/** Choose-or-create playlist dialog. */
@Composable
fun AddToPlaylistDialog(vm: DesktopViewModel, track: Track, onDismiss: () -> Unit) {
    val playlists by vm.playlists.collectAsState()
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text("Add to playlist") },
        text = {
            Column {
                playlists.forEach { p ->
                    Text(p.name, modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { vm.addToPlaylist(p.name, track); onDismiss() }
                        .padding(10.dp))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = newName, onValueChange = { newName = it },
                    singleLine = true, placeholder = { Text("New playlist name") },
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (newName.isNotBlank()) { vm.addToPlaylist(newName.trim(), track); onDismiss() }
            }) { Text("Create & add", color = Cyan) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
