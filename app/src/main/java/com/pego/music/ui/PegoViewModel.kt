package com.pego.music.ui

import android.media.MediaScannerConnection
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pego.music.AppContainer
import com.pego.music.core.image.CoverQualityResult
import com.pego.music.core.image.ImageQualityEvaluator
import com.pego.music.data.extractor.ParsedYouTubeInput
import com.pego.music.data.extractor.YouTubeUrlParser
import com.pego.music.data.local.PlaylistEntity
import com.pego.music.data.local.PlaylistTrackCrossRef
import com.pego.music.data.local.TrackEntity
import com.pego.music.data.metadata.CoverCandidate
import com.pego.music.data.metadata.TitleCleaner
import com.pego.music.domain.model.DownloadStatus
import com.pego.music.domain.model.LocalPlaylist
import com.pego.music.domain.model.LyricsResult
import com.pego.music.domain.model.PlaylistPreview
import com.pego.music.domain.model.TrackItem
import com.pego.music.player.PlaybackUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

data class SearchUiState(
    val query: String = "",
    val originalsOnly: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null,
    val results: List<TrackItem> = emptyList(),
    val playlistPreview: PlaylistPreview? = null,
    val selectedPlaylistIds: Set<String> = emptySet(),
    val infoMessage: String? = null
)

data class EvaluatedCoverCandidate(
    val candidate: CoverCandidate,
    val quality: CoverQualityResult?
)

