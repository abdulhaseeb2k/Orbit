package com.vibecaster.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * First-launch tool setup: if ffmpeg / yt-dlp are missing, download them
 * automatically into %APPDATA%\Orbit\tools — the user never has to install
 * anything manually.
 */
object ToolBootstrap {

    sealed class State {
        data object Idle : State()
        /** progress < 0 means indeterminate (extracting). */
        data class Downloading(val tool: String, val progress: Float) : State()
        data object Ready : State()
        data class Failed(val message: String) : State()
    }

    val state = MutableStateFlow<State>(State.Idle)
    val toolsDir: File get() = File(Store.dataDir, "tools").apply { mkdirs() }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        // These downloads become executables we then RUN, so a server-issued
        // https -> http redirect must never be followed silently.
        .followSslRedirects(false)
        .build()
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    @Volatile private var started = false

    /** Manual "Download now" / retry from Settings — reruns the bootstrap. */
    fun retry(player: DesktopPlayer) {
        started = false
        state.value = State.Idle
        ensure(player)
    }

    fun ensure(player: DesktopPlayer) {
        if (started) return
        started = true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!isWindows) { state.value = State.Ready; return@launch }
                val needFfmpeg = player.ffmpegPath == null
                val needYtDlp = player.ytDlpPath == null
                if (!needFfmpeg && !needYtDlp) { state.value = State.Ready; return@launch }
                OrbitLog.log("bootstrap: downloading missing tools (ffmpeg=$needFfmpeg, yt-dlp=$needYtDlp)")

                if (needYtDlp) {
                    downloadFile(
                        "yt-dlp",
                        "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe",
                        File(toolsDir, "yt-dlp.exe")
                    )
                }
                if (needFfmpeg) {
                    val zip = File(toolsDir, "ffmpeg.zip")
                    downloadFile(
                        "ffmpeg",
                        "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip",
                        zip
                    )
                    state.value = State.Downloading("ffmpeg (extracting)", -1f)
                    extractFfmpeg(zip)
                    zip.delete()
                }
                state.value = State.Ready
                OrbitLog.log("bootstrap: tools ready in $toolsDir")
            } catch (e: Exception) {
                OrbitLog.log("bootstrap failed: $e")
                state.value = State.Failed(
                    "Automatic setup failed (${e.message}). Manual install: " +
                        "winget install --id Gyan.FFmpeg && winget install yt-dlp.yt-dlp"
                )
            }
        }
    }

    private fun downloadFile(name: String, url: String, dest: File) {
        state.value = State.Downloading(name, 0f)
        val tmp = File(dest.parentFile, dest.name + ".part")
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("$name download failed: HTTP ${resp.code}")
            val body = resp.body ?: error("empty response")
            val total = body.contentLength()
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) state.value = State.Downloading(name, done.toFloat() / total)
                    }
                }
            }
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) error("could not move $name into place")
    }

    /** Gyan essentials zip -> extract only bin/ffmpeg.exe + bin/ffprobe.exe. */
    private fun extractFfmpeg(zip: File) {
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name.replace('\\', '/')
                if (!e.isDirectory && (n.endsWith("/bin/ffmpeg.exe") || n.endsWith("/bin/ffprobe.exe"))) {
                    val out = File(toolsDir, n.substringAfterLast('/'))
                    out.outputStream().use { z.copyTo(it) }
                }
            }
        }
        if (!File(toolsDir, "ffmpeg.exe").isFile) error("ffmpeg.exe not found inside archive")
    }
}
