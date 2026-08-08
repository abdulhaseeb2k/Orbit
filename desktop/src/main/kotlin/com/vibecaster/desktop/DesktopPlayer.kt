package com.vibecaster.desktop

import com.vibecaster.data.Track
import com.vibecaster.youtube.YouTubeResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong
import java.io.File
import java.io.FileWriter
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

/** Finds ffmpeg / yt-dlp even when the PATH of the launching shell is stale
 *  (winget adds its Links folder to PATH only for NEW terminals). */
object Tools {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    private fun candidates(name: String, envOverride: String): List<String> {
        val exe = if (isWindows) "$name.exe" else name
        val list = mutableListOf<String>()
        System.getenv(envOverride)?.let { list += it }
        // auto-downloaded tools (ToolBootstrap) come first
        list += File(Store.dataDir, "tools").resolve(exe).path
        // Tools BUNDLED inside the packaged app (MSI/EXE builds run the
        // :desktop:fetchTools gradle task, which ships them in app resources).
        System.getProperty("compose.application.resources.dir")?.let {
            list += File(it, "tools").resolve(exe).path
            list += File(it, exe).path
        }
        if (isWindows) {
            // Prefer the WinGet install — PATH often lists Anaconda/old tools
            // first (e.g. Anaconda3\Scripts\ffmpeg.exe, which is ancient).
            System.getenv("LOCALAPPDATA")?.let { list += "$it\\Microsoft\\WinGet\\Links\\$exe" }
        }
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            if (dir.isNotBlank()) list += File(dir.trim(), exe).path
        }
        if (isWindows) {
            list += listOf(
                "C:\\ffmpeg\\bin\\$exe",
                "C:\\Program Files\\ffmpeg\\bin\\$exe",
                "C:\\ProgramData\\chocolatey\\bin\\$exe",
            )
        }
        return list
    }

    /** Runs `exe <args>`; true only if it exits 0 — screens out broken/ancient builds. */
    private fun works(exe: String, args: List<String>): Boolean = try {
        val p = ProcessBuilder(listOf(exe) + args).redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) { false }

    fun locate(name: String, envOverride: String, validateArgs: List<String>): String? {
        val seen = HashSet<String>()
        for (c in candidates(name, envOverride)) {
            if (!seen.add(c.lowercase())) continue
            val f = File(c)
            if (!f.isFile) continue
            if (works(c, validateArgs)) return c
            OrbitLog.log("rejected $name (validation failed — old/broken build): $c")
        }
        return null
    }
}

/** Append-only debug log: %TEMP%\orbit-desktop.log — the full story of any playback problem lives here. */
object OrbitLog {
    val file: File = File(System.getProperty("java.io.tmpdir"), "orbit-desktop.log")
    private val fmt = SimpleDateFormat("HH:mm:ss")
    @Synchronized fun log(msg: String) {
        try { FileWriter(file, true).use { it.write("${fmt.format(Date())}  $msg\n") } } catch (_: Exception) {}
        println("[orbit] $msg")
    }
}

/**
 * Desktop audio engine.
 *
 * Pipeline (2 fallback levels, because YouTube's media servers sometimes
 * reject direct URLs with 403):
 *   1) NewPipe stream URL ──ffmpeg──▶ PCM          (fast path)
 *   2) yt-dlp -o - ──pipe──▶ ffmpeg ──▶ PCM         (robust path)
 * then: PCM ──▶ Tone EQ ──▶ 8D rotation ──▶ volume ──▶ speakers.
 */
class DesktopPlayer {

