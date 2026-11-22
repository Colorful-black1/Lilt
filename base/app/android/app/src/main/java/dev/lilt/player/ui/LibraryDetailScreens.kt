package dev.lilt.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lilt.player.LiltViewModel
import dev.lilt.player.data.SongEntity
import dev.lilt.player.library.AlbumGroup
import dev.lilt.player.library.ArtistGroup
import dev.lilt.player.library.LibraryGroups

@Composable
fun AlbumDetailScreen(
    vm: LiltViewModel,
    albumName: String,
    albumId: Long,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val album = remember(songs, albumName, albumId) {
        LibraryGroups.albums(songs).firstOrNull {
            it.name == albumName && (albumId <= 0 || it.albumId == albumId)
        }
    }
    var songForPlaylist by remember { mutableStateOf<SongEntity?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        topBar = { AppBar(title = album?.name ?: t("Album", "专辑"), onBack = onBack) },
        bottomBar = {
            MiniPlayer(
                state = player,
                onOpen = onOpenPlayer,
                onToggle = vm::togglePlayPause,
                onNext = vm::next,
            )
        },
    ) { inner ->
        if (album == null) {
            Column(Modifier.fillMaxSize().padding(inner)) {
                EmptyState(
                    title = t("Album not found", "找不到专辑"),
                    body = t("The library changed or the file is hidden by a folder rule.", "曲库已变化，或文件被文件夹规则隐藏了。"),
                )
            }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(inner)) {
            item {
                AlbumHeader(
                    album = album,
                    onPlay = {
                        vm.playSongs(album.songs, 0)
                        onOpenPlayer()
                    },
                    onShuffle = {
                        vm.playSongs(album.songs.shuffled(), 0)
                        onOpenPlayer()
                    },
                )
            }
            items(album.songs, key = { it.mediaId }) { song ->
                SongRow(
                    song = song,
                    isCurrent = song.mediaId == player.mediaId,
                    isFavorite = song.mediaId in favoriteIds,
                    onToggleFavorite = { vm.toggleFavorite(song.mediaId) },
                    onClick = {
                        vm.playSongs(album.songs, album.songs.indexOf(song))
                        onOpenPlayer()
                    },
                    onAction = { action ->
                        when (action) {
                            SongAction.PlayNext -> vm.enqueueNext(song)
                            SongAction.AddToQueue -> vm.addToQueue(song)
                            SongAction.AddToPlaylist -> songForPlaylist = song
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(18.dp)) }
        }
    }

    AddToPlaylistDialog(vm, songForPlaylist) { songForPlaylist = null }
}

@Composable
fun ArtistDetailScreen(
    vm: LiltViewModel,
    artistName: String,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val artist = remember(songs, artistName) {
        LibraryGroups.artists(songs).firstOrNull { it.name == artistName }
    }
    var songForPlaylist by remember { mutableStateOf<SongEntity?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        topBar = { AppBar(title = artist?.name ?: t("Artist", "艺术家"), onBack = onBack) },
        bottomBar = {
            MiniPlayer(
                state = player,
                onOpen = onOpenPlayer,
                onToggle = vm::togglePlayPause,
                onNext = vm::next,
            )
        },
    ) { inner ->
        if (artist == null) {
            Column(Modifier.fillMaxSize().padding(inner)) {
                EmptyState(
                    title = t("Artist not found", "找不到艺术家"),
                    body = t("The library changed or the file is hidden by a folder rule.", "曲库已变化，或文件被文件夹规则隐藏了。"),
                )
            }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(inner)) {
            item {
                ArtistHeader(
                    artist = artist,
                    onPlay = {
                        vm.playSongs(artist.songs, 0)
                        onOpenPlayer()
                    },
                    onShuffle = {
                        vm.playSongs(artist.songs.shuffled(), 0)
                        onOpenPlayer()
                    },
                )
            }
            items(artist.songs, key = { it.mediaId }) { song ->
                SongRow(
                    song = song,
                    isCurrent = song.mediaId == player.mediaId,
                    isFavorite = song.mediaId in favoriteIds,
                    onToggleFavorite = { vm.toggleFavorite(song.mediaId) },
                    onClick = {
                        vm.playSongs(artist.songs, artist.songs.indexOf(song))
                        onOpenPlayer()
                    },
                    onAction = { action ->
                        when (action) {
                            SongAction.PlayNext -> vm.enqueueNext(song)
                            SongAction.AddToQueue -> vm.addToQueue(song)
                            SongAction.AddToPlaylist -> songForPlaylist = song
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(18.dp)) }
        }
    }

    AddToPlaylistDialog(vm, songForPlaylist) { songForPlaylist = null }
}

@Composable
private fun AlbumHeader(
    album: AlbumGroup,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(
                albumId = album.coverAlbumId,
                modifier = Modifier.size(96.dp),
                corner = 14.dp,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    album.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    album.artist,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${trackCountLabel(album.trackCount)} · ${formatDuration(album.durationMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onPlay) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("Play all", "全部播放"))
            }
            OutlinedButton(onClick = onShuffle) {
                Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("Shuffle", "随机"))
            }
        }
    }
}

@Composable
private fun ArtistHeader(
    artist: ArtistGroup,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(artBrush(artist.name.hashCode().toLong())),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    initials(artist.name),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    artist.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${trackCountLabel(artist.trackCount)} · ${albumCountLabel(artist.albumCount)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onPlay) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("Play all", "全部播放"))
            }
            OutlinedButton(onClick = onShuffle) {
                Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("Shuffle", "随机"))
            }
        }
    }
}

@Composable
private fun AddToPlaylistDialog(
    vm: LiltViewModel,
    song: SongEntity?,
    onDismiss: () -> Unit,
) {
    if (song == null) return
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Add to playlist", "加入播放列表")) },
        text = {
            Column {
                if (playlists.isEmpty()) {
                    Text(t("Create a playlist first.", "请先新建一个播放列表。"))
                }
                playlists.forEach { playlist ->
                    TextButton(
                        onClick = {
                            vm.addToPlaylist(playlist.id, song)
                            onDismiss()
                        },
                    ) {
                        Text(playlist.name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(t("Close", "关闭")) }
        },
    )
}
