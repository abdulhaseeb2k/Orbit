package com.vibecaster.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.vibecaster.data.EightDPresets
import com.vibecaster.data.matchKey

// Intent-centric IA (report Part B): Home / Search / Library (+ Playlists via sidebar list)
enum class AppTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    SEARCH("Search", Icons.Rounded.Search),
    LIBRARY("Library", Icons.Rounded.LibraryMusic),
    PLAYLISTS("Playlists", Icons.AutoMirrored.Rounded.QueueMusic),
}
private val NAV_TABS = listOf(AppTab.HOME, AppTab.SEARCH, AppTab.LIBRARY)

fun main() = application {
    val state = rememberWindowState(size = DpSize(1220.dp, 820.dp))
    val player = remember { DesktopPlayer() }
    val vm = remember { DesktopViewModel(player) }

    // System tray: control Orbit even when minimized
    Tray(
        icon = painterResource("orbit.png"),
        tooltip = "Orbit — 8D Audio",
        menu = {
            Item("Play / Pause") { vm.togglePlayPause() }
            Item("Next") { vm.next() }
            Item("Previous") { vm.previous() }
            Separator()
            Item("Exit") { player.stop(); exitApplication() }
        }
    )

    Window(
        onCloseRequest = { player.stop(); exitApplication() },
        title = "Orbit",
        icon = painterResource("orbit.png"),
        state = state,
        undecorated = true,
        onPreviewKeyEvent = { handleShortcut(vm, it) },
    ) {
        DisposableEffect(Unit) { onDispose { player.stop() } }
        LaunchedEffect(Unit) { ToolBootstrap.ensure(player) }
        val theme by vm.themeMode.collectAsState()
        OrbitTheme(theme) {
            Column(Modifier.fillMaxSize()) {
                OrbitTitleBar(vm, state, onClose = { player.stop(); exitApplication() })
                // First launch: demo + theme + sign-in (same flow as the phone app).
                val showOnboarding by vm.showOnboarding.collectAsState()
                if (showOnboarding) OnboardingScreen(vm)
                else AppRoot(vm)
            }
        }
    }
}

/** Global keyboard shortcuts (report B.3). When a text field is focused, typing wins. */
private fun handleShortcut(vm: DesktopViewModel, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val typing = KeyGuard.textFieldFocused
    val ps = vm.player.state.value
    return when {
        e.key == Key.Spacebar && !typing -> { vm.togglePlayPause(); true }
        e.key == Key.DirectionRight && e.isCtrlPressed -> { vm.next(); true }
        e.key == Key.DirectionLeft && e.isCtrlPressed -> { vm.previous(); true }
        e.key == Key.DirectionRight && !typing && ps.track != null ->
            { vm.player.seekTo(ps.positionMs + 10_000); true }
        e.key == Key.DirectionLeft && !typing && ps.track != null ->
            { vm.player.seekTo((ps.positionMs - 10_000).coerceAtLeast(0)); true }
        e.key == Key.DirectionUp && !typing -> { vm.setVolume(vm.volumeFlow.value + 0.05f); true }
        e.key == Key.DirectionDown && !typing -> { vm.setVolume(vm.volumeFlow.value - 0.05f); true }
        e.key == Key.Eight && !typing -> { vm.setEightD(!vm.eightDOn.value); true }
        e.key == Key.Slash && !typing -> { vm.currentTab.value = AppTab.SEARCH; true }
        e.key == Key.D && e.isCtrlPressed -> { ps.track?.let { vm.download(it) }; true }
        e.key == Key.Q && e.isCtrlPressed -> { vm.showQueue.value = !vm.showQueue.value; true }
        else -> false
    }
}

