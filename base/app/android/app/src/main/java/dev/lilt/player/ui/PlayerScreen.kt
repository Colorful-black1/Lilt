package dev.lilt.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lyrics
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lilt.player.LiltViewModel
import dev.lilt.player.PlayerUiState
import dev.lilt.player.SleepUiState
import dev.lilt.player.data.SongEntity
import dev.lilt.player.library.LrcDocument
import dev.lilt.player.library.activeLrcLine
import dev.lilt.player.library.parseLrc

@Composable
fun PlayerScreen(
    vm: LiltViewModel,
    onBack: () -> Unit,
    onOpenQueues: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenLyrics: (Long) -> Unit,
) {
    val state by vm.player.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val speed by vm.playbackSpeed.collectAsStateWithLifecycle()
    val lyrics by remember(state.mediaId) { vm.lyricsFor(state.mediaId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val sleep by vm.sleep.collectAsStateWithLifecycle()
    val defaultHours by vm.sleepHours.collectAsStateWithLifecycle()
    val defaultMinutes by vm.sleepMinutes.collectAsStateWithLifecycle()
    val defaultTracks by vm.sleepTracks.collectAsStateWithLifecycle()
    val defaultWait by vm.sleepWaitTillEnd.collectAsStateWithLifecycle()
    val defaultFade by vm.sleepFade.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSaveQueue by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showBookmark by remember { mutableStateOf(false) }
    var bookmarkPosition by remember { mutableStateOf(0L) }

    val current = songs.firstOrNull { it.mediaId == state.mediaId }
    val currentBookmarks = remember(bookmarks, state.mediaId) {
        bookmarks.filter { it.mediaId == state.mediaId }.sortedBy { it.positionMs }
    }

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
                                text = { Text(t("Add bookmark", "添加书签")) },
                                leadingIcon = { Icon(Icons.Rounded.BookmarkAdd, null) },
                                enabled = current != null,
                                onClick = {
                                    menu = false
                                    bookmarkPosition = state.positionMs
                                    showBookmark = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(t("Lyrics", "歌词")) },
                                leadingIcon = { Icon(Icons.Rounded.Lyrics, null) },
                                enabled = current != null,
                                onClick = {
                                    menu = false
                                    current?.let { onOpenLyrics(it.mediaId) }
                                },
                            )
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
                if (sleep.active) {
                    SleepStatus(sleep)
                }
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    QuickAction(t("Sleep", "定时"), Icons.Rounded.Timer) { showSleep = true }
                    QuickAction(t("Speed", "速度"), Icons.Rounded.Speed) { showSpeed = true }
                    QuickAction(t("Bookmark", "书签"), Icons.Rounded.BookmarkAdd) {
                        bookmarkPosition = state.positionMs
                        showBookmark = true
                    }
                    QuickAction(t("Queues", "队列"), Icons.Rounded.QueueMusic, onOpenQueues)
                }
                if (currentBookmarks.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        currentBookmarks.forEach { bookmark ->
                            val label = bookmark.note.ifBlank {
                                formatDuration(bookmark.positionMs)
                            }
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { vm.seekTo(bookmark.positionMs) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                LyricsPanel(
                    text = lyrics?.text,
                    positionMs = state.positionMs,
                    onOpen = { if (state.mediaId > 0) onOpenLyrics(state.mediaId) },
                )
                Spacer(Modifier.height(18.dp))
            }
        }
    }

    if (showSleep) {
        SleepDialog(
            sleep = sleep,
            initialHours = defaultHours,
            initialMinutes = defaultMinutes,
            initialTracks = defaultTracks,
            initialWait = defaultWait,
            initialFade = defaultFade,
            onDismiss = { showSleep = false },
            onStartTime = { hours, minutes, wait, fade ->
                vm.startSleepTime(hours, minutes, wait, fade)
            },
            onStartTracks = { tracks, fade ->
                vm.startSleepTracks(tracks, fade)
            },
            onCancelTimer = vm::cancelSleep,
        )
    }
    if (showSpeed) {
        SpeedDialog(
            initial = speed,
            onDismiss = { value -> showSpeed = false; value?.let(vm::setSpeed) },
        )
    }
    if (showBookmark) {
        TextPromptDialog(
            title = t(
                "Add bookmark at ${formatDuration(bookmarkPosition)}",
                "在 ${formatDuration(bookmarkPosition)} 添加书签",
            ),
            label = t("Note (optional)", "备注（可选）"),
            onDismiss = { showBookmark = false },
            onConfirm = {
                vm.addBookmark(bookmarkPosition, it)
                showBookmark = false
            },
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
private fun SleepStatus(sleep: SleepUiState) {
    val label = when {
        sleep.mode == "tracks" -> t(
            "Sleep timer · ${sleep.tracksLeft} songs left",
            "睡眠定时 · 还剩 ${sleep.tracksLeft} 首",
        )
        sleep.pendingStop -> t(
            "Sleep timer · after this track",
            "睡眠定时 · 当前曲目结束后停止",
        )
        else -> t(
            "Sleep timer · ${formatDuration(sleep.remainingMs)} left",
            "睡眠定时 · 还剩 ${formatDuration(sleep.remainingMs)}",
        )
    }
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun LyricsPanel(text: String?, positionMs: Long, onOpen: () -> Unit) {
    val document = remember(text) { parseLrc(text.orEmpty()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Lyrics,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                t("Lyrics", "歌词"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (text.isNullOrBlank()) t("Add", "添加") else t("Edit", "编辑"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(8.dp))
        when {
            text.isNullOrBlank() -> Text(
                t(
                    "Tap to add lyrics for this song. LRC timestamps highlight the current line.",
                    "点击为这首歌添加歌词。LRC 时间标签会高亮当前行。",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            document.isEmpty -> Text(
                t("The saved lyrics are empty.", "已保存的歌词为空。"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            document.timed -> SyncedLyrics(document, positionMs)
            else -> Text(
                document.lines.joinToString("\n") { it.text },
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SyncedLyrics(document: LrcDocument, positionMs: Long) {
    val active = activeLrcLine(document, positionMs)
    val listState = rememberLazyListState()
    LaunchedEffect(active) {
        if (active >= 0) listState.animateScrollToItem(active)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().height(180.dp),
        contentPadding = PaddingValues(vertical = 40.dp),
    ) {
        itemsIndexed(document.lines) { index, line ->
            Text(
                line.text,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                textAlign = TextAlign.Center,
                style = if (index == active) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.bodyMedium,
                fontWeight = if (index == active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (index == active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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
private fun SleepDialog(
    sleep: SleepUiState,
    initialHours: Int,
    initialMinutes: Int,
    initialTracks: Int,
    initialWait: Boolean,
    initialFade: Boolean,
    onDismiss: () -> Unit,
    onStartTime: (Int, Int, Boolean, Boolean) -> Unit,
    onStartTracks: (Int, Boolean) -> Unit,
    onCancelTimer: () -> Unit,
) {
    var mode by remember { mutableStateOf("time") }
    var hours by remember { mutableStateOf(initialHours.toString()) }
    var minutes by remember { mutableStateOf(initialMinutes.toString()) }
    var tracks by remember { mutableStateOf(initialTracks.toString()) }
    var waitTillEnd by remember { mutableStateOf(initialWait) }
    var fade by remember { mutableStateOf(initialFade) }
    val timeTotal = (hours.toIntOrNull() ?: 0) * 60 + (minutes.toIntOrNull() ?: 0)
    val trackCount = tracks.toIntOrNull() ?: 0
    val valid = if (mode == "time") timeTotal > 0 else trackCount in 1..99

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Sleep timer", "睡眠定时")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (sleep.active) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when {
                                sleep.mode == "tracks" -> t(
                                    "Running · ${sleep.tracksLeft} songs left",
                                    "运行中 · 还剩 ${sleep.tracksLeft} 首",
                                )
                                sleep.pendingStop -> t(
                                    "Running · after this track",
                                    "运行中 · 当前曲目结束后停止",
                                )
                                else -> t(
                                    "Running · ${formatDuration(sleep.remainingMs)} left",
                                    "运行中 · 还剩 ${formatDuration(sleep.remainingMs)}",
                                )
                            },
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onCancelTimer(); onDismiss() }) {
                            Text(t("Cancel timer", "取消定时"))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoicePill(
                        label = t("After time", "按时长"),
                        selected = mode == "time",
                    ) { mode = "time" }
                    ChoicePill(
                        label = t("After songs", "按曲目数"),
                        selected = mode == "tracks",
                    ) { mode = "tracks" }
                }
                Spacer(Modifier.height(10.dp))
                if (mode == "time") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NumberField(
                            value = hours,
                            label = t("Hours", "小时"),
                            onValueChange = { hours = it.filter(Char::isDigit).take(2) },
                        )
                        NumberField(
                            value = minutes,
                            label = t("Minutes", "分钟"),
                            onValueChange = { minutes = it.filter(Char::isDigit).take(2) },
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(15, 30, 60, 90).forEach { preset ->
                            ChoicePill(
                                label = when {
                                    preset < 60 -> "$preset m"
                                    preset == 60 -> "1 h"
                                    else -> "1.5 h"
                                },
                                selected = timeTotal == preset,
                            ) {
                                hours = (preset / 60).toString()
                                minutes = (preset % 60).toString()
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    DialogSwitch(
                        title = t(
                            "Stop after the current track",
                            "等当前曲目结束后停止",
                        ),
                        checked = waitTillEnd,
                    ) { waitTillEnd = it }
                } else {
                    NumberField(
                        value = tracks,
                        label = t("Songs", "曲目数"),
                        onValueChange = { tracks = it.filter(Char::isDigit).take(2) },
                    )
                }
                DialogSwitch(
                    title = t("Fade out before stopping", "停止前淡出"),
                    checked = fade,
                ) { fade = it }
                Text(
                    t(
                        "The timer runs in the playback service, so it keeps working with the screen off.",
                        "定时器在播放服务中运行，息屏后仍会继续计时。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    if (mode == "time") {
                        onStartTime(
                            hours.toIntOrNull() ?: 0,
                            minutes.toIntOrNull() ?: 0,
                            waitTillEnd,
                            fade,
                        )
                    } else {
                        onStartTracks(trackCount, fade)
                    }
                    onDismiss()
                },
            ) { Text(t("Start", "开始")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("Close", "关闭")) }
        },
    )
}

@Composable
private fun NumberField(value: String, label: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(118.dp),
    )
}

@Composable
private fun DialogSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
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
