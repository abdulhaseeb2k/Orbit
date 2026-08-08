package com.vibecaster.data

/**
 * Shared 8D mood presets — used by BOTH the Android and desktop apps
 * (single source of truth; report section 7/D4).
 */
data class EightDPreset(
    val name: String,
    val speed: Float,   // rotations per second
    val depth: Float,   // pan intensity 0..1
    val bassDb: Float   // extra bass shelf
)

object EightDPresets {
    val ALL = listOf(
        EightDPreset("Chill", 0.08f, 0.60f, 0f),
        EightDPreset("Party", 0.25f, 0.90f, 2f),
        EightDPreset("Deep",  0.12f, 1.00f, 4f),
    )
}
