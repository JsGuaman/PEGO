package com.pego.music.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.pego.music.domain.model.DownloadStatus
import com.pego.music.domain.model.LocalPlaylist
import com.pego.music.domain.model.TrackItem

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val cleanTitle: String,
    val artist: String,
    val album: String,
    val year: String,
    val durationSeconds: Long,
    val thumbnailUrl: String,
    val hdCoverUrl: String,
    val sourceUrl: String,
    val localFilePath: String? = null,
    val localUri: String? = null,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
    val downloadStatus: String = DownloadStatus.NONE.name,
    val downloadProgress: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): TrackItem = TrackItem(
        id = id,
        title = title,
        cleanTitle = cleanTitle,
        artist = artist,
        album = album,
        year = year,
        durationSeconds = durationSeconds,
        thumbnailUrl = thumbnailUrl,
        hdCoverUrl = hdCoverUrl,
        sourceUrl = sourceUrl,
        localFilePath = localFilePath,
        localUri = localUri,
        plainLyrics = plainLyrics,
        syncedLyrics = syncedLyrics,
        downloadStatus = runCatching { DownloadStatus.valueOf(downloadStatus) }
            .getOrDefault(DownloadStatus.NONE),
        downloadProgress = downloadProgress
    )

    companion object {
        fun fromDomain(track: TrackItem): TrackEntity = TrackEntity(
            id = track.id,
            title = track.title,
            cleanTitle = track.cleanTitle,
            artist = track.artist,
            album = track.album,
            year = track.year,
            durationSeconds = track.durationSeconds,
            thumbnailUrl = track.thumbnailUrl,
            hdCoverUrl = track.hdCoverUrl,
            sourceUrl = track.sourceUrl,
            localFilePath = track.localFilePath,
            localUri = track.localUri,
            plainLyrics = track.plainLyrics,
            syncedLyrics = track.syncedLyrics,
            downloadStatus = track.downloadStatus.name,
            downloadProgress = track.downloadProgress
        )
    }
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val coverUrl: String,
    val sourceUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    indices = [Index(value = ["trackId"])]
)
data class PlaylistTrackCrossRef(
    val playlistId: Long,
    val trackId: String,
    val position: Int = 0
)

data class PlaylistWithTracks(
    @Embedded val playlist: PlaylistEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = PlaylistTrackCrossRef::class,
            parentColumn = "playlistId",
            entityColumn = "trackId"
        )
    )
    val tracks: List<TrackEntity>
) {
    fun toDomain(): LocalPlaylist = LocalPlaylist(
        id = playlist.id,
        name = playlist.name,
        coverUrl = playlist.coverUrl,
        sourceUrl = playlist.sourceUrl,
        createdAt = playlist.createdAt,
        tracks = tracks.map { it.toDomain() }
    )
}
