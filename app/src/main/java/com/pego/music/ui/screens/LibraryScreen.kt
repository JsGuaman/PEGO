package com.pego.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pego.music.domain.model.LocalPlaylist
import com.pego.music.domain.model.TrackItem
import com.pego.music.ui.components.TrackRow
import com.pego.music.ui.theme.PegoGreen
import com.pego.music.ui.theme.PegoSurface
import com.pego.music.ui.theme.PegoTextSecondary

@Composable
fun LibraryScreen(
    downloadedTracks: List<TrackItem>,
    activeDownloads: List<TrackItem>,
    playlists: List<LocalPlaylist>,
    currentTrackId: String?,
    shuffleEnabled: Boolean,
    onPlayTrack: (TrackItem, List<TrackItem>) -> Unit,
    onToggleShuffle: () -> Unit,
    onRetryDownload: (TrackItem) -> Unit,
    onCancelQueueTrack: (TrackItem) -> Unit,
    onArtistClick: (String) -> Unit,
    onAddToPlaylist: (TrackItem) -> Unit,
    onEditCover: (TrackItem) -> Unit,
    onTrimTrack: (TrackItem) -> Unit,
    onDeleteTrack: (TrackItem) -> Unit,
    onRemoveTrackFromPlaylist: (Long, String) -> Unit,
    onRenamePlaylist: (Long, String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onCreateNewPlaylist: () -> Unit,
    onSyncLocalLibrary: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var openedPlaylist by remember { mutableStateOf<LocalPlaylist?>(null) }
    var playlistToRename by remember { mutableStateOf<LocalPlaylist?>(null) }
    var playlistToDelete by remember { mutableStateOf<LocalPlaylist?>(null) }

    val currentOpened = openedPlaylist?.let { open ->
        playlists.find { it.id == open.id }
    }

    if (currentOpened != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { openedPlaylist = null }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Volver")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentOpened.name,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${currentOpened.tracks.size} canciones",
                        style = MaterialTheme.typography.bodySmall,
                        color = PegoTextSecondary
                    )
                }
                if (currentOpened.tracks.isNotEmpty()) {
                    IconButton(onClick = onToggleShuffle) {
                        Icon(
                            imageVector = if (shuffleEnabled) {
                                Icons.Rounded.Shuffle
                            } else {
                                Icons.AutoMirrored.Rounded.PlaylistPlay
                            },
                            contentDescription = if (shuffleEnabled) "Aleatorio" else "Secuencial",
                            tint = if (shuffleEnabled) PegoGreen else PegoTextSecondary
                        )
                    }
                    Button(
                        onClick = {
                            val startTrack = if (shuffleEnabled) {
                                currentOpened.tracks.random()
                            } else {
                                currentOpened.tracks.first()
                            }
                            onPlayTrack(startTrack, currentOpened.tracks)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PegoGreen,
                            contentColor = Color.Black
                        )
                    ) {
                        Text("Play", fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (currentOpened.tracks.isEmpty()) {
                EmptyState("Playlist vacía")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp)
                ) {
                    items(currentOpened.tracks, key = { it.id }, contentType = { "track" }) { track ->
                        TrackRow(
                            track = track,
                            isPlaying = track.id == currentTrackId,
                            onClick = { onPlayTrack(track, currentOpened.tracks) },
                            onDownloadClick = { onRetryDownload(track) },
                            onArtistClick = onArtistClick,
                            onAddToPlaylist = { onAddToPlaylist(track) },
                            onEditCover = { onEditCover(track) },
                            onTrimTrack = { onTrimTrack(track) },
                            onRemoveFromPlaylist = {
                                onRemoveTrackFromPlaylist(currentOpened.id, track.id)
                            },
                            onDeleteTrack = { onDeleteTrack(track) }
                        )
                    }
                }
            }
        }
        return
    }

    val uriHandler = LocalUriHandler.current

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tu Biblioteca",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onSyncLocalLibrary) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Escanear música local",
                    tint = PegoTextSecondary
                )
            }
            IconButton(onClick = { uriHandler.openUri("https://ko-fi.com/jsguaman") }) {
                Icon(
                    imageVector = Icons.Rounded.FavoriteBorder,
                    contentDescription = "Apoyar en Ko-fi",
                    tint = PegoGreen
                )
            }
        }

        val tabs = listOf(
            "Descargas (${downloadedTracks.size})",
            "Playlists (${playlists.size})",
            "Cola (${activeDownloads.size})"
        )

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = PegoGreen,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = PegoGreen
                )
            }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            maxLines = 1,
                            color = if (selectedTab == index) PegoGreen else PegoTextSecondary
                        )
                    }
                )
            }
        }

        when (selectedTab) {
            0 -> {
                if (downloadedTracks.isEmpty()) {
                    EmptyState("Sin descargas")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 120.dp)
                    ) {
                        items(downloadedTracks, key = { it.id }, contentType = { "track" }) { track ->
                            TrackRow(
                                track = track,
                                isPlaying = track.id == currentTrackId,
                                onClick = { onPlayTrack(track, downloadedTracks) },
                                onDownloadClick = { onRetryDownload(track) },
                                onArtistClick = onArtistClick,
                                onAddToPlaylist = { onAddToPlaylist(track) },
                                onEditCover = { onEditCover(track) },
                                onTrimTrack = { onTrimTrack(track) },
                                onDeleteTrack = { onDeleteTrack(track) }
                            )
                        }
                    }
                }
            }

            1 -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    OutlinedButton(
                        onClick = onCreateNewPlaylist,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = PegoGreen)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Nueva Playlist", color = PegoGreen, fontWeight = FontWeight.SemiBold)
                    }

                    if (playlists.isEmpty()) {
                        EmptyState("Sin playlists")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 120.dp)
                        ) {
                            items(playlists, key = { it.id }, contentType = { "playlist" }) { playlist ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { openedPlaylist = playlist }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val cover = playlist.coverUrl.ifBlank {
                                        playlist.tracks.firstOrNull { it.bestCoverUrl.isNotBlank() }?.bestCoverUrl.orEmpty()
                                    }
                                    if (cover.isNotBlank()) {
                                        AsyncImage(
                                            model = cover,
                                            contentDescription = playlist.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.PlaylistPlay,
                                            contentDescription = null,
                                            modifier = Modifier.size(56.dp),
                                            tint = PegoTextSecondary
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = playlist.name,
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${playlist.tracks.size} canciones",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = PegoTextSecondary
                                        )
                                    }
                                    IconButton(onClick = { playlistToRename = playlist }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Edit,
                                            contentDescription = "Editar nombre",
                                            tint = PegoTextSecondary
                                        )
                                    }
                                    IconButton(onClick = { playlistToDelete = playlist }) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = "Eliminar playlist",
                                            tint = PegoTextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            2 -> {
                if (activeDownloads.isEmpty()) {
                    EmptyState("Sin descargas en cola")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 120.dp)
                    ) {
                        items(activeDownloads, key = { it.id }, contentType = { "track" }) { track ->
                            TrackRow(
                                track = track,
                                isPlaying = track.id == currentTrackId,
                                onClick = { onPlayTrack(track, activeDownloads) },
                                onDownloadClick = { onRetryDownload(track) },
                                onArtistClick = onArtistClick,
                                onCancelOrRemoveQueue = { onCancelQueueTrack(track) }
                            )
                        }
                    }
                }
            }
        }
    }

    playlistToRename?.let { target ->
        var draftName by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { playlistToRename = null },
            containerColor = PegoSurface,
            title = { Text("Editar nombre", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    placeholder = { Text("Nombre de la playlist...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (draftName.isNotBlank()) {
                            onRenamePlaylist(target.id, draftName.trim())
                            playlistToRename = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PegoGreen,
                        contentColor = Color.Black
                    )
                ) {
                    Text("Guardar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToRename = null }) {
                    Text("Cancelar", color = PegoTextSecondary)
                }
            }
        )
    }

    playlistToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            containerColor = PegoSurface,
            title = { Text("Eliminar playlist", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "¿Seguro que quieres eliminar \"${target.name}\"? Las canciones descargadas seguirán en tu biblioteca."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeletePlaylist(target.id)
                        playlistToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    )
                ) {
                    Text("Eliminar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text("Cancelar", color = PegoTextSecondary)
                }
            }
        )
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = PegoTextSecondary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
