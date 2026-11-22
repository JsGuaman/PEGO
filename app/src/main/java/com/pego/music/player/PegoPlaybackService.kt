package com.pego.music.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.pego.music.MainActivity
import com.pego.music.PegoApp
import com.pego.music.core.network.OkHttpDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class PegoPlaybackService : MediaSessionService(), MediaSession.Callback {

    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build())

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

        val exoPlayer = ExoPlayer.Builder(this)
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

        // Ensure Android SystemUI / lock screen / Bluetooth always receive queue timeline
        // and enabled Next/Previous transport controls across the full playlist.
        val sessionPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                val base = super.getAvailableCommands()
                return if (mediaItemCount > 1) {
                    base.buildUpon()
                        .add(COMMAND_SEEK_TO_NEXT)
                        .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .add(COMMAND_SEEK_TO_PREVIOUS)
                        .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .add(COMMAND_GET_TIMELINE)
                        .build()
                } else {
                    base
                }
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return if (
                    mediaItemCount > 1 &&
                    command in setOf(
                        COMMAND_SEEK_TO_NEXT,
                        COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        COMMAND_SEEK_TO_PREVIOUS,
                        COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        COMMAND_GET_TIMELINE
                    )
                ) {
                    true
                } else {
                    super.isCommandAvailable(command)
                }
            }

            override fun seekToNext() {
                if (hasNextMediaItem()) {
                    super.seekToNext()
                } else if (mediaItemCount > 1) {
                    seekTo(0, 0L)
                } else {
                    super.seekToNext()
                }
            }

            override fun seekToNextMediaItem() {
                seekToNext()
            }

            override fun seekToPrevious() {
                if (currentPosition > maxSeekToPreviousPosition) {
                    seekTo(0L)
                } else if (hasPreviousMediaItem()) {
                    super.seekToPrevious()
                } else if (mediaItemCount > 1) {
                    seekTo(mediaItemCount - 1, 0L)
                } else {
                    super.seekToPrevious()
                }
            }

            override fun seekToPreviousMediaItem() {
                if (hasPreviousMediaItem()) {
                    super.seekToPreviousMediaItem()
                } else if (mediaItemCount > 1) {
                    seekTo(mediaItemCount - 1, 0L)
                } else {
                    super.seekToPreviousMediaItem()
                }
            }
        }

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(sessionActivityIntent)
            .setCallback(this)
            .build()
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        val resolved = mediaItems.map { item ->
            val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
            if (uri != null && item.localConfiguration?.uri != uri) {
                item.buildUpon().setUri(uri).build()
            } else {
                item
            }
        }
        return Futures.immediateFuture(resolved)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (
            player == null ||
            !player.playWhenReady ||
            player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
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
