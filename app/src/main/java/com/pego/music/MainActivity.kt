package com.pego.music

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.pego.music.data.extractor.YouTubeUrlParser
import com.pego.music.domain.model.TrackItem
import com.pego.music.ui.PegoViewModel
import com.pego.music.ui.components.AddToPlaylistDialog
import com.pego.music.ui.components.ArtistSheet
import com.pego.music.ui.components.CreatePlaylistDialog
import com.pego.music.ui.components.EditCoverDialog
import com.pego.music.ui.components.FullPlayerSheet
import com.pego.music.ui.components.MiniPlayer
import com.pego.music.ui.components.TrimAudioDialog
import com.pego.music.ui.screens.HomeScreen
import com.pego.music.ui.screens.LibraryScreen
import com.pego.music.ui.screens.SearchScreen
import com.pego.music.ui.theme.PegoBlack
import com.pego.music.ui.theme.PegoGreen
import com.pego.music.ui.theme.PegoSurface
import com.pego.music.ui.theme.PegoTextSecondary
import com.pego.music.ui.theme.PegoTheme

class MainActivity : ComponentActivity() {

    private val viewModel: PegoViewModel by viewModels {
        PegoViewModel.factory((application as PegoApp).container)
    }

    private val requestRuntimePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.syncLocalLibrary()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askRuntimePermissionsIfNeeded()

        val initialSharedUrl = extractUrlFromIntent(intent)

