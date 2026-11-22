package com.pego.music.domain.model

enum class DownloadStatus {
    NONE,
    QUEUED,
    DOWNLOADING,
    TAGGING,
    COMPLETED,
    FAILED
}

data class TrackItem(
    val id: String,
    val title: String,
    val cleanTitle: String,
    val artist: String,
    val album: String = "",
    val year: String = "",
    val durationSeconds: Long = 0L,
    val thumbnailUrl: String = "",
    val hdCoverUrl: String = "",
    val sourceUrl: String,
    val localFilePath: String? = null,
    val localUri: String? = null,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
    val downloadStatus: DownloadStatus = DownloadStatus.NONE,
    val downloadProgress: Int = 0
) {
    val bestCoverUrl: String
        get() = hdCoverUrl.ifBlank { thumbnailUrl }

    val isDownloaded: Boolean
        get() = downloadStatus == DownloadStatus.COMPLETED && (!localUri.isNullOrBlank() || !localFilePath.isNullOrBlank())
}

data class PlaylistPreview(
    val id: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String,
    val sourceUrl: String,
    val tracks: List<TrackItem>
)

data class LocalPlaylist(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val sourceUrl: String?,
    val createdAt: Long,
    val tracks: List<TrackItem> = emptyList()
)

data class EnrichedMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val year: String,
    val coverUrl: String
)

data class LyricsResult(
    val plainLyrics: String?,
    val syncedLyrics: String?
)
