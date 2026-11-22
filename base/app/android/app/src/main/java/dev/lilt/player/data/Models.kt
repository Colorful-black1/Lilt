package dev.lilt.player.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "songs", indices = [Index("folder"), Index("albumId")])
data class SongEntity(
    @PrimaryKey val mediaId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val folder: String,
    val displayName: String,
    val durationMs: Long,
    val track: Int,
    val year: Int,
    val mime: String,
    val dateAdded: Long,
    val size: Long,
    val lastSeen: Long,
) {
    val contentUri: String
        get() = "content://media/external/audio/media/$mediaId"

    val artworkUri: String?
        get() = if (albumId > 0) "content://media/external/audio/albumart/$albumId" else null
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(tableName = "playlist_songs", primaryKeys = ["playlistId", "mediaId"])
data class PlaylistSongEntity(
    val playlistId: Long,
    val mediaId: Long,
    val position: Int,
)

@Entity(tableName = "queues")
data class QueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val lastIndex: Int = 0,
    @ColumnInfo(defaultValue = "0") val lastPositionMs: Long = 0,
)

@Entity(tableName = "queue_songs", primaryKeys = ["queueId", "mediaId"])
data class QueueSongEntity(
    val queueId: Long,
    val mediaId: Long,
    val position: Int,
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val playedAt: Long,
)

/**
 * User-owned state for a scanned song. Kept separate from [SongEntity] because
 * MediaStore scans replace the songs table; favourites and play counts must not
 * be wiped by a rescan.
 */
@Entity(tableName = "song_state")
data class SongStateEntity(
    @PrimaryKey val mediaId: Long,
    val favorite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0,
)

@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["mediaId", "positionMs"], unique = true)],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val positionMs: Long,
    val note: String,
    val createdAt: Long,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long,
)

/** User-entered or imported LRC/plain lyrics. Kept apart from scan data. */
@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val mediaId: Long,
    val text: String,
    val updatedAt: Long,
)

@Entity(tableName = "folder_rules")
data class FolderRuleEntity(
    @PrimaryKey val path: String,
    val kind: String,
)

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val songCount: Int,
)

data class QueueSummary(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val songCount: Int,
    val lastIndex: Int = 0,
    val lastPositionMs: Long = 0,
)
