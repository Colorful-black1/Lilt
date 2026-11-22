package dev.lilt.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lilt.player.LiltViewModel
import dev.lilt.player.PlayerUiState
import dev.lilt.player.data.SongEntity
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    vm: LiltViewModel,
    onBack: () -> Unit,
    onOpenQueues: () -> Unit,
    onOpenEqualizer: () -> Unit,
) {
    val state by vm.player.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val speed by vm.playbackSpeed.collectAsStateWithLifecycle()
    val sleepDeadline by vm.sleepDeadline.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSaveQueue by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }

    val current = songs.firstOrNull { it.mediaId == state.mediaId }

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        topBar = {
            AppBar(
                title = t("Now playing", "正在播放"),
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = { if (current != null) vm.toggleFavorite(current.mediaId) },
                        enabled = current != null,
                    ) {
                        Icon(
                            if (current != null && current.mediaId in favoriteIds)
                                Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = if (current != null && current.mediaId in favoriteIds)
                                t("Remove favorite", "取消收藏") else t("Favorite", "收藏"),
                            tint = if (current != null && current.mediaId in favoriteIds)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onOpenQueues) {
                        Icon(Icons.Rounded.QueueMusic, t("Queues", "队列"))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreVert, t("More", "更多"))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(t("Sleep timer", "睡眠定时")) },
                                leadingIcon = { Icon(Icons.Rounded.Timer, null) },
                                onClick = { menu = false; showSleep = true },
                            )
                            DropdownMenuItem(
                                text = { Text(t("Playback speed", "播放速度")) },
                                leadingIcon = { Icon(Icons.Rounded.Speed, null) },
                                onClick = { menu = false; showSpeed = true },
                            )
                            DropdownMenuItem(
                                text = { Text(t("Equalizer", "均衡器")) },
                                leadingIcon = { Icon(Icons.Rounded.Equalizer, null) },
                                onClick = { menu = false; onOpenEqualizer() },
                            )
                            DropdownMenuItem(
                                text = { Text(t("Save as named queue", "存为命名队列")) },
                                leadingIcon = { Icon(Icons.Rounded.QueueMusic, null) },
                                enabled = state.itemCount > 0,
                                onClick = { menu = false; showSaveQueue = true },
                            )
                            DropdownMenuItem(
                                text = { Text(t("Add to playlist", "加入播放列表")) },
                                leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, null) },
                                enabled = current != null,
                                onClick = { menu = false; showPlaylistPicker = true },
                            )
                        }
                    }
                },
            )
        },
    ) { inner ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(inner)) {
            // Keep the whole control column visible on short phones (720x1280).
            val coverSize = minOf(maxWidth * 0.72f, maxHeight * 0.30f)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                AlbumArt(
                    albumId = state.albumId,
                    modifier = Modifier.size(coverSize),
                    corner = 24.dp,
                )
                Spacer(Modifier.height(22.dp))
                Text(
                    state.title.ifBlank { t("Nothing playing", "暂无播放") },
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    listOf(state.artist, state.album).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(18.dp))
                SeekBar(state = state, onSeek = vm::seekTo)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = vm::toggleShuffle) {
                        Icon(
                            Icons.Rounded.Shuffle,
                            t("Shuffle", "随机"),
                            tint = if (state.shuffle) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = vm::previous, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, t("Previous", "上一首"), modifier = Modifier.size(36.dp))
                    }
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(onClick = vm::togglePlayPause),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            t("Play or pause", "播放或暂停"),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(38.dp),
                        )
                    }
                    IconButton(onClick = vm::next, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.SkipNext, t("Next", "下一首"), modifier = Modifier.size(36.dp))
                    }
                    IconButton(onClick = vm::cycleRepeat) {
                        Icon(
                            if (state.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE)
                                Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            t("Repeat", "循环"),
                            tint = if (state.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF)
                                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (speed != 1f) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t("Speed %.2f×".format(speed), "速度 %.2f×".format(speed)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                val sleep = sleepDeadline
                if (sleep != null) {
                    SleepCountdown(sleep)
                }
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    QuickAction(t("Sleep", "定时"), Icons.Rounded.Timer) { showSleep = true }
                    QuickAction(t("Speed", "速度"), Icons.Rounded.Speed) { showSpeed = true }
                    QuickAction(t("Queues", "队列"), Icons.Rounded.QueueMusic, onOpenQueues)
                }
            }
        }
    }

    if (showSleep) {
        SleepDialog(
            active = sleepDeadline != null,
            onDismiss = { showSleep = false },
            onPick = { minutes ->
                vm.setSleepTimer(minutes)
                showSleep = false
            },
        )
    }
    if (showSpeed) {
        SpeedDialog(
            initial = speed,
            onDismiss = { value -> showSpeed = false; value?.let(vm::setSpeed) },
        )
    }
    if (showSaveQueue) {
        TextPromptDialog(
            title = t("Save the current queue", "保存当前队列"),
            initial = t("Queue", "队列"),
            onDismiss = { showSaveQueue = false },
            onConfirm = {
                vm.saveCurrentQueue(it)
                showSaveQueue = false
            },
        )
    }
    if (showPlaylistPicker) {
        PlaylistPicker(
            playlists = playlists,
            onDismiss = { showPlaylistPicker = false },
            onPick = { id ->
                current?.let { vm.addToPlaylist(id, it) }
                showPlaylistPicker = false
            },
        )
    }
}

