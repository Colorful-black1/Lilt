package dev.lilt.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import dev.lilt.player.LockScreenState
import dev.lilt.player.data.LyricsEntity
import dev.lilt.player.library.activeLrcLine
import dev.lilt.player.library.parseLrc
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun LockScreenScreen(
    state: LockScreenState,
    lyrics: LyricsEntity?,
    isFavorite: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onUnlock: () -> Unit,
) {
    BackHandler(onBack = onUnlock)
    val language = LocalLanguage.current
    val now by produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1_000L)
        }
    }
    val dateFormatter = remember(language) {
        DateTimeFormatter.ofPattern(
            if (language == "zh") "M月d日 EEE" else "EEE, MMM d",
            if (language == "zh") Locale.CHINA else Locale.ENGLISH,
        )
    }
    val dateText = remember(language, now.year, now.monthValue, now.dayOfMonth) {
        dateFormatter.format(now)
    }
    val timeText = "%02d:%02d".format(now.hour, now.minute)
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val unlockThreshold = with(LocalDensity.current) { 104.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragDistance = 0f },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        dragDistance += amount
                    },
                    onDragEnd = {
                        if (dragDistance <= -unlockThreshold) onUnlock()
                        dragDistance = 0f
                    },
                    onDragCancel = { dragDistance = 0f },
                )
            },
    ) {
        AlbumArt(
            albumId = state.albumId,
            modifier = Modifier.fillMaxSize().alpha(0.42f),
            corner = 0.dp,
            showNote = false,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.22f),
                            Color.Black.copy(alpha = 0.42f),
                            Color.Black.copy(alpha = 0.92f),
                        ),
                    ),
                ),
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 26.dp),
        ) {
            val compact = maxHeight < 700.dp
            val coverSize = if (compact) {
                minOf(maxWidth * 0.42f, maxHeight * 0.20f).coerceIn(92.dp, 148.dp)
            } else {
                // Cap the cover so tall screens give the extra height to the
                // lyric window instead of a larger artwork square.
                minOf(maxWidth * 0.62f, maxHeight * 0.26f).coerceIn(150.dp, 200.dp)
            }
            val clockSize = if (compact) 48.sp else 64.sp
            val playSize = if (compact) 64.dp else 76.dp
            val sideSize = if (compact) 48.dp else 52.dp
            val transportIcon = if (compact) 32.dp else 36.dp
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(if (compact) 6.dp else 12.dp))
                Text(
                    dateText,
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    timeText,
                    color = Color.White,
                    fontSize = clockSize,
                    fontWeight = FontWeight.Light,
                    letterSpacing = (-2).sp,
                    maxLines = 1,
                )
                Spacer(Modifier.height(if (compact) 10.dp else 18.dp))
                Box(
                    modifier = Modifier
                        .size(coverSize)
                        .shadow(26.dp, RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp)),
                ) {
                    AlbumArt(
                        albumId = state.albumId,
                        modifier = Modifier.fillMaxSize(),
                        corner = 20.dp,
                    )
                }
                Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
                Text(
                    state.title.ifBlank { t("Nothing playing", "暂无播放") },
                    color = Color.White,
                    style = if (compact) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOf(state.artist, state.album)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        subtitle,
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                LockScreenLyrics(
                    lyrics = lyrics,
                    hasTrack = state.hasTrack,
                    positionMs = state.positionMs,
                    compact = compact,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                LockScreenProgress(
                    state = state,
                    onSeek = onSeek,
                    compact = compact,
                )
                Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
                LockScreenTransport(
                    state = state,
                    isFavorite = isFavorite,
                    compact = compact,
                    playSize = playSize,
                    sideSize = sideSize,
                    transportIcon = transportIcon,
                    onPlayPause = onPlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onCycleRepeat = onCycleRepeat,
                    onToggleFavorite = onToggleFavorite,
                )
                Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
                UnlockHint(onUnlock = onUnlock)
                Spacer(Modifier.height(if (compact) 4.dp else 10.dp))
            }
        }
    }
}

