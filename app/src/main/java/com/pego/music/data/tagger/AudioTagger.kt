package com.pego.music.data.tagger

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pego.music.domain.model.EnrichedMetadata
import com.pego.music.domain.model.TrackItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.Artwork
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class SavedAudioResult(
    val localUri: String,
    val localFilePath: String?
)

class AudioTagger(private val context: Context) {

    init {
        ensureAndroidMode()
    }

    /**
     * Embeds ID3/MP4 metadata + square album art into [tempAudioFile] using JAudiotagger,
     * then exports the final .m4a file to public Music/PEGO/ storage.
     */
    suspend fun tagAndSaveM4a(
        tempAudioFile: File,
        coverFile: File?,
        metadata: EnrichedMetadata,
        lyricsText: String? = null
    ): SavedAudioResult = withContext(Dispatchers.IO) {
        applyTags(tempAudioFile, coverFile, metadata, lyricsText)
        val safeName = sanitizeFilename("${metadata.artist} - ${metadata.title}") + ".m4a"
        exportToMusicDirectory(tempAudioFile, safeName, metadata)
    }

    /**
     * Replaces the embedded cover art (or updates trimmed audio) for an already-downloaded track,
     * preserving existing tags and persisting a local cover file in app filesDir.
     */
    suspend fun retagAndReplaceAudio(
        track: TrackItem,
        sourceAudioFile: File,
        newCoverFile: File?
    ): Pair<SavedAudioResult, String?> = withContext(Dispatchers.IO) {
        val coverToEmbed = newCoverFile ?: extractExistingCover(track)
        val metadata = EnrichedMetadata(
            title = track.cleanTitle.ifBlank { track.title },
            artist = track.artist,
            album = track.album,
            year = track.year,
            coverUrl = track.bestCoverUrl
        )
        applyTags(sourceAudioFile, coverToEmbed, metadata)

        val saved = overwriteOrExportTrack(track, sourceAudioFile, metadata)

        val persistedCoverUri = if (newCoverFile != null && newCoverFile.exists()) {
            val coverDir = File(context.filesDir, "covers").apply { mkdirs() }
            val destCover = File(coverDir, "${track.id}_${System.currentTimeMillis()}.jpg")
            newCoverFile.copyTo(destCover, overwrite = true)
            destCover.toURI().toString()
        } else {
            null
        }

        saved to persistedCoverUri
    }

