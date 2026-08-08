package com.vibecaster.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.vibecaster.audio.EightDAudioProcessor
import com.vibecaster.audio.ToneAudioProcessor
import com.vibecaster.youtube.YouTubeResolver

/**
 * App-wide single ExoPlayer instance with the 8D processor wired into its audio sink.
 * Shared by the UI (MainViewModel) and the background PlaybackService.
 */
@UnstableApi
object PlayerHolder {

    val processor = EightDAudioProcessor()
    val toneProcessor = ToneAudioProcessor()

    @Volatile
    private var player: ExoPlayer? = null

    fun get(context: Context): ExoPlayer {
        player?.let { return it }
        synchronized(this) {
            player?.let { return it }
            val appContext = context.applicationContext

            val renderersFactory = object : DefaultRenderersFactory(appContext) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink {
                    return DefaultAudioSink.Builder(context)
                        .setAudioProcessors(arrayOf(toneProcessor, processor))
                        .build()
                }
            }

            // Use the same User-Agent as the YouTube extractor so stream URLs
            // resolved by NewPipe do not get rejected (403) during playback.
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(YouTubeResolver.USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
            val dataSourceFactory = DefaultDataSource.Factory(appContext, httpFactory)

            val p = ExoPlayer.Builder(appContext, renderersFactory)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(appContext).setDataSourceFactory(dataSourceFactory)
                )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ true
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
            player = p
            return p
        }
    }
}
