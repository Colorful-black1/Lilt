package dev.lilt.player.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lilt.player.LiltViewModel
import dev.lilt.player.library.formatAdjustedTimestamp
import dev.lilt.player.library.lyricsSeekMs
import dev.lilt.player.library.parseLrc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen lyrics editor. Lyrics live in Room, so they survive rescans and
 * can be imported from any local .lrc/.txt document the user picks.
 */
@Composable
fun LyricsScreen(vm: LiltViewModel, mediaId: Long, onBack: () -> Unit) {
    val songs by vm.allSongs.collectAsStateWithLifecycle()
    val stored by remember(mediaId) { vm.lyricsFor(mediaId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val song = remember(songs, mediaId) { songs.firstOrNull { it.mediaId == mediaId } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lyricsFontSize by vm.lyricsFontSize.collectAsStateWithLifecycle()
    val lyricsOpacity by vm.lyricsOpacity.collectAsStateWithLifecycle()

    var draft by remember(mediaId) { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var offsetMs by remember(mediaId) { mutableIntStateOf(0) }
    var offsetTouched by remember(mediaId) { mutableStateOf(false) }
    val value = draft ?: stored?.text.orEmpty()
    val parsed = remember(value) { parseLrc(value) }

    LaunchedEffect(mediaId, stored) {
        if (!offsetTouched) offsetMs = stored?.offsetMs ?: 0
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()
                        ?.use { it.readText() }
                }.getOrNull()
            }
            if (!text.isNullOrBlank()) draft = text
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        topBar = {
            AppBar(
                title = t("Lyrics", "歌词"),
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = {
                            vm.saveLyrics(mediaId, value, offsetMs)
                            onBack()
                        },
                    ) {
                        Icon(Icons.Rounded.Save, t("Save lyrics", "保存歌词"))
                    }
                    if (value.isNotBlank()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Rounded.Delete, t("Clear lyrics", "清空歌词"))
                        }
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                song?.title ?: t("Unknown song", "未知歌曲"),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                song?.let { listOf(it.artist, it.album).filter(String::isNotBlank).joinToString(" · ") }
                    .orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    importLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
                }) {
                    Icon(Icons.Rounded.FileOpen, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(t("Import .lrc or .txt", "导入 .lrc 或 .txt"))
                }
                Text(
                    when {
                        value.isBlank() -> ""
                        parsed.timed -> t("${parsed.lines.size} timed lines", "${parsed.lines.size} 行带时间")
                        else -> t("Plain text", "纯文本")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = value,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth().height(430.dp),
                placeholder = {
                    Text(
                        t(
                            "Paste lyrics here.\n\n[00:12.50]First line\n[00:18.20]Second line",
                            "在此粘贴歌词。\n\n[00:12.50]第一行\n[00:18.20]第二行",
                        ),
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(14.dp),
            ) {
                Text(
                    t("Timing and preview", "时间校准与预览"),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    t(
                        "Positive offsets delay the lyrics; negative values make them earlier. Each song keeps its own value.",
                        "正值让歌词延后，负值让歌词提前。每首歌单独保存。",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                LyricsOffsetControls(
                    offsetMs = offsetMs,
                    onOffsetChange = {
                        offsetTouched = true
                        offsetMs = it
                    },
                )
                if (parsed.timed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        t("Adjusted timestamps", "调整后时间"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    parsed.lines.take(3).forEach { line ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                formatAdjustedTimestamp(lyricsSeekMs(line.timeMs, offsetMs)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                line.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    t("Display preview", "显示预览"),
                    style = MaterialTheme.typography.labelLarge,
                )
                val previewAlpha = lyricsOpacity.coerceIn(0.35f, 1f)
                val previewSize = lyricsFontSize.coerceIn(12f, 24f)
                Text(
                    parsed.lines.firstOrNull()?.text
                        ?: t("Glass horizon, open wide", "玻璃地平线，向远处展开"),
                    style = TextStyle(
                        fontSize = (previewSize + 2f).sp,
                        lineHeight = (previewSize * 1.45f).sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = previewAlpha),
                )
                Text(
                    parsed.lines.getOrNull(1)?.text
                        ?: t("A steady pulse beneath the floor", "地板下稳定的脉搏"),
                    style = TextStyle(
                        fontSize = previewSize.sp,
                        lineHeight = (previewSize * 1.45f).sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = previewAlpha),
                )
            }
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(14.dp),
            ) {
                Text(
                    t("How lyrics work", "歌词说明"),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    t(
                        "Lyrics are stored on this phone and survive a rescan. LRC timestamps highlight the current line; plain text is shown as written. Timed lines use [mm:ss.xx].",
                        "歌词保存在本机，重新扫描不会丢失。LRC 时间标签会高亮当前行；纯文本按原样显示。时间格式为 [分:秒.厘秒]。",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = t("Clear these lyrics?", "清空这首歌的歌词？"),
            body = t("This only removes the stored lyrics.", "只会删除已保存的歌词。"),
            onDismiss = { confirmClear = false },
            onConfirm = {
                vm.deleteLyrics(mediaId)
                draft = ""
            },
        )
    }
}
