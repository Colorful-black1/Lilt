package dev.lilt.player.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwapVert
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lilt.player.LiltViewModel

/**
 * Queue contents with the same immediate reorder and remove actions as a
 * playlist. The header Play button starts at the first song; tapping a row
 * starts the queue at that row.
 */
@Composable
fun QueueDetailScreen(
    vm: LiltViewModel,
    queueId: Long,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val queues by vm.queues.collectAsStateWithLifecycle()
    val queue = queues.firstOrNull { it.id == queueId }
    val flow = remember(queueId) { vm.queueSongs(queueId) }
    val songs by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    var reordering by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        topBar = {
            AppBar(
                title = if (reordering) {
                    t("Reorder queue", "调整队列顺序")
                } else {
                    queue?.name ?: t("Queue", "队列")
                },
                onBack = {
                    if (reordering) reordering = false else onBack()
                },
                actions = {
                    if (reordering) {
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
                    albumId = songs.firstOrNull()?.albumId ?: queueId,
                    modifier = Modifier.size(86.dp),
                    corner = 16.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        queue?.name ?: t("Queue", "队列"),
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
                    Row {
                        RoundAction(
                            label = t("Play", "播放"),
                            icon = Icons.Rounded.PlayArrow,
                            primary = true,
                            enabled = songs.isNotEmpty(),
                        ) {
                            vm.playQueueAt(queueId, 0)
                            onOpenPlayer()
                        }
                        Spacer(Modifier.width(8.dp))
                        RoundAction(
                            label = t("Shuffle", "随机"),
                            icon = Icons.Rounded.Shuffle,
                            primary = false,
                            enabled = songs.isNotEmpty(),
                        ) {
                            vm.playSongs(songs.shuffled(), 0, queueId)
                            onOpenPlayer()
                        }
                    }
                }
            }
            if (songs.isEmpty()) {
                EmptyState(
                    title = t("This queue is empty", "这个队列是空的"),
                    body = t(
                        "Add songs from any list with the ··· menu.",
                        "在任意列表的 ··· 菜单中添加歌曲。",
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
                                if (!reordering) {
                                    vm.playQueueAt(queueId, index)
                                    onOpenPlayer()
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
                                { vm.removeQueueSong(queueId, song.mediaId) }
                            },
                            onMoveUp = if (reordering && index > 0) {
                                { vm.moveQueueSong(queueId, index, index - 1) }
                            } else {
                                null
                            },
                            onMoveDown = if (reordering && index < songs.lastIndex) {
                                { vm.moveQueueSong(queueId, index, index + 1) }
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
}
