package dev.lilt.player.data

import android.content.Context
import androidx.room.withTransaction
import dev.lilt.player.library.EQ_BANDS
import dev.lilt.player.library.EqProfile
import dev.lilt.player.library.PreampMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RestoreResult(
    val summary: RestoreSummary,
    val eq: BackupEq?,
)

/**
 * Collects and applies a local backup. Scanned songs are never written: the
 * backup only stores user data, and references are resolved against the rows
 * that are currently in the songs table.
 */
class BackupManager(private val context: Context) {
    suspend fun export(settings: SettingsStore): String = withContext(Dispatchers.IO) {
        val db = LiltDatabase.get(context)
        val songs = db.musicDao().allSongs()
        val playlists = db.playlistDao().allPlaylists()
        val playlistSongs = db.playlistDao().allPlaylistSongs()
            .groupBy { it.playlistId }
        val queues = db.queueDao().allQueues()
        val queueSongs = db.queueDao().allQueueSongs().groupBy { it.queueId }
        val activeQueue = settings.activeQueueId.value
        val activeIndex = queues.indexOfFirst { it.id == activeQueue }
        val backup = LiltBackup(
            created = System.currentTimeMillis(),
            songs = songs.map { song ->
                BackupSongRef(
                    mediaId = song.mediaId,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    folder = song.folder,
                    displayName = song.displayName,
                    durationMs = song.durationMs,
                )
            },
            playlists = playlists.map { playlist ->
                BackupPlaylist(
                    name = playlist.name,
                    createdAt = playlist.createdAt,
                    songs = playlistSongs[playlist.id]
                        .orEmpty()
                        .sortedBy { it.position }
                        .map { it.mediaId },
                )
            },
            queues = queues.map { queue ->
                BackupQueue(
                    name = queue.name,
                    createdAt = queue.createdAt,
                    lastIndex = queue.lastIndex,
                    lastPositionMs = queue.lastPositionMs,
                    songs = queueSongs[queue.id]
                        .orEmpty()
                        .sortedBy { it.position }
                        .map { it.mediaId },
                )
            },
            activeQueueIndex = activeIndex,
            songStates = db.songStateDao().all().map { state ->
                BackupSongState(
                    mediaId = state.mediaId,
                    favorite = state.favorite,
                    playCount = state.playCount,
                    lastPlayedAt = state.lastPlayedAt,
                )
            },
            history = db.musicDao().allHistory().map { entry ->
                BackupHistory(entry.mediaId, entry.playedAt)
            },
            bookmarks = db.bookmarkDao().all().map { bookmark ->
                BackupBookmark(
                    mediaId = bookmark.mediaId,
                    positionMs = bookmark.positionMs,
                    note = bookmark.note,
                    createdAt = bookmark.createdAt,
                )
            },
            lyrics = db.lyricsDao().all().map { lyric ->
                BackupLyrics(
                    mediaId = lyric.mediaId,
                    text = lyric.text,
                    offsetMs = lyric.offsetMs,
                    updatedAt = lyric.updatedAt,
                    source = lyric.source,
                    fetchedAt = lyric.fetchedAt,
                )
            },
            tags = db.tagOverrideDao().all().map { tag ->
                BackupTag(
                    mediaId = tag.mediaId,
                    title = tag.title,
                    artist = tag.artist,
                    album = tag.album,
                    year = tag.year,
                    track = tag.track,
                    updatedAt = tag.updatedAt,
                )
            },
            rules = db.musicDao().rules().map { rule ->
                BackupRule(rule.path, rule.kind)
            },
            searches = db.searchHistoryDao().all().map { search ->
                BackupSearch(search.query, search.searchedAt)
            },
            settings = settings.snapshotForBackup(),
            eq = readEq(),
        )
        backup.encode()
    }

    fun preview(text: String): BackupPreview = LiltBackup.decode(text).preview()

