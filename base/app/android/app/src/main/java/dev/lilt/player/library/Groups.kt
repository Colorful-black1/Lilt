package dev.lilt.player.library

import dev.lilt.player.data.SongEntity
import java.util.Locale

const val UNKNOWN_ALBUM = "Unknown album"
const val UNKNOWN_ARTIST = "Unknown artist"

data class AlbumGroup(
    val name: String,
    val artist: String,
    val albumId: Long,
    val songs: List<SongEntity>,
) {
    val trackCount: Int get() = songs.size
    val durationMs: Long get() = songs.sumOf { it.durationMs }
    val coverAlbumId: Long get() = albumId
}

data class ArtistGroup(
    val name: String,
    val songs: List<SongEntity>,
) {
    val trackCount: Int get() = songs.size
    val durationMs: Long get() = songs.sumOf { it.durationMs }
    val albumCount: Int
        get() = songs.map { it.album.ifBlank { UNKNOWN_ALBUM } }.distinct().size
    val coverAlbumId: Long
        get() = songs.firstOrNull { it.albumId > 0 }?.albumId ?: -1L
}

object LibraryGroups {
    fun albums(songs: List<SongEntity>): List<AlbumGroup> =
        songs.groupBy { it.album.ifBlank { UNKNOWN_ALBUM } }
            .map { (name, tracks) ->
                val ordered = tracks.sortedWith(
                    compareBy<SongEntity> { if (it.track > 0) it.track else Int.MAX_VALUE }
                        .thenBy { it.title.lowercase(Locale.ROOT) },
                )
                AlbumGroup(
                    name = name,
                    artist = ordered.firstOrNull()?.artist?.ifBlank { UNKNOWN_ARTIST }
                        ?: UNKNOWN_ARTIST,
                    albumId = ordered.firstOrNull { it.albumId > 0 }?.albumId ?: -1L,
                    songs = ordered,
                )
            }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

    fun artists(songs: List<SongEntity>): List<ArtistGroup> =
        songs.groupBy { it.artist.ifBlank { UNKNOWN_ARTIST } }
            .map { (name, tracks) ->
                ArtistGroup(
                    name = name,
                    songs = tracks.sortedBy { it.title.lowercase(Locale.ROOT) },
                )
            }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
}
