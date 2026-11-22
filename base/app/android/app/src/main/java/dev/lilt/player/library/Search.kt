package dev.lilt.player.library

import dev.lilt.player.data.PlaylistSummary
import dev.lilt.player.data.SongEntity
import java.util.Locale

data class FolderHit(val path: String, val songs: List<SongEntity>) {
    val name: String get() = path.substringAfterLast('/').ifBlank { path }
}

data class SearchResults(
    val songs: List<SongEntity> = emptyList(),
    val albums: List<AlbumGroup> = emptyList(),
    val artists: List<ArtistGroup> = emptyList(),
    val folders: List<FolderHit> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
) {
    val isEmpty: Boolean
        get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty() &&
            folders.isEmpty() && playlists.isEmpty()

    val total: Int
        get() = songs.size + albums.size + artists.size + folders.size + playlists.size
}

/**
 * Offline search across the five local groups. Matching is case-insensitive
 * substring matching; blank queries return an empty result.
 */
fun searchLibrary(
    songs: List<SongEntity>,
    playlists: List<PlaylistSummary>,
    query: String,
): SearchResults {
    val needle = query.trim().lowercase(Locale.ROOT)
    if (needle.isEmpty()) return SearchResults()

    fun matches(value: String): Boolean = value.lowercase(Locale.ROOT).contains(needle)

    val songHits = songs.filter {
        matches(it.title) || matches(it.artist) || matches(it.album) || matches(it.folder)
    }
    val albumHits = LibraryGroups.albums(songs).filter {
        matches(it.name) || matches(it.artist)
    }
    val artistHits = LibraryGroups.artists(songs).filter { matches(it.name) }
    val folderHits = songs.filter { matches(it.folder) }
        .groupBy { it.folder }
        .map { (path, tracks) -> FolderHit(path, tracks) }
        .sortedBy { it.path.lowercase(Locale.ROOT) }
    val playlistHits = playlists.filter { matches(it.name) }

    return SearchResults(
        songs = songHits.sortedBy { it.title.lowercase(Locale.ROOT) },
        albums = albumHits,
        artists = artistHits,
        folders = folderHits,
        playlists = playlistHits,
    )
}
