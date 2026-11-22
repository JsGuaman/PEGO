package com.pego.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pego.music.domain.model.LyricsResult
import com.pego.music.domain.model.TrackItem
import com.pego.music.player.PlaybackUiState
import com.pego.music.player.RepeatMode
import com.pego.music.ui.theme.PegoBlack
import com.pego.music.ui.theme.PegoCard
import com.pego.music.ui.theme.PegoGreen
import com.pego.music.ui.theme.PegoTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerSheet(
    playbackState: PlaybackUiState,
    syncedTrack: TrackItem?,
    lyrics: LyricsResult?,
    isLoadingLyrics: Boolean,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onDownloadClick: (TrackItem) -> Unit,
    onAddToPlaylist: (TrackItem) -> Unit,
    onEditCover: (TrackItem) -> Unit,
    onTrimTrack: (TrackItem) -> Unit,
    onArtistClick: (String) -> Unit
) {
    val track = syncedTrack ?: playbackState.currentTrack ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showLyrics by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PegoBlack,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Cerrar",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    text = if (track.isDownloaded) "OFFLINE" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = PegoGreen
                )
                IconButton(onClick = { showLyrics = !showLyrics }) {
                    Icon(
                        imageVector = Icons.Rounded.Lyrics,
                        contentDescription = "Letras",
                        tint = if (showLyrics) PegoGreen else PegoTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (showLyrics) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(PegoCard)
                        .padding(20.dp)
                ) {
                    when {
                        isLoadingLyrics -> {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                                color = PegoGreen
                            )
                        }
                        lyrics?.plainLyrics.isNullOrBlank() && lyrics?.syncedLyrics.isNullOrBlank() -> {
                            Text(
                                text = "Sin letras",
                                color = PegoTextSecondary,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                        else -> {
                            val displayLyrics = lyrics?.plainLyrics
                                ?: lyrics?.syncedLyrics?.replace(Regex("""\[\d{2}:\d{2}\.\d{2,3}]\s*"""), "")
                                ?: ""
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                Text(
                                    text = displayLyrics,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.88f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(PegoCard),
                        contentAlignment = Alignment.Center
                    ) {
                        if (track.bestCoverUrl.isNotBlank()) {
                            AsyncImage(
                                model = track.bestCoverUrl,
                                contentDescription = track.cleanTitle,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = PegoTextSecondary,
                                modifier = Modifier.size(80.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. Centered song name & clickable artist directly below image
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = track.cleanTitle.ifBlank { track.title },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PegoGreen,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = track.artist.isNotBlank()) {
                            onArtistClick(track.artist)
                        }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 2. Options below song name & artist
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onAddToPlaylist(track) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                        contentDescription = "Agregar a playlist",
                        tint = PegoTextSecondary
                    )
                }

                if (track.isDownloaded) {
                    IconButton(onClick = { onEditCover(track) }) {
                        Icon(
                            imageVector = Icons.Rounded.Image,
                            contentDescription = "Cambiar portada",
                            tint = PegoTextSecondary
                        )
                    }
                    IconButton(onClick = { onTrimTrack(track) }) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCut,
                            contentDescription = "Recortar",
                            tint = PegoTextSecondary
                        )
                    }
                }

                OneClickDownloadButton(
                    status = track.downloadStatus,
                    progress = track.downloadProgress,
                    onClick = { onDownloadClick(track) }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            val duration = playbackState.durationMs.coerceAtLeast(1L)
            val sliderPos = playbackState.positionMs.coerceIn(0L, duration).toFloat()

            Slider(
                value = sliderPos,
                onValueChange = { onSeekTo(it.toLong()) },
                valueRange = 0f..duration.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = PegoGreen,
                    inactiveTrackColor = PegoCard
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration(playbackState.positionMs / 1000L),
                    style = MaterialTheme.typography.labelSmall,
                    color = PegoTextSecondary
                )
                Text(
                    text = formatDuration(duration / 1000L),
                    style = MaterialTheme.typography.labelSmall,
                    color = PegoTextSecondary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        imageVector = if (playbackState.shuffleEnabled) {
                            Icons.Rounded.Shuffle
                        } else {
                            Icons.AutoMirrored.Rounded.PlaylistPlay
                        },
                        contentDescription = if (playbackState.shuffleEnabled) "Aleatorio" else "Secuencial",
                        tint = if (playbackState.shuffleEnabled) PegoGreen else PegoTextSecondary
                    )
                }

                IconButton(onClick = onPrevious, modifier = Modifier.size(52.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Anterior",
                        modifier = Modifier.size(38.dp)
                    )
                }

                Surface(
                    onClick = onTogglePlayPause,
                    shape = CircleShape,
                    color = Color.White,
                    modifier = Modifier.size(66.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (playbackState.isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = Color.Black,
                                strokeWidth = 3.dp
                            )
                        } else {
                            Icon(
                                imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color.Black,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    }
                }

                IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Siguiente",
                        modifier = Modifier.size(38.dp)
                    )
                }

                IconButton(onClick = onCycleRepeatMode) {
                    val repeatIcon = when (playbackState.repeatMode) {
                        RepeatMode.ONE -> Icons.Rounded.RepeatOne
                        else -> Icons.Rounded.Repeat
                    }
                    Icon(
                        imageVector = repeatIcon,
                        contentDescription = "Repetir",
                        tint = if (playbackState.repeatMode != RepeatMode.OFF) PegoGreen else PegoTextSecondary
                    )
                }
            }
        }
    }
}
