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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lilt.player.LiltViewModel
import dev.lilt.player.PlayerUiState
import dev.lilt.player.data.BookmarkEntity
import dev.lilt.player.data.SongEntity
import dev.lilt.player.data.SongStateEntity
import dev.lilt.player.library.AlbumGroup
import dev.lilt.player.library.ArtistGroup
import dev.lilt.player.library.LibraryGroups
import dev.lilt.player.library.SongSort
import dev.lilt.player.library.letterForSong
import dev.lilt.player.library.sortSongs
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LibrarySection {
    Songs,
    Albums,
    Artists,
    Favorites,
    Recent,
    MostPlayed,
    History,
    Bookmarks,
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
private fun playCountLabel(count: Int): String =
    if (count == 1) t("1 play", "1 条记录") else t("$count plays", "$count 条记录")

@Composable
private fun bookmarkCountLabel(count: Int): String =
    if (count == 1) t("1 bookmark", "1 个书签") else t("$count bookmarks", "$count 个书签")

@Composable
private fun recentCountLabel(count: Int): String =
    if (count == 1) t("1 recent song", "1 首最近添加") else t("$count recent songs", "$count 首最近添加")

@Composable
private fun playedSongCountLabel(count: Int): String =
    if (count == 1) t("1 played song", "1 首播放过的歌曲") else t("$count played songs", "$count 首播放过的歌曲")

@Composable
fun LibraryBrowser(
    vm: LiltViewModel,
    songs: List<SongEntity>,
    player: PlayerUiState,
    favoriteIds: Set<Long>,
    sectionName: String,
    onSectionChange: (String) -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenAlbum: (String, Long) -> Unit,
    onOpenArtist: (String) -> Unit,
    onSongAction: (SongEntity, SongAction) -> Unit,
    modifier: Modifier = Modifier,
    locateRequest: Int = 0,
    onLocateConsumed: () -> Unit = {},
) {
    val states by vm.songStates.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val sortId by vm.songSort.collectAsStateWithLifecycle()
    val descending by vm.songSortDescending.collectAsStateWithLifecycle()

    val section = LibrarySection.valueOf(sectionName)
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var playlistTarget by remember { mutableStateOf<List<SongEntity>>(emptyList()) }

    val sort = SongSort.fromId(sortId)
    val albums = remember(songs) { LibraryGroups.albums(songs) }
    val artists = remember(songs) { LibraryGroups.artists(songs) }
    val favorites = remember(songs, favoriteIds) { songs.filter { it.mediaId in favoriteIds } }
    val recent = remember(songs) {
        songs.sortedWith(
            compareByDescending<SongEntity> { it.dateAdded }
                .thenBy { it.title.lowercase(Locale.ROOT) }
        )
    }
    val mostPlayed = remember(songs, states) {
        songs.filter { (states[it.mediaId]?.playCount ?: 0) > 0 }
            .sortedWith(
                compareByDescending<SongEntity> { states[it.mediaId]?.playCount ?: 0 }
                    .thenByDescending { states[it.mediaId]?.lastPlayedAt ?: 0L }
                    .thenBy { it.title.lowercase(Locale.ROOT) }
            )
    }
    val songsById = remember(songs) { songs.associateBy { it.mediaId } }
    val historyRows = remember(history, songsById) {
        history.mapNotNull { entry -> songsById[entry.mediaId]?.let { it to entry.playedAt } }
    }
    val bookmarkRows = remember(bookmarks, songsById) {
        bookmarks.mapNotNull { bookmark -> songsById[bookmark.mediaId]?.let { it to bookmark } }
    }
    val recentLabels = mutableMapOf<Long, String>()
    for (song in recent) {
        val date = if (song.dateAdded <= 0) null else SimpleDateFormat(
            "MMM d, yyyy",
            Locale.getDefault(),
        ).format(Date(song.dateAdded * 1000))
        recentLabels[song.mediaId] = if (date == null) {
            t("Added date unknown", "添加时间未知")
        } else {
            t("Added $date", "添加于 $date")
        }
    }
    val playLabels = mutableMapOf<Long, String>()
    for (song in mostPlayed) {
        val count = states[song.mediaId]?.playCount ?: 0
        val plays = if (count == 1) t("1 play", "播放 1 次") else t("$count plays", "播放 $count 次")
        playLabels[song.mediaId] = "$plays · ${song.artist}"
    }

    LaunchedEffect(section) {
        selectionMode = false
        selectedIds = emptySet()
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
                        LibrarySection.Recent -> t("Recent", "最近添加")
                        LibrarySection.MostPlayed -> t("Most played", "最多播放")
                        LibrarySection.History -> t("History", "历史")
                        LibrarySection.Bookmarks -> t("Bookmarks", "书签")
                    },
                    selected = section == entry,
                    onClick = { onSectionChange(entry.name) },
                )
            }
        }

        val commonSelection = SelectionCallbacks(
            mode = selectionMode,
            ids = selectedIds,
            onChange = { selectedIds = it },
            onModeChange = { selectionMode = it },
            onFavorite = { selected -> vm.setFavorite(selected.map { it.mediaId }, true) },
            onQueue = vm::addSongsToQueue,
            onPlaylist = { playlistTarget = it },
        )

        when (section) {
            LibrarySection.Songs -> SongListSection(
                songs = songs,
                states = states,
                player = player,
                favoriteIds = favoriteIds,
                emptyTitle = t("No songs yet", "还没有歌曲"),
                emptyBody = t("Scan your device, then songs appear here.", "扫描设备后，歌曲会显示在这里。"),
                countLabel = songCountLabel(songs.size),
                sort = sort,
                descending = descending,
                onSort = { vm.setSongSort(it.id) },
                onToggleDirection = { vm.setSongSortDescending(!descending) },
                subtitle = { null },
                selection = commonSelection,
                onPlay = { list, index -> vm.playSongs(list, index); onOpenPlayer() },
                onToggleFavorite = vm::toggleFavorite,
                onSongAction = onSongAction,
                locateRequest = locateRequest,
                onLocateConsumed = onLocateConsumed,
            )

            LibrarySection.Favorites -> SongListSection(
                songs = favorites,
                states = states,
                player = player,
                favoriteIds = favoriteIds,
                emptyTitle = t("No favorites yet", "还没有收藏"),
                emptyBody = t("Tap the heart beside a song to save it here.", "点击歌曲旁的心形即可收藏到这里。"),
                countLabel = songCountLabel(favorites.size),
                sort = sort,
                descending = descending,
                onSort = { vm.setSongSort(it.id) },
                onToggleDirection = { vm.setSongSortDescending(!descending) },
                subtitle = { null },
                selection = commonSelection,
                onPlay = { list, index -> vm.playSongs(list, index); onOpenPlayer() },
                onToggleFavorite = vm::toggleFavorite,
                onSongAction = onSongAction,
            )

            LibrarySection.Recent -> SongListSection(
                songs = recent,
                states = states,
                player = player,
                favoriteIds = favoriteIds,
                emptyTitle = t("No recent additions", "还没有最近添加"),
                emptyBody = t("Newly scanned songs are listed here first.", "新扫描到的歌曲会排在这里。"),
                countLabel = recentCountLabel(recent.size),
                sort = null,
                descending = false,
                onSort = {},
                onToggleDirection = {},
                subtitle = { song -> recentLabels[song.mediaId] },
                selection = commonSelection,
                onPlay = { list, index -> vm.playSongs(list, index); onOpenPlayer() },
                onToggleFavorite = vm::toggleFavorite,
                onSongAction = onSongAction,
            )

            LibrarySection.MostPlayed -> SongListSection(
                songs = mostPlayed,
                states = states,
                player = player,
                favoriteIds = favoriteIds,
                emptyTitle = t("No play counts yet", "还没有播放次数"),
                emptyBody = t("A play is counted after 30% of a track.", "播放到 30% 后会计入一次播放。"),
                countLabel = playedSongCountLabel(mostPlayed.size),
                sort = null,
                descending = true,
                onSort = {},
                onToggleDirection = {},
                subtitle = { song -> playLabels[song.mediaId] },
                selection = commonSelection,
                onPlay = { list, index -> vm.playSongs(list, index); onOpenPlayer() },
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

            LibrarySection.History -> HistorySection(
                rows = historyRows,
                player = player,
                favoriteIds = favoriteIds,
                onClear = vm::clearHistory,
                onPlay = { list, index -> vm.playSongs(list, index); onOpenPlayer() },
                onToggleFavorite = vm::toggleFavorite,
                onSongAction = onSongAction,
            )

            LibrarySection.Bookmarks -> BookmarkSection(
                rows = bookmarkRows,
                player = player,
                onPlayAt = { song, position ->
                    vm.playAt(song, position)
                    onOpenPlayer()
                },
                onDelete = vm::deleteBookmark,
            )
        }
    }

    if (playlistTarget.isNotEmpty()) {
        BatchPlaylistDialog(
            songs = playlistTarget,
            vm = vm,
            onDismiss = { playlistTarget = emptyList() },
        )
    }
}

