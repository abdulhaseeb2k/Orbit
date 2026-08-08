package com.vibecaster.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Playlist(val name: String, val tracks: List<Track>)

/** Simple JSON-file persistence for playlists (no database needed). */
object PlaylistRepository {

    private fun file(context: Context) = File(context.filesDir, "playlists.json")

    @Synchronized
    fun load(context: Context): List<Playlist> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val root = JSONArray(f.readText())
            (0 until root.length()).map { i ->
                val p = root.getJSONObject(i)
                val tracksJson = p.getJSONArray("tracks")
                Playlist(
                    name = p.getString("name"),
                    tracks = (0 until tracksJson.length()).map { j ->
                        trackFromJson(tracksJson.getJSONObject(j))
                    }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(context: Context, playlists: List<Playlist>) {
        val root = JSONArray()
        playlists.forEach { p ->
            root.put(
                JSONObject()
                    .put("name", p.name)
                    .put("tracks", JSONArray().apply { p.tracks.forEach { put(trackToJson(it)) } })
            )
        }
        file(context).writeText(root.toString())
    }

    internal fun trackToJson(t: Track): JSONObject = JSONObject()
        .put("id", t.id)
        .put("title", t.title)
        .put("artist", t.artist)
        .put("uri", t.uri)
        .put("artworkUri", t.artworkUri ?: JSONObject.NULL)
        .put("durationMs", t.durationMs)
        .put("fromYouTube", t.fromYouTube)
        .put("sourceUrl", t.sourceUrl ?: JSONObject.NULL)

    internal fun trackFromJson(o: JSONObject): Track = Track(
        id = o.getLong("id"),
        title = o.getString("title"),
        artist = o.getString("artist"),
        uri = o.getString("uri"),
        artworkUri = if (o.isNull("artworkUri")) null else o.getString("artworkUri"),
        durationMs = o.getLong("durationMs"),
        fromYouTube = o.optBoolean("fromYouTube", false),
        sourceUrl = if (o.isNull("sourceUrl")) null else o.getString("sourceUrl")
    )
}
