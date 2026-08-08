package com.vibecaster.desktop

import com.vibecaster.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Desktop copy of the Android AudiusRepository (same API, no key needed). */
object AudiusRepo {

    private const val APP_NAME = "Orbit"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    @Volatile private var host: String? = null

    private fun pickHost(): String {
        host?.let { return it }
        val req = Request.Builder().url("https://api.audius.co").build()
        client.newCall(req).execute().use { resp ->
            val data = JSONObject(resp.body!!.string()).getJSONArray("data")
            val h = data.getString(0)
            host = h
            return h
        }
    }

    suspend fun trending(): List<Track> = withContext(Dispatchers.IO) {
        fetchTracks("${pickHost()}/v1/tracks/trending?app_name=$APP_NAME")
    }

    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        fetchTracks("${pickHost()}/v1/tracks/search?query=$q&app_name=$APP_NAME")
    }

    private fun fetchTracks(url: String): List<Track> {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Audius HTTP ${resp.code}")
            val data = JSONObject(resp.body!!.string()).getJSONArray("data")
            val h = host!!
            return (0 until data.length()).mapNotNull { i ->
                val t = data.getJSONObject(i)
                val id = t.getString("id")
                Track(
                    id = ("audius:$id").hashCode().toLong(),
                    title = t.optString("title", "Unknown"),
                    artist = t.optJSONObject("user")?.optString("name") ?: "Audius",
                    uri = "$h/v1/tracks/$id/stream?app_name=$APP_NAME",
                    artworkUri = t.optJSONObject("artwork")?.optString("480x480"),
                    durationMs = t.optLong("duration", 0) * 1000,
                    fromYouTube = false,
                    // stable identity for matchKey/downloaded-tick
                    sourceUrl = "audius:$id"
                )
            }
        }
    }
}
