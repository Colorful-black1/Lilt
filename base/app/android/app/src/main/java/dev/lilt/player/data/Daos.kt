package dev.lilt.player.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {
    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE")
    fun observeSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE mediaId = :id")
    suspend fun song(id: Long): SongEntity?

    @Query("SELECT * FROM songs WHERE mediaId IN (:ids)")
    suspend fun songsById(ids: List<Long>): List<SongEntity>

    @Query("SELECT * FROM songs")
    suspend fun allSongs(): List<SongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSongs(songs: List<SongEntity>)

    @Query("DELETE FROM songs WHERE lastSeen != :token")
    suspend fun deleteStale(token: Long)

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun count(): Int

    @Query("SELECT * FROM folder_rules")
    fun observeRules(): Flow<List<FolderRuleEntity>>

    @Query("SELECT * FROM folder_rules")
    suspend fun rules(): List<FolderRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRule(rule: FolderRuleEntity)

    @Query("DELETE FROM folder_rules WHERE path = :path AND kind = :kind")
    suspend fun removeRule(path: String, kind: String)

    @Insert
    suspend fun addHistory(entry: HistoryEntity)

    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT 200")
    fun observeHistory(): Flow<List<HistoryEntity>>

    @Query("DELETE FROM history")
    suspend fun clearHistory()
}

@Dao
interface SongStateDao {
    @Query("SELECT mediaId FROM song_state WHERE favorite = 1")
    fun observeFavoriteIds(): Flow<List<Long>>

    @Query("SELECT * FROM song_state WHERE mediaId = :id")
    suspend fun state(id: Long): SongStateEntity?

