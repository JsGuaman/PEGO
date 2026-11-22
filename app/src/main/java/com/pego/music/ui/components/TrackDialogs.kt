package com.pego.music.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.pego.music.domain.model.LocalPlaylist
import com.pego.music.domain.model.TrackItem
import com.pego.music.ui.CoverEditorUiState
import com.pego.music.ui.theme.PegoCard
import com.pego.music.ui.theme.PegoGreen
import com.pego.music.ui.theme.PegoSurface
import com.pego.music.ui.theme.PegoTextSecondary
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun AddToPlaylistDialog(
    track: TrackItem,
    playlists: List<LocalPlaylist>,
    onDismiss: () -> Unit,
    onSelectPlaylist: (Long) -> Unit,
    onCreatePlaylistWithTrack: (String) -> Unit
) {
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PegoSurface,
        title = {
            Text(
                text = "Agregar a playlist",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = track.cleanTitle.ifBlank { track.title },
                    style = MaterialTheme.typography.bodySmall,
                    color = PegoTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("Nueva playlist...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (newName.isNotBlank()) {
                                onCreatePlaylistWithTrack(newName.trim())
                                onDismiss()
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Crear", tint = PegoGreen)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (playlists.isEmpty()) {
                    Text(
                        text = "Sin playlists",
                        style = MaterialTheme.typography.bodySmall,
                        color = PegoTextSecondary
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        val trackKey = com.pego.music.data.metadata.TitleCleaner.normalizeForMatching(
                            "${track.artist} ${track.cleanTitle.ifBlank { track.title }}"
                        )
                        items(playlists, key = { it.id }) { pl ->
                            val alreadyInPlaylist = pl.tracks.any { existing ->
                                existing.id == track.id ||
                                    com.pego.music.data.metadata.TitleCleaner.normalizeForMatching(
                                        "${existing.artist} ${existing.cleanTitle.ifBlank { existing.title }}"
                                    ) == trackKey
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onSelectPlaylist(pl.id)
                                        onDismiss()
                                    }
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.PlaylistAdd,
                                    contentDescription = null,
                                    tint = if (alreadyInPlaylist) PegoTextSecondary else PegoGreen
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(pl.name, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(
                                        "${pl.tracks.size} canciones",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = PegoTextSecondary
                                    )
                                }
                                if (alreadyInPlaylist) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = "Ya agregada",
                                        tint = PegoGreen
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar", color = PegoTextSecondary)
            }
        }
    )
}

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PegoSurface,
        title = { Text("Nueva Playlist", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Nombre de tu playlist...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onCreate(name.trim())
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = PegoGreen,
                    contentColor = Color.Black
                )
            ) {
                Text("Crear", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = PegoTextSecondary)
            }
        }
    )
}

