package com.vibecaster.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Fetches lyrics from the free LRCLIB API (no key required). */
object LyricsRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Strips junk like "(Official Video)" from YouTube titles. */
    private fun cleanTitle(title: String): String = title
        .replace(Regex("\\(.*?\\)|\\[.*?\\]"), "")
        .replace(Regex("(?i)official|video|audio|lyrics?|lyrical|full|hd|4k"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    suspend fun fetch(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val t = cleanTitle(title)
        direct(t, artist) ?: search("$t $artist") ?: search(t)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun direct(title: String, artist: String): String? = runCatching {
        val url = "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}"
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            extract(JSONObject(resp.body?.string() ?: return null))
        }
    }.getOrNull()

    private fun search(query: String): String? = runCatching {
        val url = "https://lrclib.net/api/search?q=${enc(query)}"
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val arr = JSONArray(resp.body?.string() ?: return null)
            if (arr.length() == 0) return null
            extract(arr.getJSONObject(0))
        }
    }.getOrNull()

    private fun extract(o: JSONObject): String? {
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (plain != null) return plain
        // Fall back to synced lyrics with timestamps stripped.
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return synced.lines().joinToString("\n") { it.replace(Regex("^\\[.*?\\]\\s*"), "") }.trim()
    }
}
