package com.vibecaster.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.vibecaster.data.Track
import com.vibecaster.data.matchKey

private fun greeting(): String {
    val h = java.time.LocalTime.now().hour
    return when {
        h < 5 -> "Late night vibes 🌙"
        h < 12 -> "Good morning ☀️"
        h < 17 -> "Good afternoon 👋"
        else -> "Good evening 👋"
    }
}

@Composable
private fun currentKey(vm: DesktopViewModel): String? {
    val ps by vm.player.state.collectAsState()
    return ps.track?.matchKey()
}

@Composable
fun SearchBar(placeholder: String, loading: Boolean, onSearch: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.weight(1f)
                .onFocusChanged { KeyGuard.textFieldFocused = it.isFocused },
            singleLine = true,
            shape = RoundedCornerShape(26.dp),
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
        )
        Spacer(Modifier.width(10.dp))
        Button(onClick = { onSearch(query) }, enabled = !loading,
            shape = RoundedCornerShape(26.dp), modifier = Modifier.height(52.dp)) {
            Text(if (loading) "…" else "Search")
        }
    }
}

/** Bara icon + hint jab screen khali ho. */
@Composable
fun EmptyState(title: String, hint: String) {
    Column(
        Modifier.fillMaxSize().padding(30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(painterResource("orbit.png"), null, Modifier.size(96.dp))
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Album-cover card (Discover/Playlists grids). */
@Composable
fun CoverCard(
    vm: DesktopViewModel,
    track: Track,
    list: List<Track>,
    showDownload: Boolean,
) {
    val downloads by vm.downloads.collectAsState()
    val progress by vm.downloadProgress.collectAsState()
    val queued by vm.queuedDownloads.collectAsState()
    val downloaded = downloads.any { it.matchKey() == track.matchKey() }
    val isCurrent = currentKey(vm) == track.matchKey()
    var addDialog by remember { mutableStateOf(false) }

    Column(
        Modifier.width(170.dp).padding(6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isCurrent) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable { vm.playFrom(list, track) }
            .padding(8.dp)
    ) {
        Box {
            Artwork(track.artworkUri, 154.dp, 14.dp)
            val prog = progress[track.id] ?: if (track.id in queued) -1f else null
            when {
                prog != null && prog < 0f -> CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.8f), strokeWidth = 2.dp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp))
                prog != null -> CircularProgressIndicator(progress = { prog }, color = Cyan,
                    strokeWidth = 2.dp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp))
                downloaded -> Icon(Icons.Rounded.CheckCircle, null, tint = Cyan,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp))
                showDownload -> Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp)
                        .clip(CircleShape).background(Color(0x99000000))
                        .clickable { vm.download(track) },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Download, "Download", tint = Color.White, modifier = Modifier.size(17.dp)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(track.title, style = MaterialTheme.typography.titleSmall,
            color = if (isCurrent) Violet else MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(track.artist, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (addDialog) AddToPlaylistDialog(vm, track) { addDialog = false }
}

/** Shared track list with download / add-to-playlist actions. */
@Composable
fun TrackList(
    vm: DesktopViewModel,
    tracks: List<Track>,
    showDownload: Boolean,
    onRemove: ((Track) -> Unit)? = null,
    showBadges: Boolean = false,
) {
    val downloads by vm.downloads.collectAsState()
    val progress by vm.downloadProgress.collectAsState()
    val queued by vm.queuedDownloads.collectAsState()
    val curKey = currentKey(vm)
    var addDialogTrack by remember { mutableStateOf<Track?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        items(tracks, key = { it.matchKey() + it.id }) { t ->
            TrackRow(
                track = t,
                isCurrent = curKey == t.matchKey(),
                downloaded = downloads.any { it.matchKey() == t.matchKey() },
                downloadProgress = progress[t.id] ?: if (t.id in queued) -1f else null,
                onClick = { vm.playFrom(tracks, t) },
                onDownload = if (showDownload && (t.fromYouTube || t.uri.startsWith("http")))
                    ({ vm.download(t) }) else null,
                onCancelDownload = { vm.cancelDownload(t) },
                onAddToPlaylist = { addDialogTrack = t },
                trailing = if (onRemove != null) ({
                    IconButton(onClick = { onRemove(t) }) {
                        Icon(Icons.Rounded.Delete, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }) else null,
                sourceBadge = if (showBadges) sourceBadgeOf(t) else null
            )
        }
    }
    addDialogTrack?.let { AddToPlaylistDialog(vm, it) { addDialogTrack = null } }
}

// ---------------- Search (merged: YouTube + Audius, one box) ----------------
@Composable
fun SearchScreen(vm: DesktopViewModel) {
    val results by vm.ytResults.collectAsState()
    val loading by vm.ytLoading.collectAsState()
    val error by vm.ytError.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        SearchBar("Search songs — YouTube + Audius, or paste a video/playlist link…", loading) { vm.searchAll(it) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp)) }
        val plName by vm.playlistName.collectAsState()
        if (plName != null && results.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, tint = Violet)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(plName ?: "", style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${results.size} songs", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Mid-batch the same slot turns into the escape hatch.
                    val dlProgress by vm.downloadProgress.collectAsState()
                    val dlQueued by vm.queuedDownloads.collectAsState()
                    if (dlProgress.isNotEmpty() || dlQueued.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { vm.cancelAllDownloads() },
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Icon(Icons.Rounded.Close, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Cancel all (${dlProgress.size + dlQueued.size})")
                        }
                    } else {
                        Button(onClick = { vm.downloadAll(results) }, shape = RoundedCornerShape(20.dp)) {
                            Icon(Icons.Rounded.Download, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Download all")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Violet)
            }
            results.isEmpty() -> EmptyState("Search everywhere",
                "One search — results from both YouTube and Audius, with source badges.\nPaste a video link to play it, or a playlist link to download it all.")
            else -> TrackList(vm, results, showDownload = true, showBadges = true)
        }
    }
}

// ---------------- Home (greeting + continue listening + trending) ----------------
@Composable
fun HomeScreen(vm: DesktopViewModel) {
    val audius by vm.audiusResults.collectAsState()
    val loading by vm.audiusLoading.collectAsState()
    val error by vm.audiusError.collectAsState()
    val recents by vm.recents.collectAsState()
    val recos by vm.recommendations.collectAsState()
    val ytTrend by vm.ytTrending.collectAsState()
    LaunchedEffect(Unit) { vm.loadHome() }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(greeting(), style = MaterialTheme.typography.headlineMedium)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }

        LazyColumn(Modifier.fillMaxSize()) {
            if (recents.isNotEmpty()) {
                item { SectionHeader("Continue listening") }
                item {
                    LazyRow {
                        items(recents.take(15), key = { "r" + it.matchKey() }) { t ->
                            CoverCard(vm, t, recents, showDownload = false)
                        }
                    }
                }
            }
            if (recos.isNotEmpty()) {
                item { SectionHeader("Recommended for you") }
                item {
                    LazyRow {
                        items(recos, key = { "rec" + it.matchKey() }) { t ->
                            CoverCard(vm, t, recos, showDownload = true)
                        }
                    }
                }
            }
            if (ytTrend.isNotEmpty()) {
                item { SectionHeader("Trending on YouTube") }
                item {
                    Column {
                        ytTrend.chunked(5).forEach { row ->
                            Row { row.forEach { t -> CoverCard(vm, t, ytTrend, showDownload = true) } }
                        }
                    }
                }
            }
            item { SectionHeader(if (loading) "Trending on Audius…" else "Trending on Audius") }
            item {
                if (loading && audius.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Violet)
                    }
                } else {
                    // grid inside column: chunk into rows of cards
                    Column {
                        audius.chunked(5).forEach { row ->
                            Row {
                                row.forEach { t -> CoverCard(vm, t, audius, showDownload = true) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Library ----------------
@Composable
fun LibraryScreen(vm: DesktopViewModel) {
    val local by vm.localTracks.collectAsState()
    val downloads by vm.downloads.collectAsState()
    var section by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Library", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(18.dp))
            listOf("Downloads (${downloads.size})", "Music folder (${local.size})").forEachIndexed { i, label ->
                FilterChip(selected = section == i, onClick = { section = i },
                    label = { Text(label) }, modifier = Modifier.padding(end = 8.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.refreshLocal() }) {
                Icon(Icons.Rounded.Refresh, "Rescan", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (section == 0) {
            if (downloads.isEmpty()) EmptyState("No downloads yet",
                "Click the download icon on any song —\nit will be saved here for offline listening.")
            else TrackList(vm, downloads, showDownload = false, onRemove = { vm.deleteDownload(it) })
        } else {
            if (local.isEmpty()) EmptyState("Music folder is empty",
                "Put audio files in your PC's Music folder and press Refresh.")
            else TrackList(vm, local, showDownload = false)
        }
    }
}

// ---------------- Playlists ----------------
@Composable
fun PlaylistsScreen(vm: DesktopViewModel) {
    val playlists by vm.playlists.collectAsState()
    val open by vm.openPlaylist.collectAsState()
    var showCreate by remember { mutableStateOf(false) }

    val opened = playlists.firstOrNull { it.name == open }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        if (opened == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Playlists", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.weight(1f))
                Button(onClick = { showCreate = true }, shape = RoundedCornerShape(22.dp)) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("New playlist")
                }
            }
            Spacer(Modifier.height(12.dp))
            if (playlists.isEmpty()) {
                EmptyState("No playlists yet", "Create your first playlist with \"New playlist\",\nthen add songs using the + icon on any track.")
            } else {
                LazyVerticalGrid(columns = GridCells.Adaptive(190.dp)) {
                    items(playlists, key = { it.name }) { p ->
                        Column(
                            Modifier.padding(8.dp).clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { vm.openPlaylist.value = p.name }
                        ) {
                            Box(
                                Modifier.fillMaxWidth().height(110.dp)
                                    .background(Brush.linearGradient(listOf(VioletDeep, Pink))),
                                contentAlignment = Alignment.Center
                            ) {
                                val cover = p.tracks.firstOrNull()?.artworkUri
                                if (cover != null) Artwork(cover, 110.dp, 0.dp, Modifier.fillMaxWidth())
                                else Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null,
                                    tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(46.dp))
                            }
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, fontWeight = FontWeight.Bold, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                    Text("${p.tracks.size} songs", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { vm.deletePlaylist(p.name) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Rounded.Delete, "Delete", modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.openPlaylist.value = null }) { Text("← Playlists") }
                Spacer(Modifier.width(6.dp))
                Text(opened.name, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                if (opened.tracks.isNotEmpty()) {
                    Button(onClick = { vm.playFrom(opened.tracks, opened.tracks.first()) },
                        shape = RoundedCornerShape(22.dp)) { Text("Play all") }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (opened.tracks.isEmpty()) EmptyState("This playlist is empty",
                "Add songs here using the + (add to playlist) icon on any track.")
            else TrackList(vm, opened.tracks, showDownload = true,
                onRemove = { vm.removeFromPlaylist(opened.name, it) })
        }
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("New playlist") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it },
                singleLine = true, placeholder = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { vm.createPlaylist(name); showCreate = false }) {
                Text("Create", color = Cyan) } },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } }
        )
    }
}
