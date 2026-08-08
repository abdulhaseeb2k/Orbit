package com.vibecaster.desktop

import com.vibecaster.data.Track
import com.vibecaster.data.matchKey
import com.vibecaster.youtube.YouTubeResolver
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class Playlist(val name: String, val tracks: List<Track>)

/** Thrown when the user cancels a download that is already transferring. */
class DownloadCancelledException : Exception("Download cancelled")

/**
 * Desktop persistence — SAME JSON schema as the Android repositories
 * (PlaylistRepository / RecentsRepository / DownloadRepository), stored under
 * %APPDATA%\Orbit (Windows) or ~/.orbit elsewhere.
 */
object Store {

    val dataDir: File by lazy {
        val base = System.getenv("APPDATA")?.let { File(it, "Orbit") }
            ?: File(System.getProperty("user.home"), ".orbit")
        base.apply { mkdirs() }
    }
    val downloadsDir: File by lazy { File(dataDir, "downloads").apply { mkdirs() } }

    private fun playlistsFile() = File(dataDir, "playlists.json")
    private fun settingsFile() = File(dataDir, "settings.json")

    @Synchronized fun loadSettings(): JSONObject =
        try { JSONObject(settingsFile().readText()) } catch (_: Exception) { JSONObject() }

    @Synchronized fun saveSetting(key: String, value: String) {
        settingsFile().writeText(loadSettings().put(key, value).toString())
    }
    private fun recentsFile() = File(dataDir, "recents.json")
    private fun downloadsMeta() = File(downloadsDir, "downloads.json")

    // ---- device identity (for the account's device/session list) ----
    val deviceId: String by lazy {
        val existing = loadSettings().optString("device_id")
        if (existing.isNotBlank()) existing
        else java.util.UUID.randomUUID().toString().also { saveSetting("device_id", it) }
    }

    val deviceName: String by lazy {
        System.getenv("COMPUTERNAME")
            ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
            ?: "This PC"
    }

    // ---- auth session (Firebase sync sign-in) ----
    private fun authFile() = File(dataDir, "auth.json")

    @Synchronized fun saveAuthJson(json: String?) {
        if (json == null) authFile().delete() else authFile().writeText(json)
    }

    @Synchronized fun loadAuthJson(): String? =
        try { authFile().takeIf { it.exists() }?.readText() } catch (_: Exception) { null }

    // ---- JSON (same shape as Android's PlaylistRepository.trackToJson) ----
    fun trackToJson(t: Track): JSONObject = JSONObject()
        .put("id", t.id).put("title", t.title).put("artist", t.artist)
        .put("uri", t.uri).put("artworkUri", t.artworkUri ?: JSONObject.NULL)
        .put("durationMs", t.durationMs).put("fromYouTube", t.fromYouTube)
        .put("sourceUrl", t.sourceUrl ?: JSONObject.NULL)

    fun trackFromJson(o: JSONObject): Track = Track(
        id = o.getLong("id"), title = o.getString("title"), artist = o.getString("artist"),
        uri = o.getString("uri"),
        artworkUri = if (o.isNull("artworkUri")) null else o.getString("artworkUri"),
        durationMs = o.getLong("durationMs"),
        fromYouTube = o.optBoolean("fromYouTube", false),
        sourceUrl = if (o.isNull("sourceUrl")) null else o.getString("sourceUrl")
    )

