package dev.lilt.player.library

import dev.lilt.player.data.SongEntity
import dev.lilt.player.data.SongStateEntity
import java.util.Locale

/** Sort keys shared by the library list and the persisted preference. */
enum class SongSort(val id: String) {
    Title("title"),
    Artist("artist"),
    Album("album"),
    Duration("duration"),
    DateAdded("date_added"),
    PlayCount("play_count"),
    LastPlayed("last_played"),
    Track("track"),
    FileName("file_name"),
    ;

    companion object {
        fun fromId(id: String): SongSort = entries.firstOrNull { it.id == id } ?: Title
    }
}

private fun text(value: String): String = value.lowercase(Locale.ROOT)

/**
 * Returns a new list ordered by [sort]. Ties always fall back to the title and
 * media id so repeated sorts and rescans keep a predictable order.
 */
fun sortSongs(
    songs: List<SongEntity>,
    states: Map<Long, SongStateEntity>,
    sort: SongSort,
    descending: Boolean,
): List<SongEntity> {
    val comparator = when (sort) {
        SongSort.Title -> compareBy<SongEntity> { text(it.title) }
            .thenBy { text(it.artist) }
            .thenBy { text(it.album) }
        SongSort.Artist -> compareBy<SongEntity> { text(it.artist) }
            .thenBy { text(it.album) }
            .thenBy { if (it.track > 0) it.track else Int.MAX_VALUE }
            .thenBy { text(it.title) }
        SongSort.Album -> compareBy<SongEntity> { text(it.album) }
            .thenBy { if (it.track > 0) it.track else Int.MAX_VALUE }
            .thenBy { text(it.title) }
        SongSort.Duration -> compareBy<SongEntity> { it.durationMs }
        SongSort.DateAdded -> compareBy<SongEntity> { it.dateAdded }
        SongSort.PlayCount -> compareBy<SongEntity> { states[it.mediaId]?.playCount ?: 0 }
        SongSort.LastPlayed -> compareBy<SongEntity> { states[it.mediaId]?.lastPlayedAt ?: 0L }
        SongSort.Track -> compareBy<SongEntity> { if (it.track > 0) it.track else Int.MAX_VALUE }
            .thenBy { text(it.album) }
            .thenBy { text(it.title) }
        SongSort.FileName -> compareBy<SongEntity> { text(it.displayName) }
    }.thenBy { text(it.title) }
        .thenBy { it.mediaId }

    return if (descending) songs.sortedWith(comparator.reversed()) else songs.sortedWith(comparator)
}

/** First-grapheme bucket used by the A-Z index strip. Non-Latin titles use '#'. */
fun letterOf(value: String): Char {
    val first = value.trim().firstOrNull()?.uppercaseChar() ?: return '#'
    return if (first in 'A'..'Z') first else '#'
}

fun letterForSong(song: SongEntity): Char = letterOf(song.title)
