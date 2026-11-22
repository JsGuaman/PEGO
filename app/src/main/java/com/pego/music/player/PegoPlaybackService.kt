package com.pego.music.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.pego.music.MainActivity
import com.pego.music.PegoApp
import com.pego.music.core.network.OkHttpDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class PegoPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val extractorRepo = (application as PegoApp).container.extractorRepo

        val httpFactory = OkHttpDataSource.Factory(OkHttpDownloader.getInstance().client)
            .setUserAgent(OkHttpDownloader.USER_AGENT)

        val cachedHttpFactory = CacheDataSource.Factory()
            .setCache(PegoMediaCache.getInstance(this))
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val baseDataSourceFactory = DefaultDataSource.Factory(this, cachedHttpFactory)

        // Resolves pego://resolve?... queue URIs on-the-fly so full playlists live natively inside
        // ExoPlayer/MediaSession and work from the notification, lock screen, and Bluetooth controls.
        val resolvingFactory = ResolvingDataSource.Factory(baseDataSourceFactory) { dataSpec: DataSpec ->
            val uri = dataSpec.uri
            if (uri.scheme == SCHEME_PEGO) {
                val sourceUrl = uri.getQueryParameter("sourceUrl").orEmpty()
                val artist = uri.getQueryParameter("artist").orEmpty()
                val title = uri.getQueryParameter("title").orEmpty()
                val resolvedHttpUrl = runBlocking(Dispatchers.IO) {
                    val targetUrl = sourceUrl.ifBlank {
                        extractorRepo.searchMusic("$artist $title", originalsOnly = true)
                            .firstOrNull()
                            ?.sourceUrl
                            ?: throw IllegalStateException("No se encontró audio para $title")
                    }
                    extractorRepo.resolveFastStreamingAudioUrl(targetUrl)
                }
                dataSpec.withUri(Uri.parse(resolvedHttpUrl))
            } else {
                dataSpec
            }
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 35_000,
                /* bufferForPlaybackMs = */ 350,
                /* bufferForPlaybackAfterRebufferMs = */ 1_200
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    companion object {
        const val SCHEME_PEGO = "pego"
    }
}
