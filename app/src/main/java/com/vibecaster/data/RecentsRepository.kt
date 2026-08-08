package com.vibecaster.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/** Persists the recently played list (most recent first, max 50). */
object RecentsRepository {

    private const val MAX = 50

    private fun file(context: Context) = File(context.filesDir, "recents.json")

    @Synchronized
    fun load(context: Context): List<Track> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { PlaylistRepository.trackFromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(context: Context, tracks: List<Track>) {
        val arr = JSONArray()
        tracks.take(MAX).forEach { arr.put(PlaylistRepository.trackToJson(it)) }
        file(context).writeText(arr.toString())
    }
}
