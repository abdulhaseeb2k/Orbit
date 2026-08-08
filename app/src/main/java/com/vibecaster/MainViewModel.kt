package com.vibecaster

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.vibecaster.data.AudiusRepository
import com.vibecaster.data.DownloadRepository
import com.vibecaster.data.DownloadCancelledException
import com.vibecaster.data.LocalAudioRepository
import com.vibecaster.data.LyricsRepository
import com.vibecaster.data.RecentsRepository
import com.vibecaster.data.Playlist
import com.vibecaster.data.PlaylistRepository
import com.vibecaster.data.Recommender
import com.vibecaster.data.Track
import com.vibecaster.data.matchKey
import com.vibecaster.data.UpdateRepository
import com.vibecaster.sync.AuthManager
import com.vibecaster.sync.AuthSession
import com.vibecaster.sync.DeviceInfo
import com.vibecaster.sync.DeviceRegistry
import com.vibecaster.sync.SyncClient
import com.vibecaster.sync.SyncMerge
import com.vibecaster.sync.SyncPlaylist
import com.vibecaster.sync.isNetworkError
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.vibecaster.player.PlaybackService
import com.vibecaster.player.PlayerHolder
import com.vibecaster.ui.AppTab
import com.vibecaster.ui.theme.ThemeMode
import com.vibecaster.youtube.YouTubeResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val player: ExoPlayer = PlayerHolder.get(app)

    // A controller connected to PlaybackService keeps the media notification
    // (status bar + lock screen controls) alive on every Android version,
    // including Android 12 where MediaSessionService needs a bound controller.
    private var mediaController: MediaController? = null
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val DEMO_TRACK_ID = -424242L

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _queue = MutableStateFlow<List<Track>>(emptyList())

    private val _current = MutableStateFlow<Track?>(null)
    val current = _current.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    /** True while the player is loading/buffering from the network. */
    private val _isBuffering = MutableStateFlow(false)
    val isBuffering = _isBuffering.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs = _durationMs.asStateFlow()

    private val _effectOn = MutableStateFlow(true)
    val effectOn = _effectOn.asStateFlow()

    private val _rotationSpeed = MutableStateFlow(0.12f)
    val rotationSpeed = _rotationSpeed.asStateFlow()

    private val _intensity = MutableStateFlow(0.9f)
    val intensity = _intensity.asStateFlow()

    private val _ytLoading = MutableStateFlow(false)
    val ytLoading = _ytLoading.asStateFlow()

    private val _ytError = MutableStateFlow<String?>(null)
    val ytError = _ytError.asStateFlow()

    private val _ytResults = MutableStateFlow<List<Track>>(emptyList())
    val ytResults = _ytResults.asStateFlow()

    private val _audiusResults = MutableStateFlow<List<Track>>(emptyList())
    val audiusResults = _audiusResults.asStateFlow()

    private val _audiusLoading = MutableStateFlow(false)
    val audiusLoading = _audiusLoading.asStateFlow()

    private val _audiusError = MutableStateFlow<String?>(null)
    val audiusError = _audiusError.asStateFlow()

    private val _ytTrending = MutableStateFlow<List<Track>>(emptyList())
    val ytTrending = _ytTrending.asStateFlow()

    private val _recommendations = MutableStateFlow<List<Track>>(emptyList())
    val recommendations = _recommendations.asStateFlow()

    private val _ytPlaylistName = MutableStateFlow<String?>(null)
    val ytPlaylistName = _ytPlaylistName.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    private val _downloads = MutableStateFlow<List<Track>>(emptyList())
    val downloads = _downloads.asStateFlow()

    /** Per-track download progress (0..1), keyed by track id. */
    private val _downloadProgress = MutableStateFlow<Map<Long, Float>>(emptyMap())

    /** Ids waiting in the download queue (shown as an indeterminate ring). */
    private val _queuedDownloads = MutableStateFlow<Set<Long>>(emptySet())
    val queuedDownloads = _queuedDownloads.asStateFlow()

    private val cancelledDownloads = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    private val downloadQueue = Channel<Track>(Channel.UNLIMITED)

    /** Single worker — downloads run ONE at a time, in the order queued. */
    private val downloadWorker = viewModelScope.launch {
        for (t in downloadQueue) {
            _queuedDownloads.value = _queuedDownloads.value - t.id
            if (t.id in cancelledDownloads) {
                cancelledDownloads.remove(t.id)
                continue
            }
            if (!isDownloaded(t)) {
                try {
                    downloadNow(t)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "Queued download failed", e)
                }
            }
        }
    }
    val downloadProgress = _downloadProgress.asStateFlow()

    /** When set, the UI must launch this system dialog to confirm file deletion. */
    private val _deleteRequest = MutableStateFlow<IntentSender?>(null)
    val deleteRequest = _deleteRequest.asStateFlow()

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString("theme", ThemeMode.SYSTEM.name)!!) }
            .getOrDefault(ThemeMode.SYSTEM)
    )
    val themeMode = _themeMode.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn = _isLoggedIn.asStateFlow()

    private val _onboarded = MutableStateFlow(prefs.getBoolean("onboarded", false))
    val onboarded = _onboarded.asStateFlow()

    // ---- Account & cloud sync (same Firebase account as the desktop app) ----

    private val authPrefs = app.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val auth = AuthManager(
        persist = { json ->
            authPrefs.edit().apply {
                if (json == null) remove("session") else putString("session", json)
            }.apply()
        },
        restore = { authPrefs.getString("session", null) }
    )

    private val _authUser = MutableStateFlow<AuthSession?>(auth.session)
    val authUser = _authUser.asStateFlow()

    private val _syncBusy = MutableStateFlow(false)
    val syncBusy = _syncBusy.asStateFlow()

    /** Last sync/sign-in outcome shown in the account screen. */
    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus = _syncStatus.asStateFlow()

    private val _autoDownloadSynced = MutableStateFlow(prefs.getBoolean("auto_download_synced", true))
    val autoDownloadSynced = _autoDownloadSynced.asStateFlow()

    /** Account screen overlay (post-onboarding prompt + Settings entry). */
    private val _showAccount = MutableStateFlow(false)
    val showAccount = _showAccount.asStateFlow()

    /** Shows the "No internet connection" dialog (user-initiated actions only). */
    private val _showNetworkError = MutableStateFlow(false)
    val showNetworkError = _showNetworkError.asStateFlow()
    fun dismissNetworkError() { _showNetworkError.value = false }

    // ---- device identity & sessions ----

    /** Stable random id for THIS install (survives sign-out, not reinstall). */
    private val deviceId: String =
        prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString()
            .also { prefs.edit().putString("device_id", it).apply() }
    private val deviceName: String =
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
            .replaceFirstChar { it.uppercase() }

    val thisDeviceId: String get() = deviceId

    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices = _devices.asStateFlow()
    private val _devicesLoading = MutableStateFlow(false)
    val devicesLoading = _devicesLoading.asStateFlow()

    fun loadDevices() {
        if (!auth.isSignedIn || _devicesLoading.value) return
        _devicesLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val token = auth.freshIdToken() ?: return@launch
                val uid = auth.session?.uid ?: return@launch
                _devices.value = DeviceRegistry.list(uid, token)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Device list failed", e)
            } finally { _devicesLoading.value = false }
        }
    }

    /** Signs out another device: it drops its session on its next sync. */
    fun revokeDevice(device: DeviceInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val token = auth.freshIdToken() ?: return@launch
                val uid = auth.session?.uid ?: return@launch
                DeviceRegistry.revoke(uid, token, device.id)
                _devices.value = _devices.value.filterNot { it.id == device.id }
                _syncStatus.value = "\"${device.name}\" will be signed out on its next sync."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (isNetworkError(e)) _showNetworkError.value = true
                else _syncStatus.value = "Could not sign out that device: ${e.message}"
            }
        }
    }

    private var pushJob: Job? = null

    private val _tabOrder = MutableStateFlow(loadTabOrder())

    val tabOrder = _tabOrder.asStateFlow()


    /** Current playback queue (visible in the queue sheet). */
    val queue = _queue.asStateFlow()

    private val _queueIndex = MutableStateFlow(0)
    val queueIndex = _queueIndex.asStateFlow()

    private val _shuffleOn = MutableStateFlow(false)
    val shuffleOn = _shuffleOn.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode = _repeatMode.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed = _playbackSpeed.asStateFlow()

    /** Remaining sleep-timer time in ms, or null when off. */
    private val _sleepRemainingMs = MutableStateFlow<Long?>(null)
    val sleepRemainingMs = _sleepRemainingMs.asStateFlow()
    private var sleepJob: Job? = null

    private val _recents = MutableStateFlow<List<Track>>(emptyList())
    val recents = _recents.asStateFlow()

    private val _lyrics = MutableStateFlow<String?>(null)
    val lyrics = _lyrics.asStateFlow()

    private val _lyricsLoading = MutableStateFlow(false)
    val lyricsLoading = _lyricsLoading.asStateFlow()

    private val _bassDb = MutableStateFlow(0f)
    val bassDb = _bassDb.asStateFlow()

    private val _trebleDb = MutableStateFlow(0f)
    val trebleDb = _trebleDb.asStateFlow()

    private val _reverse8d = MutableStateFlow(false)
    val reverse8d = _reverse8d.asStateFlow()

    /** Set when a newer GitHub release is available. */
    private val _updateInfo = MutableStateFlow<UpdateRepository.UpdateInfo?>(null)
    val updateInfo = _updateInfo.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog = _showUpdateDialog.asStateFlow()

    val appVersion: String by lazy {
        runCatching {
            getApplication<Application>().packageManager
                .getPackageInfo(getApplication<Application>().packageName, 0)
                .versionName
        }.getOrNull() ?: "1.0"
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                _isBuffering.value = state == Player.STATE_BUFFERING
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _queueIndex.value = player.currentMediaItemIndex
                _current.value = _queue.value.getOrNull(player.currentMediaItemIndex) ?: _current.value
                _lyrics.value = null
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback error", error)
                if (_current.value?.fromYouTube == true) {
                    _ytError.value =
                        "Playback failed (${error.errorCodeName}). The stream URL may have expired — resolve the link again."
                }
            }
        })
        // Position ticker
        viewModelScope.launch {
            while (true) {
                _positionMs.value = player.currentPosition.coerceAtLeast(0L)
                _durationMs.value = player.duration.coerceAtLeast(0L)
                delay(500)
            }
        }
        applyEffect()
        loadPlaylists()
        loadDownloads()
        viewModelScope.launch(Dispatchers.IO) {
            _recents.value = RecentsRepository.load(getApplication())
        }
        // Silent update check on launch.
        checkForUpdates(manual = false)

        connectMediaController()

        // Pick up changes made on other devices (desktop) on every launch.
        if (auth.isSignedIn) syncNow()
    }

    // ---- Account & sync ----

    fun openAccount() { _showAccount.value = true }

    fun dismissAccount() {
        _showAccount.value = false
        prefs.edit().putBoolean("asked_signin", true).apply()
    }

    /** Lets the UI surface Credential Manager errors in the account screen. */
    fun reportAuthError(message: String) { _syncStatus.value = message }

    /** Lets the UI trigger the offline dialog for its own network failures. */
    fun reportNetworkError() { _showNetworkError.value = true }

    fun signInEmail(email: String, password: String, isNew: Boolean) {
        _syncBusy.value = true
        _syncStatus.value = null
        viewModelScope.launch {
            try {
                val s = withContext(Dispatchers.IO) {
                    if (isNew) auth.signUpEmail(email, password)
                    else auth.signInEmail(email, password)
                }
                _authUser.value = s
                toast("Signed in as ${s.email}")
                syncNow()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (isNetworkError(e)) _showNetworkError.value = true
                else _syncStatus.value = e.message ?: "Sign-in failed."
            } finally { _syncBusy.value = false }
        }
    }

    fun signInWithGoogleToken(googleIdToken: String) {
        _syncBusy.value = true
        _syncStatus.value = null
        viewModelScope.launch {
            try {
                val s = withContext(Dispatchers.IO) { auth.signInGoogle(googleIdToken) }
                _authUser.value = s
                toast("Signed in as ${s.email}")
                syncNow()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (isNetworkError(e)) _showNetworkError.value = true
                else _syncStatus.value = e.message ?: "Google sign-in failed."
            } finally { _syncBusy.value = false }
        }
    }

    fun sendPasswordReset(email: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { auth.sendPasswordReset(email) }
                _syncStatus.value =
                    "Password link sent — it may land in SPAM, check that folder too."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (isNetworkError(e)) _showNetworkError.value = true
                else _syncStatus.value = e.message ?: "Could not send the email."
            }
        }
    }

    /**
     * Sets a password on the signed-in account IN-APP (no email, no spam
     * folder) — this is how a Google account gets a password for desktop.
     */
    fun setDesktopPassword(password: String) {
        if (password.length < 6) {
            _syncStatus.value = "Password must be at least 6 characters."
            return
        }
        _syncBusy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { auth.setPassword(password) }
                _syncStatus.value = "Password set ✓ — sign in on desktop with your email + this password."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (isNetworkError(e)) _showNetworkError.value = true
                else _syncStatus.value = e.message ?: "Could not set the password."
            } finally { _syncBusy.value = false }
        }
    }

    fun signOut() {
        // Best-effort: remove this phone from the account's device list.
        val uid = auth.session?.uid
        val token = auth.session?.idToken
        if (uid != null && token != null) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { DeviceRegistry.delete(uid, token, deviceId) }
            }
        }
        auth.signOut()
        _authUser.value = null
        _syncStatus.value = null
        _devices.value = emptyList()
    }

    fun setAutoDownloadSynced(on: Boolean) {
        _autoDownloadSynced.value = on
        prefs.edit().putBoolean("auto_download_synced", on).apply()
        if (on) queueMissingPlaylistDownloads()
    }

    /**
     * Pull cloud library, union-merge with local, save, push back.
     * [manual] = user pressed a button — connectivity problems then show the
     * offline dialog; background syncs stay quiet.
     */
    fun syncNow(manual: Boolean = false) {
        if (!auth.isSignedIn || _syncBusy.value) return
        _syncBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val token = auth.freshIdToken()
                if (token == null) { _authUser.value = auth.session; return@launch }
                val uid = auth.session?.uid ?: return@launch

                // Honor a remote "sign out this device" before doing anything.
                val self = runCatching { DeviceRegistry.fetch(uid, token, deviceId) }.getOrNull()
                if (self?.revoked == true) {
                    runCatching { DeviceRegistry.delete(uid, token, deviceId) }
                    auth.signOut()
                    _authUser.value = null
                    _devices.value = emptyList()
                    _syncStatus.value = "This device was signed out from another device."
                    return@launch
                }
                runCatching {
                    DeviceRegistry.heartbeat(uid, token, DeviceInfo(
                        deviceId, deviceName, "android", System.currentTimeMillis(), false
                    ))
                }

                val remote = SyncClient.fetch(uid, token)

                val localSp = _playlists.value.map { SyncPlaylist(it.name, it.tracks) }
                val mergedSp = SyncMerge.mergePlaylists(localSp, remote?.playlists ?: emptyList())
                val mergedRecents = SyncMerge
                    .mergeTracks(_recents.value, remote?.recents ?: emptyList()).take(50)

                val mergedLocal = mergedSp.map { Playlist(it.name, it.tracks) }
                _playlists.value = mergedLocal
                PlaylistRepository.save(getApplication(), mergedLocal)
                _recents.value = mergedRecents
                RecentsRepository.save(getApplication(), mergedRecents)

                SyncClient.push(uid, token, mergedSp, mergedRecents)
                _syncStatus.value = "Synced ✓"
                if (_autoDownloadSynced.value) queueMissingPlaylistDownloads()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Sync failed", e)
                if (isNetworkError(e)) {
                    _syncStatus.value = "Offline — will sync when you're back online."
                    if (manual) _showNetworkError.value = true
                } else {
                    _syncStatus.value = "Sync failed: ${e.message}"
                }
            } finally { _syncBusy.value = false }
        }
    }

    /** Debounced cloud push after local playlist/recents edits. */
    private fun schedulePush() {
        if (!auth.isSignedIn) return
        pushJob?.cancel()
        pushJob = viewModelScope.launch(Dispatchers.IO) {
            delay(3000)
            runCatching {
                val token = auth.freshIdToken() ?: return@launch
                val uid = auth.session?.uid ?: return@launch
                SyncClient.push(uid, token,
                    _playlists.value.map { SyncPlaylist(it.name, it.tracks) },
                    _recents.value)
            }.onFailure { Log.e(TAG, "Sync push failed", it) }
        }
    }

    /** "Auto-download synced playlists": queue every playlist song not on disk. */
    private fun queueMissingPlaylistDownloads() {
        _playlists.value.flatMap { it.tracks }
            .filter { it.sourceUrl != null || it.uri.startsWith("http") }
            .filterNot { isDownloaded(it) }
            .forEach { download(it) }
    }

    private fun connectMediaController() {
        val app = getApplication<Application>()
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener(
            { runCatching { mediaController = future.get() } },
            ContextCompat.getMainExecutor(app)
        )
    }

    override fun onCleared() {
        mediaController?.release()
        mediaController = null
        super.onCleared()
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme", mode.name).apply()
    }

    fun login() {
        _isLoggedIn.value = true
    }

    fun completeOnboarding() {
        _onboarded.value = true
        prefs.edit().putBoolean("onboarded", true).apply()
        // Stop the demo clip if it is still playing
        if (_current.value?.id == DEMO_TRACK_ID) {
            player.stop()
            _current.value = null
        }
        // One-time, skippable "sign in to sync" offer after onboarding.
        if (!auth.isSignedIn && !prefs.getBoolean("asked_signin", false)) {
            _showAccount.value = true
        }
    }

    /** Bundled 12s clip for the onboarding 8D demo. */
    fun playDemo() {
        val app = getApplication<Application>()
        val track = Track(
            id = DEMO_TRACK_ID,
            title = "Orbit 8D Demo",
            artist = "Orbit",
            uri = "android.resource://${app.packageName}/raw/orbit_demo",
            artworkUri = null,
            durationMs = 12_500L
        )
        play(track, listOf(track))
    }

    private fun loadTabOrder(): List<AppTab> {
        val saved = prefs.getString("tab_order", null)
            ?.split(",")
            ?.mapNotNull { name -> runCatching { AppTab.valueOf(name) }.getOrNull() }
        return if (saved != null &&
            saved.size == AppTab.entries.size &&
            saved.toSet().size == saved.size
        ) saved else AppTab.entries.toList()
    }

    fun setTabOrder(order: List<AppTab>) {
        if (order.toSet() != AppTab.entries.toSet()) return
        _tabOrder.value = order
        prefs.edit().putString("tab_order", order.joinToString(",") { it.name }).apply()
    }

    fun loadLibrary() {
        viewModelScope.launch {
            _tracks.value = withContext(Dispatchers.IO) {
                LocalAudioRepository.load(getApplication())
            }
        }
    }

    fun play(track: Track, list: List<Track> = _tracks.value) {
        val queue = if (list.any { it.uri == track.uri }) list else listOf(track)
        _queue.value = queue
        val items = queue.map { t ->
            MediaItem.Builder()
                .setUri(t.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(t.title)
                        .setArtist(t.artist)
                        .setArtworkUri(t.artworkUri?.toUri())
                        .build()
                )
                .build()
        }
        val startIndex = queue.indexOfFirst { it.uri == track.uri }.coerceAtLeast(0)
        player.setMediaItems(items, startIndex, 0L)
        player.prepare()
        player.play()
        _queueIndex.value = startIndex
        _current.value = track
        _lyrics.value = null
        if (track.id != DEMO_TRACK_ID) addRecent(track)
        ensurePlaybackService()
    }

    fun togglePlayPause() {
        when {
            player.isPlaying -> player.pause()
            // After a song (or queue) finishes, restart from the beginning.
            player.playbackState == Player.STATE_ENDED -> {
                player.seekTo(0L)
                player.play()
                ensurePlaybackService()
            }
            else -> {
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
                ensurePlaybackService()
            }
        }
    }

    /** Keeps the media-notification service alive whenever playback starts. */
    private fun ensurePlaybackService() {
        val ctx = getApplication<Application>()
        runCatching { ctx.startService(Intent(ctx, PlaybackService::class.java)) }
    }

    fun seekTo(ms: Long) = player.seekTo(ms)
    fun next() = player.seekToNextMediaItem()
    fun previous() = player.seekToPreviousMediaItem()

    fun playQueueItem(index: Int) {
        if (index in 0 until player.mediaItemCount) {
            player.seekTo(index, 0L)
            player.play()
        }
    }

    fun toggleShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
        _shuffleOn.value = player.shuffleModeEnabled
    }

    fun cycleRepeat() {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        _repeatMode.value = player.repeatMode
    }

    fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _playbackSpeed.value = speed
    }

    /** Pass null to cancel the timer. */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        sleepJob = null
        if (minutes == null) {
            _sleepRemainingMs.value = null
            return
        }
        sleepJob = viewModelScope.launch {
            var remaining = minutes * 60_000L
            while (remaining > 0) {
                _sleepRemainingMs.value = remaining
                delay(1000)
                remaining -= 1000
            }
            _sleepRemainingMs.value = null
            // Gentle 10-second fade-out instead of an abrupt stop (report §8)
            val startVol = player.volume
            for (i in 1..20) {
                player.volume = startVol * (1f - i / 20f)
                delay(500)
            }
            player.pause()
            player.volume = startVol
        }
    }

    fun setBass(db: Float) {
        _bassDb.value = db
        PlayerHolder.toneProcessor.bassDb = db
    }

    fun setTreble(db: Float) {
        _trebleDb.value = db
        PlayerHolder.toneProcessor.trebleDb = db
    }

    fun setReverse8d(on: Boolean) {
        _reverse8d.value = on
        PlayerHolder.processor.reverse = on
    }

    fun fetchLyrics() {
        val track = _current.value ?: return
        if (_lyrics.value != null || _lyricsLoading.value) return
        viewModelScope.launch {
            _lyricsLoading.value = true
            _lyrics.value = try {
                LyricsRepository.fetch(track.title, track.artist) ?: "Lyrics not found for this song."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Lyrics fetch failed", e)
                "Could not load lyrics. Check your connection."
            } finally {
                _lyricsLoading.value = false
            }
        }
    }

    private fun addRecent(track: Track) {
        val key = track.sourceUrl ?: track.uri
        // YouTube stream URLs expire, so store them without the URL —
        // replaying re-resolves from the source link.
        val toStore = if (track.fromYouTube) track.copy(uri = "") else track
        val updated = (listOf(toStore) + _recents.value.filterNot { (it.sourceUrl ?: it.uri) == key }).take(50)
        _recents.value = updated
        viewModelScope.launch(Dispatchers.IO) {
            RecentsRepository.save(getApplication(), updated)
        }
        schedulePush()
    }

    fun setEffectOn(on: Boolean) {
        _effectOn.value = on
        applyEffect()
    }

    fun setRotationSpeed(v: Float) {
        _rotationSpeed.value = v
        applyEffect()
    }

    fun setIntensity(v: Float) {
        _intensity.value = v
        applyEffect()
    }

    private fun applyEffect() {
        PlayerHolder.processor.effectEnabled = _effectOn.value
        PlayerHolder.processor.rotationSpeed = _rotationSpeed.value
        PlayerHolder.processor.intensity = _intensity.value
    }

    fun playFromYouTube(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _ytLoading.value = true
            _ytError.value = null
            try {
                val track = YouTubeResolver.resolve(url)
                play(track, listOf(track))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "YouTube resolve failed", e)
                val cause = e.cause?.message?.let { " — $it" } ?: ""
                _ytError.value = "${e.javaClass.simpleName}: ${e.message ?: "unknown error"}$cause"
            } finally {
                _ytLoading.value = false
            }
        }
    }

    /** Search YouTube's music catalog; results are resolved on tap via [playTrack]. */
    /** Discover feed: YouTube music trending + Audius trending + personal recommendations. */
    fun loadDiscover() {
        if (_ytTrending.value.isEmpty()) viewModelScope.launch(Dispatchers.IO) {
            runCatching { YouTubeResolver.trending() }
                .onSuccess { _ytTrending.value = it }
                .onFailure { Log.e(TAG, "YT trending failed", it) }
        }
        if (_audiusResults.value.isEmpty()) viewModelScope.launch(Dispatchers.IO) {
            runCatching { AudiusRepository.trending() }
                .onSuccess { _audiusResults.value = it }
                .onFailure { Log.e(TAG, "Audius trending failed", it) }
        }
        if (_recommendations.value.isEmpty()) viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                Recommender.recommend(_recents.value, _downloads.value) { YouTubeResolver.related(it) }
            }.onSuccess { if (it.isNotEmpty()) _recommendations.value = it }
                .onFailure { Log.e(TAG, "Recommendations failed", it) }
        }
    }

    /** YouTube playlist link -> list all its songs (one-click Download All). */
    fun loadYouTubePlaylist(url: String) {
        viewModelScope.launch {
            _ytLoading.value = true
            _ytError.value = null
            try {
                val (name, items) = withContext(Dispatchers.IO) {
                    YouTubeResolver.playlistItems(url)
                }
                _ytPlaylistName.value = name
                _ytResults.value = items
                if (items.isEmpty()) _ytError.value = "Playlist is empty or unavailable."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Playlist load failed", e)
                _ytError.value = "${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
            } finally {
                _ytLoading.value = false
            }
        }
    }

    /** One click: queue every song — the worker downloads them one by one. */
    fun downloadAll(tracks: List<Track>) {
        tracks.forEach { download(it) }
    }

    /**
     * Merged search: YouTube + Audius in parallel, one result list
     * (source-agnostic search — redesign report section 5.2).
     */
    fun searchYouTube(query: String) {
        if (query.isBlank()) return
        _ytPlaylistName.value = null
        viewModelScope.launch {
            _ytLoading.value = true
            _ytError.value = null
            try {
                coroutineScope {
                    val yt = async(Dispatchers.IO) {
                        runCatching { YouTubeResolver.search(query) }
                            .onFailure { Log.e(TAG, "YouTube search failed", it) }
                            .getOrElse { emptyList() }
                    }
                    val audius = async(Dispatchers.IO) {
                        runCatching { AudiusRepository.search(query) }
                            .onFailure { Log.e(TAG, "Audius search failed", it) }
                            .getOrElse { emptyList() }
                    }
                    val merged = yt.await() + audius.await()
                    _ytResults.value = merged
                    if (merged.isEmpty()) _ytError.value = "No results found."
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Search failed", e)
                _ytError.value = "${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
            } finally {
                _ytLoading.value = false
            }
        }
    }

    /** Plays any track: YouTube tracks are resolved first, others play directly. */
    fun playTrack(track: Track, list: List<Track> = listOf(track)) {
        if (track.fromYouTube && track.uri.isBlank()) {
            playFromYouTube(track.sourceUrl ?: return)
        } else {
            play(track, list.filter { it.uri.isNotBlank() })
        }
    }

    fun searchAudius(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _audiusLoading.value = true
            _audiusError.value = null
            try {
                _audiusResults.value = AudiusRepository.search(query)
                if (_audiusResults.value.isEmpty()) _audiusError.value = "No results found."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Audius search failed", e)
                _audiusError.value = "${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
            } finally {
                _audiusLoading.value = false
            }
        }
    }

    // ---- Playlists ----

    fun loadPlaylists() {
        viewModelScope.launch(Dispatchers.IO) {
            _playlists.value = PlaylistRepository.load(getApplication())
        }
    }

    private fun savePlaylists(updated: List<Playlist>) {
        _playlists.value = updated
        viewModelScope.launch(Dispatchers.IO) {
            PlaylistRepository.save(getApplication(), updated)
        }
        schedulePush()
    }

    fun createPlaylist(name: String) {
        val n = name.trim()
        if (n.isBlank() || _playlists.value.any { it.name == n }) return
        savePlaylists(_playlists.value + Playlist(n, emptyList()))
        toast("Playlist \"$n\" created")
    }

    fun deletePlaylist(name: String) {
        savePlaylists(_playlists.value.filterNot { it.name == name })
    }

    fun addToPlaylist(name: String, track: Track) = addAllToPlaylist(name, listOf(track))

    /**
     * Adds several tracks at once, skipping ones already in the playlist —
     * and TELLS the user what actually happened (modern feedback, not silence).
     */
    fun addAllToPlaylist(name: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        var added = 0
        savePlaylists(_playlists.value.map { p ->
            if (p.name != name) p
            else {
                val existing = p.tracks.map { it.sourceUrl ?: it.uri }.toSet()
                val fresh = tracks.filter { (it.sourceUrl ?: it.uri) !in existing }
                added = fresh.size
                p.copy(tracks = p.tracks + fresh)
            }
        })
        toast(when {
            added == 0 -> "Already in \"$name\""
            added == 1 && tracks.size == 1 ->
                "\"${tracks.first().title}\" added to \"$name\""
            added == tracks.size -> "$added songs added to \"$name\""
            else -> "$added added to \"$name\" (${tracks.size - added} were already there)"
        })
    }

    fun removeFromPlaylist(name: String, track: Track) {
        val key = track.sourceUrl ?: track.uri
        savePlaylists(_playlists.value.map { p ->
            if (p.name == name) p.copy(tracks = p.tracks.filterNot { (it.sourceUrl ?: it.uri) == key })
            else p
        })
    }

    // ---- Offline downloads ----

    fun loadDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            _downloads.value = DownloadRepository.load(getApplication())
        }
    }

    fun isDownloaded(track: Track): Boolean {
        val key = track.matchKey()
        return _downloads.value.any { it.matchKey() == key }
    }

    fun download(track: Track) {
        if (isDownloaded(track) ||
            _downloadProgress.value.containsKey(track.id) ||
            track.id in _queuedDownloads.value
        ) return
        cancelledDownloads.remove(track.id)
        _queuedDownloads.value = _queuedDownloads.value + track.id
        downloadQueue.trySend(track)
    }

    fun cancelDownload(track: Track) {
        cancelledDownloads.add(track.id)
        _queuedDownloads.value = _queuedDownloads.value - track.id
    }

    fun cancelAllDownloads() {
        cancelledDownloads.addAll(_queuedDownloads.value)
        cancelledDownloads.addAll(_downloadProgress.value.keys)
        _queuedDownloads.value = emptySet()
    }

    private suspend fun downloadNow(track: Track) {
        val key = track.sourceUrl ?: track.uri
        run {
            _downloadProgress.value = _downloadProgress.value + (track.id to 0f)
            try {
                suspend fun attempt(prepared: Track): Track = withContext(Dispatchers.IO) {
                    DownloadRepository.download(
                        getApplication(),
                        prepared,
                        isCancelled = { track.id in cancelledDownloads }
                    ) { p ->
                        _downloadProgress.value = _downloadProgress.value + (track.id to p)
                    }
                }
                // YouTube tracks need a fresh, storage-optimized stream URL first.
                val prepared = if (track.fromYouTube) {
                    val resolved = YouTubeResolver.resolve(
                        track.sourceUrl ?: error("Missing video link"),
                        compact = true
                    )
                    resolved.copy(id = track.id) // keeps canonical sourceUrl
                } else {
                    track.copy(sourceUrl = key)
                }
                val local = try {
                    attempt(prepared)
                } catch (e: com.vibecaster.data.DownloadCancelledException) {
                    throw e
                } catch (e: Throwable) {
                    // YouTube URLs go stale / get blocked per-format. One retry
                    // with a FRESH resolve and a different stream usually works.
                    if (track.fromYouTube && track.sourceUrl != null) {
                        Log.w(TAG, "Download attempt 1 failed (${e.message}), retrying with fresh stream")
                        _downloadProgress.value = _downloadProgress.value + (track.id to 0f)
                        val fresh = YouTubeResolver.resolve(track.sourceUrl!!, compact = false)
                        attempt(fresh.copy(id = track.id))
                    } else throw e
                }
                _downloads.value =
                    _downloads.value.filterNot { it.matchKey() == local.matchKey() } + local
                toast("Downloaded: ${track.title}")
            } catch (e: com.vibecaster.data.DownloadCancelledException) {
                // User stopped it: no toast, just clean up.
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Download failed", e)
                toast("Download failed: ${e.message ?: "unknown error"}")
            } finally {
                _downloadProgress.value = _downloadProgress.value - track.id
                cancelledDownloads.remove(track.id)
            }
        }
    }

    fun deleteDownload(track: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            DownloadRepository.delete(getApplication(), track)
            _downloads.value = _downloads.value.filterNot { it.uri == track.uri }
        }
    }

    /** Copies a downloaded song into the public Music folder (Music/VibeCaster). */
    fun exportDownload(track: Track) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { DownloadRepository.exportToMusic(getApplication(), track) }
                    .onFailure { Log.e(TAG, "Export failed", it) }
                    .getOrDefault(false)
            }
            toast(
                if (ok) "Exported to Music/VibeCaster: ${track.title}"
                else "Export failed"
            )
        }
    }

    // ---- Local file deletion (system confirmation required on Android 11+) ----

    fun requestDeleteTrack(track: Track) = requestDeleteTracks(listOf(track))

    /** One system confirmation dialog covers all given files. */
    fun requestDeleteTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        try {
            val resolver = getApplication<Application>().contentResolver
            val uris = tracks.map { it.uri.toUri() }
            val pending = MediaStore.createDeleteRequest(resolver, uris)
            _deleteRequest.value = pending.intentSender
        } catch (e: Exception) {
            Log.e(TAG, "Delete request failed", e)
        }
    }

    fun clearDeleteRequest() {
        _deleteRequest.value = null
    }

    fun checkForUpdates(manual: Boolean) {
        viewModelScope.launch {
            if (manual) toast("Checking for updates...")
            try {
                val info = UpdateRepository.check(appVersion)
                _updateInfo.value = info
                if (info != null) {
                    _showUpdateDialog.value = true
                } else if (manual) {
                    toast("You're on the latest version ($appVersion)")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Update check failed", e)
                if (manual) toast("Update check failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }

    private fun toast(message: String)  {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "VibeCaster"
    }
}
