package com.pego.music.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.pego.music.data.extractor.YouTubeExtractorRepository
import com.pego.music.domain.model.TrackItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RepeatMode {
    OFF,
    ALL,
    ONE
}

data class PlaybackUiState(
    val currentTrack: TrackItem? = null,
    val queue: List<TrackItem> = emptyList(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val errorMessage: String? = null
)

class PlayerController(
    private val context: Context,
    private val extractorRepo: YouTubeExtractorRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    var onTrackTransition: ((TrackItem) -> Unit)? = null

    private var mediaController: MediaController? = null
    private var currentQueue: List<TrackItem> = emptyList()
    private var trackById: Map<String, TrackItem> = emptyMap()
    private var playJob: Job? = null
    private var prefetchJob: Job? = null

    init {
        connectController()
        startProgressTicker()
    }

    private fun connectController() {
        scope.launch {
            runCatching {
                val token = SessionToken(
                    context,
                    ComponentName(context, PegoPlaybackService::class.java)
                )
                val controller = MediaController.Builder(context, token).buildAsync().await()
                mediaController = controller

                // Sync if service was already playing in background
                _uiState.update {
                    it.copy(
                        isPlaying = controller.isPlaying,
                        shuffleEnabled = controller.shuffleModeEnabled,
                        repeatMode = mapExoRepeatMode(controller.repeatMode),
                        durationMs = controller.duration.coerceAtLeast(it.durationMs)
                    )
                }

                controller.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _uiState.update { it.copy(isPlaying = isPlaying) }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        _uiState.update {
                            it.copy(
                                isBuffering = playbackState == Player.STATE_BUFFERING,
                                durationMs = controller.duration.coerceAtLeast(0L)
                            )
                        }
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        val id = mediaItem?.mediaId ?: return
                        val matchedTrack = trackById[id] ?: return
                        _uiState.update {
                            it.copy(
                                currentTrack = matchedTrack,
                                positionMs = 0L,
                                durationMs = controller.duration.takeIf { d -> d > 0L }
                                    ?: (matchedTrack.durationSeconds * 1000L),
                                errorMessage = null
                            )
                        }
                        onTrackTransition?.invoke(matchedTrack)
                        prefetchUpcomingTracks(controller.currentMediaItemIndex)
                    }

                    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                        _uiState.update { it.copy(shuffleEnabled = shuffleModeEnabled) }
                    }

                    override fun onRepeatModeChanged(repeatMode: Int) {
                        _uiState.update { it.copy(repeatMode = mapExoRepeatMode(repeatMode)) }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        _uiState.update {
                            it.copy(
                                isBuffering = false,
                                isPlaying = false,
                                errorMessage = error.localizedMessage ?: "Error al reproducir el audio"
                            )
                        }
                    }
                })
            }
        }
    }

    private fun startProgressTicker() {
        scope.launch {
            while (true) {
                val controller = mediaController
                if (controller != null && controller.isPlaying) {
                    val pos = controller.currentPosition.coerceAtLeast(0L)
                    val dur = controller.duration.coerceAtLeast(_uiState.value.durationMs)
                    _uiState.update {
                        if (it.positionMs == pos && it.durationMs == dur) it
                        else it.copy(positionMs = pos, durationMs = dur)
                    }
                }
                delay(500L)
            }
        }
    }

    fun playTrack(track: TrackItem, queue: List<TrackItem> = listOf(track)) {
        val effectiveQueue = if (queue.any { it.id == track.id }) queue else listOf(track) + queue
        currentQueue = effectiveQueue
        trackById = effectiveQueue.associateBy { it.id }
        val startIndex = effectiveQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

        playJob?.cancel()
        prefetchJob?.cancel()

        playJob = scope.launch {
            _uiState.update {
                it.copy(
                    currentTrack = track,
                    queue = effectiveQueue,
                    isBuffering = true,
                    positionMs = 0L,
                    durationMs = track.durationSeconds * 1000L,
                    errorMessage = null
                )
            }

            try {
                val mediaItems = withContext(Dispatchers.IO) {
                    effectiveQueue.map { item -> buildMediaItemForTrack(item) }
                }

                val controller = mediaController ?: return@launch
                controller.setMediaItems(mediaItems, startIndex, 0L)
                controller.prepare()
                controller.play()

                prefetchUpcomingTracks(startIndex)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBuffering = false,
                        errorMessage = e.message ?: "No se pudo reproducir la canción"
                    )
                }
            }
        }
    }

    fun toggleShuffle() {
        val controller = mediaController ?: return
        val next = !controller.shuffleModeEnabled
        controller.shuffleModeEnabled = next
        _uiState.update { it.copy(shuffleEnabled = next) }
    }

    fun cycleRepeatMode() {
        val controller = mediaController ?: return
        val nextExo = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        controller.repeatMode = nextExo
        _uiState.update { it.copy(repeatMode = mapExoRepeatMode(nextExo)) }
    }

    fun playNext() {
        val controller = mediaController ?: return
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem()
        } else if (controller.repeatMode == Player.REPEAT_MODE_ALL && controller.mediaItemCount > 0) {
            controller.seekTo(0, 0L)
        }
    }

    fun playPrevious() {
        val controller = mediaController ?: return
        controller.seekToPrevious()
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
        _uiState.update { it.copy(positionMs = positionMs) }
    }

    fun refreshTrackMetadata(updatedTrack: TrackItem, replayAudio: Boolean = false) {
        currentQueue = currentQueue.map { if (it.id == updatedTrack.id) updatedTrack else it }
        trackById = currentQueue.associateBy { it.id }

        val controller = mediaController
        val idx = currentQueue.indexOfFirst { it.id == updatedTrack.id }
        if (controller != null && idx >= 0 && idx < controller.mediaItemCount) {
            scope.launch {
                val updatedItem = withContext(Dispatchers.IO) { buildMediaItemForTrack(updatedTrack) }
                if (replayAudio && _uiState.value.currentTrack?.id == updatedTrack.id) {
                    controller.replaceMediaItem(idx, updatedItem)
                    controller.seekTo(idx, 0L)
                    controller.play()
                } else {
                    controller.replaceMediaItem(idx, updatedItem)
                }
            }
        }

        if (_uiState.value.currentTrack?.id == updatedTrack.id) {
            _uiState.update {
                it.copy(
                    currentTrack = updatedTrack,
                    queue = currentQueue,
                    durationMs = if (replayAudio) updatedTrack.durationSeconds * 1000L else it.durationMs
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun prefetchUpcomingTracks(currentIndex: Int) {
        if (currentQueue.isEmpty()) return
        val upcoming = listOf(currentIndex + 1, currentIndex + 2)
            .filter { it in currentQueue.indices }
            .map { currentQueue[it] }
            .filter { it.localUri.isNullOrBlank() && it.localFilePath.isNullOrBlank() }

        if (upcoming.isEmpty()) return

        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            upcoming.forEach { nextTrack ->
                if (nextTrack.sourceUrl.isNotBlank()) {
                    extractorRepo.prefetchStreamUrl(nextTrack.sourceUrl)
                } else {
                    runCatching {
                        val found = extractorRepo.searchMusic(
                            "${nextTrack.artist} ${nextTrack.cleanTitle}",
                            originalsOnly = true
                        ).firstOrNull()
                        if (found != null) {
                            extractorRepo.prefetchStreamUrl(found.sourceUrl)
                        }
                    }
                }
            }
        }
    }

    private fun buildMediaItemForTrack(track: TrackItem): MediaItem {
        val uri = resolveMediaUri(track)
        val metadata = MediaMetadata.Builder()
            .setTitle(track.cleanTitle.ifBlank { track.title })
            .setArtist(track.artist)
            .setAlbumTitle(track.album.ifBlank { null })
            .setArtworkUri(track.bestCoverUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .build()

        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun resolveMediaUri(track: TrackItem): Uri {
        track.localUri?.takeIf { it.isNotBlank() }?.let { uriStr ->
            val parsed = Uri.parse(uriStr)
            if (isLocalUriReadable(parsed)) return parsed
        }
        track.localFilePath?.takeIf { it.isNotBlank() }?.let { path ->
            val file = java.io.File(path)
            if (file.exists() && file.length() > 0L) return Uri.fromFile(file)
        }
        return Uri.Builder()
            .scheme(PegoPlaybackService.SCHEME_PEGO)
            .authority("resolve")
            .appendQueryParameter("sourceUrl", track.sourceUrl)
            .appendQueryParameter("artist", track.artist)
            .appendQueryParameter("title", track.cleanTitle.ifBlank { track.title })
            .build()
    }

    private fun isLocalUriReadable(uri: Uri): Boolean {
        return when (uri.scheme) {
            "content" -> runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
            "file" -> uri.path?.let { java.io.File(it).let { f -> f.exists() && f.length() > 0L } } ?: false
            else -> false
        }
    }

    private fun mapExoRepeatMode(exoMode: Int): RepeatMode {
        return when (exoMode) {
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            else -> RepeatMode.OFF
        }
    }
}
