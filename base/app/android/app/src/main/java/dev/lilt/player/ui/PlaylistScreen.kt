package dev.lilt.player.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lilt.player.LiltViewModel

@Composable
fun PlaylistScreen(
    vm: LiltViewModel,
    playlistId: Long,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val playlist = playlists.firstOrNull { it.id == playlistId }
    val flow = remember(playlistId) { vm.playlistSongs(playlistId) }
    val songs by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var reordering by rememberSaveable { mutableStateOf(false) }
    val selectedSongs = songs.filter { it.mediaId in selectedIds }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        uri?.let { vm.exportPlaylist(playlistId, it) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        topBar = {
            AppBar(
                title = when {
                    selecting -> t("${selectedSongs.size} selected", "已选择 ${selectedSongs.size} 首")
                    reordering -> t("Reorder playlist", "调整播放列表顺序")
                    else -> playlist?.name ?: t("Playlist", "播放列表")
                },
                onBack = {
                    when {
                        selecting -> {
                            selecting = false
                            selectedIds = emptySet()
                        }
                        reordering -> reordering = false
                        else -> onBack()
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(
                            onClick = { vm.setFavorite(selectedIds, true) },
                            enabled = selectedSongs.isNotEmpty(),
                        ) {
                            Icon(Icons.Rounded.Favorite, t("Favorite", "收藏"))
                        }
                        IconButton(
                            onClick = { vm.addSongsToQueue(selectedSongs) },
                            enabled = selectedSongs.isNotEmpty(),
                        ) {
                            Icon(Icons.Rounded.QueueMusic, t("Add to queue", "加入队列"))
                        }
                        IconButton(
                            onClick = {
                                vm.removeSongsFromPlaylist(playlistId, selectedSongs)
                                selecting = false
                                selectedIds = emptySet()
                            },
                            enabled = selectedSongs.isNotEmpty(),
                        ) {
                            Icon(Icons.Rounded.Delete, t("Remove selected", "移除所选"))
                        }
                        TextButton(
                            onClick = {
                                selectedIds = if (selectedSongs.size == songs.size) {
                                    emptySet()
                                } else {
                                    songs.map { it.mediaId }.toSet()
                                }
                            },
                        ) {
                            Text(
                                if (selectedSongs.size == songs.size && songs.isNotEmpty())
                                    t("None", "全不选") else t("All", "全选"),
                            )
                        }
                    } else if (reordering) {
                        TextButton(onClick = { reordering = false }) {
                            Text(t("Done", "完成"))
                        }
                    } else {
                        IconButton(
                            onClick = { reordering = true },
                            enabled = songs.isNotEmpty(),
                        ) {
                            Icon(Icons.Rounded.SwapVert, t("Reorder", "调整顺序"))
                        }
                        IconButton(
                            onClick = { selecting = true },
                            enabled = songs.isNotEmpty(),
                        ) {
                            Icon(Icons.Rounded.Checklist, t("Select songs", "多选歌曲"))
                        }
                    }
                    androidx.compose.foundation.layout.Box {
                        if (!selecting && !reordering) {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Rounded.MoreVert, t("More", "更多"))
                            }
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(t("Rename", "重命名")) },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                onClick = { menu = false; rename = true },
                            )
                            DropdownMenuItem(
                                text = { Text(t("Export M3U", "导出 M3U")) },
                                leadingIcon = { Icon(Icons.Rounded.FileDownload, null) },
                                onClick = {
                                    menu = false
                                    export.launch("${playlist?.name ?: "playlist"}.m3u")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(t("Delete", "删除")) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArt(
                    albumId = songs.firstOrNull()?.albumId ?: playlistId,
                    modifier = Modifier.size(86.dp),
                    corner = 16.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        playlist?.name ?: "",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${trackCountLabel(songs.size)} · ${formatDuration(totalDuration(songs))}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RoundAction(
                            label = t("Play", "播放"),
                            icon = Icons.Rounded.PlayArrow,
                            primary = true,
                            enabled = songs.isNotEmpty(),
                        ) {
                            vm.playSongs(songs, 0)
                            onOpenPlayer()
                        }
                        RoundAction(
                            label = t("Shuffle", "随机"),
                            icon = Icons.Rounded.Shuffle,
                            primary = false,
                            enabled = songs.isNotEmpty(),
                        ) {
                            vm.playSongs(songs.shuffled(), 0)
                            onOpenPlayer()
                        }
                    }
                }
            }
            if (songs.isEmpty()) {
                EmptyState(
                    title = t("Nothing here yet", "列表还是空的"),
                    body = t(
                        "Open a folder, tap the menu beside a song and add it to this playlist.",
                        "打开文件夹，点歌曲旁的三点菜单，把它加入这个列表。",
                    ),
                )
            } else {
                if (reordering) {
                    Text(
                        t(
                            "Use the arrows to change the order. Changes save immediately.",
                            "使用箭头调整顺序，修改会立即保存。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(songs, key = { _, song -> song.mediaId }) { index, song ->
                        SongRow(
                            song = song,
                            isCurrent = song.mediaId == player.mediaId,
                            isFavorite = song.mediaId in favoriteIds,
                            onToggleFavorite = if (reordering) {
                                null
                            } else {
                                { vm.toggleFavorite(song.mediaId) }
                            },
                            onClick = {
                                when {
                                    selecting -> {
                                        selectedIds = if (song.mediaId in selectedIds) {
                                            selectedIds - song.mediaId
                                        } else {
                                            selectedIds + song.mediaId
                                        }
                                    }
                                    reordering -> Unit
                                    else -> {
                                        vm.playSongs(songs, index)
                                        onOpenPlayer()
                                    }
                                }
                            },
                            onAction = { action ->
                                when (action) {
                                    SongAction.PlayNext -> vm.enqueueNext(song)
                                    SongAction.AddToQueue -> vm.addToQueue(song)
                                    SongAction.AddToPlaylist -> Unit
                                }
                            },
                            onRemove = if (reordering) {
                                null
                            } else {
                                { vm.removeFromPlaylist(playlistId, song) }
                            },
                            selectionMode = selecting,
                            selected = song.mediaId in selectedIds,
                            onMoveUp = if (reordering && index > 0) {
                                { vm.movePlaylistSong(playlistId, index, index - 1) }
                            } else {
                                null
                            },
                            onMoveDown = if (reordering && index < songs.lastIndex) {
                                { vm.movePlaylistSong(playlistId, index, index + 1) }
                            } else {
                                null
                            },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (rename) {
        TextPromptDialog(
            title = t("Rename playlist", "重命名播放列表"),
            initial = playlist?.name.orEmpty(),
            onDismiss = { rename = false },
            onConfirm = {
                vm.renamePlaylist(playlistId, it)
                rename = false
            },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = t("Delete this playlist?", "删除这个播放列表？"),
            body = t("The audio files stay on your phone.", "音频文件仍会保留在手机上。"),
            onDismiss = { confirmDelete = false },
            onConfirm = {
                vm.deletePlaylist(playlistId)
                onBack()
            },
        )
    }
}

@Composable
fun RoundAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (primary) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            color = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
