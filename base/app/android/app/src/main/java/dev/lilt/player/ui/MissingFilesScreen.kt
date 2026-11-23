package dev.lilt.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lilt.player.LiltViewModel
import dev.lilt.player.data.MissingReference

/**
 * MediaStore rescans delete rows for files that moved. Playlists, queues,
 * favourites, lyrics and bookmarks keep the old ids, so this screen lets the
 * user point an old id at a song that exists in the library now.
 */
@Composable
fun MissingFilesScreen(vm: LiltViewModel, onBack: () -> Unit) {
    val missing by vm.missingFiles.collectAsStateWithLifecycle()
    val songs by vm.allSongs.collectAsStateWithLifecycle()
    var relinkTarget by remember { mutableStateOf<MissingReference?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refreshMissingFiles() }

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        topBar = { AppBar(title = t("Missing files", "失效文件"), onBack = onBack) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Text(
                t(
                    "These tracks disappeared from the phone. Their playlist, queue and saved song data are still here; choose a replacement file from the library to reconnect them.",
                    "这些歌曲已从手机中消失，但歌单、队列和保存的歌曲数据仍然保留。从曲库中选择替代文件即可重新关联。",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            )
            if (missing.isEmpty()) {
                EmptyState(
                    title = t("Nothing is missing", "没有失效文件"),
                    body = t(
                        "Every playlist and saved song resolves to a file in the library.",
                        "所有歌单和保存的歌曲都能在曲库中找到文件。",
                    ),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(missing, key = { it.song.mediaId }) { entry ->
                        MissingCard(entry) {
                            query = ""
                            relinkTarget = entry
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    relinkTarget?.let { target ->
        val filtered = remember(query, songs) {
            val needle = query.trim().lowercase()
            songs.filter { song ->
                needle.isEmpty() ||
                    song.title.lowercase().contains(needle) ||
                    song.artist.lowercase().contains(needle) ||
                    song.album.lowercase().contains(needle) ||
                    song.displayName.lowercase().contains(needle)
            }
        }
        AlertDialog(
            onDismissRequest = { relinkTarget = null },
            title = { Text(t("Choose the replacement", "选择替代文件")) },
            text = {
                Column {
                    Text(
                        t(
                            "Pick the song that replaces “${target.song.title.ifBlank { target.song.displayName }}”. Playlists, queues and saved data move to the new file.",
                            "选择替代“${target.song.title.ifBlank { target.song.displayName }}”的歌曲。歌单、队列和保存的数据会转移到新文件。",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        placeholder = { Text(t("Search the library", "搜索曲库")) },
                    )
                    Spacer(Modifier.height(6.dp))
                    if (filtered.isEmpty()) {
                        Text(
                            t("No songs match that search.", "没有匹配的歌曲。"),
                            modifier = Modifier.padding(vertical = 18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(Modifier.heightIn(max = 340.dp)) {
                            items(filtered, key = { it.mediaId }) { song ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            vm.relinkMissing(target.song.mediaId, song.mediaId)
                                            relinkTarget = null
                                        }
                                        .padding(vertical = 9.dp),
                                ) {
                                    Text(song.title, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        listOf(song.artist, song.album)
                                            .filter { it.isNotBlank() }
                                            .joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { relinkTarget = null }) {
                    Text(t("Cancel", "取消"))
                }
            },
        )
    }
}

@Composable
private fun MissingCard(entry: MissingReference, onRelink: () -> Unit) {
    val refs = buildList {
        if (entry.playlists.isNotEmpty()) {
            add(t("Playlists", "播放列表") + ": " + entry.playlists.joinToString(", "))
        }
        if (entry.queues.isNotEmpty()) {
            add(t("Queues", "队列") + ": " + entry.queues.joinToString(", "))
        }
        if (entry.favorite) add(t("Favourite", "收藏"))
        if (entry.plays > 0) {
            add(
                "${entry.plays} " + if (entry.plays == 1) {
                    t("play", "次播放")
                } else {
                    t("plays", "次播放")
                },
            )
        }
        if (entry.hasLyrics) add(t("Lyrics", "歌词"))
        if (entry.bookmarkCount > 0) {
            add(
                "${entry.bookmarkCount} " + if (entry.bookmarkCount == 1) {
                    t("bookmark", "个书签")
                } else {
                    t("bookmarks", "个书签")
                },
            )
        }
        if (entry.hasTagOverride) add(t("Tag override", "标签覆盖"))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
    ) {
        Text(
            entry.song.title.ifBlank { entry.song.displayName },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            listOf(entry.song.artist, entry.song.album)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (entry.song.folder.isBlank()) entry.song.displayName
            else "${entry.song.folder}/${entry.song.displayName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (refs.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                refs.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onRelink) {
                Text(t("Relink…", "重新关联…"))
            }
        }
    }
}
