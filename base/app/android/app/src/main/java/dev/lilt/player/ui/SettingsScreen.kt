package dev.lilt.player.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lilt.player.LiltViewModel
import dev.lilt.player.data.ScanState
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    vm: LiltViewModel,
    onBack: () -> Unit,
    onOpenRules: () -> Unit,
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
    val scan by vm.scanState.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val allSongs by vm.allSongs.collectAsStateWithLifecycle()

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
                Text("Lilt 1.0", style = MaterialTheme.typography.titleLarge)
                Text(
                    t(
                        "A local music player. No account, no network, no tracking.",
                        "本地音乐播放器。没有账号，没有网络，没有追踪。",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                SettingLink(t("About this build", "关于此版本"), t("What is included and what is not.", "包含与未包含的功能。"), onOpenAbout)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

private const val LYRICS_PREVIEW_TITLE = "Glass horizon, open wide"
private const val LYRICS_PREVIEW_BODY = "A steady pulse beneath the floor"

@Composable
private fun LyricsDisplaySlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
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
        Slider(
            value = local,
            onValueChange = { local = it },
            valueRange = range,
            steps = steps,
            onValueChangeFinished = { onChange(local) },
        )
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
private fun SettingLink(title: String, body: String, onClick: () -> Unit) {
    Row(
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
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        topBar = { AppBar(title = t("About", "关于"), onBack = onBack) },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(22.dp),
        ) {
            Text("Lilt", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(
                t("Offline music, close at hand.", "离线音乐，触手可及。"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))
            Text(
                t(
                    "This Android build scans the audio already on the phone through MediaStore. It keeps the library, playlists and queues in a local Room database. Playback runs in a foreground media session, so the notification, lock screen and headset buttons keep working while the screen is off. It requests no internet permission and includes no network, advertising or analytics code.",
                    "这个 Android 版本通过 MediaStore 扫描手机中已有的音频，并把曲库、播放列表和队列保存在本地 Room 数据库。播放由前台媒体会话负责，所以息屏后通知栏、锁屏和耳机按键仍可控制。应用不申请网络权限，也不包含任何联网、广告或统计代码。",
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(18.dp))
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
                t("Per-song lyric offset, font size and opacity", "每首歌歌词偏移、字号与透明度"),
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
                    "Batch tag editing, cover replacement, embedded ReplayGain writing, home-screen widgets, Android Auto and cast are not part of this build. They stay on the later list instead of being replaced with fake data.",
                    "批量标签编辑、封面替换、ReplayGain 写入、桌面小部件、Android Auto 与投屏尚未包含。它们保留在后续清单中，而不是用假数据代替。",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
