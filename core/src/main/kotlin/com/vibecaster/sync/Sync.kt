package com.vibecaster.sync

import com.vibecaster.data.Track
import com.vibecaster.data.matchKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Platform-neutral playlist shape used for syncing (both apps convert 1:1). */
data class SyncPlaylist(val name: String, val tracks: List<Track>)

data class RemoteLibrary(
    val playlists: List<SyncPlaylist>,
    val recents: List<Track>,
    val updatedAt: Long
)

/**
 * Cross-device library sync over the Firestore REST API — shared by the
 * Android and desktop apps, protected by the user-scoped security rules
 * (the users/{uid} subtree is readable/writable only by that user).
 *
 * Storage layout: ONE document `users/{uid}/data/library` with the playlists
 * and recents as JSON strings (same schema the apps already use on disk).
 */
object SyncClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun docUrl(uid: String) =
        "https://firestore.googleapis.com/v1/projects/${FirebaseConfig.projectId}" +
            "/databases/(default)/documents/users/$uid/data/library"

    // ---------- track (de)serialization — same schema as the local JSON files ----------

    fun trackToJson(t: Track): JSONObject = JSONObject()
        .put("id", t.id).put("title", t.title).put("artist", t.artist)
        .put("uri", t.uri).put("artworkUri", t.artworkUri ?: JSONObject.NULL)
        .put("durationMs", t.durationMs).put("fromYouTube", t.fromYouTube)
        .put("sourceUrl", t.sourceUrl ?: JSONObject.NULL)

    fun trackFromJson(o: JSONObject): Track = Track(
        id = o.getLong("id"), title = o.getString("title"), artist = o.getString("artist"),
        uri = o.optString("uri", ""),
        artworkUri = if (o.isNull("artworkUri")) null else o.getString("artworkUri"),
        durationMs = o.optLong("durationMs", 0L),
        fromYouTube = o.optBoolean("fromYouTube", false),
        sourceUrl = if (o.isNull("sourceUrl")) null else o.getString("sourceUrl")
    )

    private val AUDIUS_STREAM = Regex("""/v1/tracks/([A-Za-z0-9]+)/stream""")

    /**
     * Prepares a track for the cloud. Returns null for tracks that would be
     * meaningless on another device (purely local files with no source link).
     *
     * - YouTube: stream URLs expire -> store uri="" (re-resolved on play).
     * - Audius: normalize sourceUrl to "audius:<id>" so the same song matches
     *   across platforms even when found via different API hosts.
     * - Local files: another machine can't read this disk -> skipped.
     */
    fun sanitizeForSync(t: Track): Track? {
        var s = t
        if (s.sourceUrl == null) {
            AUDIUS_STREAM.find(s.uri)?.let { m ->
                s = s.copy(sourceUrl = "audius:${m.groupValues[1]}")
            }
        }
        return when {
            s.fromYouTube || (s.sourceUrl?.let { youTube(it) } == true) -> s.copy(uri = "")
            s.uri.startsWith("http") -> s
            s.sourceUrl != null -> s.copy(uri = "")
            else -> null
        }
    }

    private fun youTube(sourceUrl: String) =
        com.vibecaster.data.youTubeVideoId(sourceUrl) != null

    fun playlistsToJson(playlists: List<SyncPlaylist>): String {
        val root = JSONArray()
        playlists.forEach { p ->
            val ts = JSONArray()
            p.tracks.mapNotNull { sanitizeForSync(it) }.forEach { ts.put(trackToJson(it)) }
            root.put(JSONObject().put("name", p.name).put("tracks", ts))
        }
        return root.toString()
    }

    fun playlistsFromJson(s: String): List<SyncPlaylist> = try {
        val root = JSONArray(s)
        (0 until root.length()).map { i ->
            val p = root.getJSONObject(i)
            val ts = p.getJSONArray("tracks")
            SyncPlaylist(p.getString("name"),
                (0 until ts.length()).map { trackFromJson(ts.getJSONObject(it)) })
        }
    } catch (_: Exception) { emptyList() }

    fun tracksToJson(tracks: List<Track>): String {
        val arr = JSONArray()
        tracks.mapNotNull { sanitizeForSync(it) }.forEach { arr.put(trackToJson(it)) }
        return arr.toString()
    }

    fun tracksFromJson(s: String): List<Track> = try {
        val arr = JSONArray(s)
        (0 until arr.length()).map { trackFromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    // ---------- Firestore REST ----------

    /** Downloads the user's cloud library, or null when nothing is stored yet. */
    suspend fun fetch(uid: String, idToken: String): RemoteLibrary? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(docUrl(uid))
            .header("Authorization", "Bearer $idToken").get().build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return@withContext null
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Sync fetch failed: HTTP ${resp.code}")
            val fields = JSONObject(text).optJSONObject("fields") ?: return@withContext null
            fun str(name: String) =
                fields.optJSONObject(name)?.optString("stringValue").orEmpty()
            RemoteLibrary(
                playlists = playlistsFromJson(str("playlists")),
                recents = tracksFromJson(str("recents")),
                updatedAt = fields.optJSONObject("updatedAt")
                    ?.optString("integerValue")?.toLongOrNull() ?: 0L
            )
        }
    }

    /** Uploads the merged library (overwrites the cloud copy). */
    suspend fun push(
        uid: String, idToken: String,
        playlists: List<SyncPlaylist>, recents: List<Track>
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("fields", JSONObject()
            .put("playlists", JSONObject().put("stringValue", playlistsToJson(playlists)))
            .put("recents", JSONObject().put("stringValue", tracksToJson(recents)))
            .put("updatedAt", JSONObject().put("integerValue", System.currentTimeMillis().toString()))
        )
        val req = Request.Builder().url(docUrl(uid))
            .header("Authorization", "Bearer $idToken")
            .patch(body.toString().toRequestBody(jsonType)).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Sync push failed: HTTP ${resp.code}")
        }
        Unit
    }
}

