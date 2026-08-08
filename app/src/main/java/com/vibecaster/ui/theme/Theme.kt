package com.vibecaster.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Base palette
val DeepSpace = Color(0xFF0B0614)
val SurfaceDark = Color(0xFF17102A)
val SurfaceCard = Color(0xFF1E1536)
val Violet = Color(0xFFA78BFA)
val VioletDeep = Color(0xFF7C3AED)
val Pink = Color(0xFFF472B6)
val Cyan = Color(0xFF22D3EE)
val TextPrimary = Color(0xFFF4F0FF)
val TextSecondary = Color(0xFF9E93BF)

/** App theme selection. SYSTEM follows the device's dark/light setting. */
enum class ThemeMode { SYSTEM, VIBE, DARK, LIGHT }

/** Colors that sit outside the Material color scheme (gradients, nav bar). */
data class VibePalette(
    val bgTop: Color,
    val bgMid: Color,
    val bgBottom: Color,
    val playerTop: Color,
    val playerMid: Color,
    val playerBottom: Color,
    val navBar: Color
)

private val PaletteVibe = VibePalette(
    bgTop = Color(0xFF150B26),
    bgMid = DeepSpace,
    bgBottom = Color(0xFF06030C),
    playerTop = Color(0xFF2A1650),
    playerMid = Color(0xFF160C2B),
    playerBottom = DeepSpace,
    navBar = Color(0xF017102A)
)

private val PaletteDark = VibePalette(
    bgTop = Color(0xFF101010),
    bgMid = Color(0xFF000000),
    bgBottom = Color(0xFF000000),
    playerTop = Color(0xFF1C1C1C),
    playerMid = Color(0xFF0E0E0E),
    playerBottom = Color(0xFF000000),
    navBar = Color(0xF0111111)
)

private val PaletteLight = VibePalette(
    bgTop = Color(0xFFF6F2FD),
    bgMid = Color(0xFFF9F7FD),
    bgBottom = Color(0xFFF0EAF9),
    playerTop = Color(0xFFE4D8F8),
    playerMid = Color(0xFFF1EAFA),
    playerBottom = Color(0xFFF9F7FD),
    navBar = Color(0xF0FFFFFF)
)

val LocalVibePalette = staticCompositionLocalOf { PaletteVibe }

private val VibeColors = darkColorScheme(
    primary = Violet,
    onPrimary = Color(0xFF1A0B2E),
    secondary = Pink,
    onSecondary = Color(0xFF2A0A1C),
    tertiary = Cyan,
    background = DeepSpace,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceCard,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFF3A2D5C)
)

private val BlackColors = darkColorScheme(
    primary = Violet,
    onPrimary = Color(0xFF1A0B2E),
    secondary = Pink,
    onSecondary = Color(0xFF2A0A1C),
    tertiary = Cyan,
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF9E9E9E),
    outline = Color(0xFF3A3A3C)
)

private val LightColors = lightColorScheme(
    primary = VioletDeep,
    onPrimary = Color.White,
    secondary = Color(0xFFDB2777),
    onSecondary = Color.White,
    tertiary = Color(0xFF0891B2),
    background = Color(0xFFF9F7FD),
    onBackground = Color(0xFF1B1424),
    surface = Color.White,
    onSurface = Color(0xFF1B1424),
    surfaceVariant = Color(0xFFEAE3F6),
    onSurfaceVariant = Color(0xFF5F5776),
    outline = Color(0xFFC9BFE0)
)

private val AppTypography = Typography(
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.Bold),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyMedium = Typography().bodyMedium.copy(lineHeight = 20.sp)
)

@Composable
fun VibeCasterTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val effective = if (mode == ThemeMode.SYSTEM) {
        if (systemDark) ThemeMode.DARK else ThemeMode.LIGHT
    } else mode
    val (scheme, palette) = when (effective) {
        ThemeMode.VIBE -> VibeColors to PaletteVibe
        ThemeMode.DARK -> BlackColors to PaletteDark
        else -> LightColors to PaletteLight
    }
    CompositionLocalProvider(LocalVibePalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            content = content
        )
    }
}
