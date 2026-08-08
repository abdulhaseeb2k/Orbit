package com.vibecaster.data

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val uri: String,
    val artworkUri: String?,
    val durationMs: Long,
    val fromYouTube: Boolean = false,
    /** Original page URL (YouTube). Used to re-resolve expired stream URLs. */
    val sourceUrl: String? = null
)

private val YT_ID_PATTERNS = listOf(
    Regex("[?&]v=([A-Za-z0-9_-]{11})"),
    Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
    Regex("/shorts/([A-Za-z0-9_-]{11})"),
    Regex("/embed/([A-Za-z0-9_-]{11})"),
    Regex("/live/([A-Za-z0-9_-]{11})")
)

/** Extracts the 11-character YouTube video id from any URL form, or null. */
fun youTubeVideoId(url: String?): String? {
    if (url.isNullOrBlank()) return null
    for (p in YT_ID_PATTERNS) p.find(url)?.let { return it.groupValues[1] }
    return null
}

/**
 * Stable identity key for a track. The same YouTube video matches no matter
 * which URL form it came from (youtu.be, music.youtube.com, watch?v=...,
 * links with extra ?si=/playlist parameters, etc.), so the "downloaded"
 * tick shows up correctly everywhere.
 */
fun Track.matchKey(): String =
    youTubeVideoId(sourceUrl)?.let { "yt:$it" } ?: (sourceUrl ?: uri)
