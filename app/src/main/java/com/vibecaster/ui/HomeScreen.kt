package com.vibecaster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.vibecaster.MainViewModel
import com.vibecaster.data.EightDPresets
import com.vibecaster.data.Playlist
import com.vibecaster.data.Track
import com.vibecaster.data.matchKey
import com.vibecaster.ui.theme.Cyan
import com.vibecaster.ui.theme.ThemeMode
import com.vibecaster.ui.theme.Pink
import com.vibecaster.ui.theme.Violet
import com.vibecaster.ui.theme.VioletDeep

/**
 * "Good morning, Abdul ☀️" — the name comes from the signed-in account
 * (typed at sign-up, or taken from the Google profile). Signed-out users
 * still get the plain greeting.
 */
private fun greeting(firstName: String?): String {
    val h = java.time.LocalTime.now().hour
    val (text, emoji) = when {
        h < 5 -> "Late night vibes" to "🌙"
        h < 12 -> "Good morning" to "☀️"
        h < 17 -> "Good afternoon" to "👋"
        else -> "Good evening" to "👋"
    }
    val who = firstName?.trim()?.takeIf { it.isNotBlank() }
    return if (who == null) "$text $emoji" else "$text, $who $emoji"
}

/** Home: resume, recommendations and trending — the app's front door. */
@UnstableApi
@Composable
fun HomeScreen(vm: MainViewModel, padding: PaddingValues, onOpenPlayer: () -> Unit) {
    val recents by vm.recents.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val recommendations by vm.recommendations.collectAsStateWithLifecycle()
    val ytTrending by vm.ytTrending.collectAsStateWithLifecycle()
    val audius by vm.audiusResults.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val downloadProgress by vm.downloadProgress.collectAsStateWithLifecycle()
    val queuedDownloads by vm.queuedDownloads.collectAsStateWithLifecycle()
    val effectOn by vm.effectOn.collectAsStateWithLifecycle()
    val speed by vm.rotationSpeed.collectAsStateWithLifecycle()
    val intensity by vm.intensity.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val account by vm.authUser.collectAsStateWithLifecycle()

    var addTarget by remember { mutableStateOf<Track?>(null) }
    var show8DPanel by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val activePreset = EightDPresets.ALL.firstOrNull {
        kotlin.math.abs(speed - it.speed) < 0.006f && kotlin.math.abs(intensity - it.depth) < 0.02f
    }?.name ?: "Custom"

    LaunchedEffect(Unit) { vm.loadDiscover() }

    fun playRow(track: Track, list: List<Track>) {
        vm.playTrack(track, list)
        onOpenPlayer()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item(key = "header") {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(greeting(account?.firstName),
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                // Settings gear on Home's header (report 5.1)
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Rounded.Settings, "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            // 8D status chip: ON/OFF + active preset; tap opens the quick panel
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { show8DPanel = true }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Icon(
                        Icons.Rounded.GraphicEq, null,
                        tint = if (effectOn) Cyan else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (effectOn) "8D Audio · ON · $activePreset" else "8D Audio · OFF",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (effectOn) Cyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "tap for presets",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (recents.isNotEmpty()) {
            item(key = "h_recents") { SectionTitle("Continue listening") }
            item(key = "row_recents") {
                LazyRow {
                    items(recents.take(15), key = { "r" + it.matchKey() }) { t ->
                        HomeCard(t, isCurrent = current?.matchKey() == t.matchKey()) {
                            playRow(t, recents)
                        }
                    }
                }
            }
        }

        if (playlists.isNotEmpty()) {
            item(key = "h_pl") { SectionTitle("Your playlists") }
            item(key = "row_pl") {
                LazyRow {
                    items(playlists, key = { "p" + it.name }) { p ->
                        PlaylistCard(p) {
                            if (p.tracks.isNotEmpty()) playRow(p.tracks.first(), p.tracks)
                        }
                    }
                }
            }
        }

        if (recommendations.isNotEmpty()) {
            item(key = "h_rec") { SectionTitle("Recommended for you") }
            items(recommendations, key = { "rec" + it.id }) { t ->
                HomeTrackRow(
                    track = t,
                    isCurrent = current?.matchKey() == t.matchKey(),
                    downloaded = downloads.any { it.matchKey() == t.matchKey() },
                    progress = downloadProgress[t.id]
                        ?: if (t.id in queuedDownloads) -1f else null,
                    onClick = { playRow(t, recommendations) },
                    onAdd = { addTarget = t },
                    onDownload = { vm.download(t) },
                    onCancelDownload = { vm.cancelDownload(t) }
                )
            }
        }

        if (ytTrending.isNotEmpty()) {
            item(key = "h_yt") { SectionTitle("Trending on YouTube") }
            items(ytTrending, key = { "yt" + it.id }) { t ->
                HomeTrackRow(
                    track = t,
                    isCurrent = current?.matchKey() == t.matchKey(),
                    downloaded = downloads.any { it.matchKey() == t.matchKey() },
                    progress = downloadProgress[t.id]
                        ?: if (t.id in queuedDownloads) -1f else null,
                    onClick = { playRow(t, ytTrending) },
                    onAdd = { addTarget = t },
                    onDownload = { vm.download(t) },
                    onCancelDownload = { vm.cancelDownload(t) }
                )
            }
        }

        if (audius.isNotEmpty()) {
            item(key = "h_aud") { SectionTitle("Trending on Audius") }
            items(audius, key = { "aud" + it.id }) { t ->
                HomeTrackRow(
                    track = t,
                    isCurrent = current?.matchKey() == t.matchKey(),
                    downloaded = downloads.any { it.matchKey() == t.matchKey() },
                    progress = downloadProgress[t.id]
                        ?: if (t.id in queuedDownloads) -1f else null,
                    onClick = { playRow(t, audius) },
                    onAdd = { addTarget = t },
                    onDownload = { vm.download(t) },
                    onCancelDownload = { vm.cancelDownload(t) }
                )
            }
        }
    }

    // 8D quick panel — control the hero feature without opening the player
    if (show8DPanel) {
        AlertDialog(
            onDismissRequest = { show8DPanel = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("8D Audio") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (effectOn) "Effect is ON" else "Effect is OFF",
                            modifier = Modifier.weight(1f))
                        Switch(checked = effectOn, onCheckedChange = { vm.setEffectOn(it) })
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        EightDPresets.ALL.forEach { p ->
                            FilterChip(
                                selected = activePreset == p.name && effectOn,
                                onClick = {
                                    vm.setEffectOn(true)
                                    vm.setRotationSpeed(p.speed)
                                    vm.setIntensity(p.depth)
                                    vm.setBass(p.bassDb)
                                },
                                label = { Text(p.name) },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                    Text(
                        "Fine-tune speed and depth in the player.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = { TextButton(onClick = { show8DPanel = false }) { Text("Done", color = Cyan) } }
        )
    }

    // Quick settings from the Home gear (theme; more lives in Library)
    // Same settings dialog as Library's gear — ONE settings, everywhere.
    if (showSettings) {
        OrbitSettingsDialog(vm) { showSettings = false }
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
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Violet,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)
    )
}

/** Vertical cover card for horizontal shelves. */
@Composable
private fun HomeCard(track: Track, isCurrent: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(124.dp)
            .padding(end = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isCurrent) Violet.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            )
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Artwork(model = track.artworkUri, size = 108.dp, corner = 10.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            track.title,
            style = MaterialTheme.typography.labelLarge,
            color = if (isCurrent) Violet else MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            track.artist,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlaylistCard(playlist: Playlist, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(124.dp)
            .padding(end = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(108.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(VioletDeep, Pink))),
            contentAlignment = Alignment.Center
        ) {
            if (playlist.tracks.firstOrNull()?.artworkUri != null) {
                Artwork(model = playlist.tracks.first().artworkUri, size = 108.dp, corner = 10.dp)
            } else {
                Icon(
                    Icons.AutoMirrored.Rounded.PlaylistPlay, null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(44.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            playlist.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            "${playlist.tracks.size} songs",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HomeTrackRow(
    track: Track,
    isCurrent: Boolean,
    downloaded: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    onAdd: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit
) {
    Surface(
        color = if (isCurrent) Violet.copy(alpha = 0.18f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(10.dp)
        ) {
            Artwork(model = track.artworkUri, size = 48.dp, corner = 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isCurrent) Violet else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onAdd) {
                Icon(
                    Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                progress != null -> DownloadRing(progress, onCancel = onCancelDownload)
                downloaded -> Icon(
                    Icons.Rounded.DownloadDone, "Downloaded",
                    tint = Cyan, modifier = Modifier.size(22.dp)
                )
                else -> IconButton(onClick = onDownload) {
                    Icon(
                        Icons.Rounded.Download, "Download",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
