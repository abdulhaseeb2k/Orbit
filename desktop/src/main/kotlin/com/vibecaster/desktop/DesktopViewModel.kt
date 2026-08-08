package com.vibecaster.desktop

import com.vibecaster.data.Track
import com.vibecaster.data.EightDPreset
import com.vibecaster.data.Recommender
import com.vibecaster.data.matchKey
import com.vibecaster.sync.AuthManager
import com.vibecaster.sync.AuthSession
import com.vibecaster.sync.DeviceInfo
import com.vibecaster.sync.DeviceRegistry
import com.vibecaster.sync.SyncClient
import com.vibecaster.sync.SyncMerge
import com.vibecaster.sync.SyncPlaylist
import com.vibecaster.sync.isNetworkError
import com.vibecaster.youtube.YouTubeResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class RepeatMode { OFF, ALL, ONE }

/** The desktop MainViewModel — a slimmer twin of the Android one, same state names. */
class DesktopViewModel(val player: DesktopPlayer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Shell state (sidebar tab, queue panel) — keyboard shortcuts drive this too
    val currentTab = MutableStateFlow(AppTab.HOME)
    val showQueue = MutableStateFlow(false)

    // Volume + 8D as flows so the whole UI (bar, player, shortcuts) stays in sync
    val volumeFlow = MutableStateFlow(1f)
    fun setVolume(v: Float) { volumeFlow.value = v.coerceIn(0f, 1.5f); player.volume = volumeFlow.value }
    val eightDOn = MutableStateFlow(player.eightD.enabled)
    fun setEightD(on: Boolean) { eightDOn.value = on; player.eightD.enabled = on }
    fun applyPreset(p: EightDPreset) {
        setEightD(true)
        player.eightD.rotationSpeed = p.speed
        player.eightD.intensity = p.depth
        player.tone.bassDb = p.bassDb
    }

    // Search (merged YouTube + Audius)
    val ytResults = MutableStateFlow<List<Track>>(emptyList())
    val playlistName = MutableStateFlow<String?>(null)
    val ytLoading = MutableStateFlow(false)
    val ytError = MutableStateFlow<String?>(null)

    // Home content
    val ytTrending = MutableStateFlow<List<Track>>(emptyList())
    val recommendations = MutableStateFlow<List<Track>>(emptyList())

    // Discover (Audius + recents)
    val audiusResults = MutableStateFlow<List<Track>>(emptyList())
    val audiusLoading = MutableStateFlow(false)
    val audiusError = MutableStateFlow<String?>(null)

    // Library
    val localTracks = MutableStateFlow<List<Track>>(emptyList())
    val downloads = MutableStateFlow(Store.loadDownloads())
    val downloadProgress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    /** Ids waiting in the download queue. */
    val queuedDownloads = MutableStateFlow<Set<Long>>(emptySet())
    /** Ids the user cancelled — polled by the transfer loops (blocking reads
     *  ignore coroutine cancellation, so a flag is the reliable signal). */
    private val cancelledDownloads =
        java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    private val downloadQueue = kotlinx.coroutines.channels.Channel<Track>(
        kotlinx.coroutines.channels.Channel.UNLIMITED
    )

    val playlists = MutableStateFlow(Store.loadPlaylists())
    /** Playlist opened from the sidebar (null = list view). */
    val openPlaylist = MutableStateFlow<String?>(null)
    val recents = MutableStateFlow(Store.loadRecents())

    // Queue
    val queue = MutableStateFlow<List<Track>>(emptyList())
    val queueIndex = MutableStateFlow(0)
    val shuffleOn = MutableStateFlow(false)
    val repeatMode = MutableStateFlow(RepeatMode.OFF)

    // First-launch onboarding (demo + theme + optional sign-in), shown once.
    val showOnboarding = MutableStateFlow(
        Store.loadSettings().optString("onboarded") != "true"
    )
    fun completeOnboarding() {
        showOnboarding.value = false
        if (player.state.value.track?.id == -424242L) player.stop()
        scope.launch(Dispatchers.IO) { Store.saveSetting("onboarded", "true") }
    }

    // Settings
    val themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(Store.loadSettings().optString("theme", "VIBE")) }
            .getOrDefault(ThemeMode.VIBE)
    )
    fun setTheme(mode: ThemeMode) {
        themeMode.value = mode
        scope.launch(Dispatchers.IO) { Store.saveSetting("theme", mode.name) }
    }

    // Sleep timer
    val sleepRemainingMs = MutableStateFlow<Long?>(null)
    private var sleepJob: Job? = null

    // ---------- account & cloud sync ----------

    private val auth = AuthManager(persist = Store::saveAuthJson, restore = Store::loadAuthJson)
    val authUser = MutableStateFlow<AuthSession?>(auth.session)
    val syncBusy = MutableStateFlow(false)
    /** Last sync outcome for the Settings panel ("Synced ✓" / error text). */
    val syncStatus = MutableStateFlow<String?>(null)
    /** Shows the "No internet" dialog (user-initiated actions only). */
    val showNetworkError = MutableStateFlow(false)
    val autoDownloadSynced = MutableStateFlow(
        Store.loadSettings().optString("auto_download_synced", "true") == "true"
    )
    fun setAutoDownloadSynced(on: Boolean) {
        autoDownloadSynced.value = on
        scope.launch(Dispatchers.IO) { Store.saveSetting("auto_download_synced", on.toString()) }
        if (on) queueMissingPlaylistDownloads()
    }
    private var pushJob: Job? = null

    fun signInEmail(email: String, password: String, isNew: Boolean) {
        syncBusy.value = true; syncStatus.value = null
        scope.launch(Dispatchers.IO) {
            try {
                val s = if (isNew) auth.signUpEmail(email, password)
                        else auth.signInEmail(email, password)
                authUser.value = s
                syncStatus.value = null
                syncNow()
            } catch (e: Exception) {
                if (isNetworkError(e)) showNetworkError.value = true
                else syncStatus.value = e.message ?: "Sign-in failed."
            } finally { syncBusy.value = false }
        }
    }

    fun sendPasswordReset(email: String) {
        scope.launch(Dispatchers.IO) {
            try {
                auth.sendPasswordReset(email)
                syncStatus.value = "Password link sent — it may land in SPAM, check that folder too."
            } catch (e: Exception) {
                if (isNetworkError(e)) showNetworkError.value = true
                else syncStatus.value = e.message ?: "Could not send the email."
            }
        }
    }

    /** Google sign-in via the browser (loopback OAuth) — same account as the phone. */
    fun signInGoogle() {
        if (syncBusy.value) return
        syncBusy.value = true; syncStatus.value = "Waiting for the browser…"
        scope.launch(Dispatchers.IO) {
            try {
                val googleToken = GoogleAuthDesktop.signIn()
                val s = auth.signInGoogle(googleToken)
                authUser.value = s
                syncStatus.value = null
                syncNow()
            } catch (e: GoogleAuthDesktop.CancelledException) {
                syncStatus.value = null   // user changed their mind — not an error
            } catch (e: Exception) {
                OrbitLog.log("google sign-in failed: $e")
                if (isNetworkError(e)) showNetworkError.value = true
                else syncStatus.value = e.message ?: "Google sign-in failed."
            } finally { syncBusy.value = false }
        }
    }

    // ---- device sessions ----

    val devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devicesLoading = MutableStateFlow(false)

    fun loadDevices() {
        if (!auth.isSignedIn || devicesLoading.value) return
        devicesLoading.value = true
        scope.launch(Dispatchers.IO) {
            try {
                val token = auth.freshIdToken() ?: return@launch
                val uid = auth.session?.uid ?: return@launch
                devices.value = DeviceRegistry.list(uid, token)
            } catch (e: Exception) {
                OrbitLog.log("device list failed: $e")
            } finally { devicesLoading.value = false }
        }
    }

    /** Signs out another device: it drops its session on its next sync. */
    fun revokeDevice(device: DeviceInfo) {
        scope.launch(Dispatchers.IO) {
            try {
                val token = auth.freshIdToken() ?: return@launch
                val uid = auth.session?.uid ?: return@launch
                DeviceRegistry.revoke(uid, token, device.id)
                devices.value = devices.value.filterNot { it.id == device.id }
                syncStatus.value = "\"${device.name}\" will be signed out on its next sync."
            } catch (e: Exception) {
                if (isNetworkError(e)) showNetworkError.value = true
                else syncStatus.value = "Could not sign out that device: ${e.message}"
            }
        }
    }

    fun signOut() {
        // Best-effort: remove this device from the account's device list.
        val uid = auth.session?.uid
        val token = auth.session?.idToken
        if (uid != null && token != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { DeviceRegistry.delete(uid, token, Store.deviceId) }
            }
        }
        auth.signOut()
        authUser.value = null
        syncStatus.value = null
        devices.value = emptyList()
    }

    /**
     * Pull cloud library, union-merge with local, save, push back.
     * [manual] = user pressed the button — connectivity problems then show
     * the offline dialog; background syncs stay quiet.
     */
    fun syncNow(manual: Boolean = false) {
        if (!auth.isSignedIn || syncBusy.value) return
        syncBusy.value = true
        scope.launch(Dispatchers.IO) {
            try {
                val token = auth.freshIdToken()
                if (token == null) { authUser.value = auth.session; return@launch }
                val uid = auth.session?.uid ?: return@launch

                // Honor a remote "sign out this device" before doing anything.
                val self = runCatching { DeviceRegistry.fetch(uid, token, Store.deviceId) }.getOrNull()
                if (self?.revoked == true) {
                    runCatching { DeviceRegistry.delete(uid, token, Store.deviceId) }
                    auth.signOut()
                    authUser.value = null
                    devices.value = emptyList()
                    syncStatus.value = "This device was signed out from another device."
                    return@launch
                }
                runCatching {
                    DeviceRegistry.heartbeat(uid, token, DeviceInfo(
                        Store.deviceId, Store.deviceName, "desktop",
                        System.currentTimeMillis(), false
                    ))
                }

                val remote = SyncClient.fetch(uid, token)

                val localSp = playlists.value.map { SyncPlaylist(it.name, it.tracks) }
                val mergedSp = SyncMerge.mergePlaylists(localSp, remote?.playlists ?: emptyList())
                val mergedRecents = SyncMerge
                    .mergeTracks(recents.value, remote?.recents ?: emptyList()).take(50)

                val mergedLocal = mergedSp.map { Playlist(it.name, it.tracks) }
                playlists.value = mergedLocal
                Store.savePlaylists(mergedLocal)
                recents.value = mergedRecents
                Store.saveRecents(mergedRecents)

                SyncClient.push(uid, token, mergedSp, mergedRecents)
                syncStatus.value = "Synced ✓"
                if (autoDownloadSynced.value) queueMissingPlaylistDownloads()
            } catch (e: Exception) {
                OrbitLog.log("sync failed: $e")
                if (isNetworkError(e)) {
                    syncStatus.value = "Offline — will sync when you're back online."
                    if (manual) showNetworkError.value = true
                } else {
                    syncStatus.value = "Sync failed: ${e.message}"
                }
            } finally { syncBusy.value = false }
        }
    }

    /** Debounced cloud push after local playlist/recents edits. */
    private fun schedulePush() {
        if (!auth.isSignedIn) return
        pushJob?.cancel()
        pushJob = scope.launch(Dispatchers.IO) {
            delay(3000)
            runCatching {
                val token = auth.freshIdToken() ?: return@launch
                val uid = auth.session?.uid ?: return@launch
                SyncClient.push(uid, token,
                    playlists.value.map { SyncPlaylist(it.name, it.tracks) },
                    recents.value)
            }.onFailure { OrbitLog.log("push failed: $it") }
        }
    }

    /** "Auto-download synced playlists": queue every playlist song not on disk. */
    private fun queueMissingPlaylistDownloads() {
        playlists.value.flatMap { it.tracks }
            .filter { it.sourceUrl != null || it.uri.startsWith("http") }
            .filterNot { isDownloaded(it) }
            .forEach { download(it) }
    }

    init {
        player.onEnded = { onTrackEnded() }
        refreshLocal()
        if (auth.isSignedIn) syncNow()   // pick up phone-side changes on launch
        // Single download worker: one at a time, in queued order.
        scope.launch(Dispatchers.IO) {
            for (t in downloadQueue) {
                queuedDownloads.value = queuedDownloads.value - t.id
                if (t.id in cancelledDownloads) {          // cancelled while queued
                    cancelledDownloads.remove(t.id)
                    continue
                }
                if (!isDownloaded(t)) {
                    runCatching { downloadNow(t) }
                        .onFailure { OrbitLog.log("queued download failed: $it") }
                }
            }
        }
    }

    // ---------- playback ----------

    fun playFrom(list: List<Track>, track: Track) {
        queue.value = list
        queueIndex.value = list.indexOfFirst { it.matchKey() == track.matchKey() }.coerceAtLeast(0)
        startPlay(track)
    }

    private fun startPlay(track: Track) {
        // Downloaded copy first — offline aur instant (same as Android behavior).
        val local = downloads.value.firstOrNull { it.matchKey() == track.matchKey() }
        player.play(local ?: track)
        pushRecent(local ?: track)
    }

    fun togglePlayPause() {
        val s = player.state.value
        when {
            s.isPlaying -> player.pause()
            s.isPaused -> player.resume()
            s.track != null -> player.play(s.track!!)
        }
    }

    fun next() = advance(step = 1, fromAutoEnd = false)
    fun previous() {
        val s = player.state.value
        if (s.positionMs > 4000) { player.seekTo(0); return }
        advance(step = -1, fromAutoEnd = false)
    }

    private fun onTrackEnded() {
        when (repeatMode.value) {
            RepeatMode.ONE -> player.state.value.track?.let { player.play(it) }
            else -> advance(step = 1, fromAutoEnd = true)
        }
    }

    private fun advance(step: Int, fromAutoEnd: Boolean) {
        val q = queue.value
        if (q.isEmpty()) return
        var idx = queueIndex.value
        idx = if (shuffleOn.value && q.size > 1) {
            var r: Int; do { r = q.indices.random() } while (r == idx); r
        } else idx + step
        if (idx !in q.indices) {
            if (repeatMode.value == RepeatMode.ALL) idx = (idx + q.size) % q.size
            else if (fromAutoEnd) return   // end of queue, repeat off -> stop
            else idx = idx.coerceIn(q.indices)
        }
        queueIndex.value = idx
        startPlay(q[idx])
    }

    // ---------- search ----------

    /** ONE search box → YouTube + Audius parallel, merged (report B.2 / 5.2). */
    fun searchAll(query: String) {
        val q = query.trim(); if (q.isEmpty()) return
        ytLoading.value = true; ytError.value = null
        scope.launch(Dispatchers.IO) {
            try {
                if (q.startsWith("http")) {
                    if (YouTubeResolver.isPlaylistUrl(q)) {
                        val (name, items) = YouTubeResolver.playlistItems(q)
                        playlistName.value = name
                        ytResults.value = items
                        if (items.isEmpty()) ytError.value = "Playlist is empty or unavailable."
                    } else {
                        playlistName.value = null
                        ytResults.value = listOf(YouTubeResolver.resolve(q))
                    }
                    return@launch
                }
                playlistName.value = null
                coroutineScope {
                    val yt = async { runCatching { YouTubeResolver.search(q) }.getOrElse { emptyList() } }
                    val au = async { runCatching { AudiusRepo.search(q) }.getOrElse { emptyList() } }
                    val merged = yt.await() + au.await()
                    ytResults.value = merged
                    if (merged.isEmpty()) ytError.value = "No results found."
                }
            } catch (e: Exception) {
                ytError.value = "Search failed: ${e.message}"
            } finally { ytLoading.value = false }
        }
    }

    /** Home: YouTube music trending + Audius trending + personal recommendations. */
    fun loadHome() {
        if (ytTrending.value.isEmpty()) scope.launch(Dispatchers.IO) {
            runCatching { YouTubeResolver.trending() }
                .onSuccess { ytTrending.value = it }
                .onFailure { OrbitLog.log("yt trending failed: $it") }
        }
        loadAudiusTrending()
        if (recommendations.value.isEmpty()) scope.launch(Dispatchers.IO) {
            runCatching {
                Recommender.recommend(recents.value, downloads.value) { YouTubeResolver.related(it) }
            }.onSuccess { if (it.isNotEmpty()) recommendations.value = it }
                .onFailure { OrbitLog.log("recommendations failed: $it") }
        }
    }

    fun loadAudiusTrending() {
        if (audiusResults.value.isNotEmpty() || audiusLoading.value) return
        audiusLoading.value = true; audiusError.value = null
        scope.launch(Dispatchers.IO) {
            try { audiusResults.value = AudiusRepo.trending() }
            catch (e: Exception) { audiusError.value = "Audius: ${e.message}" }
            finally { audiusLoading.value = false }
        }
    }

    fun searchAudius(query: String) {
        audiusLoading.value = true; audiusError.value = null
        scope.launch(Dispatchers.IO) {
            try { audiusResults.value = AudiusRepo.search(query) }
            catch (e: Exception) { audiusError.value = "Audius: ${e.message}" }
            finally { audiusLoading.value = false }
        }
    }

    // ---------- library ----------

    fun refreshLocal() {
        scope.launch(Dispatchers.IO) {
            val list = Store.scanLocalMusic()
            localTracks.value = list
            // durations in background (ffprobe), update as they come
            val withDur = list.map { t ->
                if (t.durationMs > 0) t
                else t.copy(durationMs = Store.probeDurationMs(player.ffmpegPath, t.uri))
            }
            localTracks.value = withDur
        }
    }

    fun isDownloaded(t: Track) = downloads.value.any { it.matchKey() == t.matchKey() }

    fun download(track: Track) {
        if (isDownloaded(track) ||
            downloadProgress.value.containsKey(track.id) ||
            track.id in queuedDownloads.value
        ) return
        cancelledDownloads.remove(track.id)   // allow re-download after a cancel
        queuedDownloads.value = queuedDownloads.value + track.id
        downloadQueue.trySend(track)
    }

    /** Cancels one download — queued or already transferring. */
    fun cancelDownload(track: Track) {
        cancelledDownloads.add(track.id)
        queuedDownloads.value = queuedDownloads.value - track.id
    }

    /** Stops the in-flight transfer and empties the queue. */
    fun cancelAllDownloads() {
        cancelledDownloads.addAll(queuedDownloads.value)
        cancelledDownloads.addAll(downloadProgress.value.keys)
        queuedDownloads.value = emptySet()
    }

    /** One click: queue every song — the worker takes them one by one. */
    fun downloadAll(tracks: List<Track>) {
        tracks.forEach { download(it) }
    }

    private suspend fun downloadNow(track: Track) {
        val isCancelled = { track.id in cancelledDownloads }
        run {
            try {
                downloadProgress.value += (track.id to 0f)
                try {
                    val resolved = if (track.uri.startsWith("http")) track
                        else YouTubeResolver.resolve(track.sourceUrl ?: return, compact = true)
                    Store.download(
                        resolved.copy(id = track.id, title = track.title, artist = track.artist),
                        isCancelled = isCancelled
                    ) { p ->
                        downloadProgress.value += (track.id to p)
                    }
                } catch (e: DownloadCancelledException) {
                    throw e   // user stopped it — do NOT fall through to yt-dlp
                } catch (e: Exception) {
                    // Direct download 403/fail -> yt-dlp fallback (same philosophy as playback)
                    val ytdlp = player.ytDlpPath ?: throw e
                    OrbitLog.log("direct download failed ($e), using yt-dlp")
                    Store.downloadViaYtDlp(ytdlp, player.nodePath, track, isCancelled = isCancelled) { p ->
                        downloadProgress.value += (track.id to p)
                    }
                }
                downloads.value = Store.loadDownloads()
            } catch (e: DownloadCancelledException) {
                OrbitLog.log("download cancelled: ${track.title}")
            } catch (e: Exception) {
                OrbitLog.log("download failed: $e")
            } finally {
                downloadProgress.value -= track.id
                cancelledDownloads.remove(track.id)
            }
        }
    }

    fun deleteDownload(track: Track) {
        Store.deleteDownload(track)
        downloads.value = Store.loadDownloads()
    }

    // ---------- playlists ----------

    fun addToPlaylist(name: String, track: Track) {
        val list = playlists.value.toMutableList()
        val i = list.indexOfFirst { it.name == name }
        if (i >= 0) {
            if (list[i].tracks.none { it.matchKey() == track.matchKey() })
                list[i] = list[i].copy(tracks = list[i].tracks + track)
        } else list += Playlist(name, listOf(track))
        playlists.value = list; Store.savePlaylists(list); schedulePush()
    }

    fun createPlaylist(name: String) {
        if (name.isBlank() || playlists.value.any { it.name == name }) return
        val list = playlists.value + Playlist(name.trim(), emptyList())
        playlists.value = list; Store.savePlaylists(list); schedulePush()
    }

    fun deletePlaylist(name: String) {
        val list = playlists.value.filterNot { it.name == name }
        playlists.value = list; Store.savePlaylists(list); schedulePush()
    }

    fun removeFromPlaylist(name: String, track: Track) {
        val list = playlists.value.map { p ->
            if (p.name == name) p.copy(tracks = p.tracks.filterNot { it.matchKey() == track.matchKey() }) else p
        }
        playlists.value = list; Store.savePlaylists(list); schedulePush()
    }

    // ---------- recents ----------

    private fun pushRecent(track: Track) {
        val list = (listOf(track) + recents.value.filterNot { it.matchKey() == track.matchKey() }).take(50)
        recents.value = list
        scope.launch(Dispatchers.IO) { Store.saveRecents(list) }
        schedulePush()
    }

    // ---------- sleep timer ----------

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel(); sleepJob = null
        if (minutes == null) { sleepRemainingMs.value = null; return }
        sleepRemainingMs.value = minutes * 60_000L
        sleepJob = scope.launch {
            var left = minutes * 60_000L
            while (left > 0) {
                delay(1000); left -= 1000
                sleepRemainingMs.value = left
            }
            sleepRemainingMs.value = null
            player.stop()
        }
    }
}