@Composable
private fun WindowScope.OrbitTitleBar(vm: DesktopViewModel, state: WindowState, onClose: () -> Unit) {
    val palette = LocalVibePalette.current
    var showSettings by remember { mutableStateOf(false) }
    WindowDraggableArea {
        Row(
            Modifier.fillMaxWidth().height(42.dp).background(palette.navBar).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(painterResource("orbit.png"), null, Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("Orbit", fontWeight = FontWeight.Black, color = Violet,
                style = MaterialTheme.typography.titleMedium)
            Text("  •  8D Audio", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            TitleBarButton(Icons.Rounded.Settings, "Settings") { showSettings = true }
            TitleBarButton(Icons.Rounded.Remove, "Minimize") { state.isMinimized = true }
            TitleBarButton(Icons.Rounded.CropSquare, "Maximize") {
                state.placement = if (state.placement == WindowPlacement.Maximized)
                    WindowPlacement.Floating else WindowPlacement.Maximized
            }
            TitleBarButton(Icons.Rounded.Close, "Close", danger = true, onClick = onClose)
        }
    }
    if (showSettings) SettingsDialog(vm) { showSettings = false }
}

@Composable
private fun TitleBarButton(icon: ImageVector, desc: String, danger: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(icon, desc, modifier = Modifier.size(17.dp),
            tint = if (danger) Pink else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AppRoot(vm: DesktopViewModel) {
    val tab by vm.currentTab.collectAsState()
    var showPlayer by remember { mutableStateOf(false) }
    val showQueue by vm.showQueue.collectAsState()
    val palette = LocalVibePalette.current

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(palette.bgTop, palette.bgMid, palette.bgBottom)))
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 950.dp
            Column(Modifier.fillMaxSize()) {
                ToolSetupBanner()
                Row(Modifier.weight(1f)) {
                    Sidebar(vm, tab, compact) {
                        vm.currentTab.value = it
                        if (it != AppTab.PLAYLISTS) vm.openPlaylist.value = null
                    }
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            AppTab.HOME -> HomeScreen(vm)
                            AppTab.SEARCH -> SearchScreen(vm)
                            AppTab.LIBRARY -> LibraryScreen(vm)
                            AppTab.PLAYLISTS -> PlaylistsScreen(vm)
                        }
                    }
                    AnimatedVisibility(
                        visible = showQueue,
                        enter = slideInHorizontally(initialOffsetX = { it }),
                        exit = slideOutHorizontally(targetOffsetX = { it })
                    ) { QueuePanel(vm) }
                }
                TransportBar(vm, onOpenPlayer = { showPlayer = true })
            }
        }

        AnimatedVisibility(
            visible = showPlayer,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            PlayerScreen(vm) { showPlayer = false }
        }
    }
}