    data class PlayerState(
        val track: Track? = null,
        val isPlaying: Boolean = false,
        val isPaused: Boolean = false,
        val isBuffering: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    val eightD = EightD()
    val tone = Tone()
    @Volatile var volume: Float = 1f

    /** Called when a track finishes naturally (queue auto-advance). */
    @Volatile var onEnded: (() -> Unit)? = null

    // Positive results are cached, negatives are NOT — so tools are picked
    // up right after ToolBootstrap's download finishes (no restart needed).
    @Volatile private var ffmpegCached: String? = null
    val ffmpegPath: String?
        get() = ffmpegCached ?: Tools.locate("ffmpeg", "ORBIT_FFMPEG", listOf("-hide_banner", "-version"))
            .also { if (it != null) { ffmpegCached = it; OrbitLog.log("ffmpeg: $it") } }

    @Volatile private var ytDlpCached: String? = null
    val ytDlpPath: String?
        get() = ytDlpCached ?: Tools.locate("yt-dlp", "ORBIT_YTDLP", listOf("--version"))
            .also { if (it != null) { ytDlpCached = it; OrbitLog.log("yt-dlp: $it") } }

    /** JS runtime for yt-dlp's YouTube n-challenge (deno default; node works too). */
    @Volatile private var nodeCached: String? = null
    val nodePath: String?
        get() = nodeCached ?: Tools.locate("node", "ORBIT_NODE", listOf("--version"))
            .also { if (it != null) nodeCached = it }
    val ffmpegAvailable: Boolean get() = ffmpegPath != null

    private val sampleRate = 48000
    private val processes = mutableListOf<Process>()
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var playThread: Thread? = null
    @Volatile private var paused = false

    /**
     * Playback generation. Every play() and stop() bumps it, which instantly
     * invalidates any thread left over from an earlier attempt.
     *
     * This replaces a single `stopRequested` boolean. That flag was set by
     * stop() and then cleared again by the NEXT play() — so an audio thread
     * that outlived stop()'s join(1500) (easy: it can sit in a
     * non-interruptible OkHttp socket read for 20s+) woke up to find itself
     * un-cancelled, opened a second SourceDataLine and played a second track
     * over the first. A monotonic counter cannot be un-cancelled: once a
     * generation is superseded it is superseded forever.
     */
    private val generation = AtomicLong(0)

    private fun isCurrent(gen: Long) = generation.get() == gen

    /**
     * Applies a state update only while [gen] is still the live playback.
     * Uses update {} rather than value = value.copy(): the audio thread writes
     * position ~23x/second and would otherwise clobber a pause() that landed
     * between its read and its write, flipping the transport back to "playing".
     */
    private fun setState(gen: Long, block: (PlayerState) -> PlayerState) {
        if (!isCurrent(gen)) return
        _state.update { if (isCurrent(gen)) block(it) else it }
    }

    /** Tracks a child process for cleanup, or kills it if [gen] is already stale. */
    private fun register(gen: Long, p: Process): Process {
        synchronized(processes) {
            if (isCurrent(gen)) processes.add(p) else p.destroyForcibly()
        }
        return p
    }

    fun play(track: Track, startMs: Long = 0) {
        stop()
        val ffmpeg = ffmpegPath
        if (ffmpeg == null) {
            _state.value = PlayerState(
                track = track,
                error = "Audio engine (ffmpeg) is not ready yet — Orbit downloads it automatically on first launch (see the progress banner). If that failed: winget install --id Gyan.FFmpeg"
            )
            return
        }
        // Claim this attempt. Anything older is now stale by definition.
        val gen = generation.incrementAndGet()
        paused = false
        // positionMs = startMs keeps the slider from snapping to 0 mid-seek (seek-reset bug fix)
        _state.value = PlayerState(track = track, isBuffering = true,
            durationMs = track.durationMs, positionMs = startMs)

        playThread = thread(name = "orbit-audio", isDaemon = true) {
            try {
                OrbitLog.log("play: ${track.title} (start=${startMs}ms)")

                // Local file (download / library) — no network, no fallback needed.
                val localPath = track.uri.removePrefix("file://")
                if (track.uri.isNotBlank() && !track.uri.startsWith("http") && File(localPath).isFile) {
                    val cmd = mutableListOf(ffmpeg, "-hide_banner", "-loglevel", "error")
                    if (startMs > 0) { cmd += "-ss"; cmd += (startMs / 1000.0).toString() }
                    cmd += listOf("-i", localPath, "-vn", "-f", "s16le", "-acodec", "pcm_s16le",
                        "-ac", "2", "-ar", sampleRate.toString(), "pipe:1")
                    val r = runAudio(gen, ProcessBuilder(cmd), startMs)
                    finish(gen, r.first)
                    return@thread
                }

                // Path 1: direct stream URL through ffmpeg
                val streamUrl = try {
                    if (track.uri.isNotBlank()) track.uri
                    else kotlinx.coroutines.runBlocking { YouTubeResolver.resolve(track.sourceUrl!!) }.uri
                } catch (e: InterruptedException) {
                    return@thread   // user started another track — exit quietly
                } catch (e: Exception) {
                    if (!isCurrent(gen)) return@thread
                    OrbitLog.log("resolve failed: $e"); null
                }

                var played = 0L
                var lastErr = ""
                if (streamUrl != null && isCurrent(gen)) {
                    val cmd = mutableListOf(ffmpeg, "-hide_banner", "-loglevel", "error")
                    if (startMs > 0) { cmd += "-ss"; cmd += (startMs / 1000.0).toString() }
                    cmd += listOf(
                        "-user_agent", YouTubeResolver.USER_AGENT,
                        "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5",
                        "-i", streamUrl,
                        "-vn", "-f", "s16le", "-acodec", "pcm_s16le", "-ac", "2", "-ar", sampleRate.toString(), "pipe:1"
                    )
                    val r = runAudio(gen, ProcessBuilder(cmd), startMs)
                    played = r.first; lastErr = r.second
                    if (played > 0 || !isCurrent(gen)) { finish(gen, played); return@thread }
                    OrbitLog.log("direct path failed (0 audio). stderr: $lastErr")
                }

                // Path 2: yt-dlp pipes the media file, ffmpeg decodes from stdin
                val ytdlp = ytDlpPath
                if (ytdlp != null && isCurrent(gen)) {
                    OrbitLog.log("falling back to yt-dlp pipe")
                    setState(gen) { it.copy(isBuffering = true, error = null) }
                    val watch = track.sourceUrl ?: track.uri
                    val ytCmd = mutableListOf(ytdlp, "-f", "bestaudio/best", "--no-playlist", "-q", "-o", "-")
                    nodePath?.let { ytCmd += listOf("--js-runtimes", "node:$it") }
                    ytCmd += "--"   // end of options — never treat a URL as a flag
                    ytCmd += watch
                    val ffCmd = mutableListOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-i", "pipe:0")
                    if (startMs > 0) { ffCmd += "-ss"; ffCmd += (startMs / 1000.0).toString() } // decode-skip
                    ffCmd += listOf("-vn", "-f", "s16le", "-acodec", "pcm_s16le", "-ac", "2", "-ar", sampleRate.toString(), "pipe:1")

                    val yt = register(gen, ProcessBuilder(ytCmd).start())
                    val ytErr = collectStderr(yt)
                    // finally: if runAudio bails (e.g. the audio device is busy)
                    // the pump thread dies, nobody drains yt-dlp's stdout, and
                    // yt-dlp blocks forever on a full 64 KB pipe — an orphaned
                    // process and network connection per failed attempt.
                    val r = try {
                        runAudio(gen, ProcessBuilder(ffCmd), startMs) { ff ->
                            // pump yt-dlp stdout -> ffmpeg stdin
                            thread(isDaemon = true, name = "orbit-pump") {
                                try { yt.inputStream.copyTo(ff.outputStream, 65536); ff.outputStream.close() }
                                catch (_: Exception) {}
                            }
                        }
                    } finally {
                        yt.destroyForcibly()
                    }
                    played = r.first
                    if (played > 0 || !isCurrent(gen)) { finish(gen, played); return@thread }
                    lastErr = "yt-dlp: ${ytErr.toString().takeLast(400)} | ffmpeg: ${r.second}"
                    OrbitLog.log("yt-dlp path failed too. $lastErr")
                }

                if (isCurrent(gen)) {
                    val hint = if (ytDlpPath == null)
                        "\nFix: install yt-dlp → winget install yt-dlp.yt-dlp, then restart Orbit."
                    else "\nDetails: ${OrbitLog.file.absolutePath}"
                    setState(gen) {
                        it.copy(
                            isPlaying = false, isBuffering = false,
                            error = "Couldn't play this track. ${lastErr.takeLast(220)}$hint"
                        )
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                OrbitLog.log("fatal: $e")
                setState(gen) {
                    it.copy(isPlaying = false, isBuffering = false, error = "Playback error: ${e.message}")
                }
            }
        }
    }

    private fun finish(gen: Long, played: Long) {
        setState(gen) { it.copy(isPlaying = false, isBuffering = false, positionMs = 0) }
        OrbitLog.log("finished (frames=$played)")
        // Only the live generation may auto-advance the queue. A superseded
        // thread reaching its end used to fire onEnded and skip a track the
        // user never asked to skip.
        if (isCurrent(gen) && played > 0) onEnded?.invoke()
    }

    private fun collectStderr(p: Process): StringBuilder {
        val sb = StringBuilder()
        thread(isDaemon = true, name = "orbit-stderr") {
            try {
                p.errorStream.bufferedReader().forEachLine {
                    if (sb.length < 4000) sb.appendLine(it)
                    OrbitLog.log("  [proc] $it")
                }
            } catch (_: Exception) {}
        }
        return sb
    }

    /** Runs ffmpeg, streams PCM to the audio device. Returns (framesPlayed, stderrText). */
    private fun runAudio(
        gen: Long,
        pb: ProcessBuilder,
        startMs: Long,
        onStart: ((Process) -> Unit)? = null
    ): Pair<Long, String> {
        if (!isCurrent(gen)) return 0L to "superseded"
        val proc = register(gen, pb.start())
        val err = collectStderr(proc)
        onStart?.invoke(proc)

        val fmt = AudioFormat(sampleRate.toFloat(), 16, 2, true, false)
        val out = try {
            AudioSystem.getSourceDataLine(fmt).apply { open(fmt, sampleRate); start() }
        } catch (e: Exception) {
            proc.destroyForcibly()
            OrbitLog.log("audio device error: $e")
            setState(gen) {
                it.copy(isPlaying = false, isBuffering = false, error = "Audio device error: ${e.message}")
            }
            return 0L to "audio-device"
        }
        // A stale generation must never take ownership of the shared line.
        if (isCurrent(gen)) line = out else { runCatching { out.close() }; return 0L to "superseded" }

        // Position counters are LOCALS now. As instance fields two overlapping
        // playback threads shared them, so a surviving old thread rewound the
        // new track's position.
        val baseMs = startMs
        var framesWritten = 0L
        eightD.resetPhase()
        tone.reset()

        val input: InputStream = proc.inputStream
        val bytes = ByteArray(8192)
        val shorts = ShortArray(4096)
        var leftover = -1  // odd-byte carry between reads
        var started = false

        try {
            while (isCurrent(gen)) {
                while (paused && isCurrent(gen)) { out.stop(); Thread.sleep(50) }
                if (!isCurrent(gen)) break
                if (!out.isRunning) out.start()

                var off = 0
                if (leftover >= 0) { bytes[0] = leftover.toByte(); off = 1; leftover = -1 }
                val n = input.read(bytes, off, bytes.size - off)
                if (n <= 0) break
                var total = off + n
                if (total % 2 != 0) { leftover = bytes[total - 1].toInt() and 0xFF; total-- }
                val sampleCount = total / 2

                var i = 0; var s = 0
                while (i + 1 < total) {
                    shorts[s] = ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort()
                    i += 2; s++
                }
                tone.process(shorts, sampleCount, sampleRate)
                eightD.process(shorts, sampleCount, sampleRate)
                val vol = volume
                i = 0; s = 0
                while (s < sampleCount) {
                    val v = (shorts[s] * vol).toInt().coerceIn(-32768, 32767)
                    bytes[i] = (v and 0xFF).toByte()
                    bytes[i + 1] = ((v shr 8) and 0xFF).toByte()
                    i += 2; s++
                }
                out.write(bytes, 0, sampleCount * 2)
                framesWritten += sampleCount / 2

                if (!started) {
                    started = true
                    OrbitLog.log("audio flowing")
                    setState(gen) { it.copy(isPlaying = true, isBuffering = false, error = null) }
                }
                setState(gen) { it.copy(positionMs = baseMs + framesWritten * 1000 / sampleRate) }
            }
        } finally {
            try { if (framesWritten > 0 && isCurrent(gen)) out.drain() } catch (_: Exception) {}
            try { out.close() } catch (_: Exception) {}
            if (line === out) line = null
            proc.destroyForcibly()
        }
        return framesWritten to err.toString().takeLast(500)
    }

    fun pause() { paused = true; _state.update { it.copy(isPaused = true, isPlaying = false) } }

    fun resume() { paused = false; _state.update { it.copy(isPaused = false, isPlaying = true) } }

    fun seekTo(ms: Long) {
        val t = _state.value.track ?: return
        play(t, ms.coerceAtLeast(0))
    }

    /**
     * Stops playback. Returns immediately — it does NOT join the audio thread.
     *
     * play() calls this first, and play()/seekTo() are invoked straight from
     * Compose click and key handlers, so the old join(1500) froze the whole
     * window for up to 1.5s every time the user clicked a different track or
     * held the seek key. Joining is also unnecessary now: bumping the
     * generation makes the old thread inert, destroying its processes ends its
     * blocking read, and closing the line unblocks a stalled write. It then
     * unwinds and cleans up after itself in its own finally.
     */
    fun stop() {
        generation.incrementAndGet()
        paused = false
        synchronized(processes) { processes.forEach { it.destroyForcibly() }; processes.clear() }
        playThread?.interrupt()
        playThread = null
        runCatching { line?.close() }
        line = null
        _state.update { it.copy(isPlaying = false, isPaused = false, isBuffering = false, positionMs = 0) }
    }
}
