package dev.lilt.player.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.room.withTransaction
import dev.lilt.player.library.LyricsSource
import dev.lilt.player.library.clampLyricsOffset
import dev.lilt.player.library.movedItem
import dev.lilt.player.library.resolveScannedTags
import dev.lilt.player.library.withTagOverride
import dev.lilt.player.library.withTagOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(val seen: Int) : ScanState
    data class Done(val count: Int, val at: Long) : ScanState
    data class Error(val message: String) : ScanState
}

class MusicRepository(
    private val context: Context,
    private val db: LiltDatabase,
    private val settings: SettingsStore,
) {
    private val music = db.musicDao()
    private val songStates = db.songStateDao()
    private val tagOverrides = db.tagOverrideDao()
    private val bookmarks = db.bookmarkDao()
    private val searches = db.searchHistoryDao()
    private val lyrics = db.lyricsDao()
    private val lyricsFetch = db.lyricsFetchDao()
    private val playlists = db.playlistDao()
    private val queues = db.queueDao()

    val scanState = MutableStateFlow<ScanState>(ScanState.Idle)

    fun observeSongs(): Flow<List<SongEntity>> =
        combine(
            music.observeSongs(),
            tagOverrides.observeAll(),
            music.observeRules(),
            settings.includeOnly,
        ) { songs, overrides, rules, only ->
            filterSongs(songs.withTagOverrides(overrides.associateBy { it.mediaId }), rules, only)
        }

    fun observeAllSongs(): Flow<List<SongEntity>> =
        combine(music.observeSongs(), tagOverrides.observeAll()) { songs, overrides ->
            songs.withTagOverrides(overrides.associateBy { it.mediaId })
        }

    fun observeTagOverride(mediaId: Long): Flow<TagOverrideEntity?> =
        tagOverrides.observe(mediaId)

    fun observeRules(): Flow<List<FolderRuleEntity>> = music.observeRules()
    fun observeFavoriteIds(): Flow<Set<Long>> = songStates.observeFavoriteIds().map { it.toSet() }
    fun observeSongStates(): Flow<Map<Long, SongStateEntity>> =
        songStates.observeStates().map { states -> states.associateBy { it.mediaId } }
    fun observePlaylists(): Flow<List<PlaylistSummary>> = playlists.observePlaylists()
    fun observePlaylistSongs(id: Long): Flow<List<SongEntity>> =
        combine(playlists.observePlaylistSongs(id), tagOverrides.observeAll()) { songs, overrides ->
            songs.withTagOverrides(overrides.associateBy { it.mediaId })
        }
    fun observeQueues(): Flow<List<QueueSummary>> = queues.observeQueues()
    fun observeQueueSongs(id: Long): Flow<List<SongEntity>> =
        combine(queues.observeQueueSongs(id), tagOverrides.observeAll()) { songs, overrides ->
            songs.withTagOverrides(overrides.associateBy { it.mediaId })
        }
    fun observeHistory(): Flow<List<HistoryEntity>> = music.observeHistory()
    fun observeBookmarks(): Flow<List<BookmarkEntity>> = bookmarks.observeAll()
    fun observeBookmarksForSong(mediaId: Long): Flow<List<BookmarkEntity>> =
        bookmarks.observeForSong(mediaId)
    fun observeSearchHistory(): Flow<List<SearchHistoryEntity>> = searches.observe()
    fun observeLyrics(mediaId: Long): Flow<LyricsEntity?> = lyrics.observe(mediaId)
    fun observeLyricsFetch(mediaId: Long): Flow<LyricsFetchEntity?> =
        lyricsFetch.observe(mediaId)

    suspend fun song(id: Long): SongEntity? = music.song(id)

    suspend fun songWithTags(id: Long): SongEntity? = withContext(Dispatchers.IO) {
        music.song(id)?.withTagOverride(tagOverrides.get(id))
    }

    /** Queue restore needs the same tag overrides as the visible library list. */
    suspend fun songsWithTags(ids: List<Long>): List<SongEntity> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val songs = music.songsById(ids)
        val overrides = tagOverrides.all().associateBy { it.mediaId }
        songs.withTagOverrides(overrides)
    }

    suspend fun saveTagOverride(
        mediaId: Long,
        title: String,
        artist: String,
        album: String,
        year: Int,
        track: Int,
    ) = withContext(Dispatchers.IO) {
        tagOverrides.upsert(
            TagOverrideEntity(
                mediaId = mediaId,
                title = title.trim(),
                artist = artist.trim(),
                album = album.trim(),
                year = year.coerceAtLeast(0),
                track = track.coerceAtLeast(0),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteTagOverride(mediaId: Long) = withContext(Dispatchers.IO) {
        tagOverrides.delete(mediaId)
    }

    /** Orphaned MediaStore rows plus the playlists, queues and user data that point at them. */
    suspend fun missingReferences(): List<MissingReference> = withContext(Dispatchers.IO) {
        buildMissingReferences(
            missingSongs = music.allMissing(),
            currentSongIds = music.allSongs().mapTo(mutableSetOf()) { it.mediaId },
            playlists = playlists.allPlaylists(),
            playlistSongs = playlists.allPlaylistSongs(),
            queues = queues.allQueues(),
            queueSongs = queues.allQueueSongs(),
            states = songStates.all(),
            lyrics = lyrics.all(),
            bookmarks = bookmarks.all(),
            tags = tagOverrides.all(),
        )
    }

    /**
     * Re-points every user-owned reference from an old MediaStore id to a song
     * that exists now. Conflicts keep the existing row for the new id, then the
     * stale row is removed. Runs in one transaction so a failure cannot leave a
     * half-relinked library.
     */
    suspend fun relinkMissing(oldId: Long, newId: Long) = withContext(Dispatchers.IO) {
        if (oldId == newId) return@withContext
        db.withTransaction {
            playlists.remapSong(oldId, newId)
            playlists.deleteSongReferences(oldId)
            queues.remapSong(oldId, newId)
            queues.deleteSongReferences(oldId)
            songStates.remap(oldId, newId)
            songStates.deleteReferences(oldId)
            lyrics.remap(oldId, newId)
            lyrics.deleteReferences(oldId)
            lyricsFetch.remap(oldId, newId)
            lyricsFetch.deleteReferences(oldId)
            tagOverrides.remap(oldId, newId)
            tagOverrides.deleteReferences(oldId)
            bookmarks.remap(oldId, newId)
            bookmarks.deleteReferences(oldId)
            music.remapHistory(oldId, newId)
            music.deleteMissing(oldId)
        }
    }

    suspend fun scan() = withContext(Dispatchers.IO) {
        if (scanState.value is ScanState.Scanning) return@withContext
        scanState.value = ScanState.Scanning(0)
        try {
            val token = System.currentTimeMillis()
            val found = queryMediaStore(token)
            found.chunked(250).forEach { chunk ->
                music.upsertSongs(chunk)
                music.deleteMissing(chunk.map { it.mediaId })
                scanState.value = ScanState.Scanning(
                    (scanState.value as? ScanState.Scanning)?.seen?.plus(chunk.size) ?: chunk.size
                )
            }
            val stale = music.staleSongs(token)
            if (stale.isNotEmpty()) {
                music.upsertMissingSongs(
                    stale.map { song ->
                        MissingSongEntity(
                            mediaId = song.mediaId,
                            title = song.title,
                            artist = song.artist,
                            album = song.album,
                            folder = song.folder,
                            displayName = song.displayName,
                            durationMs = song.durationMs,
                            lastSeen = song.lastSeen,
                        )
                    },
                )
            }
            music.deleteStale(token)
            scanState.value = ScanState.Done(found.size, token)
        } catch (error: SecurityException) {
            scanState.value = ScanState.Error("Media permission is needed to scan.")
        } catch (error: Exception) {
            scanState.value = ScanState.Error(error.message ?: "The scan could not finish.")
        }
    }

    private fun queryMediaStore(token: Long): List<SongEntity> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection += MediaStore.Audio.Media.RELATIVE_PATH
        } else {
            projection += MediaStore.Audio.Media.DATA
        }
        val supported = setOf("mp3", "flac", "ogg", "opus", "wav", "m4a", "aac", "m4b")
        val rows = mutableListOf<SongEntity>()
        context.contentResolver.query(
            collection,
            projection.toTypedArray(),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val folderIndex = cursor.getColumnIndex(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    MediaStore.Audio.Media.RELATIVE_PATH
                else MediaStore.Audio.Media.DATA
            )
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameIndex) ?: ""
                val extension = displayName.substringAfterLast('.', "").lowercase()
                if (extension.isNotEmpty() && extension !in supported) continue
                val rawTitle = cursor.getString(titleIndex)
                val resolved = resolveScannedTags(
                    displayName = displayName,
                    rawTitle = rawTitle,
                    rawArtist = cursor.getString(artistIndex),
                    rawTrack = cursor.getInt(trackIndex),
                )
                val folder = folderFrom(cursor.getString(folderIndex), displayName)
                rows += SongEntity(
                    mediaId = cursor.getLong(idIndex),
                    title = resolved.title,
                    artist = resolved.artist,
                    album = cleanUnknown(cursor.getString(albumIndex), "Unknown album"),
                    albumId = cursor.getLong(albumIdIndex),
                    folder = folder,
                    displayName = displayName,
                    durationMs = cursor.getLong(durationIndex).coerceAtLeast(0),
                    track = resolved.track,
                    year = cursor.getInt(yearIndex),
                    mime = cursor.getString(mimeIndex) ?: "",
                    dateAdded = cursor.getLong(addedIndex),
                    size = cursor.getLong(sizeIndex),
                    lastSeen = token,
                )
            }
        }
        return rows
    }

    private fun folderFrom(raw: String?, displayName: String): String {
        if (raw.isNullOrBlank()) return "Music"
        val path = raw.replace('\\', '/')
        val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            path.trim('/')
        } else if (path.endsWith(displayName)) {
            path.substringBeforeLast('/', "")
        } else {
            path.trim('/')
        }
        val cleaned = folder
            .removePrefix("storage/emulated/0/")
            .removePrefix("sdcard/")
            .trim('/')
        return if (cleaned.isBlank()) "Music" else cleaned
    }

    private fun cleanUnknown(value: String?, fallback: String): String {
        if (value.isNullOrBlank() || value == MediaStore.UNKNOWN_STRING) return fallback
        return value
    }

    suspend fun setRule(path: String, kind: String, on: Boolean) = withContext(Dispatchers.IO) {
        if (on) music.putRule(FolderRuleEntity(path, kind)) else music.removeRule(path, kind)
    }

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        playlists.insertPlaylist(PlaylistEntity(name = name.trim(), createdAt = System.currentTimeMillis()))
    }

    suspend fun renamePlaylist(id: Long, name: String) = withContext(Dispatchers.IO) {
        playlists.renamePlaylist(id, name.trim())
    }

    suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            playlists.clearPlaylistSongs(id)
            playlists.deletePlaylist(id)
        }
    }

    suspend fun addToPlaylist(playlistId: Long, mediaId: Long) = withContext(Dispatchers.IO) {
        val position = playlists.nextPosition(playlistId)
        playlists.addSong(PlaylistSongEntity(playlistId, mediaId, position))
    }

    suspend fun addToPlaylist(playlistId: Long, mediaIds: Collection<Long>) =
        withContext(Dispatchers.IO) {
            if (mediaIds.isEmpty()) return@withContext
            db.withTransaction {
                var position = playlists.nextPosition(playlistId)
                mediaIds.forEach { id ->
                    playlists.addSong(PlaylistSongEntity(playlistId, id, position))
                    position += 1
                }
            }
        }

    suspend fun removeFromPlaylist(playlistId: Long, mediaId: Long) = withContext(Dispatchers.IO) {
        playlists.removeSong(playlistId, mediaId)
    }

    suspend fun removeFromPlaylist(playlistId: Long, mediaIds: Collection<Long>) =
        withContext(Dispatchers.IO) {
            db.withTransaction {
                mediaIds.forEach { playlists.removeSong(playlistId, it) }
            }
        }

    suspend fun playlistSongs(id: Long): List<SongEntity> = withContext(Dispatchers.IO) {
        val overrides = tagOverrides.all().associateBy { it.mediaId }
        playlists.playlistSongs(id).withTagOverrides(overrides)
    }

    suspend fun movePlaylistSong(playlistId: Long, from: Int, to: Int) =
        withContext(Dispatchers.IO) {
            db.withTransaction {
                val ids = playlists.playlistMediaIds(playlistId)
                val reordered = movedItem(ids, from, to)
                if (reordered === ids) return@withTransaction
                reordered.forEachIndexed { index, mediaId ->
                    playlists.setSongPosition(playlistId, mediaId, index)
                }
            }
        }

    suspend fun createQueue(
        name: String,
        mediaIds: List<Long>,
        lastIndex: Int = 0,
        lastPositionMs: Long = 0,
    ): Long = withContext(Dispatchers.IO) {
        val cleaned = name.trim().ifBlank { "Queue" }
        val merge = settings.queueConflict.value == "merge"
        queues.createQueue(
            cleaned,
            mediaIds,
            merge,
            lastIndex = lastIndex,
            lastPositionMs = lastPositionMs,
        )
    }

    suspend fun queue(id: Long): QueueEntity? = withContext(Dispatchers.IO) {
        queues.queue(id)
    }

    suspend fun saveQueuePosition(id: Long, index: Int, positionMs: Long) =
        withContext(Dispatchers.IO) {
            if (id <= 0) return@withContext
            queues.setQueuePosition(
                id,
                index.coerceAtLeast(0),
                positionMs.coerceAtLeast(0),
            )
        }

    suspend fun renameQueue(id: Long, name: String) = withContext(Dispatchers.IO) {
        queues.renameQueue(id, name.trim())
    }

    suspend fun deleteQueue(id: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            queues.clearQueueSongs(id)
            queues.deleteQueue(id)
        }
        if (settings.activeQueueId.value == id) settings.setActiveQueue(-1)
    }

    suspend fun queueSongs(id: Long): List<SongEntity> = withContext(Dispatchers.IO) {
        val overrides = tagOverrides.all().associateBy { it.mediaId }
        queues.queueSongs(id).withTagOverrides(overrides)
    }

    suspend fun moveQueueSong(queueId: Long, from: Int, to: Int) =
        withContext(Dispatchers.IO) {
            db.withTransaction {
                val ids = queues.queueMediaIds(queueId)
                val reordered = movedItem(ids, from, to)
                if (reordered === ids) return@withTransaction
                reordered.forEachIndexed { index, mediaId ->
                    queues.setSongPosition(queueId, mediaId, index)
                }
            }
        }

    suspend fun removeQueueSong(queueId: Long, mediaId: Long) = withContext(Dispatchers.IO) {
        queues.removeSong(queueId, mediaId)
    }

    suspend fun recordHistory(mediaId: Long) = withContext(Dispatchers.IO) {
        music.addHistory(HistoryEntity(mediaId = mediaId, playedAt = System.currentTimeMillis()))
    }

    suspend fun recordPlayed(mediaId: Long) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val current = songStates.state(mediaId)
            val next = (current ?: SongStateEntity(mediaId = mediaId)).copy(
                playCount = (current?.playCount ?: 0) + 1,
                lastPlayedAt = now,
            )
            songStates.upsert(next)
        }
    }

    suspend fun toggleFavorite(mediaId: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val current = songStates.state(mediaId)
            val next = (current ?: SongStateEntity(mediaId = mediaId)).copy(
                favorite = !(current?.favorite ?: false),
            )
            songStates.upsert(next)
        }
    }

    suspend fun setFavorite(mediaIds: Collection<Long>, favorite: Boolean) =
        withContext(Dispatchers.IO) {
            if (mediaIds.isEmpty()) return@withContext
            db.withTransaction {
                mediaIds.forEach { mediaId ->
                    val current = songStates.state(mediaId)
                    val next = (current ?: SongStateEntity(mediaId = mediaId)).copy(
                        favorite = favorite,
                    )
                    songStates.upsert(next)
                }
            }
        }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        music.clearHistory()
    }

    suspend fun addBookmark(mediaId: Long, positionMs: Long, note: String) =
        withContext(Dispatchers.IO) {
            bookmarks.upsert(
                BookmarkEntity(
                    mediaId = mediaId,
                    positionMs = positionMs.coerceAtLeast(0),
                    note = note.trim(),
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }

    suspend fun deleteBookmark(id: Long) = withContext(Dispatchers.IO) {
        bookmarks.delete(id)
    }

    suspend fun recordSearch(query: String) = withContext(Dispatchers.IO) {
        val cleaned = query.trim()
        if (cleaned.isEmpty()) return@withContext
        searches.remove(cleaned)
        searches.put(SearchHistoryEntity(cleaned, System.currentTimeMillis()))
    }

    suspend fun clearSearchHistory() = withContext(Dispatchers.IO) {
        searches.clear()
    }

    suspend fun saveLyrics(
        mediaId: Long,
        text: String,
        offsetMs: Int = 0,
    ) = withContext(Dispatchers.IO) {
        if (mediaId <= 0) return@withContext
        if (text.isBlank()) {
            lyrics.delete(mediaId)
            val state = lyricsFetch.get(mediaId)
            lyricsFetch.put(
                (state ?: LyricsFetchEntity(
                    mediaId = mediaId,
                    status = LyricsFetchStatus.IDLE,
                    lastAttemptAt = 0L,
                    nextRetryAt = 0L,
                )).copy(
                    status = LyricsFetchStatus.IDLE,
                    nextRetryAt = 0L,
                    error = "",
                    autoDisabled = true,
                ),
            )
        } else {
            lyrics.put(
                LyricsEntity(
                    mediaId = mediaId,
                    text = text.trim(),
                    offsetMs = clampLyricsOffset(offsetMs),
                    source = LyricsSource.USER.wire,
                    fetchedAt = 0L,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            lyricsFetch.delete(mediaId)
        }
    }

    suspend fun saveFetchedLyrics(
        mediaId: Long,
        text: String,
        source: LyricsSource,
        offsetMs: Int = 0,
    ) = withContext(Dispatchers.IO) {
        if (mediaId <= 0 || text.isBlank()) return@withContext
        val now = System.currentTimeMillis()
        val existing = lyrics.get(mediaId)
        lyrics.put(
            LyricsEntity(
                mediaId = mediaId,
                text = text.trim(),
                offsetMs = clampLyricsOffset(existing?.offsetMs ?: offsetMs),
                source = source.wire,
                fetchedAt = now,
                updatedAt = now,
            ),
        )
        lyricsFetch.delete(mediaId)
    }

    suspend fun setLyricsOffset(mediaId: Long, offsetMs: Int) = withContext(Dispatchers.IO) {
        if (mediaId <= 0) return@withContext
        lyrics.updateOffset(
            mediaId = mediaId,
            offsetMs = clampLyricsOffset(offsetMs),
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun deleteLyrics(mediaId: Long) = withContext(Dispatchers.IO) {
        lyrics.delete(mediaId)
        val state = lyricsFetch.get(mediaId)
        lyricsFetch.put(
            (state ?: LyricsFetchEntity(
                mediaId = mediaId,
                status = LyricsFetchStatus.IDLE,
                lastAttemptAt = 0L,
                nextRetryAt = 0L,
            )).copy(
                status = LyricsFetchStatus.IDLE,
                nextRetryAt = 0L,
                error = "",
                autoDisabled = true,
            ),
        )
    }

    suspend fun lyrics(mediaId: Long): LyricsEntity? = withContext(Dispatchers.IO) {
        lyrics.get(mediaId)
    }

    suspend fun lyricsFetchState(mediaId: Long): LyricsFetchEntity? =
        withContext(Dispatchers.IO) { lyricsFetch.get(mediaId) }

    suspend fun clearLyricsFetchState(mediaId: Long) = withContext(Dispatchers.IO) {
        lyricsFetch.delete(mediaId)
    }

    suspend fun setLyricsAutoFetch(mediaId: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        val state = lyricsFetch.get(mediaId)
        if (state != null) {
            lyricsFetch.put(
                state.copy(
                    autoDisabled = !enabled,
                    nextRetryAt = 0L,
                ),
            )
        } else if (!enabled) {
            lyricsFetch.put(
                LyricsFetchEntity(
                    mediaId = mediaId,
                    status = LyricsFetchStatus.IDLE,
                    lastAttemptAt = 0L,
                    nextRetryAt = 0L,
                    autoDisabled = true,
                ),
            )
        }
    }

    suspend fun resolveTargets(targets: List<String>): List<SongEntity> = withContext(Dispatchers.IO) {
        val resolved = mutableListOf<SongEntity>()
        var byName: Map<String, SongEntity>? = null
        targets.forEach { raw ->
            val target = raw.trim()
            if (target.isEmpty() || target.startsWith("#")) return@forEach
            val id = when {
                target.startsWith("content://media/external/audio/media/") ->
                    target.substringAfterLast('/').toLongOrNull()
                target.startsWith("lilt:") -> target.removePrefix("lilt:").toLongOrNull()
                else -> null
            }
            if (id != null) {
                music.song(id)?.let { resolved += it }
            } else {
                if (byName == null) {
                    byName = music.allSongs().associateBy { it.displayName.lowercase() }
                }
                byName?.get(target.replace('\\', '/').substringAfterLast('/').lowercase())?.let { resolved += it }
            }
        }
        resolved
    }

    companion object {
        fun filterSongs(
            songs: List<SongEntity>,
            rules: List<FolderRuleEntity>,
            onlyIncluded: Boolean,
        ): List<SongEntity> {
            val excluded = rules.filter { it.kind == "exclude" }.map { it.path }
            val included = rules.filter { it.kind == "include" }.map { it.path }
            fun inside(folder: String, rule: String): Boolean =
                folder.equals(rule, ignoreCase = true) ||
                    folder.startsWith("$rule/", ignoreCase = true)
            return songs.filter { song ->
                val isExcluded = excluded.any { inside(song.folder, it) }
                val isIncluded = included.any { inside(song.folder, it) }
                val includeOk = !onlyIncluded || isIncluded
                includeOk && !isExcluded
            }
        }

        fun albumArtUri(albumId: Long): Uri? =
            if (albumId <= 0) null
            else ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                albumId,
            )
    }
}
