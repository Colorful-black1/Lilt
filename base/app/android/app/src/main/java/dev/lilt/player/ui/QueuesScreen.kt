package dev.lilt.player.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lilt.player.LiltViewModel
import dev.lilt.player.data.QueueSummary

@Composable
fun QueuesScreen(
    vm: LiltViewModel,
    onBack: () -> Unit,
    onOpenQueue: (Long) -> Unit,
) {
    val queues by vm.queues.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val active by vm.activeQueueId.collectAsStateWithLifecycle()
    val conflict by vm.queueConflict.collectAsStateWithLifecycle()
    val rememberPositions by vm.rememberQueuePositions.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<QueueSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<QueueSummary?>(null) }
    var exportTarget by remember { mutableStateOf<QueueSummary?>(null) }
    var importName by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        val target = exportTarget
        if (uri != null && target != null) vm.exportQueue(target.id, uri)
        exportTarget = null
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importQueue(uri, "Imported queue")
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        topBar = {
            AppBar(
                title = t("Queues", "队列"),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "text/plain", "*/*")) }) {
                        Icon(Icons.Rounded.FileUpload, t("Import M3U", "导入 M3U"))
                    }
                    IconButton(onClick = { showCreate = true }) {
                        Icon(Icons.Rounded.Add, t("New queue", "新建队列"))
                    }
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Text(
                t(
                    "Up to 20 named queues. Same name: $conflict.",
                    "最多 20 个命名队列。同名时：${if (conflict == "merge") "合并" else "替换"}。",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
            Text(
                if (rememberPositions) {
                    t(
                        "Queue positions are remembered. The play button resumes where you stopped; tap a queue to see its songs.",
                        "队列位置已记忆。播放按钮会从上次停止处继续；点击队列可查看歌曲。",
                    )
                } else {
                    t(
                        "Queue positions are not remembered. Every queue starts from its first song.",
                        "未记忆队列位置。每个队列都会从第一首开始。",
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
            )
            if (queues.isEmpty()) {
                EmptyState(
                    title = t("No queues yet", "还没有队列"),
                    body = t(
                        "A queue is a snapshot of what you are playing. Save one before changing direction.",
                        "队列是当前播放内容的快照。换方向之前，先存一个。",
                    ),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(queues, key = { it.id }) { queue ->
                        QueueRow(
                            queue = queue,
                            active = queue.id == active && player.itemCount > 0,
                            rememberPositions = rememberPositions,
                            onOpen = { onOpenQueue(queue.id) },
                            onPlay = { vm.playQueue(queue.id) },
                            onRename = { renameTarget = queue },
                            onDelete = { deleteTarget = queue },
                            onExport = {
                                exportTarget = queue
                                exportLauncher.launch("${queue.name}.m3u")
                            },
                        )
                    }
                    item { Spacer(Modifier.size(24.dp)) }
                }
            }
        }
    }

    if (showCreate) {
        TextPromptDialog(
            title = t("Save current playback as a queue", "把当前播放存为队列"),
            initial = t("Queue", "队列"),
            onDismiss = { showCreate = false },
            onConfirm = {
                vm.saveCurrentQueue(it)
                showCreate = false
            },
        )
    }
    renameTarget?.let { target ->
        TextPromptDialog(
            title = t("Rename queue", "重命名队列"),
            initial = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = {
                vm.renameQueue(target.id, it)
                renameTarget = null
            },
        )
    }
    deleteTarget?.let { target ->
        ConfirmDialog(
            title = t("Delete this queue?", "删除这个队列？"),
            body = t("Songs are not deleted from your library.", "不会从曲库中删除歌曲。"),
            onDismiss = { deleteTarget = null },
            onConfirm = { vm.deleteQueue(target.id) },
        )
    }
}

@Composable
private fun QueueRow(
    queue: QueueSummary,
    active: Boolean,
    rememberPositions: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(artBrush(queue.id + 31)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.QueueMusic,
                null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(25.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    queue.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (active) {
                    Text(
                        t("  PLAYING", "  正在播放"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                buildString {
                    append(trackCountLabel(queue.songCount))
                    if (rememberPositions && queue.lastPositionMs > 0) {
                        append(" · ")
                        append(
                            t(
                                "resume ${formatDuration(queue.lastPositionMs)}",
                                "续播 ${formatDuration(queue.lastPositionMs)}",
                            ),
                        )
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onPlay) {
            Icon(Icons.Rounded.PlayArrow, t("Play", "播放"))
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Rounded.MoreVert, t("More", "更多"))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(t("Rename", "重命名")) },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = { menu = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text(t("Export M3U", "导出 M3U")) },
                    leadingIcon = { Icon(Icons.Rounded.FileDownload, null) },
                    onClick = { menu = false; onExport() },
                )
                DropdownMenuItem(
                    text = { Text(t("Delete", "删除")) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}
