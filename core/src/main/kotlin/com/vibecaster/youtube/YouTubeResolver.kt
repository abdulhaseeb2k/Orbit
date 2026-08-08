package com.vibecaster.youtube

import com.vibecaster.data.Track
import com.vibecaster.data.matchKey
import com.vibecaster.data.youTubeVideoId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Resolves a YouTube video URL into a direct audio stream using NewPipe Extractor. */
object YouTubeResolver {

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    @Volatile
    private var initialized = false

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // Content country: trending etc. follow Pakistan
            NewPipe.init(
                OkHttpDownloader(),
                org.schabi.newpipe.extractor.localization.Localization.DEFAULT,
                org.schabi.newpipe.extractor.localization.ContentCountry("PK")
            )
            initialized = true
        }
    }

    /**
     * @param compact When true, picks a storage-optimized audio stream
     * (~128-160 kbps AAC — transparent quality at roughly half the size of
     * the top bitrate). When false, picks the highest available bitrate.
     */
    suspend fun resolve(url: String, compact: Boolean = false): Track = withContext(Dispatchers.IO) {
        ensureInit()
        // Music-search results come as music.youtube.com links; the regular
        // watch URL extracts far more reliably.
        val normalized = url.trim()
            .replace("music.youtube.com", "www.youtube.com")
            .substringBefore("&list=") // playlist context can break extraction

        val info = StreamInfo.getInfo(ServiceList.YouTube, normalized)

        val candidates = info.audioStreams.filter { it.isUrl && !it.content.isNullOrBlank() }

        val audio = if (compact) {
            // Storage-optimized pick: best stream within the 96-170 kbps
            // sweet spot, else whatever is closest to 140 kbps.
            candidates.filter { it.averageBitrate in 96..170 }.maxByOrNull { it.averageBitrate }
                ?: candidates.minByOrNull { abs(it.averageBitrate - 140) }
        } else {
            candidates.maxByOrNull { it.averageBitrate }
        }

        // Fallback: muxed (video+audio) progressive stream — ExoPlayer will
        // just play its audio track. Lowest resolution saves bandwidth.
        val muxed = if (audio == null) {
            info.videoStreams
                .filter { it.isUrl && !it.content.isNullOrBlank() }
                .minByOrNull { it.resolution?.filter(Char::isDigit)?.toIntOrNull() ?: Int.MAX_VALUE }
        } else null

        val streamUrl = audio?.content ?: muxed?.content
            ?: throw IllegalStateException(
                "No playable stream for this video " +
                    "(audio: ${info.audioStreams.size}, video: ${info.videoStreams.size})"
            )

        // Canonical watch URL so the same video always matches the same
        // download/playlist entry, no matter which link form was pasted.
        val canonical = youTubeVideoId(normalized)
            ?.let { "https://www.youtube.com/watch?v=$it" } ?: normalized

        Track(
            id = canonical.hashCode().toLong(),
            title = info.name ?: "YouTube audio",
            artist = info.uploaderName ?: "YouTube",
            uri = streamUrl,
            artworkUri = info.thumbnails.maxByOrNull { it.width }?.url,
            durationMs = info.duration * 1000L,
            fromYouTube = true,
            sourceUrl = canonical
        )
    }

    /**
     * Search YouTube for songs. Returned tracks have an empty [Track.uri];
     * resolve [Track.sourceUrl] before playing.
     */
    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        ensureInit()
        val service = ServiceList.YouTube
        var firstError: Throwable? = null

        // Music-catalog search: best metadata, but misses many videos.
        val music = try {
            SearchInfo.getInfo(
                service,
                service.searchQHFactory.fromQuery(
                    query,
                    listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
                    ""
                )
            ).relatedItems.filterIsInstance<StreamInfoItem>()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            firstError = e
            emptyList()
        }

        // Regular video search catches songs missing from the music catalog.
        val videos = try {
            SearchInfo.getInfo(service, service.searchQHFactory.fromQuery(query))
                .relatedItems.filterIsInstance<StreamInfoItem>()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            firstError = e
            emptyList()
        }

        if (music.isEmpty() && videos.isEmpty() && firstError != null) throw firstError

        val seen = HashSet<String>()
        (music + videos).mapNotNull { item ->
            val url = item.url ?: return@mapNotNull null
            val key = youTubeVideoId(url) ?: url
            if (!seen.add(key)) return@mapNotNull null
            val canonical = youTubeVideoId(url)
                ?.let { "https://www.youtube.com/watch?v=$it" } ?: url
            Track(
                id = ("yt:" + canonical).hashCode().toLong(),
                title = item.name ?: "Unknown",
                artist = item.uploaderName ?: "YouTube",
                uri = "",
                artworkUri = item.thumbnails.maxByOrNull { it.width }?.url,
                durationMs = item.duration * 1000L,
                fromYouTube = true,
                sourceUrl = canonical
            )
        }
    }

    private fun itemToTrack(item: StreamInfoItem): Track? {
        val url = item.url ?: return null
        val canonical = youTubeVideoId(url)?.let { "https://www.youtube.com/watch?v=$it" } ?: url
        return Track(
            id = ("yt:" + canonical).hashCode().toLong(),
            title = item.name ?: "Unknown",
            artist = item.uploaderName ?: "YouTube",
            uri = "",
            artworkUri = item.thumbnails.maxByOrNull { it.width }?.url,
            durationMs = item.duration * 1000L,
            fromYouTube = true,
            sourceUrl = canonical
        )
    }

    /**
     * YouTube music trending. YouTube Charts is not available in every
     * country (Pakistan included), hence the fallback chain:
     * PK -> IN -> US charts -> general Trending (filtered). ContentCountry is
     * switched temporarily and then restored to PK.
     */
    suspend fun trending(): List<Track> = withContext(Dispatchers.IO) {
        ensureInit()
        val service = ServiceList.YouTube
        val kioskList = service.kioskList

        fun fetch(kioskId: String): List<StreamInfoItem> {
            val url = kioskList.getListLinkHandlerFactoryByType(kioskId).fromId(kioskId).url
            return KioskInfo.getInfo(service, url)
                .relatedItems.filterIsInstance<StreamInfoItem>()
        }

        val items: List<StreamInfoItem> = synchronized(this) {
            var result: List<StreamInfoItem> = emptyList()
            if ("trending_music" in kioskList.availableKiosks) {
                for (country in listOf("PK", "IN", "US")) {
                    try {
                        NewPipe.setupLocalization(
                            org.schabi.newpipe.extractor.localization.Localization.DEFAULT,
                            org.schabi.newpipe.extractor.localization.ContentCountry(country)
                        )
                        result = fetch("trending_music")
                        if (result.isNotEmpty()) break
                    } catch (_: Exception) { /* next country */ }
                }
                // restore PK context for subsequent search/related calls
                NewPipe.setupLocalization(
                    org.schabi.newpipe.extractor.localization.Localization.DEFAULT,
                    org.schabi.newpipe.extractor.localization.ContentCountry("PK")
                )
            }
            if (result.isEmpty()) {
                result = runCatching { fetch("Trending") }.getOrElse { emptyList() }
            }
            result
        }

        val seen = HashSet<String>()
        items.mapNotNull { itemToTrack(it) }
            // no live streams / marathons / podcasts — only song-sized videos
            .filter { it.durationMs in 60_000..900_000 }
            .filter { seen.add(it.matchKey()) }
    }

    /**
     * YouTube's own "related videos" — a free recommendation signal.
     * (This is the same data shown in the sidebar of a YouTube watch page.)
     */
    suspend fun related(videoUrl: String): List<Track> = withContext(Dispatchers.IO) {
        ensureInit()
        val normalized = videoUrl.trim()
            .replace("music.youtube.com", "www.youtube.com")
            .substringBefore("&list=")
        val info = StreamInfo.getInfo(ServiceList.YouTube, normalized)
        val seen = HashSet<String>()
        info.relatedItems.filterIsInstance<StreamInfoItem>()
            .mapNotNull { itemToTrack(it) }
            .filter { seen.add(it.matchKey()) }
    }

    /** True if the URL points at a YouTube playlist. */
    fun isPlaylistUrl(url: String): Boolean =
        (url.contains("youtube.com") || url.contains("youtu.be")) && url.contains("list=")

    /** YouTube playlist -> (playlist name, tracks). First page (~100 items). */
    suspend fun playlistItems(url: String): Pair<String, List<Track>> = withContext(Dispatchers.IO) {
        ensureInit()
        val listId = Regex("[?&]list=([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("No playlist id in URL")
        val canonical = "https://www.youtube.com/playlist?list=$listId"
        val info = org.schabi.newpipe.extractor.playlist.PlaylistInfo.getInfo(ServiceList.YouTube, canonical)
        val seen = HashSet<String>()
        val tracks = info.relatedItems.filterIsInstance<StreamInfoItem>()
            .mapNotNull { itemToTrack(it) }
            .filter { seen.add(it.matchKey()) }
        (info.name ?: "YouTube playlist") to tracks
    }

    private class OkHttpDownloader : Downloader() {

        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        override fun execute(request: Request): Response {
            val dataToSend = request.dataToSend()
            val body = dataToSend?.toRequestBody(null, 0, dataToSend.size)

            val builder = okhttp3.Request.Builder()
                .method(request.httpMethod(), body)
                .url(request.url())
                .addHeader("User-Agent", USER_AGENT)

            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }

            val response = client.newCall(builder.build()).execute()
            if (response.code == 429) {
                response.close()
                throw ReCaptchaException("reCaptcha challenge requested", request.url())
            }
            val responseBody = response.body?.string()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                responseBody,
                response.request.url.toString()
            )
        }
    }
}
