package dev.lilt.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lilt.player.LiltViewModel
import dev.lilt.player.data.SongEntity
import dev.lilt.player.library.SearchResults
import dev.lilt.player.library.searchLibrary

@Composable
fun SearchScreen(
    vm: LiltViewModel,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenAlbum: (String, Long) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val searchHistory by vm.searchHistory.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(songs, playlists, query) {
        searchLibrary(songs, playlists, query)
    }

    fun record() {
        if (query.isNotBlank()) vm.recordSearch(query)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        topBar = { AppBar(title = t("Search", "搜索"), onBack = onBack) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text(t("Songs, albums, artists, folders", "歌曲、专辑、艺术家、文件夹")) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = t("Clear", "清空"))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { record() }),
            )

            if (query.isBlank()) {
                if (searchHistory.isEmpty()) {
                    EmptyState(
                        title = t("Type to search", "输入以搜索"),
                        body = t(
                            "Results cover songs, albums, artists, folders and playlists on this phone.",
                            "搜索范围包括本机的歌曲、专辑、艺术家、文件夹和播放列表。",
                        ),
                    )
                } else {
                    SearchHistory(
                        queries = searchHistory.map { it.query },
                        onPick = { query = it },
                        onClear = vm::clearSearchHistory,
                    )
                }
                return@Column
            }

            if (results.isEmpty) {
                EmptyState(
                    title = t("No matches", "没有匹配项"),
                    body = t("Try a shorter word or a different spelling.", "试试更短的关键词或不同拼写。"),
                )
                return@Column
            }

            SearchResultList(
                results = results,
                query = query.trim(),
                player = player,
                favoriteIds = favoriteIds,
                onPlay = { list, index ->
                    record()
                    vm.playSongs(list, index)
                    onOpenPlayer()
                },
                onToggleFavorite = vm::toggleFavorite,
                onSongAction = { song, action ->
                    when (action) {
                        SongAction.PlayNext -> vm.enqueueNext(song)
                        SongAction.AddToQueue -> vm.addToQueue(song)
                        SongAction.AddToPlaylist -> Unit
                    }
                },
                onOpenPlaylist = {
                    record()
                    onOpenPlaylist(it)
                },
                onOpenAlbum = { name, id ->
                    record()
                    onOpenAlbum(name, id)
                },
                onOpenArtist = {
                    record()
                    onOpenArtist(it)
                },
                record = { record() },
            )
        }
    }
}

@Composable
private fun SearchHistory(
    queries: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                t("Recent searches", "最近搜索"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = onClear) {
                Text(t("Clear", "清空"))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            queries.forEach { value ->
                ChoicePill(
                    label = value,
                    selected = false,
                    onClick = { onPick(value) },
                )
            }
        }
    }
}

@Composable
private fun SearchResultList(
    results: SearchResults,
    query: String,
    player: dev.lilt.player.PlayerUiState,
    favoriteIds: Set<Long>,
    onPlay: (List<SongEntity>, Int) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onSongAction: (SongEntity, SongAction) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenAlbum: (String, Long) -> Unit,
    onOpenArtist: (String) -> Unit,
    record: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (results.songs.isNotEmpty()) {
            item { ResultHeader(t("Songs", "歌曲"), results.songs.size) }
            items(results.songs, key = { "s${it.mediaId}" }) { song ->
                SongRow(
                    song = song,
                    isCurrent = song.mediaId == player.mediaId,
                    isFavorite = song.mediaId in favoriteIds,
                    onToggleFavorite = { onToggleFavorite(song.mediaId) },
                    onClick = { onPlay(results.songs, results.songs.indexOf(song)) },
                    onAction = { onSongAction(song, it) },
                    highlight = query,
                )
            }
        }
        if (results.albums.isNotEmpty()) {
            item { ResultHeader(t("Albums", "专辑"), results.albums.size) }
            items(results.albums, key = { "a${it.name}|${it.albumId}" }) { album ->
                AlbumRow(
                    album = album,
                    isCurrent = album.songs.any { it.mediaId == player.mediaId },
                    onClick = {
                        record()
                        onOpenAlbum(album.name, album.albumId)
                    },
                    onPlay = {
                        record()
                        onPlay(album.songs, 0)
                    },
                )
            }
        }
        if (results.artists.isNotEmpty()) {
            item { ResultHeader(t("Artists", "艺术家"), results.artists.size) }
            items(results.artists, key = { "r${it.name}" }) { artist ->
                ArtistRow(
                    artist = artist,
                    isCurrent = artist.songs.any { it.mediaId == player.mediaId },
                    onClick = {
                        record()
                        onOpenArtist(artist.name)
                    },
                    onPlay = {
                        record()
                        onPlay(artist.songs, 0)
                    },
                )
            }
        }
        if (results.folders.isNotEmpty()) {
            item { ResultHeader(t("Folders", "文件夹"), results.folders.size) }
            items(results.folders, key = { "f${it.path}" }) { folder ->
                FolderRow(
                    name = folder.name,
                    trackCount = folder.songs.size,
                    durationMs = totalDuration(folder.songs),
                    seed = folder.path.hashCode().toLong(),
                    onClick = {
                        record()
                        onPlay(folder.songs, 0)
                    },
                    onPlay = {
                        record()
                        onPlay(folder.songs, 0)
                    },
                )
            }
        }
        if (results.playlists.isNotEmpty()) {
            item { ResultHeader(t("Playlists", "播放列表"), results.playlists.size) }
            items(results.playlists, key = { "p${it.id}" }) { playlist ->
                PlaylistRow(
                    playlist = playlist,
                    onClick = { onOpenPlaylist(playlist.id) },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ResultHeader(label: String, count: Int) {
    SectionTitle(
        text = label,
        trailing = {
            Text(
                "$count",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
