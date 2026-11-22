package dev.lilt.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lilt.player.LiltViewModel
import dev.lilt.player.PlayerUiState
import dev.lilt.player.data.SongEntity
import dev.lilt.player.library.AlbumGroup
import dev.lilt.player.library.ArtistGroup
import dev.lilt.player.library.LibraryGroups
import java.util.Locale

enum class LibrarySection {
    Songs,
    Albums,
    Artists,
    Favorites,
}

@Composable
internal fun songCountLabel(count: Int): String =
    if (count == 1) t("1 song", "1 首歌曲") else t("$count songs", "$count 首歌曲")

@Composable
internal fun trackCountLabel(count: Int): String =
    if (count == 1) t("1 track", "1 首") else t("$count tracks", "$count 首")

@Composable
internal fun albumCountLabel(count: Int): String =
    if (count == 1) t("1 album", "1 张专辑") else t("$count albums", "$count 张专辑")

@Composable
internal fun artistCountLabel(count: Int): String =
    if (count == 1) t("1 artist", "1 位艺术家") else t("$count artists", "$count 位艺术家")

@Composable
fun LibraryBrowser(
    vm: LiltViewModel,
    songs: List<SongEntity>,
    player: PlayerUiState,
    favoriteIds: Set<Long>,
    onOpenPlayer: () -> Unit,
    onOpenAlbum: (String, Long) -> Unit,
    onOpenArtist: (String) -> Unit,
    onSongAction: (SongEntity, SongAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sectionName by rememberSaveable { mutableStateOf(LibrarySection.Songs.name) }
    val section = LibrarySection.valueOf(sectionName)
    val albums = remember(songs) { LibraryGroups.albums(songs) }
    val artists = remember(songs) { LibraryGroups.artists(songs) }
    val favorites = remember(songs, favoriteIds) {
        songs.filter { it.mediaId in favoriteIds }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibrarySection.entries.forEach { entry ->
                ChoicePill(
                    label = when (entry) {
                        LibrarySection.Songs -> t("Songs", "歌曲")
                        LibrarySection.Albums -> t("Albums", "专辑")
                        LibrarySection.Artists -> t("Artists", "艺术家")
                        LibrarySection.Favorites -> t("Favorites", "收藏")
                    },
                    selected = section == entry,
                    onClick = { sectionName = entry.name },
                )
            }
        }

        when (section) {
            LibrarySection.Songs -> SongLibraryList(
                songs = songs,
                player = player,
                favoriteIds = favoriteIds,
                emptyTitle = t("No songs yet", "还没有歌曲"),
                emptyBody = t("Scan your device, then songs appear here.", "扫描设备后，歌曲会显示在这里。"),
                onPlay = { list, index ->
                    vm.playSongs(list, index)
                    onOpenPlayer()
                },
                onToggleFavorite = vm::toggleFavorite,
                onSongAction = onSongAction,
            )

            LibrarySection.Favorites -> SongLibraryList(
                songs = favorites,
                player = player,
                favoriteIds = favoriteIds,
                emptyTitle = t("No favorites yet", "还没有收藏"),
                emptyBody = t("Tap the heart beside a song to save it here.", "点击歌曲旁的心形即可收藏到这里。"),
                onPlay = { list, index ->
                    vm.playSongs(list, index)
                    onOpenPlayer()
                },
                onToggleFavorite = vm::toggleFavorite,
                onSongAction = onSongAction,
            )

            LibrarySection.Albums -> AlbumLibraryList(
                albums = albums,
                player = player,
                onOpenAlbum = onOpenAlbum,
                onPlay = { album ->
                    vm.playSongs(album.songs, 0)
                    onOpenPlayer()
                },
            )

            LibrarySection.Artists -> ArtistLibraryList(
                artists = artists,
                player = player,
                onOpenArtist = onOpenArtist,
                onPlay = { artist ->
                    vm.playSongs(artist.songs, 0)
                    onOpenPlayer()
                },
            )
        }
    }
}

@Composable
private fun SongLibraryList(
    songs: List<SongEntity>,
    player: PlayerUiState,
    favoriteIds: Set<Long>,
    emptyTitle: String,
    emptyBody: String,
    onPlay: (List<SongEntity>, Int) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onSongAction: (SongEntity, SongAction) -> Unit,
) {
    if (songs.isEmpty()) {
        EmptyState(title = emptyTitle, body = emptyBody)
        return
    }
    val ordered = remember(songs) { songs.sortedBy { it.title.lowercase(Locale.ROOT) } }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                songCountLabel(ordered.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
        items(ordered, key = { it.mediaId }) { song ->
            SongRow(
                song = song,
                isCurrent = song.mediaId == player.mediaId,
                isFavorite = song.mediaId in favoriteIds,
                onToggleFavorite = { onToggleFavorite(song.mediaId) },
                onClick = { onPlay(ordered, ordered.indexOf(song)) },
                onAction = { onSongAction(song, it) },
            )
        }
    }
}

@Composable
private fun AlbumLibraryList(
    albums: List<AlbumGroup>,
    player: PlayerUiState,
    onOpenAlbum: (String, Long) -> Unit,
    onPlay: (AlbumGroup) -> Unit,
) {
    if (albums.isEmpty()) {
        EmptyState(
            title = t("No albums yet", "还没有专辑"),
            body = t("Albums are grouped from the tags in your files.", "专辑会根据文件标签自动分组。"),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                albumCountLabel(albums.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
        items(albums, key = { "${it.name}|${it.albumId}" }) { album ->
            AlbumRow(
                album = album,
                isCurrent = album.songs.any { it.mediaId == player.mediaId },
                onClick = { onOpenAlbum(album.name, album.albumId) },
                onPlay = { onPlay(album) },
            )
        }
    }
}

@Composable
private fun ArtistLibraryList(
    artists: List<ArtistGroup>,
    player: PlayerUiState,
    onOpenArtist: (String) -> Unit,
    onPlay: (ArtistGroup) -> Unit,
) {
    if (artists.isEmpty()) {
        EmptyState(
            title = t("No artists yet", "还没有艺术家"),
            body = t("Artist names come from the tags in your files.", "艺术家名称来自文件标签。"),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                artistCountLabel(artists.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
        items(artists, key = { it.name }) { artist ->
            ArtistRow(
                artist = artist,
                isCurrent = artist.songs.any { it.mediaId == player.mediaId },
                onClick = { onOpenArtist(artist.name) },
                onPlay = { onPlay(artist) },
            )
        }
    }
}

@Composable
fun AlbumRow(
    album: AlbumGroup,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            albumId = album.coverAlbumId,
            modifier = Modifier.size(52.dp),
            corner = 10.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                album.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                listOf(
                    album.artist,
                    trackCountLabel(album.trackCount),
                    formatDuration(album.durationMs),
                ).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onPlay) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = t("Play album", "播放专辑"))
        }
    }
}

@Composable
fun ArtistRow(
    artist: ArtistGroup,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(artBrush(artist.name.hashCode().toLong())),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initials(artist.name),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                artist.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${trackCountLabel(artist.trackCount)} · ${albumCountLabel(artist.albumCount)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onPlay) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = t("Play artist", "播放艺术家"))
        }
    }
}

internal fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return "♪"
    return parts.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
}
