package com.vibecaster.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded._360
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.vibecaster.MainViewModel
import com.vibecaster.data.matchKey
import com.vibecaster.data.EightDPresets
import com.vibecaster.ui.theme.Cyan
import com.vibecaster.ui.theme.DeepSpace
import com.vibecaster.ui.theme.LocalVibePalette
import com.vibecaster.ui.theme.Pink
import com.vibecaster.ui.theme.Violet
import com.vibecaster.ui.theme.VioletDeep

@UnstableApi
@Composable
fun PlayerScreen(vm: MainViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val current by vm.current.collectAsStateWithLifecycle()
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
    val position by vm.positionMs.collectAsStateWithLifecycle()
    val duration by vm.durationMs.collectAsStateWithLifecycle()
    val effectOn by vm.effectOn.collectAsStateWithLifecycle()
    val speed by vm.rotationSpeed.collectAsStateWithLifecycle()
    val intensity by vm.intensity.collectAsStateWithLifecycle()
    val isBuffering by vm.isBuffering.collectAsStateWithLifecycle()
    val ytLoading by vm.ytLoading.collectAsStateWithLifecycle()
    val shuffleOn by vm.shuffleOn.collectAsStateWithLifecycle()
    val repeatMode by vm.repeatMode.collectAsStateWithLifecycle()
    val playbackSpeed by vm.playbackSpeed.collectAsStateWithLifecycle()
    val sleepRemaining by vm.sleepRemainingMs.collectAsStateWithLifecycle()
    val bassDb by vm.bassDb.collectAsStateWithLifecycle()
    val trebleDb by vm.trebleDb.collectAsStateWithLifecycle()
    val reverse8d by vm.reverse8d.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val downloadProgress by vm.downloadProgress.collectAsStateWithLifecycle()
    val queuedDownloads by vm.queuedDownloads.collectAsStateWithLifecycle()
    val loadingFromNet = isBuffering || ytLoading

    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }

    // Disc rotation — spins only while playing, speed matches 8D effect if on.
    var angle by remember { mutableFloatStateOf(0f) }
    val currentRotationSpeed by rememberUpdatedState(if (effectOn) speed else 0.08f)
    val reversed by rememberUpdatedState(reverse8d)
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            var last = 0L
            withFrameNanos { last = it }
            while (true) {
                withFrameNanos { now ->
                    val delta = (now - last) / 1_000_000_000f * currentRotationSpeed * 360f
                    angle = ((angle + if (reversed) -delta else delta) % 360f + 360f) % 360f
                    last = now
                }
            }
        }
    }

    val palette = LocalVibePalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(palette.playerTop, palette.playerMid, palette.playerBottom)
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                "NOW PLAYING",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            // Download / downloaded state for the current track
            current?.let { track ->
                val key = track.matchKey()
                val progress = downloadProgress[track.id]
                    ?: if (track.id in queuedDownloads) -1f else null
                val downloaded = downloads.any { it.matchKey() == key || it.uri == track.uri }
                val downloadable = track.fromYouTube ||
                    track.sourceUrl != null ||
                    track.uri.startsWith("http")
                when {
                    // The ring is also the CANCEL button — tap to stop.
                    progress != null -> DownloadRing(
                        progress = progress,
                        onCancel = { vm.cancelDownload(track) }
                    )
                    downloaded -> Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Rounded.DownloadDone,
                            contentDescription = "Downloaded",
                            tint = Cyan
                        )
                    }
                    downloadable -> IconButton(onClick = { vm.download(track) }) {
                        Icon(
                            Icons.Rounded.Download,
                            contentDescription = "Download for offline",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
            IconButton(onClick = {
                val track = current ?: return@IconButton
                val text = track.sourceUrl ?: "${track.title} — ${track.artist}"
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(intent, "Share song"))
            }) {
                Icon(
                    Icons.Rounded.Share,
                    contentDescription = "Share",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Rotating disc with glow ring
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(272.dp)
                    .background(
                        Brush.sweepGradient(
                            listOf(Violet.copy(0.45f), Pink.copy(0.35f), Cyan.copy(0.35f), Violet.copy(0.45f))
                        ),
                        CircleShape
                    )
            )
            Box(
                Modifier
                    .size(256.dp)
                    .background(DeepSpace, CircleShape)
            )
            // graphicsLayer, NOT Modifier.rotate: the lambda defers reading
            // `angle` to the draw phase. Reading it here instead put it in
            // PlayerScreen's restart scope (Column/Box are inline), so the whole
            // screen — gradients, sliders, EQ panel — recomposed every frame
            // while a track was playing.
            Box(modifier = Modifier.graphicsLayer { rotationZ = angle }) {
                Artwork(
                    model = current?.artworkUri,
                    size = 238.dp,
                    corner = 119.dp
                )
            }
            // Center hole like a vinyl
            Box(
                Modifier
                    .size(24.dp)
                    .background(DeepSpace, CircleShape)
            )
            // Live orbit: the satellite mirrors the REAL 8D pan phase,
            // so you can see the sound moving (report §5.3).
            if (effectOn) {
                val phase by androidx.compose.runtime.produceState(0.0) {
                    while (true) {
                        value = com.vibecaster.player.PlayerHolder.processor.currentPhase
                        kotlinx.coroutines.delay(50)
                    }
                }
                Box(
                    Modifier
                        .offset(
                            x = (kotlin.math.cos(phase) * 146).dp,
                            y = (kotlin.math.sin(phase) * 48).dp
                        )
                        .size(11.dp)
                        .background(Cyan, CircleShape)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            current?.title ?: "Nothing playing yet",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            current?.artist ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(12.dp))

        // Seek bar
        var dragging by remember { mutableStateOf(false) }
        var dragValue by remember { mutableFloatStateOf(0f) }
        val progress = if (duration > 0) position.toFloat() / duration else 0f
        VibeSlider(
            value = if (dragging) dragValue else progress.coerceIn(0f, 1f),
            onChange = {
                dragging = true
                dragValue = it
            },
            onChangeFinished = {
                vm.seekTo((dragValue * duration).toLong())
                dragging = false
            },
            brush = Brush.horizontalGradient(listOf(Violet, Pink)),
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatTime(if (dragging) (dragValue * duration).toLong() else position),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                formatTime(duration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(4.dp))

        // Main controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            IconButton(onClick = { vm.previous() }, modifier = Modifier.size(56.dp)) {
                Icon(
                    Icons.Rounded.SkipPrevious, null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(36.dp)
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(72.dp)
                    .background(Brush.linearGradient(listOf(VioletDeep, Pink)), CircleShape)
            ) {
                if (loadingFromNet) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(36.dp)
                    )
                } else {
                    IconButton(onClick = { vm.togglePlayPause() }, modifier = Modifier.size(72.dp)) {
                        Icon(
                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }
            IconButton(onClick = { vm.next() }, modifier = Modifier.size(56.dp)) {
                Icon(
                    Icons.Rounded.SkipNext, null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Tools row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = { vm.toggleShuffle() }) {
                Icon(
                    Icons.Rounded.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (shuffleOn) Cyan else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { vm.cycleRepeat() }) {
                Icon(
                    if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = "Repeat",
                    tint = if (repeatMode != Player.REPEAT_MODE_OFF) Cyan
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { showSpeed = true }) {
                Text(
                    "${if (playbackSpeed % 1f == 0f) playbackSpeed.toInt().toString() else playbackSpeed}x",
                    color = if (playbackSpeed != 1f) Cyan else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleSmall
                )
            }
            IconButton(onClick = { showSleep = true }) {
                Icon(
                    Icons.Rounded.Bedtime,
                    contentDescription = "Sleep timer",
                    tint = if (sleepRemaining != null) Cyan else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showQueue = true }) {
                Icon(
                    Icons.AutoMirrored.Rounded.QueueMusic,
                    contentDescription = "Queue",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = {
                showLyrics = true
                vm.fetchLyrics()
            }) {
                Icon(
                    Icons.Rounded.Lyrics,
                    contentDescription = "Lyrics",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 8D + sound panel
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded._360, null, tint = Cyan)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "8D Effect",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = effectOn,
                        onCheckedChange = { vm.setEffectOn(it) },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = VioletDeep,
                            checkedThumbColor = Color.White
                        )
                    )
                }
                if (effectOn) {
                    Spacer(Modifier.height(6.dp))
                    // One-tap mood presets (shared with desktop via :core)
                    Row {
                        EightDPresets.ALL.forEach { p ->
                            val selected =
                                kotlin.math.abs(speed - p.speed) < 0.006f &&
                                kotlin.math.abs(intensity - p.depth) < 0.02f
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    vm.setRotationSpeed(p.speed)
                                    vm.setIntensity(p.depth)
                                    vm.setBass(p.bassDb)
                                },
                                label = { Text(p.name) },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    LabeledSlider(
                        label = "Rotation speed",
                        value = speed,
                        range = 0.05f..0.5f,
                        onChange = { vm.setRotationSpeed(it) }
                    )
                    Spacer(Modifier.height(10.dp))
                    LabeledSlider(
                        label = "Depth",
                        value = intensity,
                        range = 0.2f..1f,
                        onChange = { vm.setIntensity(it) }
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Reverse direction",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = reverse8d,
                            onCheckedChange = { vm.setReverse8d(it) },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = VioletDeep,
                                checkedThumbColor = Color.White
                            )
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Equalizer panel
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = Pink)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Equalizer",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        vm.setBass(0f)
                        vm.setTreble(0f)
                    }) { Text("Reset", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                LabeledSlider(
                    label = "Bass  (${if (bassDb >= 0) "+" else ""}${bassDb.toInt()} dB)",
                    value = bassDb,
                    range = -12f..12f,
                    onChange = { vm.setBass(it) }
                )
                Spacer(Modifier.height(10.dp))
                LabeledSlider(
                    label = "Treble  (${if (trebleDb >= 0) "+" else ""}${trebleDb.toInt()} dB)",
                    value = trebleDb,
                    range = -12f..12f,
                    onChange = { vm.setTreble(it) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showSpeed) {
        AlertDialog(
            onDismissRequest = { showSpeed = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Playback speed") },
            text = {
                Column {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                        Text(
                            "${s}x" + if (s == 1f) "  (normal)" else "",
                            color = if (playbackSpeed == s) Pink else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setPlaybackSpeed(s)
                                    showSpeed = false
                                }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeed = false }) {
                    Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    if (showSleep) {
        AlertDialog(
            onDismissRequest = { showSleep = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Sleep timer") },
            text = {
                Column {
                    sleepRemaining?.let {
                        Text(
                            "Music stops in ${formatTime(it)}",
                            color = Cyan,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    listOf(15, 30, 45, 60, 90).forEach { min ->
                        Text(
                            "$min minutes",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setSleepTimer(min)
                                    showSleep = false
                                }
                                .padding(vertical = 10.dp)
                        )
                    }
                    if (sleepRemaining != null) {
                        Text(
                            "Turn off timer",
                            color = Pink,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setSleepTimer(null)
                                    showSleep = false
                                }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleep = false }) {
                    Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    if (showQueue) {
        val queue by vm.queue.collectAsStateWithLifecycle()
        val queueIndex by vm.queueIndex.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { showQueue = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Up next (${queue.size})") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    itemsIndexed(queue) { i, t ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.playQueueItem(i)
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                "${i + 1}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(28.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t.title,
                                    color = if (i == queueIndex) Violet else MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    t.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (i == queueIndex) {
                                Icon(Icons.Rounded.GraphicEq, null, tint = Pink, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQueue = false }) {
                    Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    if (showLyrics) {
        val lyrics by vm.lyrics.collectAsStateWithLifecycle()
        val lyricsLoading by vm.lyricsLoading.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { showLyrics = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(current?.title ?: "Lyrics", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                if (lyricsLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Violet, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Finding lyrics...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            lyrics ?: "No lyrics loaded.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLyrics = false }) {
                    Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
        )
        VibeSlider(
            value = value,
            onChange = onChange,
            range = range,
            brush = Brush.horizontalGradient(listOf(Cyan, Violet))
        )
    }
}