        setContent {
            PegoTheme {
                var selectedTab by remember {
                    mutableIntStateOf(if (initialSharedUrl != null) 1 else 0)
                }
                var showFullPlayer by remember { mutableStateOf(false) }
                var trackForPlaylist by remember { mutableStateOf<TrackItem?>(null) }
                var trackForTrim by remember { mutableStateOf<TrackItem?>(null) }
                var trackForCover by remember { mutableStateOf<TrackItem?>(null) }
                var showCreatePlaylist by remember { mutableStateOf(false) }

                val snackbarHostState = remember { SnackbarHostState() }

                val searchState by viewModel.mergedSearchState.collectAsState()
                val playbackState by viewModel.playbackState.collectAsState()
                val currentSyncedTrack by viewModel.currentSyncedTrack.collectAsState()
                val downloadedTracks by viewModel.downloadedTracks.collectAsState()
                val activeDownloads by viewModel.activeDownloads.collectAsState()
                val localPlaylists by viewModel.localPlaylists.collectAsState()
                val savedTracksMap by viewModel.allSavedTracksMap.collectAsState()
                val recommendations by viewModel.recommendations.collectAsState()
                val isLoadingRecs by viewModel.isLoadingRecommendations.collectAsState()
                val artistState by viewModel.artistState.collectAsState()
                val lyrics by viewModel.lyricsState.collectAsState()
                val isLoadingLyrics by viewModel.isLoadingLyrics.collectAsState()
                val coverEditorState by viewModel.coverEditorState.collectAsState()
                val uiMessage by viewModel.uiMessage.collectAsState()

                LaunchedEffect(initialSharedUrl) {
                    if (initialSharedUrl != null) {
                        viewModel.submitSearchOrLink(initialSharedUrl)
                    }
                }

                LaunchedEffect(playbackState.errorMessage) {
                    playbackState.errorMessage?.let { msg ->
                        snackbarHostState.showSnackbar(msg)
                        (application as PegoApp).container.playerController.clearError()
                    }
                }

                LaunchedEffect(uiMessage) {
                    uiMessage?.let { msg ->
                        snackbarHostState.showSnackbar(msg)
                        viewModel.clearUiMessage()
                    }
                }

                Scaffold(
                    containerColor = PegoBlack,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        Column {
                            MiniPlayer(
                                playbackState = playbackState,
                                onTogglePlayPause = viewModel::togglePlayPause,
                                onExpand = { showFullPlayer = true }
                            )
                            NavigationBar(containerColor = PegoSurface) {
                                val navColors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = PegoGreen,
                                    selectedTextColor = PegoGreen,
                                    unselectedIconColor = PegoTextSecondary,
                                    unselectedTextColor = PegoTextSecondary,
                                    indicatorColor = Color.Transparent
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 0 && artistState == null,
                                    onClick = {
                                        viewModel.closeArtistPage()
                                        selectedTab = 0
                                    },
                                    icon = { Icon(Icons.Rounded.Home, contentDescription = "Inicio") },
                                    label = { Text("Inicio") },
                                    colors = navColors
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 1 && artistState == null,
                                    onClick = {
                                        viewModel.closeArtistPage()
                                        selectedTab = 1
                                    },
                                    icon = { Icon(Icons.Rounded.Search, contentDescription = "Buscar") },
                                    label = { Text("Buscar") },
                                    colors = navColors
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 2 && artistState == null,
                                    onClick = {
                                        viewModel.closeArtistPage()
                                        selectedTab = 2
                                    },
                                    icon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = "Biblioteca") },
                                    label = { Text("Biblioteca") },
                                    colors = navColors
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (selectedTab) {
                            0 -> HomeScreen(
                                playlists = localPlaylists,
                                recommendations = recommendations,
                                isLoadingRecommendations = isLoadingRecs,
                                currentTrackId = playbackState.currentTrack?.id,
                                onSearchSubmit = { query ->
                                    selectedTab = 1
                                    viewModel.submitSearchOrLink(query)
                                },
                                onRefreshRecommendations = viewModel::refreshRecommendations,
                                onPlayTrack = viewModel::playTrack,
                                onDownloadTrack = viewModel::downloadSingleTrack,
                                onAddToPlaylist = { trackForPlaylist = it },
                                onArtistClick = viewModel::openArtistPage
                            )

                            1 -> SearchScreen(
                                state = searchState,
                                currentTrackId = playbackState.currentTrack?.id,
                                onQueryChange = viewModel::updateQuery,
                                onOriginalsFilterChange = viewModel::setOriginalsOnly,
                                onSubmitSearch = viewModel::submitSearchOrLink,
                                onPlayTrack = viewModel::playTrack,
                                onDownloadSingle = viewModel::downloadSingleTrack,
                                onArtistClick = viewModel::openArtistPage,
                                onAddToPlaylist = { trackForPlaylist = it },
                                onEditCover = {
                                    viewModel.resetCoverEditor()
                                    trackForCover = it
                                },
                                onTrimTrack = { trackForTrim = it },
                                onDeleteTrack = { viewModel.deleteDownloadedTrack(it.id) },
                                onTogglePlaylistSelection = viewModel::togglePlaylistSelection,
                                onSelectAllPlaylist = viewModel::selectAllPlaylistTracks,
                                onDownloadSelectedPlaylist = viewModel::downloadSelectedPlaylist,
                                onClearPlaylist = viewModel::clearPlaylistPreview
                            )

                            2 -> LibraryScreen(
                                downloadedTracks = downloadedTracks,
                                activeDownloads = activeDownloads,
                                playlists = localPlaylists,
                                currentTrackId = playbackState.currentTrack?.id,
                                onPlayTrack = viewModel::playTrack,
                                onPlayShuffled = { tracks ->
                                    if (tracks.isNotEmpty()) {
                                        if (!playbackState.shuffleEnabled) {
                                            viewModel.toggleShuffle()
                                        }
                                        viewModel.playTrack(tracks.random(), tracks)
                                    }
                                },
                                onRetryDownload = viewModel::downloadSingleTrack,
                                onCancelQueueTrack = { viewModel.cancelOrRemoveQueuedDownload(it.id) },
                                onArtistClick = viewModel::openArtistPage,
                                onAddToPlaylist = { trackForPlaylist = it },
                                onEditCover = {
                                    viewModel.resetCoverEditor()
                                    trackForCover = it
                                },
                                onTrimTrack = { trackForTrim = it },
                                onDeleteTrack = { viewModel.deleteDownloadedTrack(it.id) },
                                onRemoveTrackFromPlaylist = viewModel::removeTrackFromPlaylist,
                                onDeletePlaylist = viewModel::deletePlaylist,
                                onCreateNewPlaylist = { showCreatePlaylist = true },
                                onSyncLocalLibrary = { viewModel.syncLocalLibrary(showToast = true) }
                            )
                        }

                        artistState?.let { currentArtist ->
                            ArtistSheet(
                                state = currentArtist,
                                currentTrackId = playbackState.currentTrack?.id,
                                onDismiss = viewModel::closeArtistPage,
                                onPlayTrack = viewModel::playTrack,
                                onPlayShuffled = { tracks ->
                                    if (tracks.isNotEmpty()) {
                                        if (!playbackState.shuffleEnabled) {
                                            viewModel.toggleShuffle()
                                        }
                                        viewModel.playTrack(tracks.random(), tracks)
                                    }
                                },
                                onDownloadTrack = viewModel::downloadSingleTrack,
                                onAddToPlaylist = { trackForPlaylist = it },
                                onEditCover = {
                                    viewModel.resetCoverEditor()
                                    trackForCover = it
                                },
                                onTrimTrack = { trackForTrim = it },
                                onDeleteTrack = { viewModel.deleteDownloadedTrack(it.id) }
                            )
                        }
                    }
                }

                if (showFullPlayer && playbackState.currentTrack != null) {
                    val synced = currentSyncedTrack ?: playbackState.currentTrack
                    FullPlayerSheet(
                        playbackState = playbackState,
                        syncedTrack = synced,
                        lyrics = lyrics,
                        isLoadingLyrics = isLoadingLyrics,
                        onDismiss = { showFullPlayer = false },
                        onTogglePlayPause = viewModel::togglePlayPause,
                        onNext = viewModel::playNext,
                        onPrevious = viewModel::playPrevious,
                        onSeekTo = viewModel::seekTo,
                        onToggleShuffle = viewModel::toggleShuffle,
                        onCycleRepeatMode = viewModel::cycleRepeatMode,
                        onDownloadClick = viewModel::downloadSingleTrack,
                        onAddToPlaylist = { trackForPlaylist = it },
                        onEditCover = {
                            viewModel.resetCoverEditor()
                            trackForCover = it
                        },
                        onTrimTrack = { trackForTrim = it },
                        onArtistClick = { artist ->
                            showFullPlayer = false
                            viewModel.openArtistPage(artist)
                        }
                    )
                }

                trackForPlaylist?.let { target ->
                    AddToPlaylistDialog(
                        track = target,
                        playlists = localPlaylists,
                        onDismiss = { trackForPlaylist = null },
                        onSelectPlaylist = { plId -> viewModel.addTrackToPlaylist(plId, target) },
                        onCreatePlaylistWithTrack = { name -> viewModel.createPlaylist(name, target) }
                    )
                }

                if (showCreatePlaylist) {
                    CreatePlaylistDialog(
                        onDismiss = { showCreatePlaylist = false },
                        onCreate = { name -> viewModel.createPlaylist(name, null) }
                    )
                }

                trackForTrim?.let { target ->
                    val syncedTarget = savedTracksMap[target.id] ?: target
                    TrimAudioDialog(
                        track = syncedTarget,
                        onDismiss = { trackForTrim = null },
                        onConfirmTrim = { startMs, endMs, onDone ->
                            viewModel.trimDownloadedTrack(syncedTarget, startMs, endMs, onDone)
                        }
                    )
                }

                trackForCover?.let { target ->
                    val syncedTarget = savedTracksMap[target.id] ?: target
                    EditCoverDialog(
                        track = syncedTarget,
                        state = coverEditorState,
                        onDismiss = { trackForCover = null },
                        onSearchInternet = { q -> viewModel.searchCoversFromInternet(q) },
                        onApplyUrl = { url -> viewModel.applyCoverFromUrl(syncedTarget, url) },
                        onApplyGalleryUri = { uri -> viewModel.applyCoverFromGalleryUri(syncedTarget, uri) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractUrlFromIntent(intent)?.let { url ->
            viewModel.submitSearchOrLink(url)
        }
    }

    private fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { YouTubeUrlParser.extractFirstUrl(it) }
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
    }

    private fun askRuntimePermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (needed.isNotEmpty()) {
            requestRuntimePermissions.launch(needed.toTypedArray())
        }
    }
}