@Composable
private fun Sidebar(vm: DesktopViewModel, tab: AppTab, compact: Boolean, onSelect: (AppTab) -> Unit) {
    val palette = LocalVibePalette.current
    val playlists by vm.playlists.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val width = if (compact) 64.dp else 212.dp

    Column(
        Modifier.width(width).fillMaxHeight().background(palette.bgTop)
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 12.dp)
    ) {
        NAV_TABS.forEach { t ->
            val selected = tab == t
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) VioletDeep else Color.Transparent)
                    .clickable { onSelect(t) }
                    .padding(horizontal = if (compact) 0.dp else 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start
            ) {
                Icon(t.icon, t.label, modifier = Modifier.size(20.dp),
                    tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                if (!compact) {
                    Spacer(Modifier.width(12.dp))
                    Text(t.label, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        if (!compact) {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Spacer(Modifier.height(10.dp))
            Text("PLAYLISTS", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                playlists.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable { vm.openPlaylist.value = p.name; onSelect(AppTab.PLAYLISTS) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, Modifier.size(16.dp),
                            tint = Violet.copy(alpha = 0.8f))
                        Spacer(Modifier.width(8.dp))
                        Text(p.name, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable { vm.openPlaylist.value = null; onSelect(AppTab.PLAYLISTS) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(16.dp), tint = Cyan)
                    Spacer(Modifier.width(8.dp))
                    Text("New playlist", style = MaterialTheme.typography.bodySmall, color = Cyan)
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(AppTab.LIBRARY) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Download, null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("Downloads · ${downloads.size}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

/** First-launch tool download progress (ffmpeg / yt-dlp). */
@Composable
private fun ToolSetupBanner() {
    val st by ToolBootstrap.state.collectAsState()
    when (val s = st) {
        is ToolBootstrap.State.Downloading -> Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                if (s.progress >= 0f)
                    "Setting up audio engine — downloading ${s.tool}  ${(s.progress * 100).toInt()}%"
                else "Setting up audio engine — ${s.tool}…",
                style = MaterialTheme.typography.bodySmall, color = Cyan
            )
            Spacer(Modifier.height(5.dp))
            if (s.progress >= 0f) LinearProgressIndicator(
                progress = { s.progress }, color = Cyan,
                modifier = Modifier.fillMaxWidth().height(4.dp))
            else LinearProgressIndicator(color = Cyan, modifier = Modifier.fillMaxWidth().height(4.dp))
        }
        is ToolBootstrap.State.Failed -> Text(
            s.message, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
        else -> {}
    }
}

/** Right-side queue panel (Ctrl+Q, or the ☰ in the transport bar). */
@Composable
private fun QueuePanel(vm: DesktopViewModel) {
    val palette = LocalVibePalette.current
    val queue by vm.queue.collectAsState()
    val idx by vm.queueIndex.collectAsState()
    Column(
        Modifier.width(300.dp).fillMaxHeight().background(palette.bgTop).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Queue", style = MaterialTheme.typography.titleMedium, color = Violet,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("${queue.size} songs", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        if (queue.isEmpty()) {
            Text("Play something — your queue will appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn {
                itemsIndexed(queue) { i, t ->
                    val current = i == idx
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (current) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .clickable { vm.playFrom(queue, t) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Artwork(t.artworkUri, 30.dp, 7.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                color = if (current) Violet else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(formatTime(t.durationMs), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Full-width bottom transport bar: info | transport + seek | 8D presets + volume + queue. */
@Composable
private fun TransportBar(vm: DesktopViewModel, onOpenPlayer: () -> Unit) {
    val palette = LocalVibePalette.current
    val ps by vm.player.state.collectAsState()
    val shuffle by vm.shuffleOn.collectAsState()
    val repeat by vm.repeatMode.collectAsState()
    val eightDOn by vm.eightDOn.collectAsState()
    val volume by vm.volumeFlow.collectAsState()
    val track = ps.track

    Row(
        Modifier.fillMaxWidth().height(84.dp).background(palette.navBar).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.width(260.dp).clip(RoundedCornerShape(12.dp))
                .clickable(enabled = track != null, onClick = onOpenPlayer)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Artwork(track?.artworkUri, 52.dp, 10.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(track?.title ?: "Nothing playing", style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track?.artist ?: "", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }

        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.shuffleOn.value = !shuffle }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.Shuffle, "Shuffle", Modifier.size(18.dp),
                        tint = if (shuffle) Violet else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = { vm.previous() }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.width(6.dp))
                FilledIconButton(onClick = { vm.togglePlayPause() }, modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Violet, contentColor = Color(0xFF1A0B2E))) {
                    if (ps.isBuffering) CircularProgressIndicator(Modifier.size(20.dp),
                        strokeWidth = 2.dp, color = Color(0xFF1A0B2E))
                    else Icon(if (ps.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        null, Modifier.size(26.dp))
                }
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = { vm.next() }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = {
                    vm.repeatMode.value = when (repeat) {
                        RepeatMode.OFF -> RepeatMode.ALL
                        RepeatMode.ALL -> RepeatMode.ONE
                        RepeatMode.ONE -> RepeatMode.OFF
                    }
                }, modifier = Modifier.size(34.dp)) {
                    Icon(if (repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        "Repeat", Modifier.size(18.dp),
                        tint = if (repeat != RepeatMode.OFF) Violet else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(0.72f)) {
                Text(formatTime(ps.positionMs), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                var dragPos by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = dragPos ?: ps.positionMs.toFloat(),
                    onValueChange = { dragPos = it },
                    onValueChangeFinished = { dragPos?.let { vm.player.seekTo(it.toLong()) }; dragPos = null },
                    valueRange = 0f..(ps.durationMs.coerceAtLeast(1).toFloat()),
                    enabled = track != null,
                    modifier = Modifier.weight(1f).height(26.dp).padding(horizontal = 8.dp)
                )
                Text(formatTime(ps.durationMs), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(Modifier.width(300.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End) {
            // 8D chip -> presets menu (shared :core presets)
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                FilterChip(
                    selected = eightDOn,
                    onClick = { menuOpen = true },
                    label = { Text(if (eightDOn) "8D ON" else "8D OFF", fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = VioletDeep, selectedLabelColor = Color.White)
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    EightDPresets.ALL.forEach { p ->
                        DropdownMenuItem(
                            text = { Text("${p.name}  ·  ${"%.2f".format(p.speed)} r/s · ${(p.depth * 100).toInt()}%") },
                            onClick = { vm.applyPreset(p); menuOpen = false }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (eightDOn) "Turn 8D off" else "Turn 8D on") },
                        onClick = { vm.setEightD(!eightDOn); menuOpen = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Custom… (open player)") },
                        onClick = { menuOpen = false; onOpenPlayer() }
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Rounded.VolumeUp, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = volume, onValueChange = { vm.setVolume(it) },
                valueRange = 0f..1.5f, modifier = Modifier.width(100.dp).height(26.dp))
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = { vm.showQueue.value = !vm.showQueue.value }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue (Ctrl+Q)", Modifier.size(20.dp),
                    tint = if (vm.showQueue.collectAsState().value) Violet
                           else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