@Composable
fun TrimAudioDialog(
    track: TrackItem,
    onDismiss: () -> Unit,
    onConfirmTrim: (startMs: Long, endMs: Long, onDone: (Boolean, String?) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val fallbackDurationMs = (track.durationSeconds.coerceAtLeast(30L)) * 1000L

    var totalDurationMs by remember { mutableLongStateOf(fallbackDurationMs) }
    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(fallbackDurationMs) }
    var isPreviewPlaying by remember { mutableStateOf(false) }
    var previewPosMs by remember { mutableLongStateOf(0L) }
    var isTrimming by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val previewPlayer = remember(track.id) {
        ExoPlayer.Builder(context).build().apply {
            val uri = track.localUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
                ?: track.localFilePath?.let { Uri.fromFile(File(it)) }
            if (uri != null) {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
            }
        }
    }

    DisposableEffect(previewPlayer) {
        onDispose {
            previewPlayer.release()
        }
    }

    LaunchedEffect(previewPlayer, isPreviewPlaying) {
        while (true) {
            val dur = previewPlayer.duration
            if (dur > 0L && dur != totalDurationMs) {
                totalDurationMs = dur
                if (endMs == fallbackDurationMs || endMs > dur) {
                    endMs = dur
                }
            }
            if (previewPlayer.isPlaying) {
                previewPosMs = previewPlayer.currentPosition.coerceAtLeast(0L)
                if (previewPosMs >= endMs) {
                    previewPlayer.pause()
                    isPreviewPlaying = false
                }
            }
            delay(200L)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isTrimming) onDismiss() },
        containerColor = PegoSurface,
        title = {
            Text("Recortar canción", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = track.cleanTitle.ifBlank { track.title },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(14.dp))

                val safeMax = totalDurationMs.coerceAtLeast(1000L).toFloat()
                RangeSlider(
                    value = startMs.toFloat().coerceIn(0f, safeMax)..endMs.toFloat().coerceIn(0f, safeMax),
                    onValueChange = { range ->
                        val newStart = range.start.toLong().coerceIn(0L, totalDurationMs - 1000L)
                        val newEnd = range.endInclusive.toLong().coerceIn(newStart + 1000L, totalDurationMs)
                        startMs = newStart
                        endMs = newEnd
                    },
                    valueRange = 0f..safeMax,
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
                        text = "Inicio: ${formatDuration(startMs / 1000L)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = PegoGreen
                    )
                    Text(
                        text = "Duración: ${formatDuration((endMs - startMs) / 1000L)}",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = "Fin: ${formatDuration(endMs / 1000L)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = PegoTextSecondary
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = {
                        startMs = (startMs - 1000L).coerceAtLeast(0L)
                    }) { Text("-1s") }

                    IconButton(
                        onClick = {
                            if (previewPlayer.isPlaying) {
                                previewPlayer.pause()
                                isPreviewPlaying = false
                            } else {
                                previewPlayer.seekTo(startMs)
                                previewPlayer.play()
                                isPreviewPlaying = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isPreviewPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "Escuchar desde inicio",
                            tint = PegoGreen,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    OutlinedButton(onClick = {
                        startMs = (startMs + 1000L).coerceAtMost(endMs - 1000L)
                    }) { Text("+1s") }
                }

                if (isPreviewPlaying) {
                    TextButton(
                        onClick = {
                            val currentPos = previewPlayer.currentPosition
                            if (currentPos in 0L until (endMs - 1000L)) {
                                startMs = currentPos
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            "Fijar inicio aquí (${formatDuration(previewPosMs / 1000L)})",
                            color = PegoGreen
                        )
                    }
                }

                errorMsg?.let { msg ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                if (isTrimming) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = PegoGreen)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Recortando audio con Media3...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isTrimming && endMs - startMs >= 1000L,
                onClick = {
                    previewPlayer.pause()
                    isPreviewPlaying = false
                    isTrimming = true
                    errorMsg = null
                    onConfirmTrim(startMs, endMs) { ok, err ->
                        isTrimming = false
                        if (ok) onDismiss() else errorMsg = err
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = PegoGreen,
                    contentColor = Color.Black
                )
            ) {
                Text("Recortar y guardar", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(enabled = !isTrimming, onClick = onDismiss) {
                Text("Cancelar", color = PegoTextSecondary)
            }
        }
    )
}

@Composable
fun EditCoverDialog(
    track: TrackItem,
    state: CoverEditorUiState,
    onDismiss: () -> Unit,
    onSearchInternet: (String) -> Unit,
    onApplyUrl: (String) -> Unit,
    onApplyGalleryUri: (String) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("${track.artist} ${track.cleanTitle}".trim()) }
    var directUrl by remember { mutableStateOf("") }

    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onApplyGalleryUri(uri.toString())
        }
    }

    LaunchedEffect(track.id) {
        onSearchInternet(query)
    }

    AlertDialog(
        onDismissRequest = { if (!state.isApplying) onDismiss() },
        containerColor = PegoSurface,
        title = { Text("Cambiar portada", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabRow(selectedTabIndex = selectedTab, containerColor = PegoSurface, contentColor = PegoGreen) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Internet") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Galería") }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (selectedTab == 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Buscar") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onSearchInternet(query) }) {
                            Icon(Icons.Rounded.Search, contentDescription = "Buscar", tint = PegoGreen)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = directUrl,
                            onValueChange = { directUrl = it },
                            placeholder = { Text("URL") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        TextButton(
                            enabled = directUrl.startsWith("http") && !state.isApplying,
                            onClick = { onApplyUrl(directUrl.trim()) }
                        ) {
                            Text("Usar", color = PegoGreen)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (state.isSearching) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = PegoGreen)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.candidates, key = { it.candidate.imageUrl }) { eval ->
                                Column(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(PegoCard)
                                        .clickable(enabled = !state.isApplying) {
                                            onApplyUrl(eval.candidate.imageUrl)
                                        }
                                        .padding(6.dp)
                                ) {
                                    AsyncImage(
                                        model = eval.candidate.imageUrl,
                                        contentDescription = eval.candidate.album,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(6.dp))
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val psnrLabel = eval.quality?.let {
                                        "${it.width}px • %.1f dB".format(it.psnrDb)
                                    }.orEmpty()
                                    Text(
                                        text = "${eval.candidate.source} • $psnrLabel",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PegoGreen,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Button(
                            enabled = !state.isApplying,
                            onClick = {
                                galleryPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PegoGreen,
                                contentColor = Color.Black
                            )
                        ) {
                            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Elegir de galería", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (state.isApplying) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = PegoGreen)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Verificando PSNR e incrustando...", style = MaterialTheme.typography.bodySmall)
                    }
                }

                state.statusMessage?.let { msg ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(msg, color = PegoGreen, style = MaterialTheme.typography.bodySmall)
                }

                state.errorMessage?.let { err ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !state.isApplying, onClick = onDismiss) {
                Text("Cerrar", color = PegoTextSecondary)
            }
        }
    )
}
