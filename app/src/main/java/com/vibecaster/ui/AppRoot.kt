package com.vibecaster.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.vibecaster.MainViewModel
import com.vibecaster.ui.theme.Cyan
import com.vibecaster.ui.theme.LocalVibePalette
import com.vibecaster.ui.theme.Violet

@UnstableApi
@Composable
fun AppRoot(vm: MainViewModel) {
    val onboarded by vm.onboarded.collectAsStateWithLifecycle()
    if (!onboarded) {
        OnboardingScreen(vm)
        return
    }

    var tab by remember { mutableIntStateOf(0) }
    var showPlayer by remember { mutableStateOf(false) }


    val current by vm.current.collectAsStateWithLifecycle()
    val order by vm.tabOrder.collectAsStateWithLifecycle()
    val showAccount by vm.showAccount.collectAsStateWithLifecycle()

    // Back gesture closes overlays instead of exiting the app.
    BackHandler(enabled = showPlayer) { showPlayer = false }
    BackHandler(enabled = showAccount) { vm.dismissAccount() }

    val palette = LocalVibePalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(palette.bgTop, palette.bgMid, palette.bgBottom)
                )
            )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                Column {
                    if (current != null) {
                        MiniPlayer(vm) { showPlayer = true }
                    }
                    NavigationBar(containerColor = palette.navBar) {
                        order.forEachIndexed { index, t ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { Icon(t.icon(), contentDescription = null) },
                                label = { Text(t.label) },
                                colors = navColors()
                            )
                        }
                    }
                }
            }
        ) { padding ->
            when (order.getOrElse(tab) { AppTab.HOME }) {
                AppTab.HOME -> HomeScreen(vm, padding) { showPlayer = true }
                AppTab.SEARCH -> YouTubeScreen(vm, padding) { showPlayer = true }
                AppTab.PLAYLISTS -> PlaylistsScreen(vm, padding) { showPlayer = true }
                AppTab.LIBRARY -> LibraryScreen(vm, padding) { showPlayer = true }
            }
        }

        AnimatedVisibility(
            visible = showPlayer,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            PlayerScreen(vm) { showPlayer = false }
        }

        // Account / sync overlay (post-onboarding offer + Settings entry).
        AnimatedVisibility(
            visible = showAccount,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            AccountScreen(vm)
        }

        UpdateDialogHost(vm)
    }
}

@UnstableApi
@Composable
private fun UpdateDialogHost(vm: MainViewModel) {
    val show by vm.showUpdateDialog.collectAsStateWithLifecycle()
    val info by vm.updateInfo.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val update = info
    if (!show || update == null) return

    AlertDialog(
        onDismissRequest = { vm.dismissUpdateDialog() },
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text("Update available — v${update.version}") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "You have v${vm.appVersion}. A newer version is ready on GitHub.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Cyan
                )
                if (update.notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "What's new:",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        update.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val url = update.apkUrl ?: update.pageUrl
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                vm.dismissUpdateDialog()
            }) { Text("Download", color = Cyan) }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissUpdateDialog() }) {
                Text("Later", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

private fun AppTab.icon() = when (this) {
    AppTab.HOME -> Icons.Rounded.Home
    AppTab.SEARCH -> Icons.Rounded.Search
    AppTab.PLAYLISTS -> Icons.AutoMirrored.Rounded.QueueMusic
    AppTab.LIBRARY -> Icons.Rounded.LibraryMusic
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Violet,
    selectedTextColor = Violet,
    indicatorColor = Violet.copy(alpha = 0.15f),
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
)

@UnstableApi
@Composable
private fun MiniPlayer(vm: MainViewModel, onOpen: () -> Unit) {
    val current by vm.current.collectAsStateWithLifecycle()
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
    val isBuffering by vm.isBuffering.collectAsStateWithLifecycle()
    val track = current ?: return

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onOpen)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Artwork(model = track.artworkUri, size = 44.dp, corner = 10.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleMedium,
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
            if (isBuffering) {
                CircularProgressIndicator(
                    color = Violet,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(14.dp))
            } else {
                IconButton(onClick = { vm.togglePlayPause() }) {
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Violet
                    )
                }
            }
            IconButton(onClick = { vm.next() }) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = Violet)
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}