    @Query("SELECT * FROM song_state")
    fun observeStates(): Flow<List<SongStateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SongStateEntity)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE mediaId = :mediaId ORDER BY positionMs ASC")
    fun observeForSong(mediaId: Long): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT 12")
    fun observe(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE query = :query COLLATE NOCASE")
    suspend fun remove(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE mediaId = :mediaId")
    fun observe(mediaId: Long): Flow<LyricsEntity?>

    @Query("SELECT * FROM lyrics WHERE mediaId = :mediaId")
    suspend fun get(mediaId: Long): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: LyricsEntity)

    @Query("DELETE FROM lyrics WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: Long)
}

@Dao
interface PlaylistDao {
    @Query(
        """
        SELECT p.id, p.name, p.createdAt,
               (SELECT COUNT(*) FROM playlist_songs ps WHERE ps.playlistId = p.id) AS songCount
        FROM playlists p
        ORDER BY p.name COLLATE NOCASE
        """
    )
    fun observePlaylists(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun playlist(id: Long): PlaylistEntity?

    @Query(
        """
        SELECT s.* FROM songs s
        INNER JOIN playlist_songs ps ON ps.mediaId = s.mediaId
        WHERE ps.playlistId = :id
        ORDER BY ps.position
        """
    )
    fun observePlaylistSongs(id: Long): Flow<List<SongEntity>>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :id")
    suspend fun clearPlaylistSongs(id: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_songs WHERE playlistId = :id")
    suspend fun nextPosition(id: Long): Int

    @Query("SELECT mediaId FROM playlist_songs WHERE playlistId = :id ORDER BY position")
    suspend fun playlistMediaIds(id: Long): List<Long>

    @Query("UPDATE playlist_songs SET position = :position WHERE playlistId = :id AND mediaId = :mediaId")
    suspend fun setSongPosition(id: Long, mediaId: Long, position: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSong(entry: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :id AND mediaId = :mediaId")
    suspend fun removeSong(id: Long, mediaId: Long)

    @Query(
        """
        SELECT s.* FROM songs s
        INNER JOIN playlist_songs ps ON ps.mediaId = s.mediaId
        WHERE ps.playlistId = :id
        ORDER BY ps.position
        """
    )
    suspend fun playlistSongs(id: Long): List<SongEntity>
}

@Dao
interface QueueDao {
    @Query(
        """
        SELECT q.id, q.name, q.createdAt,
               (SELECT COUNT(*) FROM queue_songs qs WHERE qs.queueId = q.id) AS songCount,
               q.lastIndex, q.lastPositionMs
        FROM queues q
        ORDER BY q.createdAt DESC
        """
    )
    fun observeQueues(): Flow<List<QueueSummary>>

    @Query("SELECT * FROM queues WHERE id = :id")
    suspend fun queue(id: Long): QueueEntity?

    @Query("SELECT * FROM queues WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun queueByName(name: String): QueueEntity?

    @Query("SELECT * FROM queues ORDER BY createdAt ASC LIMIT 1")
    suspend fun oldestQueue(): QueueEntity?

    @Query("SELECT COUNT(*) FROM queues")
    suspend fun count(): Int

    @Insert
    suspend fun insertQueue(queue: QueueEntity): Long

    @Query("UPDATE queues SET name = :name WHERE id = :id")
    suspend fun renameQueue(id: Long, name: String)

    @Query("UPDATE queues SET lastIndex = :index, lastPositionMs = :positionMs WHERE id = :id")
    suspend fun setQueuePosition(id: Long, index: Int, positionMs: Long)

    @Query("DELETE FROM queues WHERE id = :id")
    suspend fun deleteQueue(id: Long)

    @Query("DELETE FROM queue_songs WHERE queueId = :id")
    suspend fun clearQueueSongs(id: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM queue_songs WHERE queueId = :id")
    suspend fun nextPosition(id: Long): Int

    @Query("SELECT mediaId FROM queue_songs WHERE queueId = :id ORDER BY position")
    suspend fun queueMediaIds(id: Long): List<Long>

    @Query("UPDATE queue_songs SET position = :position WHERE queueId = :id AND mediaId = :mediaId")
    suspend fun setSongPosition(id: Long, mediaId: Long, position: Int)

    @Query("DELETE FROM queue_songs WHERE queueId = :id AND mediaId = :mediaId")
    suspend fun removeSong(id: Long, mediaId: Long)

    @Query("SELECT COUNT(*) FROM queue_songs WHERE queueId = :id")
    suspend fun songCount(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addQueueSong(entry: QueueSongEntity)

    @Query(
        """
        SELECT s.* FROM songs s
        INNER JOIN queue_songs qs ON qs.mediaId = s.mediaId
        WHERE qs.queueId = :id
        ORDER BY qs.position
        """
    )
    fun observeQueueSongs(id: Long): Flow<List<SongEntity>>

    @Query(
        """
        SELECT s.* FROM songs s
        INNER JOIN queue_songs qs ON qs.mediaId = s.mediaId
        WHERE qs.queueId = :id
        ORDER BY qs.position
        """
    )
    suspend fun queueSongs(id: Long): List<SongEntity>

    @Transaction
    suspend fun createQueue(
        name: String,
        mediaIds: List<Long>,
        merge: Boolean,
        limit: Int = 20,
        lastIndex: Int = 0,
        lastPositionMs: Long = 0,
    ): Long {
        val existing = queueByName(name)
        val queueId: Long
        if (existing != null && !merge) {
            deleteQueueSongs(existing.id)
            queueId = existing.id
        } else if (existing != null) {
            queueId = existing.id
        } else {
            queueId = insertQueue(QueueEntity(name = name, createdAt = System.currentTimeMillis()))
        }
        var position = nextPosition(queueId)
        mediaIds.forEach { id ->
            addQueueSong(QueueSongEntity(queueId, id, position))
            position += 1
        }
        setQueuePosition(
            queueId,
            lastIndex.coerceAtLeast(0),
            lastPositionMs.coerceAtLeast(0),
        )
        while (count() > limit) {
            val oldest = oldestQueue() ?: break
            deleteQueueSongs(oldest.id)
            deleteQueue(oldest.id)
        }
        return queueId
    }

    @Transaction
    suspend fun deleteQueueSongs(id: Long) {
        clearQueueSongs(id)
    }
}
