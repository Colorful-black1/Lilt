package dev.lilt.player.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
    private val playlists = db.playlistDao()
    private val queues = db.queueDao()

    val scanState = MutableStateFlow<ScanState>(ScanState.Idle)

    fun observeSongs(): Flow<List<SongEntity>> =
        combine(music.observeSongs(), music.observeRules(), settings.includeOnly) { songs, rules, only ->
            filterSongs(songs, rules, only)
        }

    fun observeAllSongs(): Flow<List<SongEntity>> = music.observeSongs()

    fun observeRules(): Flow<List<FolderRuleEntity>> = music.observeRules()
    fun observePlaylists(): Flow<List<PlaylistSummary>> = playlists.observePlaylists()
    fun observePlaylistSongs(id: Long): Flow<List<SongEntity>> = playlists.observePlaylistSongs(id)
    fun observeQueues(): Flow<List<QueueSummary>> = queues.observeQueues()
    fun observeQueueSongs(id: Long): Flow<List<SongEntity>> = queues.observeQueueSongs(id)
    fun observeHistory(): Flow<List<HistoryEntity>> = music.observeHistory()

    suspend fun song(id: Long): SongEntity? = music.song(id)

    suspend fun scan() = withContext(Dispatchers.IO) {
        if (scanState.value is ScanState.Scanning) return@withContext
        scanState.value = ScanState.Scanning(0)
        try {
            val token = System.currentTimeMillis()
            val found = queryMediaStore(token)
            found.chunked(250).forEach { chunk ->
                music.upsertSongs(chunk)
                scanState.value = ScanState.Scanning(
                    (scanState.value as? ScanState.Scanning)?.seen?.plus(chunk.size) ?: chunk.size
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
                    ?: displayName.substringBeforeLast('.').ifBlank { "Untitled" }
                val folder = folderFrom(cursor.getString(folderIndex), displayName)
                rows += SongEntity(
                    mediaId = cursor.getLong(idIndex),
                    title = rawTitle.ifBlank { "Untitled" },
                    artist = cleanUnknown(cursor.getString(artistIndex), "Unknown artist"),
                    album = cleanUnknown(cursor.getString(albumIndex), "Unknown album"),
                    albumId = cursor.getLong(albumIdIndex),
                    folder = folder,
                    displayName = displayName,
                    durationMs = cursor.getLong(durationIndex).coerceAtLeast(0),
                    track = cursor.getInt(trackIndex),
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

    suspend fun removeFromPlaylist(playlistId: Long, mediaId: Long) = withContext(Dispatchers.IO) {
        playlists.removeSong(playlistId, mediaId)
    }

    suspend fun playlistSongs(id: Long): List<SongEntity> = withContext(Dispatchers.IO) {
        playlists.playlistSongs(id)
    }

    suspend fun createQueue(name: String, mediaIds: List<Long>): Long = withContext(Dispatchers.IO) {
        val cleaned = name.trim().ifBlank { "Queue" }
        val merge = settings.queueConflict.value == "merge"
        val id = queues.createQueue(cleaned, mediaIds, merge)
        settings.setActiveQueue(id)
        id
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
        queues.queueSongs(id)
    }

    suspend fun recordPlayed(mediaId: Long) = withContext(Dispatchers.IO) {
        music.addHistory(HistoryEntity(mediaId = mediaId, playedAt = System.currentTimeMillis()))
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