data class CoverEditorUiState(
    val isSearching: Boolean = false,
    val isApplying: Boolean = false,
    val candidates: List<EvaluatedCoverCandidate> = emptyList(),
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

data class ArtistUiState(
    val artistName: String,
    val downloadedTracks: List<TrackItem> = emptyList(),
    val recommendedTracks: List<TrackItem> = emptyList(),
    val isLoadingRecommendations: Boolean = false
)

class PegoViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val dao = container.database.pegoDao()

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _lyricsState = MutableStateFlow<LyricsResult?>(null)
    val lyricsState: StateFlow<LyricsResult?> = _lyricsState.asStateFlow()

    private val _isLoadingLyrics = MutableStateFlow(false)
    val isLoadingLyrics: StateFlow<Boolean> = _isLoadingLyrics.asStateFlow()

    private val _coverEditorState = MutableStateFlow(CoverEditorUiState())
    val coverEditorState: StateFlow<CoverEditorUiState> = _coverEditorState.asStateFlow()

    private val _rawRecommendations = MutableStateFlow<List<TrackItem>>(emptyList())
    private val _isLoadingRecommendations = MutableStateFlow(false)
    val isLoadingRecommendations: StateFlow<Boolean> = _isLoadingRecommendations.asStateFlow()

    private val _selectedArtistName = MutableStateFlow<String?>(null)
    private val _rawArtistRecs = MutableStateFlow<List<TrackItem>>(emptyList())
    private val _isLoadingArtistRecs = MutableStateFlow(false)
    private val _resolvingTrackIds = MutableStateFlow<Set<String>>(emptySet())

    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    private var searchJob: Job? = null
    private var enrichJob: Job? = null
    private var recommendJob: Job? = null
    private var artistJob: Job? = null
    private var syncJob: Job? = null
    private var searchGeneration = 0L

    val playbackState: StateFlow<PlaybackUiState> = container.playerController.uiState

    val allSavedTracksMap: StateFlow<Map<String, TrackItem>> = dao.observeAllTracks()
        .map { list -> list.associate { it.id to it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val downloadedTracks: StateFlow<List<TrackItem>> = dao.observeDownloadedTracks()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeDownloads: StateFlow<List<TrackItem>> = dao.observeActiveAndFailedDownloads()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val localPlaylists: StateFlow<List<LocalPlaylist>> = dao.observePlaylistsWithTracks()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Syncs recommendation download status with Room (matching by ID or normalized artist+title)
     * plus in-flight YouTube resolution IDs so the progress spinner appears immediately.
     */
    val recommendations: StateFlow<List<TrackItem>> = combine(
        _rawRecommendations,
        allSavedTracksMap,
        _resolvingTrackIds
    ) { recs, savedMap, resolvingIds ->
        syncTrackListWithDownloads(recs, savedMap, resolvingIds)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artistState: StateFlow<ArtistUiState?> = combine(
        combine(_selectedArtistName, _rawArtistRecs, _isLoadingArtistRecs) { name, recs, loading ->
            Triple(name, recs, loading)
        },
        downloadedTracks,
        allSavedTracksMap,
        _resolvingTrackIds
    ) { (artistName, rawRecs, loadingRecs), downloaded, savedMap, resolvingIds ->
        if (artistName.isNullOrBlank()) return@combine null

        val downloadedOfArtist = downloaded.filter { track ->
            TitleCleaner.isExplicitArtistMatch(
                targetArtist = artistName,
                candidateArtist = track.artist,
                candidateTitle = track.title
            )
        }
        val syncedRecs = syncTrackListWithDownloads(rawRecs, savedMap, resolvingIds)

        ArtistUiState(
            artistName = artistName,
            downloadedTracks = downloadedOfArtist,
            recommendedTracks = syncedRecs,
            isLoadingRecommendations = loadingRecs
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun syncTrackListWithDownloads(
        tracks: List<TrackItem>,
        savedMap: Map<String, TrackItem>,
        resolvingIds: Set<String>
    ): List<TrackItem> {
        val byNormalized = savedMap.values.associateBy {
            TitleCleaner.normalizeForMatching("${it.artist} ${it.cleanTitle.ifBlank { it.title }}")
        }
        return tracks.map { rec ->
            val match = savedMap[rec.id]
                ?: byNormalized[TitleCleaner.normalizeForMatching("${rec.artist} ${rec.cleanTitle}")]
            when {
                match != null -> rec.copy(
                    id = match.id,
                    sourceUrl = match.sourceUrl.ifBlank { rec.sourceUrl },
                    downloadStatus = match.downloadStatus,
                    downloadProgress = match.downloadProgress,
                    localUri = match.localUri,
                    localFilePath = match.localFilePath,
                    plainLyrics = match.plainLyrics ?: rec.plainLyrics,
                    syncedLyrics = match.syncedLyrics ?: rec.syncedLyrics,
                    hdCoverUrl = rec.hdCoverUrl.ifBlank { match.hdCoverUrl }
                )
                rec.id in resolvingIds -> rec.copy(
                    downloadStatus = DownloadStatus.QUEUED,
                    downloadProgress = 0
                )
                else -> rec
            }
        }
    }

    val currentSyncedTrack: StateFlow<TrackItem?> = combine(
        playbackState.map { it.currentTrack }.distinctUntilChanged(),
        allSavedTracksMap,
        _resolvingTrackIds
    ) { current, savedMap, resolvingIds ->
        if (current == null) null
        else syncTrackListWithDownloads(listOf(current), savedMap, resolvingIds).firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        container.playerController.onTrackTransition = { newTrack ->
            loadLyricsFor(newTrack)
        }
        syncLocalLibrary()
        viewModelScope.launch {
            downloadedTracks
                .map { list -> list.map { it.id }.toSet() }
                .distinctUntilChanged()
                .collect {
                    if (_rawRecommendations.value.isEmpty() || downloadedTracks.value.isNotEmpty()) {
                        refreshRecommendations()
                    }
                }
        }
    }

    fun refreshRecommendations() {
        recommendJob?.cancel()
        recommendJob = viewModelScope.launch {
            _isLoadingRecommendations.value = true
            val recs = container.recommendationEngine.recommendForLibrary(
                downloadedTracks = downloadedTracks.value,
                limit = 16
            )
            _rawRecommendations.value = recs
            _isLoadingRecommendations.value = false
            prefetchRecommendationsAsync(recs.take(4))
        }
    }

    private fun prefetchRecommendationsAsync(tracks: List<TrackItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            tracks.map { track ->
                async {
                    val resolved = resolveTrackIfNeeded(track) ?: return@async
                    if (resolved.id != track.id || resolved.sourceUrl != track.sourceUrl) {
                        _rawRecommendations.update { list ->
                            list.map { if (it.id == track.id) resolved else it }
                        }
                    }
                    container.extractorRepo.prefetchStreamUrl(resolved.sourceUrl)
                }
            }.awaitAll()
        }
    }

    val mergedSearchState: StateFlow<SearchUiState> = combine(
        _searchState,
        allSavedTracksMap
    ) { state, savedMap ->
        val mergedResults = state.results.map { track ->
            savedMap[track.id]?.let { saved ->
                track.copy(
                    downloadStatus = saved.downloadStatus,
                    downloadProgress = saved.downloadProgress,
                    localUri = saved.localUri,
                    localFilePath = saved.localFilePath,
                    hdCoverUrl = saved.hdCoverUrl.ifBlank { track.hdCoverUrl }
                )
            } ?: track
        }
        val mergedPlaylist = state.playlistPreview?.let { pl ->
            pl.copy(
                tracks = pl.tracks.map { track ->
                    savedMap[track.id]?.let { saved ->
                        track.copy(
                            downloadStatus = saved.downloadStatus,
                            downloadProgress = saved.downloadProgress,
                            localUri = saved.localUri,
                            localFilePath = saved.localFilePath,
                            hdCoverUrl = saved.hdCoverUrl.ifBlank { track.hdCoverUrl }
                        )
                    } ?: track
                }
            )
        }
        state.copy(results = mergedResults, playlistPreview = mergedPlaylist)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun updateQuery(newQuery: String) {
        _searchState.update { it.copy(query = newQuery, error = null, infoMessage = null) }
    }

    fun setOriginalsOnly(originalsOnly: Boolean) {
        _searchState.update { it.copy(originalsOnly = originalsOnly) }
        if (_searchState.value.query.isNotBlank() && _searchState.value.playlistPreview == null) {
            submitSearchOrLink(_searchState.value.query)
        }
    }

    fun submitSearchOrLink(input: String = _searchState.value.query) {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return

        searchJob?.cancel()
        enrichJob?.cancel()
        val generation = ++searchGeneration
        val originalsOnly = _searchState.value.originalsOnly

        _searchState.update {
            it.copy(
                query = trimmed,
                isLoading = true,
                error = null,
                infoMessage = null
            )
        }

        searchJob = viewModelScope.launch {
            try {
                when (val parsed = YouTubeUrlParser.parse(trimmed)) {
                    is ParsedYouTubeInput.PlaylistUrl -> {
                        val preview = container.extractorRepo.extractPlaylist(parsed.url)
                        if (generation != searchGeneration) return@launch
                        val savedMap = allSavedTracksMap.value
                        val selectableIds = preview.tracks
                            .filter { savedMap[it.id]?.isDownloaded != true }
                            .map { it.id }
                            .toSet()
                        _searchState.update {
                            it.copy(
                                isLoading = false,
                                playlistPreview = preview,
                                results = emptyList(),
                                selectedPlaylistIds = selectableIds
                            )
                        }
                        enrichTopCoversAsync(preview.tracks.take(10), isPlaylist = true, generation = generation)
                    }

                    is ParsedYouTubeInput.VideoUrl -> {
                        val track = container.extractorRepo.getTrackInfo(parsed.url)
                        if (generation != searchGeneration) return@launch
                        _searchState.update {
                            it.copy(
                                isLoading = false,
                                results = listOf(track),
                                playlistPreview = null
                            )
                        }
                        enrichTopCoversAsync(listOf(track), isPlaylist = false, generation = generation)
                    }

                    is ParsedYouTubeInput.SearchQuery -> {
                        val results = container.extractorRepo.searchMusic(
                            query = parsed.query,
                            originalsOnly = originalsOnly
                        )
                        if (generation != searchGeneration) return@launch
                        _searchState.update {
                            it.copy(
                                isLoading = false,
                                results = results,
                                playlistPreview = null
                            )
                        }
                        enrichTopCoversAsync(results.take(8), isPlaylist = false, generation = generation)
                        prefetchTopSearchStreamsAsync(results.take(3))
                    }
                }
            } catch (e: Exception) {
                if (generation != searchGeneration) return@launch
                _searchState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Error al buscar o analizar el enlace"
                    )
                }
            }
        }
    }

    private fun prefetchTopSearchStreamsAsync(tracks: List<TrackItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            tracks.forEach { track ->
                if (track.sourceUrl.isNotBlank()) {
                    launch { container.extractorRepo.prefetchStreamUrl(track.sourceUrl) }
                }
            }
        }
    }

    private fun enrichTopCoversAsync(
        tracks: List<TrackItem>,
        isPlaylist: Boolean,
        generation: Long
    ) {
        enrichJob = viewModelScope.launch {
            val enrichedPairs = tracks.map { track ->
                async {
                    val meta = container.metadataEnricher.enrich(
                        rawTitle = track.cleanTitle.ifBlank { track.title },
                        rawArtist = track.artist
                    )
                    track.id to meta
                }
            }.awaitAll().toMap()

            if (generation != searchGeneration) return@launch

            _searchState.update { state ->
                if (isPlaylist) {
                    val updatedPl = state.playlistPreview?.let { pl ->
                        pl.copy(
                            tracks = pl.tracks.map { t ->
                                val meta = enrichedPairs[t.id]
                                if (meta != null && meta.coverUrl.isNotBlank()) {
                                    t.copy(
                                        hdCoverUrl = meta.coverUrl,
                                        album = meta.album.ifBlank { t.album }
                                    )
                                } else t
                            }
                        )
                    }
                    state.copy(playlistPreview = updatedPl)
                } else {
                    state.copy(
                        results = state.results.map { t ->
                            val meta = enrichedPairs[t.id]
                            if (meta != null && meta.coverUrl.isNotBlank()) {
                                t.copy(
                                    hdCoverUrl = meta.coverUrl,
                                    album = meta.album.ifBlank { t.album }
                                )
                            } else t
                        }
                    )
                }
            }
        }
    }

    fun togglePlaylistSelection(trackId: String) {
        if (allSavedTracksMap.value[trackId]?.isDownloaded == true) return
        _searchState.update { state ->
            val current = state.selectedPlaylistIds
            val next = if (trackId in current) current - trackId else current + trackId
            state.copy(selectedPlaylistIds = next)
        }
    }

    fun selectAllPlaylistTracks(selectAll: Boolean) {
        val savedMap = allSavedTracksMap.value
        _searchState.update { state ->
            val allIds = if (selectAll) {
                state.playlistPreview?.tracks
                    ?.filter { savedMap[it.id]?.isDownloaded != true }
                    ?.map { it.id }
                    ?.toSet()
                    .orEmpty()
            } else emptySet()
            state.copy(selectedPlaylistIds = allIds)
        }
    }

    fun downloadSingleTrack(track: TrackItem) {
        val current = allSavedTracksMap.value[track.id] ?: track
        if (current.id in _resolvingTrackIds.value ||
            current.downloadStatus == DownloadStatus.COMPLETED ||
            current.downloadStatus == DownloadStatus.QUEUED ||
            current.downloadStatus == DownloadStatus.DOWNLOADING ||
            current.downloadStatus == DownloadStatus.TAGGING
        ) {
            return
        }

        viewModelScope.launch {
            _resolvingTrackIds.update { it + current.id }
            try {
                val resolved = if (current.sourceUrl.isBlank()) {
                    val ytMatch = runCatching {
                        container.extractorRepo.searchMusic(
                            query = "${current.artist} ${current.cleanTitle}",
                            originalsOnly = true
                        ).firstOrNull()
                    }.getOrNull()

                    if (ytMatch == null) {
                        _uiMessage.value = "No se encontró fuente descargable para ${current.cleanTitle}"
                        return@launch
                    }

                    val merged = ytMatch.copy(
                        cleanTitle = current.cleanTitle,
                        artist = current.artist,
                        album = current.album,
                        hdCoverUrl = current.bestCoverUrl
                    )
                    // Update recommendations, artist list, and active player with resolved YouTube ID
                    _rawRecommendations.update { list ->
                        list.map { if (it.id == current.id) merged else it }
                    }
                    _rawArtistRecs.update { list ->
                        list.map { if (it.id == current.id) merged else it }
                    }
                    container.playerController.replaceResolvedTrack(current.id, merged)
                    merged
                } else {
                    current
                }

                if ((allSavedTracksMap.value[resolved.id]?.downloadStatus) == DownloadStatus.COMPLETED) {
                    return@launch
                }
                container.downloadOrchestrator.enqueueSingleTrack(resolved)
            } finally {
                _resolvingTrackIds.update { it - current.id }
            }
        }
    }

    fun downloadSelectedPlaylist() {
        val state = _searchState.value
        val preview = state.playlistPreview ?: return
        val savedMap = allSavedTracksMap.value
        val selected = preview.tracks.filter {
            it.id in state.selectedPlaylistIds && savedMap[it.id]?.isDownloaded != true
        }
        if (selected.isEmpty()) {
            _searchState.update {
                it.copy(infoMessage = "Las canciones seleccionadas ya están descargadas")
            }
            return
        }

        viewModelScope.launch {
            container.downloadOrchestrator.enqueuePlaylist(preview, selected)
            _searchState.update {
                it.copy(infoMessage = "Cola iniciada: ${selected.size} canciones en descarga")
            }
        }
    }

    fun clearPlaylistPreview() {
        _searchState.update { it.copy(playlistPreview = null, infoMessage = null) }
    }

    fun cancelOrRemoveQueuedDownload(trackId: String) {
        viewModelScope.launch {
            container.downloadOrchestrator.cancelOrRemoveTrack(trackId)
        }
    }

    fun openArtistPage(rawArtist: String) {
        val primary = TitleCleaner.extractPrimaryArtist(rawArtist)
        if (primary.isBlank()) return
        _selectedArtistName.value = primary
        _rawArtistRecs.value = emptyList()
        artistJob?.cancel()
        artistJob = viewModelScope.launch {
            _isLoadingArtistRecs.value = true
            val recs = runCatching {
                container.recommendationEngine.recommendStrictForArtist(
                    rawArtist = primary,
                    downloadedTracks = downloadedTracks.value,
                    limit = 20
                )
            }.getOrDefault(emptyList())
            _rawArtistRecs.value = recs
            _isLoadingArtistRecs.value = false
        }
    }

    fun closeArtistPage() {
        artistJob?.cancel()
        _selectedArtistName.value = null
        _rawArtistRecs.value = emptyList()
    }

    // --- Custom Playlist Management ---

    fun createPlaylist(name: String, initialTrack: TrackItem? = null) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val playlistId = dao.insertPlaylist(
                PlaylistEntity(
                    name = trimmed,
                    coverUrl = initialTrack?.bestCoverUrl.orEmpty(),
                    sourceUrl = null
                )
            )
            if (initialTrack != null) {
                val trackToSave = resolveTrackIfNeeded(initialTrack) ?: initialTrack
                dao.insertTrackIgnore(TrackEntity.fromDomain(trackToSave))
                dao.insertPlaylistRefs(
                    listOf(PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackToSave.id, position = 0))
                )
            }
            _uiMessage.value = "Playlist \"$trimmed\" creada"
        }
    }

    fun addTrackToPlaylist(playlistId: Long, track: TrackItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val trackToSave = resolveTrackIfNeeded(track) ?: track
            dao.insertTrackIgnore(TrackEntity.fromDomain(trackToSave))
            dao.insertPlaylistRefs(
                listOf(PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackToSave.id, position = 0))
            )
            if (trackToSave.bestCoverUrl.isNotBlank()) {
                dao.updatePlaylistCoverIfEmpty(playlistId, trackToSave.bestCoverUrl)
            }
            _uiMessage.value = "Agregada a la playlist"
        }
    }

    private suspend fun resolveTrackIfNeeded(track: TrackItem): TrackItem? {
        if (track.sourceUrl.isNotBlank()) return track
        val found = runCatching {
            container.extractorRepo.searchMusic("${track.artist} ${track.cleanTitle}", originalsOnly = true)
                .firstOrNull()
        }.getOrNull() ?: return null
        return found.copy(
            cleanTitle = track.cleanTitle,
            artist = track.artist,
            album = track.album,
            hdCoverUrl = track.bestCoverUrl
        )
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.removeTrackFromPlaylist(playlistId, trackId)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.clearPlaylistRefs(playlistId)
            dao.deletePlaylist(playlistId)
        }
    }

    fun deleteDownloadedTrack(trackId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = dao.getTrackById(trackId)
            container.localLibraryScanner.markDeleted(existing?.localUri, existing?.localFilePath)
            container.downloadOrchestrator.cancelOrRemoveTrack(trackId)

            existing?.let { deleteLocalTrackFiles(it) }

            dao.removeTrackFromAllPlaylists(trackId)
            dao.deleteTrack(trackId)
            _uiMessage.value = "Canción eliminada"
        }
    }

    private fun deleteLocalTrackFiles(entity: TrackEntity) {
        val coversDir = File(container.appContext.filesDir, "covers")
        File(coversDir, "${entity.id}.jpg").delete()
        if (entity.hdCoverUrl.startsWith("file://")) {
            runCatching {
                val coverFile = File(URI(entity.hdCoverUrl))
                if (coverFile.parentFile == coversDir) {
                    coverFile.delete()
                }
            }
        }

        entity.localFilePath?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching {
                val audioFile = File(path)
                if (audioFile.exists() && audioFile.delete()) {
                    MediaScannerConnection.scanFile(
                        container.appContext,
                        arrayOf(audioFile.absolutePath),
                        null,
                        null
                    )
                }
            }
        }

        entity.localUri?.takeIf { it.isNotBlank() }?.let { uriStr ->
            when {
                uriStr.startsWith("content://") -> {
                    runCatching {
                        container.appContext.contentResolver.delete(Uri.parse(uriStr), null, null)
                    }
                }
                uriStr.startsWith("file://") -> {
                    runCatching {
                        val audioFile = File(URI(uriStr))
                        if (audioFile.exists()) {
                            audioFile.delete()
                        }
                    }
                }
            }
        }
    }

    // --- Playback Controls (Sequential / Shuffle / Repeat) ---

    fun playTrack(track: TrackItem, queue: List<TrackItem> = listOf(track)) {
        val synced = allSavedTracksMap.value[track.id] ?: track
        val syncedQueue = queue.map { allSavedTracksMap.value[it.id] ?: it }
        container.playerController.playTrack(synced, syncedQueue)
        loadLyricsFor(synced)
    }

    fun togglePlayPause() = container.playerController.togglePlayPause()
    fun playNext() = container.playerController.playNext()
    fun playPrevious() = container.playerController.playPrevious()
    fun seekTo(posMs: Long) = container.playerController.seekTo(posMs)
    fun toggleShuffle() = container.playerController.toggleShuffle()
    fun cycleRepeatMode() = container.playerController.cycleRepeatMode()

    fun loadLyricsFor(track: TrackItem) {
        val synced = allSavedTracksMap.value[track.id] ?: track
        if (!synced.plainLyrics.isNullOrBlank() || !synced.syncedLyrics.isNullOrBlank()) {
            _lyricsState.value = LyricsResult(
                plainLyrics = synced.plainLyrics,
                syncedLyrics = synced.syncedLyrics
            )
            _isLoadingLyrics.value = false
            return
        }

        viewModelScope.launch {
            _isLoadingLyrics.value = true
            val fetched = container.lyricsRepository.fetchLyrics(
                artist = synced.artist,
                title = synced.cleanTitle.ifBlank { synced.title },
                durationSeconds = synced.durationSeconds
            )
            _lyricsState.value = fetched
            _isLoadingLyrics.value = false

            if (fetched != null && synced.isDownloaded &&
                (!fetched.plainLyrics.isNullOrBlank() || !fetched.syncedLyrics.isNullOrBlank())
            ) {
                withContext(Dispatchers.IO) {
                    dao.updateTrackLyrics(synced.id, fetched.plainLyrics, fetched.syncedLyrics)
                }
            }
        }
    }

    // --- Cover Art Editor (Gallery or Internet + PSNR >= 20 dB) ---

    fun resetCoverEditor() {
        _coverEditorState.value = CoverEditorUiState()
    }

    fun searchCoversFromInternet(query: String) {
        viewModelScope.launch {
            _coverEditorState.update {
                it.copy(isSearching = true, errorMessage = null, statusMessage = null)
            }
            val rawCandidates = container.metadataEnricher.searchCoverCandidates(query)
            val evaluated = withContext(Dispatchers.IO) {
                rawCandidates.map { candidate ->
                    async {
                        val temp = File(container.appContext.cacheDir, "eval_${candidate.imageUrl.hashCode()}.jpg")
                        val ok = downloadFileTo(candidate.imageUrl, temp)
                        val quality = if (ok) ImageQualityEvaluator.evaluateFile(temp) else null
                        temp.delete()
                        EvaluatedCoverCandidate(candidate, quality)
                    }
                }.awaitAll()
            }
                .filter { it.quality?.isAcceptable == true }

            _coverEditorState.update {
                it.copy(
                    isSearching = false,
                    candidates = evaluated,
                    statusMessage = if (evaluated.isEmpty()) {
                        "No se encontraron portadas con PSNR >= 20 dB"
                    } else {
                        "${evaluated.size} portadas HD (>= 20 dB) encontradas"
                    }
                )
            }
        }
    }

    fun applyCoverFromUrl(track: TrackItem, imageUrl: String) {
        viewModelScope.launch {
            _coverEditorState.update { it.copy(isApplying = true, errorMessage = null) }
            try {
                val tempCover = File(container.appContext.cacheDir, "new_cover_${track.id}.jpg")
                val downloaded = withContext(Dispatchers.IO) { downloadFileTo(imageUrl, tempCover) }
                if (!downloaded) {
                    throw IllegalStateException("No se pudo descargar la imagen")
                }
                val quality = withContext(Dispatchers.IO) { ImageQualityEvaluator.evaluateFile(tempCover) }
                if (quality == null || !quality.isAcceptable) {
                    tempCover.delete()
                    val dbText = quality?.let { "%.1f dB".format(it.psnrDb) } ?: "inválida"
                    throw IllegalStateException("Imagen rechazada por baja calidad (PSNR: $dbText < 20 dB)")
                }
                applyCoverFileToTrack(track, tempCover)
                tempCover.delete()
                _coverEditorState.update {
                    it.copy(
                        isApplying = false,
                        statusMessage = "Portada actualizada (PSNR: %.1f dB)".format(quality.psnrDb)
                    )
                }
            } catch (e: Exception) {
                _coverEditorState.update {
                    it.copy(isApplying = false, errorMessage = e.message ?: "Error al aplicar portada")
                }
            }
        }
    }

    fun applyCoverFromGalleryUri(track: TrackItem, uriString: String, bypassPsnrCheck: Boolean = false) {
        viewModelScope.launch {
            _coverEditorState.update { it.copy(isApplying = true, errorMessage = null) }
            try {
                val tempCover = container.audioTagger.copyUriToTempFile(uriString, "gallery_cover", "jpg")
                    ?: throw IllegalStateException("No se pudo leer la imagen seleccionada")

                val quality = withContext(Dispatchers.IO) { ImageQualityEvaluator.evaluateFile(tempCover) }
                if (!bypassPsnrCheck && (quality == null || !quality.isAcceptable)) {
                    tempCover.delete()
                    val dbText = quality?.let { "%.1f dB".format(it.psnrDb) } ?: "desconocida"
                    throw IllegalStateException("La imagen de galería tiene PSNR $dbText (< 20 dB) y se verá pixelada")
                }

                applyCoverFileToTrack(track, tempCover)
                tempCover.delete()
                val psnrText = quality?.let { " (PSNR: %.1f dB)".format(it.psnrDb) }.orEmpty()
                _coverEditorState.update {
                    it.copy(isApplying = false, statusMessage = "Portada de galería aplicada$psnrText")
                }
            } catch (e: Exception) {
                _coverEditorState.update {
                    it.copy(isApplying = false, errorMessage = e.message ?: "Error aplicando imagen")
                }
            }
        }
    }

    private suspend fun applyCoverFileToTrack(track: TrackItem, coverFile: File) {
        val sourceUri = track.localUri?.takeIf { it.isNotBlank() }
            ?: track.localFilePath?.let { "file://$it" }
            ?: throw IllegalStateException("Solo se puede editar la portada de canciones descargadas")

        val tempAudio = container.audioTagger.copyUriToTempFile(sourceUri, "retag_${track.id}", "m4a")
            ?: throw IllegalStateException("No se pudo acceder al archivo de audio")

        try {
            val (saved, persistedCoverUri) = container.audioTagger.retagAndReplaceAudio(
                track = track,
                sourceAudioFile = tempAudio,
                newCoverFile = coverFile
            )
            val newCoverUrl = persistedCoverUri ?: track.bestCoverUrl
            withContext(Dispatchers.IO) {
                dao.updateTrackCover(
                    trackId = track.id,
                    hdCoverUrl = newCoverUrl,
                    localUri = saved.localUri,
                    localFilePath = saved.localFilePath
                )
            }
            val updated = track.copy(
                hdCoverUrl = newCoverUrl,
                localUri = saved.localUri,
                localFilePath = saved.localFilePath
            )
            container.playerController.refreshTrackMetadata(updated, replayAudio = false)
            _uiMessage.value = "Portada actualizada"
        } finally {
            tempAudio.delete()
        }
    }

    // --- Audio Trimmer (Remove Intro / Outro via Media3 Transformer) ---

    fun trimDownloadedTrack(
        track: TrackItem,
        startMs: Long,
        endMs: Long,
        onComplete: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val inputUri = track.localUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
                    ?: track.localFilePath?.let { Uri.fromFile(File(it)) }
                    ?: throw IllegalStateException("Archivo local no disponible")

                val trimmedOut = File(container.appContext.cacheDir, "trimmed_${track.id}.m4a")
                container.audioTrimmer.trimAudio(
                    inputUri = inputUri,
                    startMs = startMs,
                    endMs = endMs,
                    outputFile = trimmedOut
                )

                val (saved, _) = container.audioTagger.retagAndReplaceAudio(
                    track = track,
                    sourceAudioFile = trimmedOut,
                    newCoverFile = null
                )
                trimmedOut.delete()

                val newDurationSeconds = ((endMs - startMs) / 1000L).coerceAtLeast(1L)
                withContext(Dispatchers.IO) {
                    dao.updateTrackTrimmed(
                        trackId = track.id,
                        durationSeconds = newDurationSeconds,
                        localUri = saved.localUri,
                        localFilePath = saved.localFilePath
                    )
                }

                val updatedTrack = track.copy(
                    durationSeconds = newDurationSeconds,
                    localUri = saved.localUri,
                    localFilePath = saved.localFilePath
                )
                container.playerController.refreshTrackMetadata(updatedTrack, replayAudio = true)
                _uiMessage.value = "Audio recortado (${newDurationSeconds}s)"
                onComplete(true, null)
            } catch (e: Exception) {
                onComplete(false, e.message ?: "Error al recortar el audio")
            }
        }
    }

    fun syncLocalLibrary(showToast: Boolean = false) {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            val imported = runCatching { container.localLibraryScanner.scanAndImport() }
                .getOrDefault(0)
            if (showToast) {
                _uiMessage.value = if (imported > 0) {
                    "$imported canciones restauradas desde el dispositivo"
                } else {
                    "Biblioteca sincronizada"
                }
            }
            val recoveredCovers = runCatching { backfillMissingCovers() }.getOrDefault(0)
            if (recoveredCovers > 0) {
                _uiMessage.value = "$recoveredCovers portadas restauradas"
            }
        }
    }

    /**
     * Non-destructively restores missing cover art for downloaded tracks (preserving track IDs,
     * playlists, and lyrics): extracts embedded MP4 art if present, or fetches verified HD art
     * (PSNR >= 20 dB), caches it offline, and embeds it back into writable .m4a files.
     */
    private suspend fun backfillMissingCovers(): Int = withContext(Dispatchers.IO) {
        val candidates = dao.getAllTracksOnce()
            .filter { entity ->
                entity.downloadStatus == DownloadStatus.COMPLETED.name &&
                    !hasReadableCover(entity.hdCoverUrl, entity.thumbnailUrl)
            }
        if (candidates.isEmpty()) return@withContext 0

        val semaphore = Semaphore(3)
        val recovered = AtomicInteger(0)

        kotlinx.coroutines.coroutineScope {
            candidates.map { entity ->
                async {
                    semaphore.withPermit {
                        if (dao.getTrackById(entity.id) == null) return@withPermit
                        val track = entity.toDomain()
                        val embedded = container.audioTagger.extractExistingCover(track)
                        if (embedded != null) {
                            try {
                                if (dao.getTrackById(track.id) == null) return@withPermit
                                val persisted = container.audioTagger.persistCoverFile(track.id, embedded)
                                if (!persisted.isNullOrBlank()) {
                                    dao.updateTrackCoverUrl(track.id, persisted)
                                    refreshActivePlayerCover(track, persisted)
                                    recovered.incrementAndGet()
                                    return@withPermit
                                }
                            } finally {
                                embedded.delete()
                            }
                        }

                        val fetchedCover = fetchAndValidateCoverFile(track) ?: return@withPermit
                        try {
                            if (dao.getTrackById(track.id) == null) return@withPermit
                            val persisted = container.audioTagger.persistCoverFile(track.id, fetchedCover)
                                ?: return@withPermit
                            container.audioTagger.tryEmbedCoverInExistingTrack(track, fetchedCover)
                            dao.updateTrackCoverUrl(track.id, persisted)
                            refreshActivePlayerCover(track, persisted)
                            recovered.incrementAndGet()
                        } finally {
                            fetchedCover.delete()
                        }
                    }
                }
            }.awaitAll()
        }

        recovered.get()
    }

    private fun hasReadableCover(hdCoverUrl: String, thumbnailUrl: String): Boolean {
        val best = hdCoverUrl.ifBlank { thumbnailUrl }
        if (best.isBlank()) return false
        if (best.startsWith("http://") || best.startsWith("https://")) return true
        if (best.startsWith("file://")) {
            val file = runCatching { File(URI(best)) }.getOrNull() ?: return false
            return file.exists() && file.length() > 0L
        }
        return true
    }

    private suspend fun fetchAndValidateCoverFile(track: TrackItem): File? {
        val cleanArtist = if (track.artist.equals("Artista desconocido", ignoreCase = true)) {
            ""
        } else {
            track.artist
        }
        val cleanTitle = track.cleanTitle.ifBlank { track.title }
        val enriched = runCatching {
            container.metadataEnricher.enrich(cleanTitle, cleanArtist)
        }.getOrNull()

        val primaryUrl = enriched?.coverUrl?.takeIf { it.isNotBlank() }
        if (primaryUrl != null) {
            tryDownloadAcceptableCover(primaryUrl, track.id)?.let { return it }
        }

        val query = "$cleanArtist $cleanTitle".trim()
        val candidateUrls = runCatching { container.metadataEnricher.searchCoverCandidates(query) }
            .getOrDefault(emptyList())
            .map { it.imageUrl }
            .filter { it.isNotBlank() && it != primaryUrl }
            .distinct()
            .take(3)

        for (url in candidateUrls) {
            tryDownloadAcceptableCover(url, track.id)?.let { return it }
        }
        return null
    }

    private fun tryDownloadAcceptableCover(url: String, trackId: String): File? {
        val tempFile = File(container.appContext.cacheDir, "backfill_cover_${trackId}.jpg")
        val ok = downloadFileTo(url, tempFile)
        val quality = if (ok) ImageQualityEvaluator.evaluateFile(tempFile) else null
        if (quality != null && quality.isAcceptable) {
            return tempFile
        }
        tempFile.delete()
        return null
    }

    private suspend fun refreshActivePlayerCover(track: TrackItem, coverUrl: String) {
        if (playbackState.value.currentTrack?.id == track.id) {
            withContext(Dispatchers.Main) {
                container.playerController.refreshTrackMetadata(
                    track.copy(hdCoverUrl = coverUrl),
                    replayAudio = false
                )
            }
        }
    }

    fun clearUiMessage() {
        _uiMessage.value = null
    }

    private fun downloadFileTo(url: String, dest: File): Boolean {
        return runCatching {
            val req = Request.Builder().url(url).get().build()
            container.httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching false
                val body = resp.body ?: return@runCatching false
                body.byteStream().use { input ->
                    FileOutputStream(dest).use { out -> input.copyTo(out) }
                }
                dest.exists() && dest.length() > 0L
            }
        }.getOrDefault(false)
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PegoViewModel(container) as T
                }
            }
    }
}