    suspend fun copyUriToTempFile(uriString: String, prefix: String, ext: String): File? =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(uriString)
            val tempFile = File(context.cacheDir, "${prefix}_${System.currentTimeMillis()}.$ext")
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: return@withContext null
                if (tempFile.length() > 0L) tempFile else {
                    tempFile.delete()
                    null
                }
            }.getOrElse {
                tempFile.delete()
                null
            }
        }

    fun extractExistingCover(track: TrackItem): File? {
        val directFile = track.localFilePath?.let(::File)?.takeIf { it.exists() }
        var tempCopy: File? = null
        val localSource = directFile ?: run {
            val uriStr = track.localUri?.takeIf { it.isNotBlank() } ?: return null
            val parsed = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
            if (parsed.scheme == "file") {
                parsed.path?.let(::File)?.takeIf { it.exists() }
            } else {
                runCatching {
                    val temp = File(context.cacheDir, "orig_audio_${track.id}.m4a")
                    context.contentResolver.openInputStream(parsed)?.use { input ->
                        FileOutputStream(temp).use { output -> input.copyTo(output) }
                    }
                    temp.takeIf { it.exists() && it.length() > 0L }?.also { tempCopy = it }
                }.getOrNull()
            }
        } ?: return null

        try {
            val jAudioBytes = runCatching {
                ensureAndroidMode()
                AudioFileIO.read(localSource).tag?.firstArtwork?.binaryData
            }.getOrNull()?.takeIf { it.isNotEmpty() }

            val artBytes = jAudioBytes ?: runCatching {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(localSource.absolutePath)
                    mmr.embeddedPicture
                } finally {
                    mmr.release()
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }

            if (artBytes != null) {
                val out = File(context.cacheDir, "extracted_cover_${track.id}.jpg")
                out.writeBytes(artBytes)
                return out
            }
            return null
        } finally {
            tempCopy?.delete()
        }
    }

    /**
     * Safely attempts to embed [coverFile] (and current track tags) into an existing local .m4a
     * without ever creating duplicate files if Scoped Storage denies write access.
     */
    suspend fun tryEmbedCoverInExistingTrack(track: TrackItem, coverFile: File): Boolean =
        withContext(Dispatchers.IO) {
            if (!coverFile.exists() || coverFile.length() == 0L) return@withContext false
            val sourceUri = track.localUri?.takeIf { it.isNotBlank() }
                ?: track.localFilePath?.let { File(it).toURI().toString() }
                ?: return@withContext false

            val tempAudio = copyUriToTempFile(sourceUri, "backfill_${track.id}", "m4a")
                ?: return@withContext false

            try {
                val metadata = EnrichedMetadata(
                    title = track.cleanTitle.ifBlank { track.title },
                    artist = track.artist,
                    album = track.album,
                    year = track.year,
                    coverUrl = track.bestCoverUrl
                )
                val tagged = applyTags(tempAudio, coverFile, metadata)
                if (!tagged || tempAudio.length() <= 0L) return@withContext false

                // Sanity-check that the resulting file still reads cleanly before overwriting
                val verified = runCatching {
                    ensureAndroidMode()
                    val check = AudioFileIO.read(tempAudio)
                    check.tag?.firstArtwork?.binaryData?.isNotEmpty() == true
                }.getOrDefault(false)
                if (!verified) return@withContext false

                val existingUri = track.localUri?.takeIf { it.startsWith("content://") }?.let(Uri::parse)
                if (existingUri != null) {
                    val written = runCatching {
                        context.contentResolver.openOutputStream(existingUri, "wt")?.use { out ->
                            FileInputStream(tempAudio).use { input -> input.copyTo(out) }
                        } != null
                    }.getOrDefault(false)
                    if (written) return@withContext true
                }

                val existingPath = track.localFilePath?.let(::File)
                if (existingPath != null && existingPath.exists() && existingPath.canWrite()) {
                    return@withContext runCatching {
                        tempAudio.copyTo(existingPath, overwrite = true)
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(existingPath.absolutePath),
                            arrayOf("audio/mp4"),
                            null
                        )
                        true
                    }.getOrDefault(false)
                }

                false
            } finally {
                tempAudio.delete()
            }
        }

    fun persistCoverFile(trackId: String, coverFile: File): String? {
        if (!coverFile.exists() || coverFile.length() == 0L) return null
        return runCatching {
            val coverDir = File(context.filesDir, "covers").apply { mkdirs() }
            val destCover = File(coverDir, "$trackId.jpg")
            coverFile.copyTo(destCover, overwrite = true)
            destCover.toURI().toString()
        }.getOrNull()
    }

    private fun applyTags(
        audioFileTarget: File,
        coverFile: File?,
        metadata: EnrichedMetadata,
        lyricsText: String? = null
    ): Boolean {
        return runCatching {
            ensureAndroidMode()
            val audioFile = AudioFileIO.read(audioFileTarget)
            val tag = audioFile.tagOrCreateAndSetDefault
            if (metadata.title.isNotBlank()) tag.setField(FieldKey.TITLE, metadata.title)
            if (metadata.artist.isNotBlank()) tag.setField(FieldKey.ARTIST, metadata.artist)
            if (metadata.album.isNotBlank()) tag.setField(FieldKey.ALBUM, metadata.album)
            if (metadata.year.isNotBlank()) tag.setField(FieldKey.YEAR, metadata.year)
            if (!lyricsText.isNullOrBlank()) tag.setField(FieldKey.LYRICS, lyricsText)

            if (coverFile != null && coverFile.exists() && coverFile.length() > 0L) {
                val artwork = buildAndroidArtwork(coverFile)
                if (artwork != null) {
                    tag.deleteArtworkField()
                    tag.setField(artwork)
                }
            }
            audioFile.commit()
            true
        }.getOrDefault(false)
    }

    /**
     * Builds JAudiotagger Artwork without calling javax.imageio.ImageIO (which crashes on Android).
     * Normalizes non-JPEG/PNG images (e.g., WebP) into JPEG so MP4 covr atoms stay universally readable.
     */
    private fun buildAndroidArtwork(coverFile: File): Artwork? {
        return runCatching {
            val rawBytes = coverFile.readBytes()
            if (rawBytes.isEmpty()) return@runCatching null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)

            val (finalBytes, mimeType) = when (bounds.outMimeType) {
                "image/jpeg" -> rawBytes to "image/jpeg"
                "image/png" -> rawBytes to "image/png"
                else -> {
                    val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                        ?: return@runCatching null
                    val baos = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, baos)
                    bitmap.recycle()
                    baos.toByteArray() to "image/jpeg"
                }
            }

            ArtworkFactory.getNew().apply {
                binaryData = finalBytes
                this.mimeType = mimeType
                pictureType = 3
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    width = bounds.outWidth
                    height = bounds.outHeight
                }
            }
        }.getOrNull()
    }

    companion object {
        fun ensureAndroidMode() {
            TagOptionSingleton.getInstance().setAndroid(true)
        }
    }

    private fun overwriteOrExportTrack(
        track: TrackItem,
        sourceFile: File,
        metadata: EnrichedMetadata
    ): SavedAudioResult {
        val existingUri = track.localUri?.takeIf { it.startsWith("content://") }?.let(Uri::parse)
        if (existingUri != null) {
            val written = runCatching {
                context.contentResolver.openOutputStream(existingUri, "wt")?.use { out ->
                    FileInputStream(sourceFile).use { input -> input.copyTo(out) }
                } != null
            }.getOrDefault(false)
            if (written) {
                return SavedAudioResult(localUri = existingUri.toString(), localFilePath = track.localFilePath)
            }
        }

        val existingPath = track.localFilePath?.let(::File)
        if (existingPath != null && existingPath.parentFile?.exists() == true) {
            sourceFile.copyTo(existingPath, overwrite = true)
            MediaScannerConnection.scanFile(
                context,
                arrayOf(existingPath.absolutePath),
                arrayOf("audio/mp4"),
                null
            )
            return SavedAudioResult(
                localUri = existingPath.toURI().toString(),
                localFilePath = existingPath.absolutePath
            )
        }

        val safeName = sanitizeFilename("${metadata.artist} - ${metadata.title}") + ".m4a"
        return exportToMusicDirectory(sourceFile, safeName, metadata)
    }

    private fun exportToMusicDirectory(
        sourceFile: File,
        displayName: String,
        metadata: EnrichedMetadata
    ): SavedAudioResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/PEGO")
                put(MediaStore.Audio.Media.TITLE, metadata.title)
                put(MediaStore.Audio.Media.ARTIST, metadata.artist)
                if (metadata.album.isNotBlank()) {
                    put(MediaStore.Audio.Media.ALBUM, metadata.album)
                }
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out)
                    }
                }
                val finishValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                resolver.update(uri, finishValues, null, null)
                return SavedAudioResult(localUri = uri.toString(), localFilePath = null)
            }
        }

        val musicRoot = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val pegoDir = File(musicRoot, "PEGO").apply { mkdirs() }
        val destFile = File(pegoDir, displayName)
        sourceFile.copyTo(destFile, overwrite = true)

        MediaScannerConnection.scanFile(
            context,
            arrayOf(destFile.absolutePath),
            arrayOf("audio/mp4"),
            null
        )
        return SavedAudioResult(
            localUri = destFile.toURI().toString(),
            localFilePath = destFile.absolutePath
        )
    }

    private fun sanitizeFilename(raw: String): String {
        val cleaned = raw
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return cleaned.ifBlank { "pego_track_${System.currentTimeMillis()}" }.take(90)
    }
}
