package com.pego.music.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pego.music.domain.model.TrackItem
import com.pego.music.ui.ArtistUiState
import com.pego.music.ui.theme.PegoBlack
import com.pego.music.ui.theme.PegoGreen
import com.pego.music.ui.theme.PegoTextSecondary

@Composable
fun ArtistSheet(
    state: ArtistUiState,
    currentTrackId: String?,
    shuffleEnabled: Boolean,
    onDismiss: () -> Unit,
    onPlayTrack: (TrackItem, List<TrackItem>) -> Unit,
    onToggleShuffle: () -> Unit,
    onDownloadTrack: (TrackItem) -> Unit,
    onAddToPlaylist: (TrackItem) -> Unit,
    onEditCover: (TrackItem) -> Unit,
    onTrimTrack: (TrackItem) -> Unit,
    onDeleteTrack: (TrackItem) -> Unit
) {
    val allArtistTracks = state.downloadedTracks + state.recommendedTracks

    BackHandler(onBack = onDismiss)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PegoBlack)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Volver")
            }
            Text(
                text = state.artistName,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (allArtistTracks.isNotEmpty()) {
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
                Spacer(modifier = Modifier.width(4.dp))
                Button(
                    onClick = {
                        val startTrack = if (shuffleEnabled) {
                            allArtistTracks.random()
                        } else {
                            allArtistTracks.first()
                        }
                        onPlayTrack(startTrack, allArtistTracks)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PegoGreen,
                        contentColor = Color.Black
                    )
                ) {
                    Text("Play", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            item(contentType = "header") {
                Text(
                    text = "Descargadas (${state.downloadedTracks.size})",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (state.downloadedTracks.isEmpty()) {
                item(contentType = "empty_downloaded") {
                    Text(
                        text = "Sin canciones descargadas",
                        color = PegoTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            } else {
                items(state.downloadedTracks, key = { "dl_${it.id}" }, contentType = { "track" }) { track ->
                    TrackRow(
                        track = track,
                        isPlaying = track.id == currentTrackId,
                        onClick = { onPlayTrack(track, state.downloadedTracks) },
                        onDownloadClick = { onDownloadTrack(track) },
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        onEditCover = { onEditCover(track) },
                        onTrimTrack = { onTrimTrack(track) },
                        onDeleteTrack = { onDeleteTrack(track) }
                    )
                }
            }

            item(contentType = "header_recs") {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Más de ${state.artistName}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            when {
                state.isLoadingRecommendations -> {
                    item(contentType = "loading_recs") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = PegoGreen)
                        }
                    }
                }

                state.recommendedTracks.isEmpty() -> {
                    item(contentType = "empty_recs") {
                        Text(
                            text = "Sin resultados disponibles",
                            color = PegoTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                else -> {
                    items(state.recommendedTracks, key = { "rec_${it.id}" }, contentType = { "track" }) { track ->
                        TrackRow(
                            track = track,
                            isPlaying = track.id == currentTrackId,
                            onClick = { onPlayTrack(track, state.recommendedTracks) },
                            onDownloadClick = { onDownloadTrack(track) },
                            onAddToPlaylist = { onAddToPlaylist(track) }
                        )
                    }
                }
            }
        }
    }
}
