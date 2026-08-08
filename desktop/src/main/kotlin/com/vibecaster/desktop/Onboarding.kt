package com.vibecaster.desktop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vibecaster.data.Track
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

private const val DEMO_TRACK_ID = -424242L

/** Extracts the bundled 12s demo clip so ffmpeg can play it from disk. */
private object DemoClip {
    fun path(): String? = runCatching {
        val f = File(Store.dataDir, "orbit_demo.m4a")
        if (!f.isFile) {
            DemoClip::class.java.getResourceAsStream("/orbit_demo.m4a")?.use { i ->
                f.outputStream().use { i.copyTo(it) }
            } ?: return null
        }
        f.absolutePath
    }.getOrNull()
}

/**
 * First-launch onboarding — the desktop twin of the phone app's:
 * 1) hear the 8D demo, 2) pick a theme, 3) optional sign-in. Shown once.
 */
@Composable
fun OnboardingScreen(vm: DesktopViewModel) {
    var step by remember { mutableStateOf(0) }
    val palette = LocalVibePalette.current

    fun stopDemo() {
        if (vm.player.state.value.track?.id == DEMO_TRACK_ID) vm.player.stop()
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(palette.bgTop, palette.bgMid, palette.bgBottom))),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
            modifier = Modifier.width(560.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 40.dp, vertical = 32.dp)
            ) {
                when (step) {
                    0 -> DemoStep(vm)
                    1 -> ThemeStep(vm)
                    else -> AccountStep(vm)
                }

                Spacer(Modifier.height(28.dp))

                // dots + navigation
                Row(verticalAlignment = Alignment.CenterVertically) {
                    repeat(3) { i ->
                        Box(
                            Modifier.padding(end = 6.dp).size(if (i == step) 10.dp else 7.dp)
                                .background(
                                    if (i == step) Violet
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    CircleShape
                                )
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (step > 0) {
                        TextButton(onClick = { stopDemo(); step-- }) {
                            Text("Back", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(
                        onClick = {
                            stopDemo()
                            if (step < 2) step++ else vm.completeOnboarding()
                        },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VioletDeep),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text(
                            when (step) {
                                0 -> "Next"
                                1 -> "Next"
                                else -> "Start listening"
                            },
                            color = Color.White, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DemoStep(vm: DesktopViewModel) {
    val playerState by vm.player.state.collectAsState()
    val eightD by vm.eightDOn.collectAsState()
    val bootState by ToolBootstrap.state.collectAsState()
    val demoPlaying = playerState.track?.id == DEMO_TRACK_ID && playerState.isPlaying

    Text("Welcome to Orbit", style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Black)
    Spacer(Modifier.height(6.dp))
    Text(
        "Sound that moves around your head. Put on headphones and try the demo.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(22.dp))

    // Orbiting dot around the "8" badge — spins while the demo plays.
    val transition = rememberInfiniteTransition()
    val angle by transition.animateFloat(
        0f, 360f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart)
    )
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(96.dp)
                .background(Brush.linearGradient(listOf(VioletDeep, Pink)), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("8", style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black, color = Color.White)
        }
        if (demoPlaying && eightD) {
            val rad = Math.toRadians(angle.toDouble())
            Box(
                Modifier
                    .offset(x = (cos(rad) * 66).dp, y = (sin(rad) * 66).dp)
                    .size(12.dp)
                    .background(Cyan, CircleShape)
            )
        }
    }
    Spacer(Modifier.height(18.dp))

    val engineReady = vm.player.ffmpegAvailable
    if (!engineReady) {
        val bs = bootState
        Text(
            when (bs) {
                is ToolBootstrap.State.Downloading ->
                    if (bs.progress >= 0f)
                        "Preparing the audio engine… ${(bs.progress * 100).toInt()}%"
                    else "Preparing the audio engine…"
                is ToolBootstrap.State.Failed -> "Audio engine setup failed — see Settings."
                else -> "Preparing the audio engine…"
            },
            style = MaterialTheme.typography.bodySmall, color = Cyan
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(color = Cyan, modifier = Modifier.fillMaxWidth().height(4.dp))
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    if (demoPlaying) vm.player.stop()
                    else DemoClip.path()?.let { p ->
                        vm.setEightD(true)
                        vm.player.play(Track(
                            id = DEMO_TRACK_ID, title = "Orbit 8D Demo", artist = "Orbit",
                            uri = p, artworkUri = null, durationMs = 12_500L
                        ))
                    }
                },
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Violet)
            ) {
                Text(if (demoPlaying) "Stop demo" else "▶  Play the 8D demo", color = Color.White)
            }
            Spacer(Modifier.width(16.dp))
            Text("8D", style = MaterialTheme.typography.labelLarge,
                color = if (eightD) Cyan else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Switch(checked = eightD, onCheckedChange = { vm.setEightD(it) })
        }
        Text(
            "Flip the switch while it plays — hear the difference instantly.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun ThemeStep(vm: DesktopViewModel) {
    val theme by vm.themeMode.collectAsState()
    Text("Pick your vibe", style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Black)
    Spacer(Modifier.height(6.dp))
    Text("You can change this anytime in Settings.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(22.dp))
    Row {
        ThemeCard("Vibe", "Deep space purple", listOf(Color(0xFF2A1B54), Color(0xFF0F0A1F)),
            theme == ThemeMode.VIBE) { vm.setTheme(ThemeMode.VIBE) }
        Spacer(Modifier.width(14.dp))
        ThemeCard("Dark", "Pure black", listOf(Color(0xFF1C1C1E), Color(0xFF000000)),
            theme == ThemeMode.DARK) { vm.setTheme(ThemeMode.DARK) }
        Spacer(Modifier.width(14.dp))
        ThemeCard("Light", "Clean & bright", listOf(Color(0xFFF5F3FF), Color(0xFFE9E4FF)),
            theme == ThemeMode.LIGHT) { vm.setTheme(ThemeMode.LIGHT) }
    }
}

@Composable
private fun ThemeCard(
    name: String, subtitle: String, colors: List<Color>,
    selected: Boolean, onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Violet else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Box(
            Modifier.size(width = 120.dp, height = 74.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.verticalGradient(colors))
        )
        Spacer(Modifier.height(8.dp))
        Text(name, style = MaterialTheme.typography.titleSmall,
            color = if (selected) Violet else MaterialTheme.colorScheme.onSurface)
        Text(subtitle, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AccountStep(vm: DesktopViewModel) {
    val user by vm.authUser.collectAsState()
    val busy by vm.syncBusy.collectAsState()
    val status by vm.syncStatus.collectAsState()

    Text("Sync your Orbit", style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Black)
    Spacer(Modifier.height(6.dp))
    Text(
        if (user == null)
            "One account — the same playlists on every device you sign in on. Optional, free."
        else "You're all set.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(20.dp))

    if (user != null) {
        Text("Signed in as ${user?.email} ✓", color = Cyan,
            style = MaterialTheme.typography.titleSmall)
    } else {
        Button(
            onClick = { vm.signInGoogle() },
            enabled = !busy,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                disabledContainerColor = Color.White.copy(alpha = 0.85f)
            ),
            border = BorderStroke(1.dp, Color(0xFF747775)),
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp),
                    color = Color(0xFF4285F4), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Waiting for the browser…", color = Color(0xFF1F1F1F))
            } else {
                Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.Black)
                Spacer(Modifier.width(10.dp))
                Text("Continue with Google", color = Color(0xFF1F1F1F),
                    fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(12.dp))
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        Row {
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("Email") }, singleLine = true,
                modifier = Modifier.weight(1f).onKeyGuard()
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f).onKeyGuard()
            )
        }
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedButton(
                onClick = { vm.signInEmail(email, password, isNew = false) },
                enabled = !busy && email.isNotBlank() && password.isNotBlank()
            ) { Text("Sign in") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { vm.signInEmail(email, password, isNew = true) },
                enabled = !busy && email.isNotBlank() && password.isNotBlank()
            ) { Text("Create account") }
        }
        status?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(6.dp))
        Text("No account needed. Ever. Skip with \"Start listening\".",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
