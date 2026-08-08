package com.vibecaster.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.vibecaster.youtube.YouTubeResolver
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Thrown when the user cancels a download that is already transferring. */
class DownloadCancelledException : Exception("Download cancelled")

/**
 * Offline downloads. Audio-only streams are saved (small size, full audio
 * quality) into app-specific storage, so no extra permissions are needed
 * and other apps cannot access the files.
 */
object DownloadRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads").apply { mkdirs() }

    private fun metaFile(context: Context) = File(dir(context), "downloads.json")

    @Synchronized
    fun load(context: Context): List<Track> {
        val f = metaFile(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length())
                .map { PlaylistRepository.trackFromJson(arr.getJSONObject(it)) }
                .filter { t ->
                    val path = Uri.parse(t.uri).path
                    path != null && File(path).exists()
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun saveMeta(context: Context, tracks: List<Track>) {
        val arr = JSONArray()
        tracks.forEach { arr.put(PlaylistRepository.trackToJson(it)) }
        metaFile(context).writeText(arr.toString())
    }

    /**
     * Downloads [track]'s stream (track.uri must be a resolved http URL)
     * and returns the local, offline-playable copy.
     *
     * [isCancelled] is polled on every 64 KB chunk: the moment it returns true
     * the transfer stops, the half-written file is deleted and
     * [DownloadCancelledException] is thrown. Coroutine cancellation alone
     * cannot do this — the socket read is a blocking call.
     */
    fun download(
        context: Context,
        track: Track,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit
    ): Track {
        val d = dir(context)
        val safe = track.title
            .replace(Regex("[^A-Za-z0-9 _.-]"), "")
            .trim()
            .take(40)
            .ifBlank { "track" }
        val base = "${safe}_${abs(track.id)}"

        // YouTube (googlevideo) rejects or heavily throttles single full-file
        // GETs, which is exactly why downloads "start then die" while streaming
        // playback (which reads in ranges) keeps working. So for googlevideo we
        // download in ranged chunks like a player would; everything else
        // (Audius, direct files) still uses one plain GET.
        val ranged = track.uri.contains("googlevideo.com")
        var partial: File? = null
        val audioFile: File = try {
            if (ranged) downloadRanged(d, base, track.uri, isCancelled, onProgress) { partial = it }
            else downloadPlain(d, base, track.uri, isCancelled, onProgress) { partial = it }
        } catch (e: Throwable) {
            // Cancelled or failed: never leave a truncated file behind — it
            // would show up in the library as a playable-but-broken download.
            partial?.delete()
            throw e
        }

        // Cache the artwork too, so it shows offline.
        var artUri = track.artworkUri
        if (artUri != null && artUri.startsWith("http")) {
            runCatching {
                client.newCall(Request.Builder().url(artUri!!).build()).execute().use { ar ->
                    if (ar.isSuccessful) {
                        val artFile = File(d, "$base.jpg")
                        ar.body?.byteStream()?.use { input ->
                            FileOutputStream(artFile).use { input.copyTo(it) }
                        }
                        artUri = Uri.fromFile(artFile).toString()
                    }
                }
            }
        }

        val local = track.copy(
            uri = Uri.fromFile(audioFile).toString(),
            artworkUri = artUri,
            fromYouTube = false
        )
        saveMeta(context, load(context).filterNot { it.sourceUrl == local.sourceUrl } + local)
        return local
    }

    private fun extFor(contentType: String) = when {
        contentType.contains("mpeg") -> "mp3"
        contentType.contains("webm") -> "webm"
        else -> "m4a"
    }

    /** One plain GET — for hosts that serve full files happily. */
    private fun downloadPlain(
        d: File, base: String, url: String,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
        onTarget: (File) -> Unit
    ): File {
        val call = client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", YouTubeResolver.USER_AGENT).build()
        )
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Download failed: HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("Empty response body")
            val target = File(d, "$base.${extFor(resp.header("Content-Type").orEmpty())}")
            onTarget(target)
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        if (isCancelled()) {
                            call.cancel()
                            throw DownloadCancelledException()
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            return target
        }
    }

    /**
     * Sequential ranged GETs (~3 MB each) — the way media players read, so
     * googlevideo neither throttles the transfer to a crawl nor 403s it.
     */
    private fun downloadRanged(
        d: File, base: String, url: String,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
        onTarget: (File) -> Unit
    ): File {
        val chunk = 3L * 1024 * 1024
        var target: File? = null
        var out: FileOutputStream? = null
        var pos = 0L
        var total = -1L
        try {
            while (total < 0 || pos < total) {
                if (isCancelled()) throw DownloadCancelledException()
                val req = Request.Builder().url(url)
                    .header("User-Agent", YouTubeResolver.USER_AGENT)
                    .header("Range", "bytes=$pos-${pos + chunk - 1}")
                    .build()
                val call = client.newCall(req)
                var served = 0L
                var wholeFile = false
                call.execute().use { resp ->
                    if (resp.code == 416) { total = pos; return@use }   // past EOF -> done
                    if (!resp.isSuccessful)
                        throw IllegalStateException("Download failed: HTTP ${resp.code}")
                    val body = resp.body ?: throw IllegalStateException("Empty response body")
                    if (target == null) {
                        target = File(d, "$base.${extFor(resp.header("Content-Type").orEmpty())}")
                        onTarget(target!!)
                        out = FileOutputStream(target!!)
                    }
                    if (resp.code == 206) {
                        // "bytes 0-3145727/4508876" -> grand total after the slash
                        if (total < 0) total = resp.header("Content-Range")
                            ?.substringAfter('/')?.toLongOrNull() ?: -1L
                    } else {
                        wholeFile = true            // server ignored Range (200)
                        total = body.contentLength()
                    }
                    val buf = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            if (isCancelled()) {
                                call.cancel()
                                throw DownloadCancelledException()
                            }
                            val n = input.read(buf)
                            if (n < 0) break
                            out!!.write(buf, 0, n)
                            served += n
                            pos += n
                            if (total > 0) onProgress((pos.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (wholeFile) break
                if (served <= 0) break              // server sent nothing more -> EOF
                if (total < 0 && served < chunk) break
            }
        } finally {
            runCatching { out?.close() }
        }
        return target ?: throw IllegalStateException("Download produced no data")
    }

    /**
     * Copies a downloaded file into the public Music/Orbit folder via
     * MediaStore, so other apps (and the user) can access it.
     */
    fun exportToMusic(context: Context, track: Track): Boolean {
        val path = Uri.parse(track.uri).path ?: return false
        val src = File(path)
        if (!src.exists()) return false
        val ext = src.extension.ifBlank { "m4a" }
        val mime = when (ext) {
            "mp3" -> "audio/mpeg"
            "webm" -> "audio/webm"
            else -> "audio/mp4"
        }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "${track.title}.$ext")
            put(MediaStore.Audio.Media.TITLE, track.title)
            put(MediaStore.Audio.Media.ARTIST, track.artist)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Orbit")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: return false
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return true
    }

    @Synchronized
    fun delete(context: Context, track: Track) {
        Uri.parse(track.uri).path?.let { path ->
            val f = File(path)
            f.delete()
            File(f.parentFile, f.nameWithoutExtension + ".jpg").delete()
        }
        saveMeta(context, load(context).filterNot { it.uri == track.uri })
    }
}
