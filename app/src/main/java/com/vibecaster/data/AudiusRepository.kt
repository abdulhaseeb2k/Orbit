package com.vibecaster.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Free music search and streaming via the Audius public API.
 * Audius is an open-source music platform; its API needs no key,
 * only an app_name parameter. Streams are full MP3s (legal to play).
 * Docs: https://docs.audius.org/developers/api
 */
object AudiusRepository {

    private const val APP_NAME = "Orbit"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var host: String? = null

    /** api.audius.co returns a list of live discovery nodes; pick the first. */
    private fun pickHost(): String {
        host?.let { return it }
        val request = Request.Builder().url("https://api.audius.co").build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Audius host lookup failed: HTTP ${resp.code}")
            val data = JSONObject(resp.body!!.string()).getJSONArray("data")
            if (data.length() == 0) throw IllegalStateException("No Audius hosts available")
            val h = data.getString(0)
            host = h
            return h
        }
    }

    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        val h = pickHost()
        fetchTracks("$h/v1/tracks/search?query=${URLEncoder.encode(query, "UTF-8")}&app_name=$APP_NAME", h)
    }

    /**
     * Rebuilds a playable stream URL from a bare track id.
     *
     * Synced tracks only carry sourceUrl = "audius:<id>" (the discovery host
     * differs per device and per session), so the URL has to be built again
     * against a live host before the track can be streamed or downloaded.
     */
    suspend fun streamUrl(trackId: String): String = withContext(Dispatchers.IO) {
        "${pickHost()}/v1/tracks/$trackId/stream?app_name=$APP_NAME"
    }

    /** Audius trending tracks — the default feed for Discover. */
    suspend fun trending(): List<Track> = withContext(Dispatchers.IO) {
        val h = pickHost()
        fetchTracks("$h/v1/tracks/trending?app_name=$APP_NAME", h)
    }

    private fun fetchTracks(url: String, h: String): List<Track> {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Audius request failed: HTTP ${resp.code}")
            val data = JSONObject(resp.body!!.string()).getJSONArray("data")
            return (0 until data.length()).mapNotNull { i ->
                val t = data.optJSONObject(i) ?: return@mapNotNull null
                val id = t.optString("id")
                if (id.isBlank()) return@mapNotNull null
                Track(
                    id = ("audius:$id").hashCode().toLong(),
                    title = t.optString("title", "Unknown"),
                    artist = t.optJSONObject("user")?.optString("name").orEmpty()
                        .ifBlank { "Unknown artist" },
                    // The stream endpoint redirects to the actual MP3; ExoPlayer follows it.
                    uri = "$h/v1/tracks/$id/stream?app_name=$APP_NAME",
                    artworkUri = t.optJSONObject("artwork")?.optString("480x480")
                        ?.ifBlank { null },
                    durationMs = t.optLong("duration", 0L) * 1000L
                )
            }
        }
    }
}