@Composable
private fun SeekBar(state: PlayerUiState, onSeek: (Long) -> Unit) {
    var dragging by remember(state.mediaId) { mutableStateOf<Float?>(null) }
    val position = dragging ?: state.positionMs.toFloat()
    val duration = state.durationMs.coerceAtLeast(1L)
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = (position / duration).coerceIn(0f, 1f),
            onValueChange = { dragging = it * duration },
            onValueChangeFinished = {
                dragging?.let { onSeek(it.toLong()) }
                dragging = null
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(position.toLong()), style = MaterialTheme.typography.bodyMedium)
            Text(formatDuration(duration), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SleepCountdown(deadline: Long) {
    val remaining by produceState(initialValue = deadline - System.currentTimeMillis(), deadline) {
        while (value > 0) {
            value = deadline - System.currentTimeMillis()
            delay(1_000)
        }
    }
    Text(
        t(
            "Sleep timer · ${formatDuration(remaining.coerceAtLeast(0))} left",
            "睡眠定时 · 还剩 ${formatDuration(remaining.coerceAtLeast(0))}",
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun QuickAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(10.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SleepDialog(active: Boolean, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val options = listOf(
        0 to t("Off", "关闭"),
        5 to t("5 minutes", "5 分钟"),
        15 to t("15 minutes", "15 分钟"),
        30 to t("30 minutes", "30 分钟"),
        60 to t("1 hour", "1 小时"),
        90 to t("1 hour 30", "1 小时 30 分"),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Sleep timer", "睡眠定时")) },
        text = {
            Column {
                if (active) {
                    Text(
                        t("A timer is running.", "定时器正在运行。"),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                options.forEach { (minutes, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(minutes) }.padding(vertical = 13.dp),
                    ) { Text(label, style = MaterialTheme.typography.bodyLarge) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(t("Close", "关闭")) }
        },
    )
}

@Composable
private fun SpeedDialog(initial: Float, onDismiss: (Float?) -> Unit) {
    var value by remember { mutableFloatStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDismiss(null) },
        title = { Text(t("Playback speed", "播放速度")) },
        text = {
            Column {
                Text("%.2f×".format(value), style = MaterialTheme.typography.headlineMedium)
                Slider(
                    value = value,
                    onValueChange = { value = (it * 20).toInt() / 20f },
                    valueRange = 0.5f..3f,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { preset ->
                        Text(
                            "%.2f×".format(preset),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (value == preset) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { value = preset }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (value == preset) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(value) }) { Text(t("Done", "完成")) }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(null) }) { Text(t("Cancel", "取消")) }
        },
    )
}

@Composable
private fun PlaylistPicker(
    playlists: List<dev.lilt.player.data.PlaylistSummary>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Add to playlist", "加入播放列表")) },
        text = {
            Column {
                if (playlists.isEmpty()) {
                    Text(t("Create a playlist from the Playlists tab first.", "请先在播放列表页新建列表。"))
                }
                playlists.forEach { playlist ->
                    Text(
                        playlist.name,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(playlist.id) }
                            .padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("Close", "关闭")) } },
    )
}
