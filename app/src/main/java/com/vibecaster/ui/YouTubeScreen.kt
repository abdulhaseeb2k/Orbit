package com.vibecaster.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.media3.common.util.UnstableApi
import com.vibecaster.MainViewModel
import com.vibecaster.data.Track
import com.vibecaster.data.matchKey
import com.vibecaster.ui.theme.Cyan
import com.vibecaster.ui.theme.Pink
import com.vibecaster.ui.theme.Violet

/**
 * YouTube tab: one field for both searching songs and pasting video links.
 * Input containing "youtube.com" / "youtu.be" is treated as a link.
 */
@UnstableApi
@Composable
fun YouTubeScreen(vm: MainViewModel, padding: PaddingValues, onOpenPlayer: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val results by vm.ytResults.collectAsStateWithLifecycle()
    val playlistName by vm.ytPlaylistName.collectAsStateWithLifecycle()
    val queuedDownloads by vm.queuedDownloads.collectAsStateWithLifecycle()
    // 0 = All, 1 = YouTube, 2 = Audius (report 5.2: filter chips for the rare user who cares)
    var sourceFilter by remember { mutableStateOf(0) }
    var showPlaylistDownloadDialog by remember { mutableStateOf(false) }
    var dismissedClip by remember { mutableStateOf<String?>(null) }
    val clipboardText = LocalClipboardManager.current.getText()?.text?.trim() ?: ""
    val loading by vm.ytLoading.collectAsStateWithLifecycle()
    val error by vm.ytError.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val downloadProgress by vm.downloadProgress.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    fun hideKeyboard() {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    var addTarget by remember { mutableStateOf<Track?>(null) }

    fun submit() {
        hideKeyboard()
        val q = query.trim()
        if (q.isBlank()) return
        if (q.contains("youtube.com") || q.contains("youtu.be")) {
            if (q.contains("list=")) {
                // Playlist link: list all songs + one-click Download All
                vm.loadYouTubePlaylist(q)
            } else {
                vm.playFromYouTube(q)
                onOpenPlayer()
            }
        } else {
            vm.searchYouTube(q)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Search", style = MaterialTheme.typography.headlineMedium)
        Text(
            "YouTube + Audius in one search — or paste a video/playlist link",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search songs or paste a link...") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            leadingIcon = {
                IconButton(onClick = { submit() }) {
                    Icon(Icons.Rounded.Search, contentDescription = "Search", tint = Pink)
                }
            },
            trailingIcon = {
                IconButton(onClick = {
                    scope.launch {
                        val text = clipboard.getClipEntry()
                            ?.clipData?.getItemAt(0)?.text?.toString()
                        if (!text.isNullOrBlank()) query = text
                    }
                }) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste", tint = Violet)
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Violet,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                cursorColor = Violet
            ),
            modifier = Modifier.fillMaxWidth()
        )

        if (loading) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                color = Violet,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error ?: "", color = Pink, style = MaterialTheme.typography.bodyMedium)
        }

        if (playlistName != null && results.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            playlistName ?: "",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${results.size} songs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    // While a batch is running, the same slot becomes the
                    // escape hatch — one tap empties the whole queue.
                    if (downloadProgress.isNotEmpty() || queuedDownloads.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { vm.cancelAllDownloads() },
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Icon(Icons.Rounded.Close, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Cancel all")
                        }
                    } else {
                        Button(
                            onClick = { showPlaylistDownloadDialog = true },
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text("Download all")
                        }
                    }
                }
            }
        }

        // "Download all" on a YouTube playlist: offer to mirror it as an
        // Orbit playlist too, instead of dumping songs only into Downloads.
        if (showPlaylistDownloadDialog && playlistName != null) {
            val pName = playlistName ?: ""
            AlertDialog(
                onDismissRequest = { showPlaylistDownloadDialog = false },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                title = { Text("Download \"$pName\"") },
                text = {
                    Text(
                        "Also create an Orbit playlist \"$pName\" with these ${results.size} songs? " +
                            "Otherwise they'll only appear in Downloads."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.createPlaylist(pName)
                        vm.addAllToPlaylist(pName, results)
                        vm.downloadAll(results)
                        showPlaylistDownloadDialog = false
                    }) { Text("Create playlist + download", color = Cyan) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        vm.downloadAll(results)
                        showPlaylistDownloadDialog = false
                    }) {
                        Text("Just download",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        }

        Spacer(Modifier.height(10.dp))

        // Source filter chips (All / YouTube / Audius)
        Row {
            listOf("All", "YouTube", "Audius").forEachIndexed { i, label ->
                FilterChip(
                    selected = sourceFilter == i,
                    onClick = { sourceFilter = i },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }

        // Clipboard link detection: paste-to-play in one tap (report 5.2)
        val clipIsLink = (clipboardText.contains("youtube.com") || clipboardText.contains("youtu.be")) &&
            clipboardText != dismissedClip
        if (clipIsLink) {
            Spacer(Modifier.height(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Cyan.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Link copied — tap to play", color = Cyan,
                            style = MaterialTheme.typography.labelLarge)
                        Text(clipboardText.take(48), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = {
                        dismissedClip = clipboardText
                        if (clipboardText.contains("list=")) {
                            vm.loadYouTubePlaylist(clipboardText)
                        } else {
                            vm.playFromYouTube(clipboardText)
                            onOpenPlayer()
                        }
                    }) { Text("Play", color = Cyan) }
                    TextButton(onClick = { dismissedClip = clipboardText }) {
                        Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        val displayed = when (sourceFilter) {
            1 -> results.filter { it.fromYouTube }
            2 -> results.filter { !it.fromYouTube }
            else -> results
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(displayed, key = { it.id }) { track ->
                YtResultRow(
                    track = track,
                    isCurrent = current?.matchKey() == track.matchKey(),
                    downloaded = downloads.any { it.matchKey() == track.matchKey() },
                    progress = downloadProgress[track.id]
                        ?: if (track.id in queuedDownloads) -1f else null,
                    onClick = {
                        hideKeyboard()
                        vm.playTrack(track)
                        onOpenPlayer()
                    },
                    onAddToPlaylist = { addTarget = track },
                    onDownload = { vm.download(track) },
                    onCancelDownload = { vm.cancelDownload(track) }
                )
            }
        }
    }

    addTarget?.let { track ->
        AddToPlaylistDialog(
            playlists = playlists.map { it.name },
            memberOf = playlists.filter { p ->
                p.tracks.any { it.matchKey() == track.matchKey() }
            }.map { it.name }.toSet(),
            onPick = { name ->
                vm.addToPlaylist(name, track)
                addTarget = null
            },
            onCreateAndAdd = { name ->
                vm.createPlaylist(name)
                vm.addToPlaylist(name, track)
                addTarget = null
            },
            onDismiss = { addTarget = null }
        )
    }
}

@Composable
private fun YtResultRow(
    track: Track,
    isCurrent: Boolean,
    downloaded: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit
) {
    Surface(
        color = if (isCurrent) Violet.copy(alpha = 0.18f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(10.dp)
        ) {
            Artwork(model = track.artworkUri, size = 52.dp, corner = 12.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isCurrent) Violet else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Source badge (report 5.2): YT / AUD
                    val isYt = track.fromYouTube
                    Surface(
                        color = if (isYt) Color(0x33F87171) else Color(0x3322D3EE),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            if (isYt) "YT" else "AUD",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isYt) Color(0xFFF87171) else Cyan,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (track.durationMs > 0) {
                        Text(
                            "  •  " + formatTime(track.durationMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (isCurrent) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = "Playing", tint = Pink)
            }
            when {
                progress != null ->
                    DownloadProgressBadge(progress, onCancel = onCancelDownload)
                downloaded -> Icon(
                    Icons.Rounded.DownloadDone,
                    contentDescription = "Downloaded",
                    tint = Cyan
                )
                else -> IconButton(onClick = onDownload) {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = "Download for offline",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onAddToPlaylist) {
                Icon(
                    Icons.AutoMirrored.Rounded.PlaylistAdd,
                    contentDescription = "Add to playlist",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
