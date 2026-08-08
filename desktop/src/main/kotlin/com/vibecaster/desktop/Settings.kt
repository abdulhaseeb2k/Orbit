package com.vibecaster.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.awt.Desktop

/** Settings panel — theme, tool status, folders. */
@Composable
fun SettingsDialog(vm: DesktopViewModel, onClose: () -> Unit) {
    val theme by vm.themeMode.collectAsState()
    val showNetError by vm.showNetworkError.collectAsState()
    if (showNetError) NetworkErrorDialog(onDismiss = { vm.showNetworkError.value = false })
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.width(560.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings")
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close") }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {

                SectionHeader("Account & sync")
                AccountSection(vm)

                SectionHeader("Theme")
                Row {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(
                            selected = theme == m,
                            onClick = { vm.setTheme(m) },
                            label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }

                SectionHeader("Playback tools")
                val bootState by ToolBootstrap.state.collectAsState()
                ToolRow("ffmpeg (required)", vm.player.ffmpegPath, "winget install --id Gyan.FFmpeg")
                ToolRow("yt-dlp (fallback + downloads)", vm.player.ytDlpPath, "winget install yt-dlp.yt-dlp")
                when (val bs = bootState) {
                    is ToolBootstrap.State.Downloading -> {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (bs.progress >= 0f)
                                "Downloading ${bs.tool}  ${(bs.progress * 100).toInt()}%"
                            else "${bs.tool}…",
                            style = MaterialTheme.typography.bodySmall, color = Cyan
                        )
                        if (bs.progress >= 0f) LinearProgressIndicator(
                            progress = { bs.progress }, color = Cyan,
                            modifier = Modifier.fillMaxWidth().height(4.dp)
                        ) else LinearProgressIndicator(
                            color = Cyan, modifier = Modifier.fillMaxWidth().height(4.dp)
                        )
                    }
                    is ToolBootstrap.State.Failed -> {
                        Text(bs.message, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { ToolBootstrap.retry(vm.player) }) {
                            Text("Retry download")
                        }
                    }
                    else -> if (vm.player.ffmpegPath == null || vm.player.ytDlpPath == null) {
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = { ToolBootstrap.retry(vm.player) },
                            colors = ButtonDefaults.buttonColors(containerColor = VioletDeep)
                        ) { Text("Download missing tools", color = Color.White) }
                    }
                }

                SectionHeader("Storage")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Downloads & data", style = MaterialTheme.typography.bodyMedium)
                        Text(Store.dataDir.absolutePath, style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        runCatching { Desktop.getDesktop().open(Store.dataDir) }
                    }) { Icon(Icons.Rounded.FolderOpen, "Open folder", tint = Violet) }
                }

                SectionHeader("Debug")
                Text("Log file: ${OrbitLog.file.absolutePath}",
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                SectionHeader("About")
                Text("Orbit Desktop 1.3.0 — 8D Audio Experience",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {}
    )
}

/** "No internet" popup — Orbit style, same design language as the phone app. */
@Composable
fun NetworkErrorDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(26.dp),
        title = null,
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .background(Brush.linearGradient(listOf(VioletDeep, Cyan)), CircleShape)
                ) {
                    Icon(Icons.Rounded.WifiOff, null, tint = Color.White,
                        modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("No internet connection",
                    style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Orbit couldn't reach the server. Check your connection and try again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Violet),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) { Text("Got it", fontWeight = FontWeight.Bold, color = Color.White) }
        }
    )
}

/** Sign in / signed-in state — same Firebase account as the phone app. */
@Composable
private fun AccountSection(vm: DesktopViewModel) {
    val user by vm.authUser.collectAsState()
    val busy by vm.syncBusy.collectAsState()
    val status by vm.syncStatus.collectAsState()
    val autoDl by vm.autoDownloadSynced.collectAsState()

    if (user == null) {
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        Text(
            "Sign in to get the same playlists on every device. Optional — the app works fully without an account.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        // Google sign-in — opens the browser, comes right back.
        Button(
            onClick = { vm.signInGoogle() },
            enabled = !busy,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                disabledContainerColor = Color.White.copy(alpha = 0.85f)
            ),
            border = BorderStroke(1.dp, Color(0xFF747775)),
            modifier = Modifier.fillMaxWidth().height(44.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp),
                    color = Color(0xFF4285F4), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Waiting for the browser…", color = Color(0xFF1F1F1F))
            } else {
                Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.Black)
                Spacer(Modifier.width(10.dp))
                Text("Continue with Google", color = Color(0xFF1F1F1F), fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("or with email", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("Email") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().onKeyGuard()
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().onKeyGuard()
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { vm.signInEmail(email, password, isNew = false) },
                enabled = !busy && email.isNotBlank() && password.isNotBlank()
            ) { Text("Sign in") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { vm.signInEmail(email, password, isNew = true) },
                enabled = !busy && email.isNotBlank() && password.isNotBlank()
            ) { Text("Create account") }
            Spacer(Modifier.width(8.dp))
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        TextButton(onClick = { if (email.isNotBlank()) vm.sendPasswordReset(email) }) {
            Text("Email me a password link", style = MaterialTheme.typography.labelSmall)
        }
        Text(
            "Used Google sign-in on your phone? Open the phone app → Settings → Account → " +
                "\"Desktop password\", set one there (no email needed), then sign in here with your Gmail + that password.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AccountCircle, null, tint = Cyan, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(user?.email ?: "Signed in", style = MaterialTheme.typography.bodyMedium)
                Text(status ?: "Playlists sync automatically",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { vm.syncNow(manual = true) }) {
                Icon(Icons.Rounded.CloudSync, "Sync now", tint = Violet)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-download synced playlists", style = MaterialTheme.typography.bodyMedium)
                Text("Songs added on other devices download here automatically",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = autoDl, onCheckedChange = { vm.setAutoDownloadSynced(it) })
        }

        // ---- signed-in devices ----
        val devices by vm.devices.collectAsState()
        val devicesLoading by vm.devicesLoading.collectAsState()
        LaunchedEffect(Unit) { vm.loadDevices() }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Devices", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f))
            if (devicesLoading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            else TextButton(onClick = { vm.loadDevices() }) {
                Text("Refresh", style = MaterialTheme.typography.labelSmall)
            }
        }
        devices.forEach { d ->
            val isThis = d.id == Store.deviceId
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 2.dp)) {
                Icon(
                    if (d.platform == "android") Icons.Rounded.Smartphone else Icons.Rounded.Computer,
                    null, tint = if (isThis) Cyan else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isThis) "${d.name} (this device)" else d.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isThis) Cyan else MaterialTheme.colorScheme.onSurface
                    )
                    Text("Active ${agoText(d.lastSeenMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!isThis) TextButton(onClick = { vm.revokeDevice(d) }) {
                    Text("Sign out", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }

        TextButton(onClick = { vm.signOut() }) { Text("Sign out") }
    }
}

/** "just now" / "5m ago" / "3h ago" / "2d ago" */
private fun agoText(thenMs: Long): String {
    if (thenMs <= 0) return "recently"
    val mins = (System.currentTimeMillis() - thenMs) / 60_000
    return when {
        mins < 2 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 48 * 60 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}

@Composable
private fun ToolRow(label: String, path: String?, installCmd: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(
            if (path != null) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
            null, tint = if (path != null) Cyan else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(path ?: "Not found — use \"Download missing tools\" below, or: $installCmd",
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
