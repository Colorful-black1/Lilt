package dev.lilt.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lilt.player.LiltViewModel
import dev.lilt.player.library.parseTagNumber

/**
 * Edits display tags without rewriting the audio file. Values are stored in
 * Room and merged after every MediaStore scan; reset removes the override.
 */
@Composable
fun TagEditorScreen(vm: LiltViewModel, mediaId: Long, onBack: () -> Unit) {
    val songs by vm.allSongs.collectAsStateWithLifecycle()
    val overrideFlow = remember(mediaId) { vm.tagOverride(mediaId) }
    val override by overrideFlow.collectAsStateWithLifecycle(initialValue = null)
    val song = remember(songs, mediaId) { songs.firstOrNull { it.mediaId == mediaId } }

    var initialized by rememberSaveable(mediaId) { mutableStateOf(false) }
    var title by rememberSaveable(mediaId) { mutableStateOf("") }
    var artist by rememberSaveable(mediaId) { mutableStateOf("") }
    var album by rememberSaveable(mediaId) { mutableStateOf("") }
    var year by rememberSaveable(mediaId) { mutableStateOf("") }
    var track by rememberSaveable(mediaId) { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(song?.mediaId) {
        val current = song ?: return@LaunchedEffect
        if (!initialized) {
            title = current.title
            artist = current.artist
            album = current.album
            year = if (current.year > 0) current.year.toString() else ""
            track = if (current.track > 0) current.track.toString() else ""
            initialized = true
        }
    }

    val parsedYear = parseTagNumber(year)
    val parsedTrack = parseTagNumber(track)
    val titleValid = title.isNotBlank()
    val valid = song != null && titleValid && parsedYear != null && parsedTrack != null

    fun save() {
        if (!valid) return
        vm.saveTagOverride(
            mediaId = mediaId,
            title = title,
            artist = artist,
            album = album,
            year = parsedYear ?: 0,
            track = parsedTrack ?: 0,
        )
        onBack()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        topBar = {
            AppBar(
                title = t("Edit tags", "编辑标签"),
                onBack = onBack,
                actions = {
                    TextButton(onClick = { save() }, enabled = valid) {
                        Text(t("Save", "保存"))
                    }
                    if (override != null) {
                        IconButton(onClick = { confirmReset = true }) {
                            Icon(Icons.Rounded.Restore, t("Reset tags", "恢复标签"))
                        }
                    }
                },
            )
        },
    ) { inner ->
        if (song == null) {
            Column(Modifier.fillMaxSize().padding(inner)) {
                EmptyState(
                    title = t("Song not found", "找不到歌曲"),
                    body = t(
                        "The library changed. Rescan and try again.",
                        "曲库已变化，请重新扫描后再试。",
                    ),
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                song.title,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                listOf(song.artist, song.album).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (override != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    t("Custom tags active", "已使用自定义标签"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(t("Title", "标题")) },
                singleLine = true,
                isError = !titleValid,
                supportingText = if (!titleValid) {
                    { Text(t("Title cannot be empty", "标题不能为空")) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = artist,
                onValueChange = { artist = it },
                label = { Text(t("Artist", "艺术家")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = album,
                onValueChange = { album = it },
                label = { Text(t("Album", "专辑")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = year,
                    onValueChange = { year = it },
                    label = { Text(t("Year", "年份")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = parsedYear == null,
                    supportingText = if (parsedYear == null) {
                        { Text(t("Use a whole number", "请输入非负整数")) }
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = track,
                    onValueChange = { track = it },
                    label = { Text(t("Track", "音轨号")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = parsedTrack == null,
                    supportingText = if (parsedTrack == null) {
                        { Text(t("Use a whole number", "请输入非负整数")) }
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                t(
                    "Tags are stored in Lilt and survive a rescan. The audio file is not modified.",
                    "标签保存在 Lilt 中，重新扫描不会丢失；不会修改音频文件。",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (override != null) {
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = { confirmReset = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(t("Reset to scanned tags", "恢复扫描标签"))
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = t("Reset these tags?", "恢复扫描标签？"),
            body = t(
                "Your custom title, artist, album, year and track will be removed.",
                "将删除自定义的标题、艺术家、专辑、年份和音轨号。",
            ),
            onDismiss = { confirmReset = false },
            onConfirm = {
                confirmReset = false
                vm.resetTagOverride(mediaId)
                onBack()
            },
        )
    }
}