    // ---- playlists ----
    @Synchronized fun loadPlaylists(): List<Playlist> {
        val f = playlistsFile(); if (!f.exists()) return emptyList()
        return try {
            val root = JSONArray(f.readText())
            (0 until root.length()).map { i ->
                val p = root.getJSONObject(i)
                val ts = p.getJSONArray("tracks")
                Playlist(p.getString("name"), (0 until ts.length()).map { trackFromJson(ts.getJSONObject(it)) })
            }
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized fun savePlaylists(playlists: List<Playlist>) {
        val root = JSONArray()
        playlists.forEach { p ->
            root.put(JSONObject().put("name", p.name)
                .put("tracks", JSONArray().apply { p.tracks.forEach { put(trackToJson(it)) } }))
        }
        playlistsFile().writeText(root.toString())
    }

    // ---- recents (max 50, most recent first) ----
    @Synchronized fun loadRecents(): List<Track> {
        val f = recentsFile(); if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { trackFromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized fun saveRecents(tracks: List<Track>) {
        val arr = JSONArray(); tracks.take(50).forEach { arr.put(trackToJson(it)) }
        recentsFile().writeText(arr.toString())
    }

    // ---- downloads ----
    @Synchronized fun loadDownloads(): List<Track> {
        val f = downloadsMeta(); if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { trackFromJson(arr.getJSONObject(it)) }
                .filter { File(it.uri).exists() }
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized fun saveDownloadsMeta(tracks: List<Track>) {
        val arr = JSONArray(); tracks.forEach { arr.put(trackToJson(it)) }
        downloadsMeta().writeText(arr.toString())
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    /**
     * Mirrors Android DownloadRepository.download(): fetches the resolved
     * stream URL to downloadsDir, caches artwork, updates the meta file.
     */
    fun download(track: Track, isCancelled: () -> Boolean = { false }, onProgress: (Float) -> Unit): Track {
        val safe = track.title.replace(Regex("[^A-Za-z0-9 _.-]"), "").trim().take(40).ifBlank { "track" }
        val base = "${safe}_${abs(track.id)}"

        val request = Request.Builder().url(track.uri)
            .header("User-Agent", YouTubeResolver.USER_AGENT).build()
        val call = client.newCall(request)
        var partial: File? = null
        val audioFile: File = try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Download failed: HTTP ${resp.code}")
                val body = resp.body ?: throw IllegalStateException("Empty body")
                val ct = resp.header("Content-Type").orEmpty()
                val ext = when { ct.contains("mpeg") -> "mp3"; ct.contains("webm") -> "webm"; else -> "m4a" }
                val target = File(downloadsDir, "$base.$ext")
                partial = target
                val total = body.contentLength()
                body.byteStream().use { input ->
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(64 * 1024); var done = 0L
                        while (true) {
                            // Blocking socket reads ignore coroutine cancellation,
                            // so the loop polls the flag itself.
                            if (isCancelled()) { call.cancel(); throw DownloadCancelledException() }
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                target
            }
        } catch (e: Throwable) {
            partial?.delete()   // never leave a truncated file in the library
            throw e
        }
        var artUri = track.artworkUri
        if (artUri != null && artUri.startsWith("http")) {
            runCatching {
                client.newCall(Request.Builder().url(artUri!!).build()).execute().use { ar ->
                    if (ar.isSuccessful) {
                        val artFile = File(downloadsDir, "$base.jpg")
                        ar.body?.byteStream()?.use { i -> FileOutputStream(artFile).use { i.copyTo(it) } }
                        artUri = artFile.absolutePath
                    }
                }
            }
        }
        val local = track.copy(uri = audioFile.absolutePath, artworkUri = artUri, fromYouTube = false)
        saveDownloadsMeta(loadDownloads().filterNot { it.matchKey() == local.matchKey() } + local)
        return local
    }

    /** yt-dlp fallback download — for when the direct stream URL 403s. */
    fun downloadViaYtDlp(
        ytdlp: String,
        nodePath: String?,
        track: Track,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit
    ): Track {
        val watch = track.sourceUrl ?: error("no source url")
        val safe = track.title.replace(Regex("[^A-Za-z0-9 _.-]"), "").trim().take(40).ifBlank { "track" }
        val base = "${safe}_${abs(track.id)}"
        val outTemplate = File(downloadsDir, "$base.%(ext)s").absolutePath
        val cmd = mutableListOf(ytdlp, "-f", "bestaudio/best", "--no-playlist", "--newline", "-o", outTemplate)
        nodePath?.let { cmd += listOf("--js-runtimes", "node:$it") }
        cmd += watch
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val pct = Regex("""\[download]\s+([\d.]+)%""")
        var cancelled = false
        p.inputStream.bufferedReader().forEachLine { line ->
            if (!cancelled && isCancelled()) {
                // yt-dlp prints a progress line roughly every 100 ms, so this
                // reacts almost immediately. destroyForcibly kills the child.
                cancelled = true
                p.destroyForcibly()
            }
            if (!cancelled) {
                pct.find(line)?.let { m -> m.groupValues[1].toFloatOrNull()?.let { onProgress(it / 100f) } }
            }
        }
        if (cancelled || isCancelled()) {
            p.destroyForcibly()
            downloadsDir.listFiles()?.filter { it.name.startsWith("$base.") }?.forEach { it.delete() }
            throw DownloadCancelledException()
        }
        if (!p.waitFor(30, TimeUnit.MINUTES) || p.exitValue() != 0)
            error("yt-dlp download failed (exit ${runCatching { p.exitValue() }.getOrNull()})")
        val audioFile = downloadsDir.listFiles()?.firstOrNull {
            it.nameWithoutExtension == base && it.extension != "jpg" && it.extension != "json"
        } ?: error("yt-dlp output file not found")

        var artUri = track.artworkUri
        if (artUri != null && artUri.startsWith("http")) {
            runCatching {
                client.newCall(Request.Builder().url(artUri!!).build()).execute().use { ar ->
                    if (ar.isSuccessful) {
                        val artFile = File(downloadsDir, "$base.jpg")
                        ar.body?.byteStream()?.use { i -> FileOutputStream(artFile).use { i.copyTo(it) } }
                        artUri = artFile.absolutePath
                    }
                }
            }
        }
        val local = track.copy(uri = audioFile.absolutePath, artworkUri = artUri, fromYouTube = false)
        saveDownloadsMeta(loadDownloads().filterNot { it.matchKey() == local.matchKey() } + local)
        return local
    }

    @Synchronized fun deleteDownload(track: Track) {
        val f = File(track.uri); f.delete()
        File(f.parentFile, f.nameWithoutExtension + ".jpg").delete()
        saveDownloadsMeta(loadDownloads().filterNot { it.uri == track.uri })
    }

    // ---- local music scan (~/Music) ----
    private val audioExts = setOf("mp3", "m4a", "aac", "webm", "opus", "flac", "wav", "ogg")

    fun scanLocalMusic(): List<Track> {
        val musicDir = File(System.getProperty("user.home"), "Music")
        if (!musicDir.isDirectory) return emptyList()
        val files = mutableListOf<File>()
        fun walk(dir: File, depth: Int) {
            if (depth > 3) return
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory && f.name != "Orbit") walk(f, depth + 1)
                else if (f.isFile && f.extension.lowercase() in audioExts) files += f
            }
        }
        walk(musicDir, 0)
        return files.sortedBy { it.name.lowercase() }.take(1000).map { f ->
            Track(
                id = f.absolutePath.hashCode().toLong(),
                title = f.nameWithoutExtension, artist = f.parentFile?.name ?: "Local",
                uri = f.absolutePath, artworkUri = null, durationMs = 0
            )
        }
    }

    /** Reads the duration via ffprobe (ships with ffmpeg) — call in the background. */
    fun probeDurationMs(ffmpegPath: String?, file: String): Long {
        val ffprobe = ffmpegPath?.let { File(File(it).parentFile, File(it).name.replace("ffmpeg", "ffprobe")) }
            ?.takeIf { it.isFile }?.absolutePath ?: return 0
        return try {
            val p = ProcessBuilder(ffprobe, "-v", "quiet", "-show_entries", "format=duration",
                "-of", "csv=p=0", file).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(10, TimeUnit.SECONDS)
            (out.toDoubleOrNull()?.times(1000))?.toLong() ?: 0
        } catch (_: Exception) { 0 }
    }
}