private data class SelectionCallbacks(
    val mode: Boolean,
    val ids: Set<Long>,
    val onChange: (Set<Long>) -> Unit,
    val onModeChange: (Boolean) -> Unit,
    val onFavorite: (List<SongEntity>) -> Unit,
    val onQueue: (List<SongEntity>) -> Unit,
    val onPlaylist: (List<SongEntity>) -> Unit,
)

@Composable
private fun SongListSection(
    songs: List<SongEntity>,
    states: Map<Long, SongStateEntity>,
    player: PlayerUiState,
    favoriteIds: Set<Long>,
    emptyTitle: String,
    emptyBody: String,
    countLabel: String,
    sort: SongSort?,
    descending: Boolean,
    onSort: (SongSort) -> Unit,
    onToggleDirection: () -> Unit,
    subtitle: (SongEntity) -> String?,
    selection: SelectionCallbacks,
    onPlay: (List<SongEntity>, Int) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onSongAction: (SongEntity, SongAction) -> Unit,
    locateRequest: Int = 0,
    onLocateConsumed: () -> Unit = {},
) {
    if (songs.isEmpty()) {
        EmptyState(title = emptyTitle, body = emptyBody)
        return
    }
    val ordered = remember(songs, states, sort, descending) {
        if (sort == null) songs else sortSongs(songs, states, sort, descending)
    }
    val selected = remember(selection.ids, ordered) {
        ordered.filter { it.mediaId in selection.ids }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var sortMenu by remember { mutableStateOf(false) }
    var handledLocate by remember { mutableIntStateOf(0) }
    val currentIndex = ordered.indexOfFirst { it.mediaId == player.mediaId }

    LaunchedEffect(locateRequest, currentIndex) {
        if (locateRequest == 0) {
            handledLocate = 0
        } else if (locateRequest > handledLocate) {
            handledLocate = locateRequest
            if (currentIndex >= 0) {
                listState.animateScrollToItem(currentIndex + 1)
            }
            onLocateConsumed()
        }
    }
    val indexable = sort == SongSort.Title
    val letterStarts = remember(ordered, indexable) {
        if (!indexable) emptyMap()
        else ordered.foldIndexed(linkedMapOf<Char, Int>()) { index, map, song ->
            map.apply { putIfAbsent(letterForSong(song), index) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                if (selection.mode) {
                    SelectionBar(
                        selected = selected,
                        all = ordered,
                        onFavorite = selection.onFavorite,
                        onQueue = selection.onQueue,
                        onPlaylist = selection.onPlaylist,
                        onChange = selection.onChange,
                        onClose = {
                            selection.onModeChange(false)
                            selection.onChange(emptySet())
                        },
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            countLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (currentIndex >= 0) {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        listState.animateScrollToItem(currentIndex + 1)
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Rounded.MyLocation,
                                    contentDescription = t("Locate current song", "定位当前歌曲"),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (sort != null) {
                            Box {
                                IconButton(onClick = { sortMenu = true }) {
                                    Icon(
                                        Icons.Rounded.Sort,
                                        contentDescription = t("Sort", "排序"),
                                    )
                                }
                                SortMenu(
                                    expanded = sortMenu,
                                    sort = sort,
                                    descending = descending,
                                    onDismiss = { sortMenu = false },
                                    onSort = {
                                        onSort(it)
                                        sortMenu = false
                                    },
                                    onToggleDirection = {
                                        onToggleDirection()
                                        sortMenu = false
                                    },
                                )
                            }
                        }
                        IconButton(onClick = { selection.onModeChange(true) }) {
                            Icon(
                                Icons.Rounded.Checklist,
                                contentDescription = t("Select songs", "多选歌曲"),
                            )
                        }
                    }
                }
            }
            items(ordered, key = { it.mediaId }) { song ->
                SongRow(
                    song = song,
                    isCurrent = song.mediaId == player.mediaId,
                    isFavorite = song.mediaId in favoriteIds,
                    onToggleFavorite = { onToggleFavorite(song.mediaId) },
                    onClick = {
                        if (selection.mode) {
                            selection.onChange(
                                if (song.mediaId in selection.ids) selection.ids - song.mediaId
                                else selection.ids + song.mediaId
                            )
                        } else {
                            onPlay(ordered, ordered.indexOf(song))
                        }
                    },
                    onAction = { onSongAction(song, it) },
                    subtitle = subtitle(song),
                    selectionMode = selection.mode,
                    selected = song.mediaId in selection.ids,
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }

        if (letterStarts.isNotEmpty() && ordered.size >= 24) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                letterStarts.forEach { (letter, index) ->
                    Text(
                        letter.toString(),
                        fontSize = 9.sp,
                        lineHeight = 9.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable {
                                scope.launch {
                                    listState.animateScrollToItem(index + 1)
                                }
                            }
                            .padding(horizontal = 3.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionBar(
    selected: List<SongEntity>,
    all: List<SongEntity>,
    onFavorite: (List<SongEntity>) -> Unit,
    onQueue: (List<SongEntity>) -> Unit,
    onPlaylist: (List<SongEntity>) -> Unit,
    onChange: (Set<Long>) -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, contentDescription = t("Cancel selection", "取消多选"))
        }
        Text(
            t("${selected.size} selected", "已选择 ${selected.size} 首"),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { if (selected.isNotEmpty()) onFavorite(selected) },
            enabled = selected.isNotEmpty(),
        ) {
            Icon(Icons.Rounded.Favorite, contentDescription = t("Favorite", "收藏"))
        }
        IconButton(
            onClick = { if (selected.isNotEmpty()) onQueue(selected) },
            enabled = selected.isNotEmpty(),
        ) {
            Icon(Icons.Rounded.QueueMusic, contentDescription = t("Add to queue", "加入队列"))
        }
        IconButton(
            onClick = { if (selected.isNotEmpty()) onPlaylist(selected) },
            enabled = selected.isNotEmpty(),
        ) {
            Icon(Icons.Rounded.PlaylistAdd, contentDescription = t("Add to playlist", "加入播放列表"))
        }
        TextButton(
            onClick = {
                onChange(
                    if (selected.size == all.size) emptySet()
                    else all.map { it.mediaId }.toSet()
                )
            },
        ) {
            Text(
                if (selected.size == all.size) t("None", "全不选") else t("All", "全选"),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun SortMenu(
    expanded: Boolean,
    sort: SongSort,
    descending: Boolean,
    onDismiss: () -> Unit,
    onSort: (SongSort) -> Unit,
    onToggleDirection: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = {
                Text(
                    if (descending) t("Descending", "降序") else t("Ascending", "升序"),
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            onClick = onToggleDirection,
        )
        SongSort.entries.forEach { option ->
            DropdownMenuItem(
                text = {
                    Text(
                        sortLabel(option),
                        fontWeight = if (option == sort) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (option == sort) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                },
                onClick = { onSort(option) },
            )
        }
    }
}

@Composable
private fun sortLabel(sort: SongSort): String = when (sort) {
    SongSort.Title -> t("Title", "标题")
    SongSort.Artist -> t("Artist", "艺术家")
    SongSort.Album -> t("Album", "专辑")
    SongSort.Duration -> t("Duration", "时长")
    SongSort.DateAdded -> t("Date added", "添加时间")
    SongSort.PlayCount -> t("Play count", "播放次数")
    SongSort.LastPlayed -> t("Last played", "最后播放")
    SongSort.Track -> t("Track number", "音轨号")
    SongSort.FileName -> t("File name", "文件名")
}

@Composable
private fun BatchPlaylistDialog(
    songs: List<SongEntity>,
    vm: LiltViewModel,
    onDismiss: () -> Unit,
) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(t("Add ${songs.size} songs to a playlist", "将 ${songs.size} 首歌曲加入播放列表"))
        },
        text = {
            Column {
                if (playlists.isEmpty()) {
                    Text(t("Create a playlist in the Playlists tab first.", "请先在播放列表页新建列表。"))
                }
                playlists.forEach { playlist ->
                    TextButton(
                        onClick = {
                            vm.addSongsToPlaylist(playlist.id, songs)
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

@Composable
private fun HistorySection(
    rows: List<Pair<SongEntity, Long>>,
    player: PlayerUiState,
    favoriteIds: Set<Long>,
    onClear: () -> Unit,
    onPlay: (List<SongEntity>, Int) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onSongAction: (SongEntity, SongAction) -> Unit,
) {
    if (rows.isEmpty()) {
        EmptyState(
            title = t("No playback history", "还没有播放历史"),
            body = t("Songs you play are listed here, newest first.", "播放过的歌曲会按时间倒序显示。"),
        )
        return
    }
    val songs = rows.map { it.first }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    playCountLabel(rows.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear) {
                    Text(t("Clear", "清空"))
                }
            }
        }
        items(rows, key = { "h${it.second}-${it.first.mediaId}" }) { (song, playedAt) ->
            SongRow(
                song = song,
                isCurrent = song.mediaId == player.mediaId,
                isFavorite = song.mediaId in favoriteIds,
                onToggleFavorite = { onToggleFavorite(song.mediaId) },
                onClick = { onPlay(songs, songs.indexOf(song)) },
                onAction = { onSongAction(song, it) },
                subtitle = "${song.artist} · ${playedAtLabel(playedAt)}",
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun BookmarkSection(
    rows: List<Pair<SongEntity, BookmarkEntity>>,
    player: PlayerUiState,
    onPlayAt: (SongEntity, Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    if (rows.isEmpty()) {
        EmptyState(
            title = t("No bookmarks yet", "还没有书签"),
            body = t(
                "Open the player and add a bookmark to remember a moment in a song.",
                "打开播放页，添加书签即可记住歌曲中的位置。",
            ),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                bookmarkCountLabel(rows.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
        items(rows, key = { "b${it.second.id}" }) { (song, bookmark) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayAt(song, bookmark.positionMs) }
                    .background(
                        if (song.mediaId == player.mediaId)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                        else Color.Transparent
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArt(albumId = song.albumId, modifier = Modifier.size(46.dp), corner = 8.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        bookmark.note.ifBlank {
                            t(
                                "Bookmark at ${formatDuration(bookmark.positionMs)}",
                                "书签 ${formatDuration(bookmark.positionMs)}",
                            )
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${song.title} · ${song.artist} · ${formatDuration(bookmark.positionMs)}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        playedAtLabel(bookmark.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onDelete(bookmark.id) }) {
                    Icon(Icons.Rounded.Delete, contentDescription = t("Delete bookmark", "删除书签"))
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
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

private fun playedAtLabel(timestamp: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
