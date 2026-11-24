package dev.lilt.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import dev.lilt.player.LiltViewModel
import dev.lilt.player.PlayerUiState
import dev.lilt.player.library.activeLrcLine
import dev.lilt.player.library.lyricsSeekMs
import dev.lilt.player.library.parseLrc

/**
 * Immersive playback lyrics. The current line stays in the middle and each
 * timed line can be tapped to seek; editing still happens in [LyricsScreen].
 */
@Composable
fun LyricsPlayerScreen(
    vm: LiltViewModel,
    mediaId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
) {
    val player by vm.player.collectAsStateWithLifecycle()
    val songs by vm.allSongs.collectAsStateWithLifecycle()
    val activeMediaId = if (player.mediaId > 0) player.mediaId else mediaId
    val stored by remember(activeMediaId) { vm.lyricsFor(activeMediaId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val fetchState by remember(activeMediaId) { vm.lyricsFetchFor(activeMediaId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val onlineLyrics by vm.onlineLyrics.collectAsStateWithLifecycle()
    val aiKeySaved by vm.aiKeySaved.collectAsStateWithLifecycle()
    val lyricsFontSize by vm.lyricsFontSize.collectAsStateWithLifecycle()
    val lyricsOpacity by vm.lyricsOpacity.collectAsStateWithLifecycle()
    val aiLyrics by vm.aiLyrics.collectAsStateWithLifecycle()
    val candidates by vm.candidates.collectAsStateWithLifecycle()

    val song = remember(songs, activeMediaId) {
        songs.firstOrNull { it.mediaId == activeMediaId }
    }
    var offsetMs by remember(activeMediaId) { mutableIntStateOf(stored?.offsetMs ?: 0) }
    LaunchedEffect(activeMediaId, stored?.offsetMs) {
        stored?.let { offsetMs = it.offsetMs }
    }
    val document = remember(stored?.text) { parseLrc(stored?.text.orEmpty()) }
    val active = activeLrcLine(document, player.positionMs, offsetMs)
    val listState = rememberLazyListState()
    val alpha = lyricsOpacity.coerceIn(0.35f, 1f)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
        topBar = {
            AppBar(
                title = song?.title ?: t("Lyrics", "歌词"),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { onEdit(activeMediaId) }) {
                        Icon(Icons.Rounded.Edit, t("Edit lyrics", "编辑歌词"))
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOf(song?.artist.orEmpty(), song?.album.orEmpty())
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!stored?.source.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        lyricsSourceLabel(stored?.source),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            if (document.isEmpty) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.widthIn(max = 430.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Rounded.Lyrics,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.size(34.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            t("No lyrics yet", "还没有歌词"),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            t(
                                "Tap a timed line to seek. Lyrics stay on this phone.",
                                "点击带时间的歌词可以跳转。歌词只保存在本机。",
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(10.dp))
                        LyricsEmptyState(
                            fetchState = fetchState,
                            onlineLyrics = onlineLyrics,
                            aiKeySaved = aiKeySaved,
                            onRetry = { vm.retryOnlineLyrics(activeMediaId) },
                            onAi = { vm.startAiLyrics(activeMediaId) },
                            onCandidates = { vm.openCandidates(activeMediaId) },
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            t("Edit or import lyrics", "编辑或导入歌词"),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onEdit(activeMediaId) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val density = LocalDensity.current
                    val viewportPx = with(density) { maxHeight.toPx() }
                    val centerOffset =
                        (-(viewportPx * 0.5f) + with(density) { 16.dp.toPx() }).toInt()
                    LaunchedEffect(active, viewportPx) {
                        if (active >= 0) {
                            listState.animateScrollToItem(
                                active + if (document.timed) 1 else 0,
                                centerOffset,
                            )
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 0.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (document.timed) {
                            item { Spacer(Modifier.height(maxHeight / 2)) }
                        }
                        itemsIndexed(document.lines) { index, line ->
                            val isActive = index == active
                            val played = active >= 0 && index < active
                            val size = if (isActive) {
                                (lyricsFontSize + 7f).coerceIn(20f, 32f)
                            } else {
                                (lyricsFontSize + 2f).coerceIn(15f, 24f)
                            }
                            val color = when {
                                isActive -> MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                                played -> MaterialTheme.colorScheme.onSurfaceVariant
                                    .copy(alpha = alpha * 0.50f)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    .copy(alpha = alpha * 0.82f)
                            }
                            val background = if (isActive) {
                                Modifier.background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                    RoundedCornerShape(14.dp),
                                )
                            } else {
                                Modifier
                            }
                            Text(
                                line.text,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(background)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable(enabled = document.timed) {
                                        vm.seekTo(lyricsSeekMs(line.timeMs, offsetMs))
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(
                                    fontSize = size.sp,
                                    lineHeight = (size * 1.45f).sp,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                                color = color,
                            )
                        }
                        if (document.timed) {
                            item { Spacer(Modifier.height(maxHeight / 2)) }
                        }
                    }
                    val flatBackground = MaterialTheme.colorScheme.background
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(34.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(flatBackground, Color.Transparent),
                                ),
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(34.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, flatBackground),
                                ),
                            ),
                    )
                }
            }

            if (document.timed) {
                LyricsOffsetControls(
                    offsetMs = offsetMs,
                    onOffsetChange = {
                        offsetMs = it
                        vm.setLyricsOffset(activeMediaId, it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(4.dp))
            SeekBar(state = player, onSeek = vm::seekTo)
            Spacer(Modifier.height(4.dp))
            LyricsTransport(player = player, vm = vm)
            Spacer(Modifier.height(12.dp))
        }
    }

    if (aiLyrics.visible) {
        AiLyricsDialog(
            state = aiLyrics,
            onSave = vm::saveAiLyrics,
            onDismiss = vm::dismissAiLyrics,
        )
    }
    if (candidates.visible) {
        CandidateDialog(
            state = candidates,
            onSelect = vm::selectCandidate,
            onSave = vm::saveSelectedCandidate,
            onDismiss = vm::dismissCandidates,
        )
    }
}

@Composable
private fun LyricsTransport(player: PlayerUiState, vm: LiltViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = vm::toggleShuffle) {
            Icon(
                Icons.Rounded.Shuffle,
                t("Shuffle", "随机"),
                tint = if (player.shuffle) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = vm::previous, modifier = Modifier.size(50.dp)) {
            Icon(Icons.Rounded.SkipPrevious, t("Previous", "上一首"), modifier = Modifier.size(34.dp))
        }
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = vm::togglePlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                t("Play or pause", "播放或暂停"),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(36.dp),
            )
        }
        IconButton(onClick = vm::next, modifier = Modifier.size(50.dp)) {
            Icon(Icons.Rounded.SkipNext, t("Next", "下一首"), modifier = Modifier.size(34.dp))
        }
        IconButton(onClick = vm::cycleRepeat) {
            Icon(
                if (player.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne
                else Icons.Rounded.Repeat,
                t("Repeat", "循环"),
                tint = if (player.repeatMode != Player.REPEAT_MODE_OFF)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
