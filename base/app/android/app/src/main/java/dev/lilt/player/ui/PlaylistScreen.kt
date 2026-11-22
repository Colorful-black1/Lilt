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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
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
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        uri?.let { vm.exportPlaylist(playlistId, it) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        topBar = {
            AppBar(
                title = playlist?.name ?: t("Playlist", "播放列表"),
                onBack = onBack,
                actions = {
                    androidx.compose.foundation.layout.Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreVert, t("More", "更多"))
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
                        t("${songs.size} tracks · ${formatDuration(totalDuration(songs))}", "${songs.size} 首 · ${formatDuration(totalDuration(songs))}"),
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
                LazyColumn(Modifier.fillMaxSize()) {
                    items(songs, key = { it.mediaId }) { song ->
                        SongRow(
                            song = song,
                            isCurrent = song.mediaId == player.mediaId,
                            isFavorite = song.mediaId in favoriteIds,
                            onToggleFavorite = { vm.toggleFavorite(song.mediaId) },
                            onClick = {
                                vm.playSongs(songs, songs.indexOf(song))
                                onOpenPlayer()
                            },
                            onAction = { action ->
                                when (action) {
                                    SongAction.PlayNext -> vm.enqueueNext(song)
                                    SongAction.AddToQueue -> vm.addToQueue(song)
                                    SongAction.AddToPlaylist -> Unit
                                }
                            },
                            onRemove = { vm.removeFromPlaylist(playlistId, song) },
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
