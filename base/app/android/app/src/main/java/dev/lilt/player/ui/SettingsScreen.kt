package dev.lilt.player.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lilt.player.LiltViewModel
import dev.lilt.player.data.UpdateChecker
import dev.lilt.player.data.BackupPreview
import dev.lilt.player.data.ScanState
import dev.lilt.player.library.AI_PROVIDERS
import dev.lilt.player.library.DuplicateCandidate
import dev.lilt.player.library.accentArgb
import dev.lilt.player.library.findAiProvider
import dev.lilt.player.library.findDuplicateGroups
import dev.lilt.player.widget.LiltWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    vm: LiltViewModel,
    onBack: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenMissingFiles: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenEqualizer: () -> Unit,
) {
    val theme by vm.theme.collectAsStateWithLifecycle()
    val accent by vm.accent.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    val includeOnly by vm.includeOnly.collectAsStateWithLifecycle()
    val conflict by vm.queueConflict.collectAsStateWithLifecycle()
    val rememberPositions by vm.rememberQueuePositions.collectAsStateWithLifecycle()
    val fadeIn by vm.fadeInMs.collectAsStateWithLifecycle()
    val fadeOut by vm.fadeOutMs.collectAsStateWithLifecycle()
    val lyricsFontSize by vm.lyricsFontSize.collectAsStateWithLifecycle()
    val lyricsOpacity by vm.lyricsOpacity.collectAsStateWithLifecycle()
    val floatingLyrics by vm.floatingLyrics.collectAsStateWithLifecycle()
    val floatingLocked by vm.floatingLyricsLocked.collectAsStateWithLifecycle()
    val floatingFontSize by vm.floatingFontSize.collectAsStateWithLifecycle()
    val floatingPlayedColor by vm.floatingPlayedColor.collectAsStateWithLifecycle()
    val floatingUnplayedColor by vm.floatingUnplayedColor.collectAsStateWithLifecycle()
    val onlineLyrics by vm.onlineLyrics.collectAsStateWithLifecycle()
    val aiProvider by vm.aiProvider.collectAsStateWithLifecycle()
    val aiBaseUrl by vm.aiBaseUrl.collectAsStateWithLifecycle()
    val aiModel by vm.aiModel.collectAsStateWithLifecycle()
    val aiKeySaved by vm.aiKeySaved.collectAsStateWithLifecycle()
    val aiModels by vm.aiModels.collectAsStateWithLifecycle()
    val aiSettingsBusy by vm.aiSettingsBusy.collectAsStateWithLifecycle()
    val aiSettingsStatus by vm.aiSettingsStatus.collectAsStateWithLifecycle()
    val scan by vm.scanState.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val allSongs by vm.allSongs.collectAsStateWithLifecycle()
    val missingFiles by vm.missingFiles.collectAsStateWithLifecycle()
    val duplicateGroups = remember(allSongs) {
        findDuplicateGroups(
            allSongs.map { song ->
                DuplicateCandidate(
                    title = song.title.ifBlank { song.displayName.substringBeforeLast('.') },
                    artist = song.artist,
                    durationMs = song.durationMs,
                    size = song.size,
                    bitrate = if (song.durationMs > 0) {
                        ((song.size * 8) / song.durationMs).toInt()
                    } else {
                        0
                    },
                )
            },
        )
    }

    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val defaultFloatingUnplayed = 0xFFF8F3EA.toInt()
    val effectivePlayedColor = floatingPlayedColor ?: accentArgb(accent)
    val effectiveUnplayedColor = floatingUnplayedColor ?: defaultFloatingUnplayed
    var colorTarget by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refreshMissingFiles() }
    val isChinese = language == "zh"
    fun countText(count: Int, one: String, many: String, zh: String): String =
        "$count " + if (isChinese) zh else if (count == 1) one else many
    var overlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayAllowed = Settings.canDrawOverlays(context)
                vm.refreshFloatingOverlay()
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    val backupScope = rememberCoroutineScope()
    var backupBusy by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Pair<String, BackupPreview>?>(null) }
    val backupExportedText = t("Backup exported.", "备份已导出。")
    val exportFailedText = t("Export failed.", "导出失败。")
    val notBackupText = t("This is not a Lilt backup.", "这不是 Lilt 备份文件。")
    val restoreFailedText = t("Restore failed.", "恢复失败。")
    val referencesMissingText = t("references were not found.", "个引用未找到。")
    val backupRestoredText = t("Backup restored: ", "备份已恢复：")
    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            backupBusy = true
            val result = runCatching {
                val text = withContext(Dispatchers.IO) { vm.exportBackup() }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("Could not open the selected file.")
                }
            }
            backupBusy = false
            Toast.makeText(
                context,
                if (result.isSuccess) {
                    backupExportedText
                } else {
                    result.exceptionOrNull()?.message ?: exportFailedText
                },
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            backupBusy = true
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Could not read the selected file.")
                }
                text to withContext(Dispatchers.IO) { vm.previewBackup(text) }
            }
            backupBusy = false
            result.onSuccess { pendingRestore = it }
                .onFailure {
                    Toast.makeText(
                        context,
                        it.message ?: notBackupText,
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        topBar = { AppBar(title = t("Settings", "设置"), onBack = onBack) },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            SettingCard(t("Appearance", "外观")) {
                SettingLabel(t("Theme", "主题"), t("Black keeps night listening calm.", "纯黑更适合夜晚聆听。"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    listOf(
                        "black" to t("Black", "纯黑"),
                        "dark" to t("Dark", "深色"),
                        "light" to t("Light", "浅色"),
                        "system" to t("System", "跟随系统"),
                    ).forEach { (id, label) ->
                        ChoicePill(label, selected = theme == id) { vm.setTheme(id) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                SettingLabel(t("Accent", "强调色"), "")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                    accents.forEach { choice ->
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(choice.color)
                                .clickable { vm.setAccent(choice.id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (accent == choice.id) {
                                Icon(Icons.Rounded.Check, null, tint = Color(0xFF201A12), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SettingCard(t("Language", "语言")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoicePill("English", selected = language == "en") { vm.setLanguage("en") }
                    ChoicePill("中文", selected = language == "zh") { vm.setLanguage("zh") }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    t("The interface switches immediately.", "界面会立即切换语言。"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            SettingCard(t("Library", "曲库")) {
                SettingSwitch(
                    title = t("Include-only folders", "仅包含指定文件夹"),
                    body = t("When on, only folders marked Include are shown.", "开启后，只显示标记为“包含”的文件夹。"),
                    checked = includeOnly,
                ) { vm.setIncludeOnly(it) }
                SettingLink(
                    title = t("Folder rules", "文件夹规则"),
                    body = t("Exclude a folder or include just a few.", "排除整个文件夹，或只保留少数几个。"),
                    onClick = onOpenRules,
                )
                SettingLink(
                    title = t("Missing files", "失效文件"),
                    body = if (missingFiles.isEmpty()) {
                        t("No orphaned playlists or saved song data.", "没有失效的歌单引用或歌曲数据。")
                    } else {
                        countText(missingFiles.size, "track", "tracks", "首歌曲") + " " +
                            t(
                                "can be reconnected to files in the library.",
                                "可以重新关联到曲库文件。",
                            )
                    },
                    onClick = onOpenMissingFiles,
                )
                SettingLink(
                    title = t("Duplicate songs", "重复歌曲"),
                    body = if (duplicateGroups.isEmpty()) {
                        t("No suspected duplicates.", "没有疑似重复的歌曲。")
                    } else {
                        countText(duplicateGroups.size, "group", "groups", "组") + " · " +
                            countText(
                                duplicateGroups.sumOf { it.indices.size },
                                "file",
                                "files",
                                "个文件",
                            )
                    },
                    onClick = onOpenDuplicates,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t("Device scan", "设备扫描"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (val state = scan) {
                                is ScanState.Scanning -> t("Scanning… ${state.seen} found", "正在扫描… 已找到 ${state.seen} 首")
                                is ScanState.Done -> t(
                                    "${allSongs.size} songs in the library · ${songs.size} visible",
                                    "曲库中有 ${allSongs.size} 首歌 · 当前显示 ${songs.size} 首",
                                )
                                is ScanState.Error -> state.message
                                else -> t(
                                    "${allSongs.size} songs in the library · ${songs.size} visible",
                                    "曲库中有 ${allSongs.size} 首歌 · 当前显示 ${songs.size} 首",
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = vm::scan) {
                        Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(t("Rescan", "重新扫描"))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SettingCard(t("Audio", "音频")) {
                SettingLink(
                    title = t("Equalizer", "均衡器"),
                    body = t("Ten bands, stored for this phone.", "十段均衡，保存在本机。"),
                    onClick = onOpenEqualizer,
                )
                FadeSlider(
                    title = t("Fade in on play", "播放淡入"),
                    valueMs = fadeIn,
                    onChange = vm::setFadeInMs,
                )
                FadeSlider(
                    title = t("Fade out on pause", "暂停淡出"),
                    valueMs = fadeOut,
                    onChange = vm::setFadeOutMs,
                )
                Text(
                    t(
                        "Output follows the phone's sound settings. Use the hardware volume keys while music plays.",
                        "输出跟随手机声音设置。播放时可用音量键调节。",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            SettingCard(t("Lyrics", "歌词")) {
                Text(
                    t(
                        "Applies to timed lyrics in the player. Each song keeps its own offset there.",
                        "应用于播放页的同步歌词。每首歌单独保存偏移。",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LyricsDisplaySlider(
                    title = t("Font size", "字号"),
                    value = lyricsFontSize,
                    range = 12f..24f,
                    steps = 23,
                    format = { "%.1f sp".format(it) },
                    zoom = true,
                    onChange = vm::setLyricsFontSize,
                )
                LyricsDisplaySlider(
                    title = t("Opacity", "透明度"),
                    value = lyricsOpacity,
                    range = 0.35f..1f,
                    steps = 12,
                    format = { "${(it * 100).roundToInt()} %" },
                    onChange = vm::setLyricsOpacity,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    LYRICS_PREVIEW_TITLE,
                    style = TextStyle(
                        fontSize = (lyricsFontSize + 2f).sp,
                        lineHeight = (lyricsFontSize * 1.45f).sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = lyricsOpacity),
                )
                Text(
                    LYRICS_PREVIEW_BODY,
                    style = TextStyle(
                        fontSize = lyricsFontSize.sp,
                        lineHeight = (lyricsFontSize * 1.45f).sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = lyricsOpacity),
                )
            }

            Spacer(Modifier.height(12.dp))
            SettingCard(t("Online lyrics", "在线歌词")) {
                Text(
                    t(
                        "When a song starts without lyrics, Lilt queries LRCLIB in the background. Only title, artist, album and duration are sent. The AI fallback is manual and its result is previewed before saving.",
                        "歌曲开始播放且没有歌词时，Lilt 会在后台查询 LRCLIB。只发送歌名、艺术家、专辑和时长。AI 兜底只在点击按钮时请求，结果先预览再保存。",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitch(
                    title = t("Fetch lyrics while playing", "播放时获取歌词"),
                    body = t(
                        "Audio files, paths and listening history never leave this device.",
                        "音频文件、路径和收听记录不会离开本机。",
                    ),
                    checked = onlineLyrics,
                ) { vm.setOnlineLyrics(it) }

                var providerMenu by remember { mutableStateOf(false) }
                var modelMenu by remember { mutableStateOf(false) }
                var keyInput by remember { mutableStateOf("") }

                Spacer(Modifier.height(6.dp))
                Text(
                    t("AI fallback (manual)", "AI 兜底（手动）"),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    t(
                        "OpenAI-compatible providers. Model coverage varies; if one model cannot find a track, try another. The preview marks AI output as potentially inaccurate.",
                        "OpenAI 兼容供应商。不同模型的歌词覆盖不同；某个模型找不到时可切换其他模型。预览会提示 AI 结果可能不准确。",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        t("Provider", "供应商"),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Box {
                        TextButton(onClick = { providerMenu = true }) {
                            Text(findAiProvider(aiProvider).name)
                        }
                        DropdownMenu(
                            expanded = providerMenu,
                            onDismissRequest = { providerMenu = false },
                        ) {
                            AI_PROVIDERS.forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(preset.name) },
                                    onClick = {
                                        vm.setAiProvider(preset.id)
                                        providerMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = aiBaseUrl,
                    onValueChange = { vm.setAiBaseUrl(it) },
                    label = { Text(t("Base URL", "接口地址")) },
                    supportingText = {
                        Text(
                            t(
                                "Adds /v1 when the path has no version segment; remote HTTP is rejected.",
                                "路径缺少版本号时会自动补 /v1；远程 HTTP 地址会被拒绝。",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = aiModel,
                        onValueChange = { vm.setAiModel(it) },
                        label = { Text(t("Model", "模型")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(
                        onClick = { vm.fetchAiModels() },
                        enabled = !aiSettingsBusy,
                    ) {
                        Text(t("Fetch models", "获取模型"))
                    }
                    if (aiSettingsBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                if (aiModels.isNotEmpty()) {
                    Box {
                        TextButton(onClick = { modelMenu = true }) {
                            Text(
                                t(
                                    "Choose a fetched model (${aiModels.size})",
                                    "选择已获取的模型（${aiModels.size}）",
                                ),
                            )
                        }
                        DropdownMenu(
                            expanded = modelMenu,
                            onDismissRequest = { modelMenu = false },
                        ) {
                            aiModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model) },
                                    onClick = {
                                        vm.setAiModel(model)
                                        modelMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = {
                            Text(
                                if (aiKeySaved) {
                                    t("API Key (saved)", "API Key（已保存）")
                                } else {
                                    t("API Key", "API Key")
                                },
                            )
                        },
                        placeholder = {
                            Text(
                                if (aiKeySaved) {
                                    t("Leave empty to keep it", "留空表示不修改")
                                } else {
                                    t("Paste the provider key", "粘贴供应商的 API Key")
                                },
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(
                        onClick = {
                            vm.saveAiKey(keyInput)
                            keyInput = ""
                        },
                        enabled = keyInput.isNotBlank(),
                    ) {
                        Text(t("Save", "保存"))
                    }
                    if (aiKeySaved) {
                        TextButton(onClick = { vm.clearAiKey() }) {
                            Text(t("Remove", "移除"))
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { vm.testAiConnection() },
                        enabled = !aiSettingsBusy,
                    ) {
                        Text(t("Test connection", "测试连接"))
                    }
                    TextButton(onClick = { vm.clearLyricsFetchCache() }) {
                        Text(t("Clear cache", "清理缓存"))
                    }
                }
                if (aiSettingsStatus.isNotBlank()) {
                    Text(
                        aiSettingsStatus,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    t(
                        "The AI provider may charge for requests. If one model cannot find lyrics, try another model. If you clear a song's lyrics, Lilt will not fetch them again automatically.",
                        "AI 请求可能产生费用。若某个模型找不到歌词，可切换其他模型。手动清空某首歌的歌词后，Lilt 不会再次自动获取。",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            SettingCard(t("Floating lyrics", "悬浮歌词")) {
                Text(
                    t(
                        "A draggable single-line lyric that stays above other apps. Locked mode leaves only the lyric text with no card or buttons. It reads lyrics already stored on this device.",
                        "悬浮在其他应用上方的单行歌词。锁定后只保留歌词文字，没有背景卡片和按钮。只读取本机已保存的歌词。",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitch(
                    title = t("Show over other apps", "在其他应用上层显示"),
                    body = t(
                        "Requires the display-over-other-apps permission. Drag the header to move it.",
                        "需要“显示在其他应用上层”权限。拖动标题条可移动位置。",
                    ),
                    checked = floatingLyrics,
                ) { enabled ->
                    vm.setFloatingLyrics(enabled)
                    overlayAllowed = Settings.canDrawOverlays(context)
                    if (enabled && !overlayAllowed) {
                        requestOverlayPermission(context)
                    }
                }
                if (floatingLyrics && !overlayAllowed) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            t(
                                "Overlay permission is not granted yet.",
                                "尚未授予悬浮窗权限。",
                            ),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { requestOverlayPermission(context) }) {
                            Text(t("Grant", "去授权"))
                        }
                    }
                }
                SettingSwitch(
                    title = t("Lock position and controls", "锁定位置与控件"),
                    body = t(
                        "Hides the drag bar, buttons and card background, leaving only the lyric. Touch input is disabled until unlocked here.",
                        "隐藏拖动条、按钮和背景，只保留歌词；窗口不再接收触摸，需在这里解锁。",
                    ),
                    checked = floatingLocked,
                ) { vm.setFloatingLyricsLocked(it) }
                LyricsDisplaySlider(
                    title = t("Floating size", "悬浮歌词字号"),
                    value = floatingFontSize,
                    range = 18f..44f,
                    steps = 51,
                    format = { "%.1f sp".format(it) },
                    zoom = true,
                    onChange = vm::setFloatingFontSize,
                )
                FloatingColorRow(
                    title = t("Played color", "已播放颜色"),
                    body = t(
                        "The part of the active line that has already been sung.",
                        "当前行已经唱过的部分。",
                    ),
                    color = effectivePlayedColor,
                    resetLabel = if (floatingPlayedColor != null) t("Theme", "主题") else null,
                    onPick = { colorTarget = "played" },
                    onReset = { vm.setFloatingPlayedColor(null) },
                )
                FloatingColorRow(
                    title = t("Unplayed color", "未播放颜色"),
                    body = t(
                        "The part still to sing. Defaults to warm white.",
                        "当前行还没唱到的部分，默认暖白色。",
                    ),
                    color = effectiveUnplayedColor,
                    resetLabel = if (floatingUnplayedColor != null) t("Default", "默认") else null,
                    onPick = { colorTarget = "unplayed" },
                    onReset = { vm.setFloatingUnplayedColor(null) },
                )
                FloatingLyricPreview(
                    played = effectivePlayedColor,
                    unplayed = effectiveUnplayedColor,
                )
                colorTarget?.let { target ->
                    LyricsColorDialog(
                        initial = if (target == "played") {
                            effectivePlayedColor
                        } else {
                            effectiveUnplayedColor
                        },
                        onDismiss = { colorTarget = null },
                        onSave = { color ->
                            if (target == "played") {
                                vm.setFloatingPlayedColor(color)
                            } else {
                                vm.setFloatingUnplayedColor(color)
                            }
                            colorTarget = null
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                val widgetUnavailable = t(
                    "Add Lilt from your launcher's widget picker.",
                    "请在桌面启动器的小部件列表中添加 Lilt。",
                )
                SettingLink(
                    t("Add home screen widget", "添加主屏小部件"),
                    t(
                        "Cover, title, progress and playback buttons.",
                        "封面、标题、进度与播放控制。",
                    ),
                ) {
                    requestWidgetPin(context, widgetUnavailable)
                }
            }

            Spacer(Modifier.height(12.dp))
            SettingCard(t("Backup & restore", "备份与恢复")) {
                Text(
                    t(
                        "One local JSON file with playlists, queues, favourites, history, lyrics, bookmarks, tags and settings. Audio files are never included.",
                        "一个本地 JSON 文件，包含播放列表、队列、收藏、历史、歌词、书签、标签和设置；不包含音频文件。",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                SettingLink(
                    t("Export backup", "导出备份"),
                    t(
                        "Choose where to save the JSON file.",
                        "选择 JSON 文件的保存位置。",
                    ),
                ) {
                    if (!backupBusy) {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
                            .format(Date())
                        createBackup.launch("lilt-backup-$stamp.json")
                    }
                }
                SettingLink(
                    t("Restore backup", "恢复备份"),
                    t(
                        "Replaces the user data on this phone after a confirmation.",
                        "确认后替换本机上的用户数据。",
                    ),
                ) {
                    if (!backupBusy) openBackup.launch(arrayOf("*/*"))
                }
                if (backupBusy) {
                    Text(
                        t("Working…", "处理中…"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            SettingCard(t("Queues", "队列")) {
                SettingSwitch(
                    title = t("Remember queue positions", "记住队列位置"),
                    body = t(
                        "Return to each queue where you left it.",
                        "切换队列时回到上次播放的位置。",
                    ),
                    checked = rememberPositions,
                ) { vm.setRememberQueuePositions(it) }
                SettingLabel(t("Same-name queue", "同名队列"), t("Used when you save a queue with a name that exists.", "保存同名队列时的处理方式。"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    ChoicePill(t("Replace", "替换"), selected = conflict == "replace") { vm.setQueueConflict("replace") }
                    ChoicePill(t("Merge", "合并"), selected = conflict == "merge") { vm.setQueueConflict("merge") }
                }
            }

            Spacer(Modifier.height(12.dp))
            SettingCard(t("About", "关于")) {
                Text(
                    "Lilt ${UpdateChecker.currentVersion(context)}",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    t(
                        "A local music player. No account, no tracking. Only the manual update check contacts GitHub.",
                        "本地音乐播放器。没有账号、没有追踪；只有手动检查更新时会访问 GitHub。",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                SettingLink(t("About & updates", "关于与更新"), t("Version, update check, and what is included.", "版本、检查更新，以及包含与未包含的功能。"), onOpenAbout)
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    pendingRestore?.let { (text, preview) ->
        AlertDialog(
            onDismissRequest = { if (!backupBusy) pendingRestore = null },
            title = { Text(t("Restore this backup?", "恢复此备份？")) },
            text = {
                Text(
                    t(
                        "This replaces playlists, queues, favourites, history, lyrics, bookmarks, tags and settings on this phone. Audio files are not changed.",
                        "这会替换本机的播放列表、队列、收藏、历史、歌词、书签、标签和设置；音频文件不会被修改。",
                    ) + "\n\n" + t(
                        "Backup contains: ",
                        "备份包含：",
                    ) + countText(preview.playlists, "playlist", "playlists", "个播放列表") +
                        ", " + countText(preview.queues, "queue", "queues", "个队列") +
                        ", " + countText(preview.favorites, "favourite", "favourites", "首收藏") +
                        ", " + countText(preview.lyrics, "lyric file", "lyric files", "份歌词") +
                        ", " + countText(preview.bookmarks, "bookmark", "bookmarks", "个书签"),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !backupBusy,
                    onClick = {
                        backupScope.launch {
                            backupBusy = true
                            val result = runCatching { vm.restoreBackup(text) }
                            backupBusy = false
                            pendingRestore = null
                            result.onSuccess { summary ->
                                val unresolved = if (summary.unresolved > 0) {
                                    " ${summary.unresolved} $referencesMissingText"
                                } else {
                                    ""
                                }
                                Toast.makeText(
                                    context,
                                    backupRestoredText +
                                        countText(
                                            summary.playlists,
                                            "playlist",
                                            "playlists",
                                            "个播放列表",
                                        ) +
                                        ", " + countText(
                                            summary.queues,
                                            "queue",
                                            "queues",
                                            "个队列",
                                        ) +
                                        unresolved,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }.onFailure {
                                Toast.makeText(
                                    context,
                                    it.message ?: restoreFailedText,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                ) {
                    Text(t("Restore", "恢复"))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) {
                    Text(t("Cancel", "取消"))
                }
            },
        )
    }
}

private const val LYRICS_PREVIEW_TITLE = "Glass horizon, open wide"
private const val LYRICS_PREVIEW_BODY = "A steady pulse beneath the floor"

private fun requestOverlayPermission(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )
    runCatching { context.startActivity(intent) }.onFailure {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }
}

private fun requestWidgetPin(context: Context, unavailable: String) {
    val manager = AppWidgetManager.getInstance(context)
    val provider = ComponentName(context, LiltWidgetProvider::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        manager.isRequestPinAppWidgetSupported
    ) {
        manager.requestPinAppWidget(provider, null, null)
    } else {
        Toast.makeText(context, unavailable, Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun LyricsDisplaySlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    zoom: Boolean = false,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    fun commit(next: Float) {
        local = next.coerceIn(range.start, range.endInclusive)
        onChange(local)
    }
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                format(local),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (zoom) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { commit(local - 1f) }) {
                    Text(t("Smaller", "缩小"))
                }
                Slider(
                    value = local,
                    onValueChange = { local = it },
                    valueRange = range,
                    steps = steps,
                    modifier = Modifier.weight(1f),
                    onValueChangeFinished = { onChange(local) },
                )
                TextButton(onClick = { commit(local + 1f) }) {
                    Text(t("Larger", "放大"))
                }
            }
        } else {
            Slider(
                value = local,
                onValueChange = { local = it },
                valueRange = range,
                steps = steps,
                onValueChangeFinished = { onChange(local) },
            )
        }
    }
}

@Composable
private fun FadeSlider(title: String, valueMs: Int, onChange: (Int) -> Unit) {
    var local by remember(valueMs) { mutableFloatStateOf(valueMs.toFloat()) }
    Column(Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (local <= 0f) t("Off", "关闭") else "%.1f s".format(local / 1000f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            valueRange = 0f..4_000f,
            steps = 15,
            onValueChangeFinished = { onChange(local.toInt()) },
        )
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun SettingLabel(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (body.isNotBlank()) {
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SettingSwitch(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingLink(title: String, body: String, onClick: () -> Unit) {    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AboutScreen(vm: LiltViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val update by vm.updateState.collectAsStateWithLifecycle()
    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        topBar = { AppBar(title = t("About", "关于"), onBack = onBack) },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(22.dp),
        ) {
            Text("Lilt", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(
                t("Local music, close at hand.", "本地音乐，触手可及。"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))
            Text(
                t(
                    "This Android build scans the audio already on the phone through MediaStore. It keeps the library, playlists and queues in a local Room database. Playback runs in a foreground media session, so the notification, lock screen and headset buttons keep working while the screen is off. Audio and user data stay on the device. Online lyrics are optional: only the track title, artist, album and duration are sent to LRCLIB or the AI endpoint configured in Settings, and the feature can be turned off there. The app includes no advertising, analytics or crash-reporting code. It can check GitHub Releases for updates when you press the button on this page.",
                    "这个 Android 版本通过 MediaStore 扫描手机中已有的音频，并把曲库、播放列表和队列保存在本地 Room 数据库。播放由前台媒体会话负责，所以息屏后通知栏、锁屏和耳机按键仍可控制。音频与用户数据都保存在本机。在线歌词是可选功能：只把歌名、艺术家、专辑和时长发送给 LRCLIB 或设置中配置的 AI 接口，可随时关闭。应用不包含广告、统计或崩溃上报代码。只有在这个页面手动检查更新时会访问 GitHub Releases。",
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(22.dp))
            Text(t("Software update", "软件更新"), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "${t("Current version", "当前版本")}: ${update.currentVersion}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            when {
                update.checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        t("Checking GitHub Releases…", "正在检查 GitHub Releases…"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                update.downloading -> {
                    Text(
                        t(
                            "Downloading v${update.latestVersion.orEmpty()}…",
                            "正在下载 v${update.latestVersion.orEmpty()}…",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    val progress = update.progress
                    if (progress == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                update.error != null -> Text(
                    "${t("Update check failed", "检查更新失败")}: ${update.error}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                update.downloadedPath != null -> Text(
                    t(
                        "v${update.latestVersion.orEmpty()} downloaded. The system installer will ask for confirmation.",
                        "v${update.latestVersion.orEmpty()} 已下载。系统安装器会再次确认。",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                update.upToDate -> Text(
                    t(
                        "You are up to date (latest v${update.latestVersion.orEmpty()}).",
                        "已是最新版本（最新 v${update.latestVersion.orEmpty()}）。",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                update.noRelease -> Text(
                    t("No release has been published yet.", "还没有已发布的版本。"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    t(
                        "Checks GitHub Releases and downloads the APK when a newer version is available. No library data is sent.",
                        "从 GitHub Releases 检查更新；发现新版本后自动下载 APK。不会发送曲库数据。",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = vm::checkForUpdates,
                    enabled = !update.checking && !update.downloading,
                ) {
                    Text(t("Check for updates", "检查更新"))
                }
                if (update.downloadedPath != null) {
                    Button(onClick = vm::installDownloadedUpdate) {
                        Text(t("Install update", "安装更新"))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                UpdateChecker.RELEASES_URL,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASES_URL)),
                        )
                    }
                },
            )
            Spacer(Modifier.height(22.dp))
            Text(t("In this build", "本版本包含"), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            listOf(
                t("MediaStore scan and folder browsing", "MediaStore 扫描与文件夹浏览"),
                t("Songs, albums, artists and favourites", "歌曲、专辑、艺术家与收藏"),
                t("Album and artist detail pages", "专辑与艺术家详情页"),
                t("Recent, most played, history and bookmarks", "最近添加、最多播放、历史与书签"),
                t("Sorting, multi-select and A-Z index", "排序、多选与字母索引"),
                t("Grouped search with query history", "分组搜索与搜索历史"),
                t("Local lyrics with LRC timing and import", "本地歌词、LRC 时间轴与导入"),
                t("Optional online lyrics from LRCLIB, plus a manual AI fallback", "可选的 LRCLIB 在线歌词与手动 AI 兜底"),
                t("Per-song lyric offset, font size and opacity", "每首歌歌词偏移、字号与透明度"),
                t("Played/unplayed lyric colours with progress fill", "已播放/未播放歌词颜色与进度染色"),
                t("Virtual single-song tag editing", "单曲虚拟标签编辑"),
                t("Foreground service, notification and lock-screen controls", "前台服务、通知栏与锁屏控制"),
                t("Named queues with M3U import and export", "命名队列与 M3U 导入导出"),
                t("Playlists, full sleep timer and playback speed", "播放列表、完整睡眠定时与播放速度"),
                t("Play/pause fades and queue position memory", "播放/暂停淡入淡出与队列位置记忆"),
                t("Ten-band equalizer with preamp, bass, balance and per-device profiles", "十段均衡器，支持前级、低音、平衡与按设备配置"),
                t("Folder include and exclude rules", "文件夹包含与排除规则"),
                t("English and Chinese interface", "中文与英文界面"),
            ).forEach {
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(t("Not in this build", "本版本未包含"), style = MaterialTheme.typography.titleLarge)
            Text(
                t(
                    "Batch tag editing, cover replacement, embedded ReplayGain writing, Android Auto and cast are not part of this build. They stay on the later list instead of being replaced with fake data.",
                    "批量标签编辑、封面替换、ReplayGain 写入、Android Auto 与投屏尚未包含。它们保留在后续清单中，而不是用假数据代替。",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FloatingColorRow(
    title: String,
    body: String,
    color: Int,
    resetLabel: String?,
    onPick: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (resetLabel != null) {
            TextButton(onClick = onReset) { Text(resetLabel) }
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(color))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable(onClick = onPick),
        )
    }
}

@Composable
private fun FloatingLyricPreview(played: Int, unplayed: Int) {
    val text = t("Played part / remaining part", "已播放部分 / 未播放部分")
    Canvas(Modifier.fillMaxWidth().height(40.dp)) {
        val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = 20.sp.toPx()
        }
        val x = 2.dp.toPx()
        val baseline = size.height * 0.68f
        paint.color = unplayed
        drawContext.canvas.nativeCanvas.drawText(text, x, baseline, paint)
        val textWidth = paint.measureText(text)
        drawContext.canvas.nativeCanvas.save()
        drawContext.canvas.nativeCanvas.clipRect(
            x,
            0f,
            x + textWidth * 0.55f,
            size.height,
        )
        paint.color = played
        drawContext.canvas.nativeCanvas.drawText(text, x, baseline, paint)
        drawContext.canvas.nativeCanvas.restore()
    }
}

@Composable
private fun LyricsColorDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    val hsv = remember(initial) {
        FloatArray(3).also { AndroidColor.colorToHSV(initial, it) }
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var brightness by remember { mutableFloatStateOf(hsv[2]) }
    val color = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness))
        .or(0xFF000000.toInt())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Lyric color", "歌词颜色")) },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(color)),
                )
                Spacer(Modifier.height(12.dp))
                Text(t("Hue", "色相"), style = MaterialTheme.typography.bodyMedium)
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
                Text(t("Saturation", "饱和度"), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = saturation,
                    onValueChange = { saturation = it },
                    valueRange = 0f..1f,
                )
                Text(t("Brightness", "亮度"), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = brightness,
                    onValueChange = { brightness = it },
                    valueRange = 0f..1f,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(color) }) {
                Text(t("Use color", "使用此颜色"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("Cancel", "取消")) }
        },
    )
}