// ---------------------------------------------------------------------------
// Device sessions — "which phones/PCs are signed in, and kick one out".
// Firebase Auth has no client-side session list, so Orbit keeps its own
// registry at users/{uid}/devices/{deviceId} (covered by the same rules).
// "Sign out" = set revoked=true; that device signs itself out on its next
// sync/launch (not instant, but honest and simple).
// ---------------------------------------------------------------------------

data class DeviceInfo(
    val id: String,
    val name: String,
    /** "android" | "desktop" */
    val platform: String,
    val lastSeenMs: Long,
    val revoked: Boolean
)

object DeviceRegistry {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun baseUrl(uid: String) =
        "https://firestore.googleapis.com/v1/projects/${FirebaseConfig.projectId}" +
            "/databases/(default)/documents/users/$uid/devices"

    private fun docUrl(uid: String, id: String) = "${baseUrl(uid)}/$id"

    private fun deviceFromDoc(doc: JSONObject): DeviceInfo? {
        val name = doc.optString("name")               // full resource path
        val id = name.substringAfterLast('/')
        val f = doc.optJSONObject("fields") ?: return null
        fun str(k: String) = f.optJSONObject(k)?.optString("stringValue").orEmpty()
        return DeviceInfo(
            id = id,
            name = str("name").ifBlank { "Unknown device" },
            platform = str("platform").ifBlank { "unknown" },
            lastSeenMs = f.optJSONObject("lastSeen")
                ?.optString("integerValue")?.toLongOrNull() ?: 0L,
            revoked = f.optJSONObject("revoked")?.optBoolean("booleanValue", false) ?: false
        )
    }

    /** This device's own registry entry (null = not registered yet). */
    suspend fun fetch(uid: String, idToken: String, deviceId: String): DeviceInfo? =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(docUrl(uid, deviceId))
                .header("Authorization", "Bearer $idToken").get().build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@withContext null
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("Device fetch failed: HTTP ${resp.code}")
                deviceFromDoc(JSONObject(text))
            }
        }

    /**
     * Registers/refreshes this device. updateMask deliberately EXCLUDES
     * `revoked`, so a heartbeat can never un-revoke a kicked device.
     */
    suspend fun heartbeat(uid: String, idToken: String, device: DeviceInfo) =
        withContext(Dispatchers.IO) {
            val url = docUrl(uid, device.id) +
                "?updateMask.fieldPaths=name&updateMask.fieldPaths=platform&updateMask.fieldPaths=lastSeen"
            val body = JSONObject().put("fields", JSONObject()
                .put("name", JSONObject().put("stringValue", device.name))
                .put("platform", JSONObject().put("stringValue", device.platform))
                .put("lastSeen", JSONObject().put("integerValue", device.lastSeenMs.toString()))
            )
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $idToken")
                .patch(body.toString().toRequestBody(jsonType)).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Device heartbeat failed: HTTP ${resp.code}")
            }
            Unit
        }

    /** All devices signed in to this account, most recently active first. */
    suspend fun list(uid: String, idToken: String): List<DeviceInfo> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("${baseUrl(uid)}?pageSize=50")
                .header("Authorization", "Bearer $idToken").get().build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("Device list failed: HTTP ${resp.code}")
                val docs = JSONObject(text).optJSONArray("documents") ?: return@withContext emptyList()
                (0 until docs.length())
                    .mapNotNull { deviceFromDoc(docs.getJSONObject(it)) }
                    .filterNot { it.revoked }
                    .sortedByDescending { it.lastSeenMs }
            }
        }

    /** Marks a device signed-out; it drops its session on its next sync. */
    suspend fun revoke(uid: String, idToken: String, deviceId: String) =
        withContext(Dispatchers.IO) {
            val url = docUrl(uid, deviceId) + "?updateMask.fieldPaths=revoked"
            val body = JSONObject().put("fields", JSONObject()
                .put("revoked", JSONObject().put("booleanValue", true)))
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $idToken")
                .patch(body.toString().toRequestBody(jsonType)).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Device revoke failed: HTTP ${resp.code}")
            }
            Unit
        }

    /** Removes the registry entry (on local sign-out / after honoring a revoke). */
    suspend fun delete(uid: String, idToken: String, deviceId: String) =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(docUrl(uid, deviceId))
                .header("Authorization", "Bearer $idToken").delete().build()
            http.newCall(req).execute().use { }
            Unit
        }
}

/**
 * Union-merge: nothing is ever lost. Local order wins; remote-only playlists
 * and tracks are appended. (Deletions intentionally do NOT propagate — safer
 * for a personal library than trying to guess which side deleted.)
 */
object SyncMerge {

    fun mergeTracks(local: List<Track>, remote: List<Track>): List<Track> {
        val seen = local.map { it.matchKey() }.toMutableSet()
        val extra = remote.filter { seen.add(it.matchKey()) }
        return local + extra
    }

    fun mergePlaylists(
        local: List<SyncPlaylist>, remote: List<SyncPlaylist>
    ): List<SyncPlaylist> {
        val remoteByName = remote.associateBy { it.name }
        val merged = local.map { lp ->
            val rp = remoteByName[lp.name] ?: return@map lp
            SyncPlaylist(lp.name, mergeTracks(lp.tracks, rp.tracks))
        }
        val localNames = local.map { it.name }.toSet()
        return merged + remote.filter { it.name !in localNames }
    }
}
