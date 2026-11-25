package dev.lilt.player.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import dev.lilt.player.LockScreenState
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun LockScreenScreen(
    state: LockScreenState,
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
            val coverSize = minOf(maxWidth * 0.66f, maxHeight * 0.36f).coerceAtMost(320.dp)
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
                Spacer(Modifier.weight(if (compact) 0.5f else 0.7f))
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
                Spacer(Modifier.height(if (compact) 14.dp else 22.dp))
                Text(
                    state.title.ifBlank { t("Nothing playing", "暂无播放") },
                    color = Color.White,
                    style = if (compact) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        MaterialTheme.typography.headlineMedium
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
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
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
