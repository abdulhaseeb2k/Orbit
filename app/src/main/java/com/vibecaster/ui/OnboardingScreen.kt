package com.vibecaster.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.vibecaster.MainViewModel
import com.vibecaster.ui.theme.Cyan
import com.vibecaster.ui.theme.DeepSpace
import com.vibecaster.ui.theme.Pink
import com.vibecaster.ui.theme.ThemeMode
import com.vibecaster.ui.theme.Violet
import com.vibecaster.ui.theme.VioletDeep
import kotlin.math.cos
import kotlin.math.sin

/**
 * 3-screen onboarding (design report §4):
 *   1. Hook — live 8D demo with ON/OFF toggle (the product sells itself)
 *   2. Theme pick — instant ownership
 *   3. Permissions with plain-language reasons. No account. Ever.
 */
@UnstableApi
@Composable
fun OnboardingScreen(vm: MainViewModel) {
    var step by remember { mutableIntStateOf(0) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF150B26), DeepSpace, Color(0xFF06030C))))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(0.7f))
            when (step) {
                0 -> StepDemo(vm)
                1 -> StepTheme(vm)
                else -> StepPermissions(vm)
            }
            Spacer(Modifier.weight(1f))

            // dots + nav
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { i ->
                    Box(
                        Modifier
                            .padding(4.dp)
                            .size(if (i == step) 10.dp else 7.dp)
                            .background(if (i == step) Violet else Color(0xFF3A2D5C), CircleShape)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            if (step < 2) {
                Button(
                    onClick = { step++ },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Color(0xFF1A0B2E)),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) { Text("Continue", fontWeight = FontWeight.Bold) }
                TextButton(onClick = { vm.completeOnboarding() }) {
                    Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@UnstableApi
@Composable
private fun StepDemo(vm: MainViewModel) {
    val effectOn by vm.effectOn.collectAsStateWithLifecycle()
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()

    // Animated orbit logo
    val spin = rememberInfiniteTransition(label = "orbit")
    val angle by spin.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "angle"
    )
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(132.dp)
                .background(
                    Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFF9333EA))),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("8", fontSize = 64.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
        val rad = Math.toRadians(angle.toDouble())
        Box(
            Modifier
                .offset(x = (cos(rad) * 92).dp, y = (sin(rad) * 30).dp)
                .size(12.dp)
                .background(Color(0xFFFDE047), CircleShape)
        )
    }
    Spacer(Modifier.height(28.dp))
    Text("Sound that orbits you", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(
        "Real-time 8D audio. Put your headphones on\nand hear the difference live.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(22.dp))
    Button(
        onClick = { vm.setEffectOn(true); vm.playDemo() },
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color(0xFF06282E)),
        modifier = Modifier.height(46.dp)
    ) { Text(if (isPlaying) "Playing…" else "▶  Try the 8D demo", fontWeight = FontWeight.Bold) }
    if (isPlaying) {
        Spacer(Modifier.height(12.dp))
        Surface(color = Color(0x331E1536), shape = RoundedCornerShape(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text("8D effect", color = Color.White)
                Spacer(Modifier.width(10.dp))
                Switch(checked = effectOn, onCheckedChange = { vm.setEffectOn(it) })
            }
        }
        Text(
            "Toggle it — hear the sound stop moving.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@UnstableApi
@Composable
private fun StepTheme(vm: MainViewModel) {
    val mode by vm.themeMode.collectAsStateWithLifecycle()
    Text("Make it yours", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(6.dp))
    Text(
        "Pick a look — change it anytime in Library → Settings.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(20.dp))
    ThemeCard("Vibe", "Deep space purple — the Orbit look", ThemeMode.VIBE, mode) { vm.setThemeMode(it) }
    ThemeCard("Dark", "Pure black — great on OLED", ThemeMode.DARK, mode) { vm.setThemeMode(it) }
    ThemeCard("Light", "Clean and bright", ThemeMode.LIGHT, mode) { vm.setThemeMode(it) }
}

@Composable
private fun ThemeCard(
    title: String, subtitle: String,
    value: ThemeMode, selected: ThemeMode,
    onPick: (ThemeMode) -> Unit
) {
    val isSel = selected == value
    Surface(
        color = if (isSel) VioletDeep.copy(alpha = 0.35f) else Color(0x2E1E1536),
        shape = RoundedCornerShape(18.dp),
        border = if (isSel) androidx.compose.foundation.BorderStroke(2.dp, Violet) else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onPick(value) }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isSel) Text("✓", color = Cyan, fontSize = 18.sp)
        }
    }
}

@UnstableApi
@Composable
private fun StepPermissions(vm: MainViewModel) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.completeOnboarding() }

    Text("Two quick permissions", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(20.dp))
    PermCard("🔔", "Playback controls", "Play / pause from your notification shade")
    PermCard("🎵", "Your music files", "So Orbit can play songs already on this phone")
    Spacer(Modifier.height(26.dp))
    Button(
        onClick = {
            val perms = buildList {
                if (Build.VERSION.SDK_INT >= 33) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                    add(Manifest.permission.READ_MEDIA_AUDIO)
                }
            }
            if (perms.isEmpty()) vm.completeOnboarding() else launcher.launch(perms.toTypedArray())
        },
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Color(0xFF1A0B2E)),
        modifier = Modifier.fillMaxWidth().height(50.dp)
    ) { Text("Start listening", fontWeight = FontWeight.Bold) }
    Spacer(Modifier.height(10.dp))
    Text(
        "No account needed. Ever.",
        style = MaterialTheme.typography.labelMedium,
        color = Pink
    )
}

@Composable
private fun PermCard(emoji: String, title: String, reason: String) {
    Surface(
        color = Color(0x2E1E1536),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Text(emoji, fontSize = 22.sp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(reason, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
