package com.vibecaster.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import com.vibecaster.MainViewModel
import com.vibecaster.data.Playlist
import com.vibecaster.data.Track
import com.vibecaster.data.matchKey
import com.vibecaster.ui.theme.Cyan
import com.vibecaster.ui.theme.Pink
import com.vibecaster.ui.theme.Violet
import com.vibecaster.ui.theme.VioletDeep

@UnstableApi
@Composable
fun PlaylistsScreen(vm: MainViewModel, padding: PaddingValues, onOpenPlayer: () -> Unit) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()

    var openedName by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var showAddSongs by remember { mutableStateOf(false) }
    var deletePlaylistTarget by remember { mutableStateOf<String?>(null) }
    val opened = playlists.firstOrNull { it.name == openedName }

    // Back gesture returns to the playlist list instead of exiting.
    BackHandler(enabled = opened != null) { openedName = null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        if (opened == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Playlists", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Your collections, played in 8D",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showCreate = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "New playlist", tint = Pink)
                }
            }

            Spacer(Modifier.height(12.dp))

            if (playlists.isEmpty()) {
                Text(
                    "No playlists yet. Tap + to create one, or use the add-to-playlist button on any song.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(playlists, key = { it.name }) { playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        onClick = { openedName = playlist.name },
                        onDelete = { deletePlaylistTarget = playlist.name }
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { openedName = null }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Violet)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        opened.name,
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${opened.tracks.size} songs",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Add songs right here — no detour through Library needed.
                IconButton(onClick = { showAddSongs = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add songs", tint = Pink)
                }
            }

            Spacer(Modifier.height(12.dp))

            if (opened.tracks.isEmpty()) {
                Text(
                    "This playlist is empty. Tap + above to add songs from your downloads, recents and library.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(opened.tracks, key = { it.sourceUrl ?: it.uri }) { track ->
                    PlaylistTrackRow(
                        track = track,
                        isCurrent = current?.let { c ->
                            (c.sourceUrl ?: c.uri) == (track.sourceUrl ?: track.uri)
                        } == true,
                        onClick = {
                            vm.playTrack(track, opened.tracks)
                            onOpenPlayer()
                        },
                        onRemove = { vm.removeFromPlaylist(opened.name, track) }
                    )
                }
            }
        }
    }

    if (showAddSongs && opened != null) {
        AddSongsDialog(
            vm = vm,
            playlist = opened,
            onDismiss = { showAddSongs = false }
        )
    }

    deletePlaylistTarget?.let { name ->
        OrbitConfirmDialog(
            title = "Delete playlist?",
            message = "\"$name\" will be deleted. The songs themselves are not removed.",
            confirmLabel = "Delete",
            onConfirm = {
                vm.deletePlaylist(name)
                deletePlaylistTarget = null
            },
            onDismiss = { deletePlaylistTarget = null }
        )
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Playlist name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Violet,
                        cursorColor = Violet
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        vm.createPlaylist(name.trim())
                        showCreate = false
                    }
                }) { Text("Create", color = Pink) }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

/**
 * Song picker for a playlist: downloads + recents + phone library in one
 * searchable, multi-select list (songs already in the playlist are hidden).
 */
@UnstableApi
@Composable
private fun AddSongsDialog(
    vm: MainViewModel,
    playlist: Playlist,
    onDismiss: () -> Unit
) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val localTracks by vm.tracks.collectAsStateWithLifecycle()

    // Phone library may not be loaded yet if the Library tab was never opened.
    LaunchedEffect(Unit) { vm.loadLibrary() }

    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }   // matchKeys
    // Source tabs: 0 = All, 1 = Downloads, 2 = Recents, 3 = Phone
    var source by remember { mutableStateOf(0) }

    val existing = remember(playlist) { playlist.tracks.map { it.matchKey() }.toSet() }
    val candidates = remember(downloads, recents, localTracks, playlist) {
        (downloads + recents + localTracks)
            .distinctBy { it.matchKey() }
            .filterNot { it.matchKey() in existing }
    }
    // Keys per source, so the tabs filter the SAME deduped candidate list
    // (a downloaded song stays selectable from any tab it appears in).
    val downloadKeys = remember(downloads) { downloads.map { it.matchKey() }.toSet() }
    val recentKeys = remember(recents) { recents.map { it.matchKey() }.toSet() }
    val localKeys = remember(localTracks) { localTracks.map { it.matchKey() }.toSet() }

    val bySource = when (source) {
        1 -> candidates.filter { it.matchKey() in downloadKeys }
        2 -> candidates.filter { it.matchKey() in recentKeys }
        3 -> candidates.filter { it.matchKey() in localKeys }
        else -> candidates
    }
    val shown = if (query.isBlank()) bySource else bySource.filter {
        it.title.contains(query, ignoreCase = true) ||
            it.artist.contains(query, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text("Add to \"${playlist.name}\"") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search your songs") },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Violet,
                        cursorColor = Violet
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                // Source tabs — jump straight to your downloads, recents or phone songs.
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(
                        "All" to candidates.size,
                        "Downloads" to candidates.count { it.matchKey() in downloadKeys },
                        "Recents" to candidates.count { it.matchKey() in recentKeys },
                        "Phone" to candidates.count { it.matchKey() in localKeys }
                    ).forEachIndexed { i, (label, count) ->
                        FilterChip(
                            selected = source == i,
                            onClick = { source = i },
                            label = { Text(if (count > 0) "$label ($count)" else label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VioletDeep.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (shown.isEmpty()) {
                    Text(
                        when {
                            candidates.isEmpty() ->
                                "Everything you have is already in this playlist. Find new songs in Search."
                            query.isNotBlank() -> "No songs match \"$query\"."
                            source == 1 -> "No downloads left to add — they're all in this playlist already."
                            source == 2 -> "No recent songs left to add."
                            else -> "No phone songs left to add."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                ) {
                    items(shown, key = { it.matchKey() }) { track ->
                        val key = track.matchKey()
                        val isPicked = key in picked
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picked = if (isPicked) picked - key else picked + key
                                }
                                .padding(vertical = 6.dp)
                        ) {
                            Artwork(model = track.artworkUri, size = 40.dp, corner = 10.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    track.artist,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                if (isPicked) Icons.Rounded.CheckCircle
                                else Icons.Rounded.RadioButtonUnchecked,
                                contentDescription = if (isPicked) "Selected" else "Not selected",
                                tint = if (isPicked) Cyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val toAdd = candidates.filter { it.matchKey() in picked }
                    if (toAdd.isNotEmpty()) vm.addAllToPlaylist(playlist.name, toAdd)
                    onDismiss()
                },
                enabled = picked.isNotEmpty(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VioletDeep)
            ) { Text(if (picked.isEmpty()) "Add" else "Add ${picked.size}", color = Color.White) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = Violet)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${playlist.tracks.size} songs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "Delete playlist",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PlaylistTrackRow(
    track: Track,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit
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
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isCurrent) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = "Playing", tint = Pink)
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Remove from playlist",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