/**
 * A five-line window that follows the active LRC line. Only the window is
 * composed, so long lyrics never push the active line outside the viewport;
 * a one-line slide keeps the highlight in the middle as playback advances.
 */
@Composable
private fun LockScreenLyrics(
    lyrics: LyricsEntity?,
    hasTrack: Boolean,
    positionMs: Long,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val document = remember(lyrics?.text) { parseLrc(lyrics?.text.orEmpty()) }
    val lineHeight = if (compact) 30.dp else 36.dp
    if (document.isEmpty) {
        Box(modifier = modifier.clipToBounds(), contentAlignment = Alignment.Center) {
            if (hasTrack) {
                Text(
                    t("No lyrics", "暂无歌词"),
                    color = Color.White.copy(alpha = 0.42f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        return
    }
    val timed = document.timed
    val offsetMs = lyrics?.offsetMs ?: 0
    val active = if (timed) activeLrcLine(document, positionMs, offsetMs) else -1
    val focus = if (timed) active.coerceAtLeast(0) else 0
    val highlighted = timed && active >= 0
    BoxWithConstraints(
        modifier = modifier.clipToBounds(),
    ) {
        val viewportHeight = maxHeight
        val density = LocalDensity.current
        val lineHeightPx = with(density) { lineHeight.toPx() }
        val widthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val measurer = rememberTextMeasurer()
        // Keep the whole five-line window inside short screens too; a
        // two-line active lyric needs one extra row of headroom.
        val windowRadius = if (viewportHeight >= lineHeight * 5.5f) 2 else 1
        val activeStyle = remember(compact) {
            TextStyle(
                fontSize = if (compact) 15.sp else 16.sp,
                lineHeight = if (compact) 21.sp else 22.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
        AnimatedContent(
            targetState = focus,
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopStart,
            transitionSpec = {
                if (targetState == initialState + 1) {
                    (
                        slideInVertically(
                            animationSpec = tween(360, easing = FastOutSlowInEasing),
                            initialOffsetY = { lineHeightPx.roundToInt() },
                        ) + fadeIn(tween(140))
                        ) togetherWith (
                        slideOutVertically(
                            animationSpec = tween(360, easing = FastOutSlowInEasing),
                            targetOffsetY = { -lineHeightPx.roundToInt() },
                        ) + fadeOut(tween(90))
                        )
                } else {
                    fadeIn(tween(200)) togetherWith fadeOut(tween(120))
                }
            },
            label = "lock-lyrics",
        ) { index ->
            // A five-line window keeps every child near the viewport. A
            // single tall column would be clipped once playback moved past
            // the first few lines.
            val first = (index - windowRadius).coerceAtLeast(0)
            val last = (index + windowRadius).coerceAtMost(document.lines.lastIndex)
            val activeHeight = remember(index, widthPx, compact, document) {
                if (timed) {
                    val result = measurer.measure(
                        text = document.lines[index].text,
                        style = activeStyle,
                        constraints = Constraints(maxWidth = widthPx),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    lineHeight * result.lineCount.coerceAtLeast(1)
                } else {
                    lineHeight
                }
            }
            val rowsBefore = index - first
            val target = if (timed) {
                viewportHeight / 2 - (lineHeight * rowsBefore + activeHeight / 2)
            } else {
                // Plain text has no timestamps, so centre the whole block.
                viewportHeight / 2 - lineHeight * (last - first + 1) / 2
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = target),
            ) {
                for (lineIndex in first..last) {
                    val line = document.lines[lineIndex]
                    val activeLine = highlighted && lineIndex == index
                    val distance = abs(lineIndex - index)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (activeLine) activeHeight else lineHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            line.text,
                            color = when {
                                !timed -> Color.White.copy(alpha = 0.76f)
                                activeLine -> Color.White
                                distance == 0 -> Color.White.copy(alpha = 0.56f)
                                distance == 1 -> Color.White.copy(alpha = 0.66f)
                                distance == 2 -> Color.White.copy(alpha = 0.42f)
                                else -> Color.White.copy(alpha = 0.26f)
                            },
                            style = if (activeLine) {
                                activeStyle
                            } else {
                                TextStyle(
                                    fontSize = if (compact) 14.sp else 15.sp,
                                    fontWeight = FontWeight.Normal,
                                    textAlign = TextAlign.Center,
                                )
                            },
                            maxLines = if (activeLine) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LockScreenProgress(
    state: LockScreenState,
    onSeek: (Long) -> Unit,
    compact: Boolean,
) {
    val duration = state.durationMs.coerceAtLeast(0L)
    var dragging by remember(state.mediaId) { mutableStateOf(false) }
    var dragPosition by remember(state.mediaId) { mutableFloatStateOf(0f) }
    val position = if (dragging) dragPosition.toLong() else state.positionMs
    val colors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = Color.White,
        inactiveTrackColor = Color.White.copy(alpha = 0.24f),
        disabledThumbColor = Color.White.copy(alpha = 0.3f),
        disabledActiveTrackColor = Color.White.copy(alpha = 0.3f),
        disabledInactiveTrackColor = Color.White.copy(alpha = 0.12f),
    )
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = if (duration > 0L) {
                position.toFloat().coerceIn(0f, duration.toFloat())
            } else {
                0f
            },
            onValueChange = {
                dragging = true
                dragPosition = it
            },
            onValueChangeFinished = {
                if (duration > 0L) onSeek(dragPosition.toLong())
                dragging = false
            },
            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
            enabled = duration > 0L && state.hasTrack,
            colors = colors,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatDuration(position),
                color = Color.White.copy(alpha = 0.62f),
                fontSize = if (compact) 11.sp else 12.sp,
            )
            Text(
                formatDuration(duration),
                color = Color.White.copy(alpha = 0.62f),
                fontSize = if (compact) 11.sp else 12.sp,
            )
        }
    }
}

@Composable
private fun LockScreenTransport(
    state: LockScreenState,
    isFavorite: Boolean,
    compact: Boolean,
    playSize: Dp,
    sideSize: Dp,
    transportIcon: Dp,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val iconSize = if (compact) 26.dp else 28.dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            IconButton(onClick = onCycleRepeat, enabled = state.hasTrack) {
                Icon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) {
                        Icons.Rounded.RepeatOne
                    } else {
                        Icons.Rounded.Repeat
                    },
                    contentDescription = t("Repeat", "循环"),
                    tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.White.copy(alpha = 0.82f)
                    },
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onPrevious,
                enabled = state.hasTrack,
                modifier = Modifier.size(sideSize),
            ) {
                Icon(
                    Icons.Rounded.SkipPrevious,
                    contentDescription = t("Previous", "上一首"),
                    tint = Color.White,
                    modifier = Modifier.size(transportIcon),
                )
            }
            Box(
                modifier = Modifier
                    .size(playSize)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f))
                    .clickable(enabled = state.hasTrack, onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = t("Play or pause", "播放或暂停"),
                    tint = Color.White,
                    modifier = Modifier.size(if (compact) 30.dp else 34.dp),
                )
            }
            IconButton(
                onClick = onNext,
                enabled = state.hasTrack,
                modifier = Modifier.size(sideSize),
            ) {
                Icon(
                    Icons.Rounded.SkipNext,
                    contentDescription = t("Next", "下一首"),
                    tint = Color.White,
                    modifier = Modifier.size(transportIcon),
                )
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            IconButton(onClick = onToggleFavorite, enabled = state.hasTrack) {
                Icon(
                    if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (isFavorite) {
                        t("Remove favorite", "取消收藏")
                    } else {
                        t("Favorite", "收藏")
                    },
                    tint = if (isFavorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.White.copy(alpha = 0.82f)
                    },
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

@Composable
private fun UnlockHint(onUnlock: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "unlock")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "unlock-alpha",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onUnlock)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.Center) {
            repeat(3) { index ->
                Icon(
                    Icons.Rounded.KeyboardArrowUp,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = alpha * (0.58f + index * 0.15f)),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            t("Swipe up to unlock", "滑动解锁"),
            color = Color.White.copy(alpha = 0.82f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
