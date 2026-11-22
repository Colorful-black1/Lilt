package dev.lilt.player.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lilt.player.LiltViewModel
import dev.lilt.player.PlayerUiState
import dev.lilt.player.data.ScanState
import dev.lilt.player.data.SongEntity
import dev.lilt.player.library.FolderNode
import dev.lilt.player.library.FolderTree
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator

private fun fileName(context: android.content.Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()?.substringBeforeLast('.') ?: "Imported playlist"
}

@Composable
fun HomeScreen(
    vm: LiltViewModel,
    onOpenPlayer: () -> Unit,
    onOpenQueues: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val scan by vm.scanState.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var tab by rememberSaveable { mutableStateOf(0) }
    var folderPath by rememberSaveable { mutableStateOf("") }
    var showNewPlaylist by remember { mutableStateOf(false) }
    var songForPlaylist by remember { mutableStateOf<SongEntity?>(null) }

    val tree = remember(songs) { FolderTree.build(songs) }
    val node = remember(tree, folderPath) { FolderTree.find(tree, folderPath) }
    val importPlaylist = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importPlaylist(it, fileName(context, it)) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppBar(
                title = t("Lilt", "Lilt 音乐"),
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Rounded.Search, contentDescription = t("Search", "搜索"))
                    }
                    IconButton(onClick = onOpenQueues) {
                        Icon(Icons.Rounded.QueueMusic, contentDescription = t("Queues", "队列"))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = t("Settings", "设置"))
                    }
                },
            )
        },
        bottomBar = {
            MiniPlayer(
                state = player,
                onOpen = onOpenPlayer,
                onToggle = vm::togglePlayPause,
                onNext = vm::next,
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(t("Folders", "文件夹")) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(t("Playlists", "播放列表")) })
            }
            when (scan) {
                is ScanState.Scanning -> {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                        Text(
                            t("Scanning your device… ${(scan as ScanState.Scanning).seen} found", "正在扫描设备… 已找到 ${(scan as ScanState.Scanning).seen} 首"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                is ScanState.Error -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            (scan as ScanState.Error).message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = vm::scan) {
                            Icon(Icons.Rounded.Refresh, t("Rescan", "重新扫描"))
                        }
                    }
                }
                else -> Unit
            }
            if (tab == 0) {
                FolderBrowser(
                    vm = vm,
                    tree = tree,
                    node = node,
                    songs = songs,
                    player = player,
                    folderPath = folderPath,
                    onFolder = { folderPath = it },
                    onRescan = vm::scan,
                    onOpenPlayer = onOpenPlayer,
                    onSongAction = { song, action ->
                        when (action) {
                            SongAction.PlayNext -> vm.enqueueNext(song)
                            SongAction.AddToQueue -> vm.addToQueue(song)
                            SongAction.AddToPlaylist -> songForPlaylist = song
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                PlaylistBrowser(
                    vm = vm,
                    playlists = playlists,
                    onCreate = { showNewPlaylist = true },
                    onOpen = onOpenPlaylist,
                    onImport = { importPlaylist.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "text/plain", "*/*")) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (showNewPlaylist) {
        TextPromptDialog(
            title = t("New playlist", "新建播放列表"),
            onDismiss = { showNewPlaylist = false },
            onConfirm = {
                vm.createPlaylist(it)
                showNewPlaylist = false
            },
        )
    }

    val pickedSong = songForPlaylist
    if (pickedSong != null) {
        AlertDialog(
            onDismissRequest = { songForPlaylist = null },
            title = { Text(t("Add to playlist", "加入播放列表")) },
            text = {
                Column {
                    if (playlists.isEmpty()) {
                        Text(t("Create a playlist first.", "请先新建一个播放列表。"))
                    }
                    playlists.forEach { playlist ->
                        TextButton(onClick = {
                            vm.addToPlaylist(playlist.id, pickedSong)
                            songForPlaylist = null
                        }) {
                            Text(playlist.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    songForPlaylist = null
                    showNewPlaylist = true
                }) {
                    Text(t("New playlist", "新建播放列表"))
                }
            },
            dismissButton = {
                TextButton(onClick = { songForPlaylist = null }) { Text(t("Close", "关闭")) }
            },
        )
    }
}

@Composable
private fun FolderBrowser(
    vm: LiltViewModel,
    tree: List<FolderNode>,
    node: FolderNode?,
    songs: List<SongEntity>,
    player: PlayerUiState,
    folderPath: String,
    onFolder: (String) -> Unit,
    onRescan: () -> Unit,
    onOpenPlayer: () -> Unit,
    onSongAction: (SongEntity, SongAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val folders = node?.children ?: tree
    val displaySongs = node?.directSongs ?: emptyList()
    val allHere = node?.let { FolderTree.recursiveSongs(it) } ?: tree.flatMap { FolderTree.recursiveSongs(it) }
    val seed = folderPath.hashCode().toLong()
    val currentId = player.mediaId

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        t("Library", "曲库"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clickableNoRipple { onFolder("") },
                    )
                    FolderTree.breadcrumb(folderPath).forEach { (path, label) ->
                        Text("  /  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .clickableNoRipple { onFolder(path) }
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                    }
                }
                IconButton(onClick = onRescan) {
                    Icon(Icons.Rounded.Refresh, t("Rescan", "重新扫描"))
                }
            }
        }
        if (allHere.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickableNoRipple { vm.playSongs(allHere, 0); onOpenPlayer() }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("Play all", "全部播放"), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickableNoRipple {
                                vm.playSongs(allHere.shuffled(), 0)
                                onOpenPlayer()
                            }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Shuffle, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(t("Shuffle", "随机"), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        if (folders.isNotEmpty()) {
            item { SectionTitle(t("Folders", "文件夹")) }
            items(folders, key = { it.path }) { folder ->
                val folderSongs = FolderTree.recursiveSongs(folder)
                FolderRow(
                    name = folder.name,
                    trackCount = folderSongs.size,
                    durationMs = totalDuration(folderSongs),
                    seed = folder.path.hashCode().toLong(),
                    onClick = { onFolder(folder.path) },
                    onPlay = { vm.playSongs(folderSongs, 0); onOpenPlayer() },
                )
            }
        }
        if (displaySongs.isNotEmpty()) {
            item { SectionTitle(t("Tracks here", "此处的歌曲")) }
            items(displaySongs, key = { it.mediaId }) { song ->
                SongRow(
                    song = song,
                    isCurrent = song.mediaId == currentId,
                    onClick = {
                        vm.playSongs(displaySongs, displaySongs.indexOf(song))
                        onOpenPlayer()
                    },
                    onAction = { onSongAction(song, it) },
                )
            }
        }
        if (songs.isEmpty()) {
            item {
                EmptyState(
                    title = t("No music visible", "没有可见的音乐"),
                    body = t(
                        "Check the folder rules, grant audio access, or scan this phone again. Everything stays on the device.",
                        "请检查文件夹规则、授予音频访问权限，或重新扫描。所有内容只留在这台手机上。",
                    ),
                    action = {
                        Row(
                            modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .clickableNoRipple(onRescan)
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                        ) { Text(t("Scan device", "扫描设备"), color = MaterialTheme.colorScheme.onPrimary) }
                    },
                )
            }
        } else if (folders.isEmpty() && displaySongs.isEmpty()) {
            item {
                EmptyState(
                    title = t("This folder is empty", "此文件夹为空"),
                    body = t("Try the parent folder or rescan.", "返回上级文件夹或重新扫描。"),
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun PlaylistBrowser(
    vm: LiltViewModel,
    playlists: List<dev.lilt.player.data.PlaylistSummary>,
    onCreate: () -> Unit,
    onOpen: (Long) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AddChip(t("New playlist", "新建列表"), onCreate)
                AddChip(t("Import M3U", "导入 M3U"), onImport)
            }
        }
        if (playlists.isEmpty()) {
            item {
                EmptyState(
                    title = t("No playlists yet", "还没有播放列表"),
                    body = t(
                        "Make a list for a mood, a trip or a set of favourites.",
                        "按心情、旅程或收藏创建自己的列表。",
                    ),
                )
            }
        } else {
            items(playlists, key = { it.id }) { playlist ->
                PlaylistRow(playlist = playlist, onClick = { onOpen(playlist.id) })
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
