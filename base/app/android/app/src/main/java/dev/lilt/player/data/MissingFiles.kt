package dev.lilt.player.data

/** A track that vanished from MediaStore plus every place that still points at it. */
data class MissingReference(
    val song: MissingSongEntity,
    val playlists: List<String>,
    val queues: List<String>,
    val favorite: Boolean,
    val plays: Int,
    val hasLyrics: Boolean,
    val bookmarkCount: Int,
    val hasTagOverride: Boolean,
) {
    val hasUserData: Boolean
        get() = favorite || plays > 0 || hasLyrics || bookmarkCount > 0 || hasTagOverride

    val referenceCount: Int
        get() = playlists.size + queues.size
}

/**
 * Groups the orphaned rows into one entry per formerly scanned song. Entries
 * whose media id is present again are skipped: MediaStore reused the id and the
 * file already resolves through the normal songs table.
 */
fun buildMissingReferences(
    missingSongs: List<MissingSongEntity>,
    currentSongIds: Set<Long>,
    playlists: List<PlaylistEntity>,
    playlistSongs: List<PlaylistSongEntity>,
    queues: List<QueueEntity>,
    queueSongs: List<QueueSongEntity>,
    states: List<SongStateEntity>,
    lyrics: List<LyricsEntity>,
    bookmarks: List<BookmarkEntity>,
    tags: List<TagOverrideEntity>,
): List<MissingReference> {
    val playlistNames = playlists.associate { it.id to it.name }
    val queueNames = queues.associate { it.id to it.name }
    val playlistRefs = playlistSongs.groupBy { it.mediaId }.mapValues { (_, rows) ->
        rows.mapNotNull { playlistNames[it.playlistId] }.distinct().sorted()
    }
    val queueRefs = queueSongs.groupBy { it.mediaId }.mapValues { (_, rows) ->
        rows.mapNotNull { queueNames[it.queueId] }.distinct().sorted()
    }
    val stateById = states.associateBy { it.mediaId }
    val lyricIds = lyrics.mapTo(mutableSetOf()) { it.mediaId }
    val bookmarkCounts = bookmarks.groupingBy { it.mediaId }.eachCount()
    val tagIds = tags.mapTo(mutableSetOf()) { it.mediaId }

    return missingSongs
        .asSequence()
        .filter { it.mediaId !in currentSongIds }
        .map { song ->
            val state = stateById[song.mediaId]
            MissingReference(
                song = song,
                playlists = playlistRefs[song.mediaId].orEmpty(),
                queues = queueRefs[song.mediaId].orEmpty(),
                favorite = state?.favorite == true,
                plays = state?.playCount ?: 0,
                hasLyrics = song.mediaId in lyricIds,
                bookmarkCount = bookmarkCounts[song.mediaId] ?: 0,
                hasTagOverride = song.mediaId in tagIds,
            )
        }
        .sortedWith(
            compareBy(
                { it.song.title.lowercase() },
                { it.song.artist.lowercase() },
                { it.song.mediaId },
            ),
        )
        .toList()
}
