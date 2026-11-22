package com.pego.music.data.local

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pego.music.data.metadata.TitleCleaner
import com.pego.music.data.tagger.AudioTagger
import com.pego.music.domain.model.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.FileOutputStream

/**
 * Scans device storage (MediaStore Music/PEGO + app-external Music/PEGO) so that
 * previously downloaded tracks, embedded covers, and lyrics are automatically restored
 * into PEGO's Room database after uninstalling/reinstalling the app.
 */
class LocalLibraryScanner(
    private val context: Context,
    private val dao: PegoDao
) {

    suspend fun scanAndImport(): Int = withContext(Dispatchers.IO) {
        val existingTracks = dao.getAllTracksOnce()
        val existingUris = existingTracks.mapNotNull { it.localUri }.toHashSet()
        val existingPaths = existingTracks.mapNotNull { it.localFilePath }.toHashSet()
        val existingKeys = existingTracks
            .filter { it.downloadStatus == DownloadStatus.COMPLETED.name }
            .map { TitleCleaner.normalizeForMatching("${it.artist} ${it.cleanTitle.ifBlank { it.title }}") }
            .toHashSet()

        var importedCount = 0

        // 1. Scan MediaStore public Music/PEGO folder
        importedCount += scanMediaStore(existingUris, existingKeys)

        // 2. Scan app-specific external Music/PEGO folder (API < 29 fallback)
        val appMusicDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,
            "PEGO"
        )
        if (appMusicDir.exists() && appMusicDir.isDirectory) {
            appMusicDir.listFiles()?.forEach { file ->
                if (file.isFile && isSupportedAudio(file.name) && file.absolutePath !in existingPaths) {
                    val entity = buildEntityFromFile(
                        file = file,
                        id = "local_file_${file.name.hashCode()}",
                        localUri = file.toURI().toString(),
                        localPath = file.absolutePath,
                        fallbackDisplayName = file.nameWithoutExtension
                    )
                    val key = TitleCleaner.normalizeForMatching("${entity.artist} ${entity.cleanTitle}")
                    if (key !in existingKeys) {
                        dao.upsertTrack(entity)
                        existingKeys.add(key)
                        importedCount++
                    }
                }
            }
        }

        importedCount
    }

    private suspend fun scanMediaStore(
        existingUris: MutableSet<String>,
        existingKeys: MutableSet<String>
    ): Int {
        var count = 0
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Audio.Media.RELATIVE_PATH)
        } else {
            @Suppress("DEPRECATION")
            projection.add(MediaStore.Audio.Media.DATA)
        }

        val selection: String
        val selectionArgs: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            selectionArgs = arrayOf("%Music/PEGO%")
        } else {
            @Suppress("DEPRECATION")
            selection = "${MediaStore.Audio.Media.DATA} LIKE ?"
            selectionArgs = arrayOf("%/Music/PEGO/%")
        }

        runCatching {
            context.contentResolver.query(
                collection,
                projection.toTypedArray(),
                selection,
                selectionArgs,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        mediaId
                    )
                    val uriString = contentUri.toString()
                    if (uriString in existingUris) continue

                    val displayName = cursor.getString(nameCol).orEmpty()
                    if (!isSupportedAudio(displayName)) continue

                    val fallbackTitle = cursor.getString(titleCol).orEmpty()
                        .ifBlank { displayName.substringBeforeLast('.') }
                    val fallbackArtist = cursor.getString(artistCol).orEmpty()
                        .takeUnless { it.equals("<unknown>", ignoreCase = true) }
                        .orEmpty()
                    val fallbackAlbum = cursor.getString(albumCol).orEmpty()
                        .takeUnless { it.equals("<unknown>", ignoreCase = true) }
                        .orEmpty()
                    val durationSec = (cursor.getLong(durCol) / 1000L).coerceAtLeast(0L)

                    val entity = importFromContentUri(
                        contentUri = contentUri,
                        mediaId = mediaId,
                        displayName = displayName,
                        fallbackTitle = fallbackTitle,
                        fallbackArtist = fallbackArtist,
                        fallbackAlbum = fallbackAlbum,
                        durationSeconds = durationSec
                    )

                    val key = TitleCleaner.normalizeForMatching("${entity.artist} ${entity.cleanTitle}")
                    if (key !in existingKeys) {
                        dao.upsertTrack(entity)
                        existingUris.add(uriString)
                        existingKeys.add(key)
                        count++
                    }
                }
            }
        }
        return count
    }

    private fun importFromContentUri(
        contentUri: Uri,
        mediaId: Long,
        displayName: String,
        fallbackTitle: String,
        fallbackArtist: String,
        fallbackAlbum: String,
        durationSeconds: Long
    ): TrackEntity {
        val ext = displayName.substringAfterLast('.', "m4a").lowercase()
        val tempFile = File(context.cacheDir, "scan_${mediaId}.$ext")
        return try {
            context.contentResolver.openInputStream(contentUri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }
            if (tempFile.exists() && tempFile.length() > 0L) {
                buildEntityFromFile(
                    file = tempFile,
                    id = "local_$mediaId",
                    localUri = contentUri.toString(),
                    localPath = null,
                    fallbackDisplayName = displayName.substringBeforeLast('.'),
                    fallbackTitle = fallbackTitle,
                    fallbackArtist = fallbackArtist,
                    fallbackAlbum = fallbackAlbum,
                    fallbackDurationSec = durationSeconds
                )
            } else {
                buildBasicEntity(
                    id = "local_$mediaId",
                    localUri = contentUri.toString(),
                    localPath = null,
                    rawName = displayName.substringBeforeLast('.'),
                    title = fallbackTitle,
                    artist = fallbackArtist,
                    album = fallbackAlbum,
                    durationSec = durationSeconds
                )
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun buildEntityFromFile(
        file: File,
        id: String,
        localUri: String,
        localPath: String?,
        fallbackDisplayName: String,
        fallbackTitle: String = "",
        fallbackArtist: String = "",
        fallbackAlbum: String = "",
        fallbackDurationSec: Long = 0L
    ): TrackEntity {
        var title = fallbackTitle
        var artist = fallbackArtist
        var album = fallbackAlbum
        var year = ""
        var lyrics: String? = null
        var coverUriString = ""
        var durationSec = fallbackDurationSec

        runCatching {
            AudioTagger.ensureAndroidMode()
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag
            if (tag != null) {
                tag.getFirst(FieldKey.TITLE).takeIf { it.isNotBlank() }?.let { title = it }
                tag.getFirst(FieldKey.ARTIST).takeIf { it.isNotBlank() }?.let { artist = it }
                tag.getFirst(FieldKey.ALBUM).takeIf { it.isNotBlank() }?.let { album = it }
                tag.getFirst(FieldKey.YEAR).takeIf { it.isNotBlank() }?.let { year = it }
                tag.getFirst(FieldKey.LYRICS).takeIf { it.isNotBlank() }?.let { lyrics = it }

                val artBytes = tag.firstArtwork?.binaryData
                if (artBytes != null && artBytes.isNotEmpty()) {
                    val coverDir = File(context.filesDir, "covers").apply { mkdirs() }
                    val coverOut = File(coverDir, "${id}.jpg")
                    coverOut.writeBytes(artBytes)
                    coverUriString = coverOut.toURI().toString()
                }
            }
            if (durationSec <= 0L) {
                durationSec = audioFile.audioHeader?.trackLength?.toLong()?.coerceAtLeast(0L) ?: 0L
            }
        }

        if (coverUriString.isBlank() || durationSec <= 0L) {
            runCatching {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(file.absolutePath)
                    if (durationSec <= 0L) {
                        val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        durationSec = durMs / 1000L
                    }
                    if (coverUriString.isBlank()) {
                        val artBytes = mmr.embeddedPicture
                        if (artBytes != null && artBytes.isNotEmpty()) {
                            val coverDir = File(context.filesDir, "covers").apply { mkdirs() }
                            val coverOut = File(coverDir, "${id}.jpg")
                            coverOut.writeBytes(artBytes)
                            coverUriString = coverOut.toURI().toString()
                        }
                    }
                } finally {
                    mmr.release()
                }
            }
        }

        val (parsedArtist, parsedTitle) = TitleCleaner.extractArtistAndTrack(
            rawTitle = title.ifBlank { fallbackDisplayName },
            rawUploader = artist
        )
        val finalTitle = title.ifBlank { parsedTitle }
        val finalArtist = artist.ifBlank { parsedArtist }.ifBlank { "Artista desconocido" }

        val isSyncedLrc = lyrics?.contains(Regex("""\[\d{2}:\d{2}\.\d{2,3}]""")) == true

        return TrackEntity(
            id = id,
            title = "$finalArtist - $finalTitle",
            cleanTitle = finalTitle,
            artist = finalArtist,
            album = album,
            year = year,
            durationSeconds = durationSec,
            thumbnailUrl = coverUriString,
            hdCoverUrl = coverUriString,
            sourceUrl = "",
            localFilePath = localPath,
            localUri = localUri,
            plainLyrics = if (isSyncedLrc) null else lyrics,
            syncedLyrics = if (isSyncedLrc) lyrics else null,
            downloadStatus = DownloadStatus.COMPLETED.name,
            downloadProgress = 100
        )
    }

    private fun buildBasicEntity(
        id: String,
        localUri: String,
        localPath: String?,
        rawName: String,
        title: String,
        artist: String,
        album: String,
        durationSec: Long
    ): TrackEntity {
        val (parsedArtist, parsedTitle) = TitleCleaner.extractArtistAndTrack(
            rawTitle = title.ifBlank { rawName },
            rawUploader = artist
        )
        val finalTitle = title.ifBlank { parsedTitle }
        val finalArtist = artist.ifBlank { parsedArtist }.ifBlank { "Artista desconocido" }
        return TrackEntity(
            id = id,
            title = "$finalArtist - $finalTitle",
            cleanTitle = finalTitle,
            artist = finalArtist,
            album = album,
            year = "",
            durationSeconds = durationSec,
            thumbnailUrl = "",
            hdCoverUrl = "",
            sourceUrl = "",
            localFilePath = localPath,
            localUri = localUri,
            downloadStatus = DownloadStatus.COMPLETED.name,
            downloadProgress = 100
        )
    }

    private fun isSupportedAudio(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".m4a") || lower.endsWith(".mp3") ||
            lower.endsWith(".flac") || lower.endsWith(".ogg") || lower.endsWith(".opus")
    }
}
