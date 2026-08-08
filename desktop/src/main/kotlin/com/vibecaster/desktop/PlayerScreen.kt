package com.vibecaster.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecaster.data.matchKey

/**
 * Desktop-first full player: NO scrolling on the main stage.
 * Left = stage (blurred-art backdrop, big artwork, seek, transport).
 * Right = control rack (8D / EQ / Up next / Sleep) as glass cards.
 */
@Composable
fun PlayerScreen(vm: DesktopViewModel, onClose: () -> Unit) {
    val palette = LocalVibePalette.current
    val ps by vm.player.state.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val shuffle by vm.shuffleOn.collectAsState()
    val repeat by vm.repeatMode.collectAsState()
    val progress by vm.downloadProgress.collectAsState()
    val track = ps.track
    val artBmp = rememberArtworkBitmap(track?.artworkUri)

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(palette.playerTop, palette.playerMid, palette.playerBottom)))
    ) {
        // Blurred album-art backdrop (premium look), theme overlay for legibility
        if (artBmp != null) {
            Image(artBmp, null, Modifier.fillMaxSize().blur(90.dp), contentScale = ContentScale.Crop)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            palette.playerTop.copy(alpha = 0.80f),
                            palette.playerMid.copy(alpha = 0.88f),
                            palette.playerBottom.copy(alpha = 0.95f)
                        )
                    )
                )
            )
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 10.dp)) {

            // ---- Top bar ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = onClose,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Close")
                }
                Spacer(Modifier.weight(1f))
                Text("NOW PLAYING", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 3.sp)
                Spacer(Modifier.weight(1f))
                if (track != null && track.fromYouTube) {
                    val downloaded = downloads.any { it.matchKey() == track.matchKey() }
                    val prog = progress[track.id]
                    when {
                        prog != null -> CircularProgressIndicator(progress = { prog }, color = Cyan,
                            strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                        downloaded -> Icon(Icons.Rounded.CheckCircle, "Downloaded", tint = Cyan)
                        else -> FilledTonalIconButton(onClick = { vm.download(track) },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))) {
                            Icon(Icons.Rounded.Download, "Download")
                        }
                    }
                } else Spacer(Modifier.size(40.dp))
            }

            // ---- Main area: stage + rack, fills remaining height, NO page scroll ----
            Row(Modifier.weight(1f).fillMaxWidth()) {

                // == LEFT: stage ==
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val artSize = when {
                        maxHeight < 560.dp -> 240.dp
                        maxHeight < 680.dp -> 320.dp
                        else -> 400.dp
                    }
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Artwork(track?.artworkUri, artSize, 26.dp)
                        Spacer(Modifier.height(22.dp))
                        Text(track?.title ?: "Nothing playing",
                            style = MaterialTheme.typography.headlineMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(0.85f))
                        Spacer(Modifier.height(4.dp))
                        Text(track?.artist ?: "", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        ps.error?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(0.8f))
                        }

                        // Seek
                        Spacer(Modifier.height(14.dp))
                        var dragPos by remember { mutableStateOf<Float?>(null) }
                        Slider(
                            value = dragPos ?: ps.positionMs.toFloat(),
                            onValueChange = { dragPos = it },
                            onValueChangeFinished = { dragPos?.let { vm.player.seekTo(it.toLong()) }; dragPos = null },
                            valueRange = 0f..(ps.durationMs.coerceAtLeast(1).toFloat()),
                            enabled = track != null,
                            modifier = Modifier.fillMaxWidth(0.72f)
                        )
                        Row(Modifier.fillMaxWidth(0.72f)) {
                            Text(formatTime((dragPos ?: ps.positionMs.toFloat()).toLong()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Text(formatTime(ps.durationMs), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        // Transport
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { vm.shuffleOn.value = !shuffle }) {
                                Icon(Icons.Rounded.Shuffle, "Shuffle",
                                    tint = if (shuffle) Violet else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(14.dp))
                            IconButton(onClick = { vm.previous() }, modifier = Modifier.size(52.dp)) {
                                Icon(Icons.Rounded.SkipPrevious, "Previous",
                                    tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(38.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            FilledIconButton(onClick = { vm.togglePlayPause() }, modifier = Modifier.size(72.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = Violet, contentColor = Color(0xFF1A0B2E))) {
                                if (ps.isBuffering) CircularProgressIndicator(Modifier.size(28.dp),
                                    strokeWidth = 2.5.dp, color = Color(0xFF1A0B2E))
                                else Icon(if (ps.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    null, Modifier.size(40.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            IconButton(onClick = { vm.next() }, modifier = Modifier.size(52.dp)) {
                                Icon(Icons.Rounded.SkipNext, "Next",
                                    tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(38.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            IconButton(onClick = {
                                vm.repeatMode.value = when (repeat) {
                                    RepeatMode.OFF -> RepeatMode.ALL
                                    RepeatMode.ALL -> RepeatMode.ONE
                                    RepeatMode.ONE -> RepeatMode.OFF
                                }
                            }) {
                                Icon(if (repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                    "Repeat",
                                    tint = if (repeat != RepeatMode.OFF) Violet else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                Spacer(Modifier.width(22.dp))

                // == RIGHT: control rack (glass cards) ==
                Column(
                    Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RackCard("8D Audio", Icons.Rounded.GraphicEq) {
                        val enabled by vm.eightDOn.collectAsState()
                        var rev by remember { mutableStateOf(vm.player.eightD.reverse) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = enabled,
                                onCheckedChange = { vm.setEightD(it) })
                            Spacer(Modifier.width(8.dp))
                            Text(if (enabled) "On" else "Off", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.weight(1f))
                            Text("Reverse", style = MaterialTheme.typography.bodySmall)
                            Checkbox(checked = rev, onCheckedChange = { rev = it; vm.player.eightD.reverse = it })
                        }
                        var speed by remember { mutableStateOf(vm.player.eightD.rotationSpeed) }
                        RackSlider("Rotation  ${"%.2f".format(speed)} r/s", speed, 0.05f..0.5f) {
                            speed = it; vm.player.eightD.rotationSpeed = it
                        }
                        var depth by remember { mutableStateOf(vm.player.eightD.intensity) }
                        RackSlider("Depth  ${"%.0f".format(depth * 100)}%", depth, 0f..1f) {
                            depth = it; vm.player.eightD.intensity = it
                        }
                    }

                    RackCard("Equalizer", Icons.Rounded.VolumeUp) {
                        var bass by remember { mutableStateOf(vm.player.tone.bassDb) }
                        RackSlider("Bass  ${"%+.0f".format(bass)} dB", bass, -12f..12f) {
                            bass = it; vm.player.tone.bassDb = it
                        }
                        var treble by remember { mutableStateOf(vm.player.tone.trebleDb) }
                        RackSlider("Treble  ${"%+.0f".format(treble)} dB", treble, -12f..12f) {
                            treble = it; vm.player.tone.trebleDb = it
                        }
                        val vol by vm.volumeFlow.collectAsState()
                        RackSlider("Volume  ${"%.0f".format(vol * 100)}%", vol, 0f..1.5f) {
                            vm.setVolume(it)
                        }
                    }

                    UpNextCard(vm)
                    SleepCard(vm)
                }
            }
        }
    }
}

@Composable
private fun RackCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Violet, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = Violet,
                    fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

/** Compact labelled slider for the rack. */
@Composable
private fun RackSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>,
                       onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = value, onValueChange = onChange, valueRange = range,
            modifier = Modifier.height(30.dp))
    }
}

@Composable
private fun UpNextCard(vm: DesktopViewModel) {
    val queue by vm.queue.collectAsState()
    val idx by vm.queueIndex.collectAsState()
    val upNext = queue.drop(idx + 1).take(3)
    if (upNext.isEmpty()) return
    RackCard("Up next", Icons.Rounded.SkipNext) {
        upNext.forEach { t ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clickable { vm.playFrom(queue, t) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Artwork(t.artworkUri, 34.dp, 8.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Text(formatTime(t.durationMs), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SleepCard(vm: DesktopViewModel) {
    val sleepLeft by vm.sleepRemainingMs.collectAsState()
    RackCard("Sleep timer", Icons.Rounded.Timer) {
        if (sleepLeft != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Stopping in ${formatTime(sleepLeft!!)}", color = Cyan,
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.setSleepTimer(null) }) { Text("Cancel") }
            }
        } else {
            Row {
                listOf(15, 30, 60, 90).forEach { m ->
                    FilterChip(selected = false, onClick = { vm.setSleepTimer(m) },
                        label = { Text("${m}m") }, modifier = Modifier.padding(end = 8.dp))
                }
            }
        }
    }
}