    suspend fun restore(backup: LiltBackup, settings: SettingsStore): RestoreResult =
        withContext(Dispatchers.IO) {
            val db = LiltDatabase.get(context)
            val currentSongs = db.musicDao().allSongs()
            val resolved = resolveBackupSongIds(backup, currentSongs)
            var activeQueueId = -1L

            db.withTransaction {
                db.playlistDao().clearAllPlaylistSongs()
                db.playlistDao().clearAllPlaylists()
                backup.playlists.forEach { playlist ->
                    val id = db.playlistDao().insertPlaylist(
                        PlaylistEntity(
                            name = playlist.name,
                            createdAt = playlist.createdAt.takeIf { it > 0L }
                                ?: System.currentTimeMillis(),
                        ),
                    )
                    val mediaIds = playlist.songs
                        .mapNotNull(resolved::get)
                        .distinct()
                    if (mediaIds.isNotEmpty()) {
                        db.playlistDao().addSongs(
                            mediaIds.mapIndexed { index, mediaId ->
                                PlaylistSongEntity(id, mediaId, index)
                            },
                        )
                    }
                }

                db.queueDao().clearAllQueueSongs()
                db.queueDao().clearAllQueues()
                val queueIds = mutableListOf<Long>()
                backup.queues.forEach { queue ->
                    val mediaIds = queue.songs
                        .mapNotNull(resolved::get)
                        .distinct()
                    val id = db.queueDao().insertQueue(
                        QueueEntity(
                            name = queue.name,
                            createdAt = queue.createdAt.takeIf { it > 0L }
                                ?: System.currentTimeMillis(),
                            lastIndex = queue.lastIndex.coerceIn(
                                0,
                                (mediaIds.size - 1).coerceAtLeast(0),
                            ),
                            lastPositionMs = queue.lastPositionMs,
                        ),
                    )
                    queueIds += id
                    if (mediaIds.isNotEmpty()) {
                        db.queueDao().addQueueSongs(
                            mediaIds.mapIndexed { index, mediaId ->
                                QueueSongEntity(id, mediaId, index)
                            },
                        )
                    }
                }
                activeQueueId = queueIds.getOrNull(backup.activeQueueIndex)
                    ?: queueIds.firstOrNull()
                    ?: -1L

                db.songStateDao().clear()
                db.songStateDao().upsertAll(
                    backup.songStates.mapNotNull { state ->
                        resolved[state.mediaId]?.let { mediaId ->
                            SongStateEntity(
                                mediaId = mediaId,
                                favorite = state.favorite,
                                playCount = state.playCount,
                                lastPlayedAt = state.lastPlayedAt,
                            )
                        }
                    }.distinctBy { it.mediaId },
                )

                db.musicDao().clearHistory()
                val history = backup.history.mapNotNull { entry ->
                    resolved[entry.mediaId]?.let { mediaId ->
                        HistoryEntity(mediaId = mediaId, playedAt = entry.playedAt)
                    }
                }
                if (history.isNotEmpty()) db.musicDao().addHistory(history)

                db.bookmarkDao().clear()
                val bookmarks = backup.bookmarks.mapNotNull { bookmark ->
                    resolved[bookmark.mediaId]?.let { mediaId ->
                        BookmarkEntity(
                            mediaId = mediaId,
                            positionMs = bookmark.positionMs,
                            note = bookmark.note,
                            createdAt = bookmark.createdAt,
                        )
                    }
                }.distinctBy { it.mediaId to it.positionMs }
                if (bookmarks.isNotEmpty()) db.bookmarkDao().insertAll(bookmarks)

                db.lyricsDao().clear()
                db.lyricsFetchDao().clear()
                val lyrics = backup.lyrics.mapNotNull { lyric ->
                    resolved[lyric.mediaId]?.let { mediaId ->
                        LyricsEntity(
                            mediaId = mediaId,
                            text = lyric.text,
                            offsetMs = lyric.offsetMs,
                            source = lyric.source,
                            fetchedAt = lyric.fetchedAt,
                            updatedAt = lyric.updatedAt,
                        )
                    }
                }.distinctBy { it.mediaId }
                if (lyrics.isNotEmpty()) db.lyricsDao().putAll(lyrics)

                db.tagOverrideDao().clear()
                val tags = backup.tags.mapNotNull { tag ->
                    resolved[tag.mediaId]?.let { mediaId ->
                        TagOverrideEntity(
                            mediaId = mediaId,
                            title = tag.title,
                            artist = tag.artist,
                            album = tag.album,
                            year = tag.year,
                            track = tag.track,
                            updatedAt = tag.updatedAt,
                        )
                    }
                }.distinctBy { it.mediaId }
                if (tags.isNotEmpty()) db.tagOverrideDao().upsertAll(tags)

                db.musicDao().clearRules()
                backup.rules.forEach { rule ->
                    db.musicDao().putRule(FolderRuleEntity(rule.path, rule.kind))
                }

                db.searchHistoryDao().clear()
                backup.searches.forEach { search ->
                    db.searchHistoryDao().put(
                        SearchHistoryEntity(search.query, search.searchedAt),
                    )
                }
            }

            settings.restoreFromBackup(backup.settings)
            settings.setActiveQueue(activeQueueId)
            writeEq(backup.eq)

            RestoreResult(
                summary = RestoreSummary(
                    playlists = backup.playlists.size,
                    queues = backup.queues.size,
                    songs = resolved.size,
                    favorites = backup.songStates.count { it.favorite },
                    lyrics = backup.lyrics.size,
                    bookmarks = backup.bookmarks.size,
                    unresolved = unresolvedCount(backup, resolved),
                ),
                eq = backup.eq,
            )
        }

    private fun readEq(): BackupEq {
        val prefs = context.getSharedPreferences("lilt_effects", Context.MODE_PRIVATE)
        val storedGains = runCatching { prefs.getString("gains", null) }.getOrNull()
        val gains = storedGains
            ?.split(',')
            ?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == EQ_BANDS }
            ?: List(EQ_BANDS) { 0f }
        return BackupEq(
            enabled = prefs.getBoolean("enabled", true),
            gains = gains,
            preampMode = prefs.getString("preamp_mode", "off") ?: "off",
            headroomDb = prefs.getFloat("headroom_db", 6f),
            boostDb = prefs.getFloat("boost_db", 2f),
            bass = prefs.getFloat("bass", 0f),
            virtualizer = prefs.getFloat("virtualizer", 0f),
            balance = prefs.getFloat("balance", 100f),
            preset = prefs.getString("preset", "Flat") ?: "Flat",
            perDevice = prefs.getBoolean("per_device", true),
        )
    }

    private fun writeEq(eq: BackupEq?) {
        if (eq == null) return
        context.getSharedPreferences("lilt_effects", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", eq.enabled)
            .putString("gains", eq.gains.joinToString(","))
            .putString("preamp_mode", eq.preampMode)
            .putFloat("headroom_db", eq.headroomDb)
            .putFloat("boost_db", eq.boostDb)
            .putFloat("bass", eq.bass)
            .putFloat("virtualizer", eq.virtualizer)
            .putFloat("balance", eq.balance)
            .putString("preset", eq.preset)
            .putBoolean("per_device", eq.perDevice)
            .apply()
    }
}

fun BackupEq.toProfile(): EqProfile = EqProfile(
    enabled = enabled,
    gains = gains,
    preampMode = PreampMode.fromId(preampMode),
    headroomDb = headroomDb,
    boostDb = boostDb,
    bass = bass,
    virtualizer = virtualizer,
    balance = balance,
    preset = preset,
).cleaned()
