package dev.lilt.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lilt.player.LiltViewModel
import dev.lilt.player.data.SongEntity
import dev.lilt.player.library.DuplicateCandidate
import dev.lilt.player.library.findDuplicateGroups
import dev.lilt.player.library.formatDuplicateBytes

/**
 * Read-only duplicate browser. Lilt never deletes audio files; the list exists
 * so a person can see which copies share tags before managing the files with
 * another tool.
 */
@Composable
fun DuplicatesScreen(vm: LiltViewModel, onBack: () -> Unit) {
    val songs by vm.allSongs.collectAsStateWithLifecycle()
    val groups = remember(songs) {
        val candidates = songs.map(::candidateFor)
        findDuplicateGroups(candidates)
            .mapNotNull { group ->
                val entries = group.indices.mapNotNull { songs.getOrNull(it) }
                entries.takeIf { it.size >= 2 }
            }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        topBar = { AppBar(title = t("Duplicate songs", "重复歌曲"), onBack = onBack) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Text(
                t(
                    "Groups share a normalised title and artist with a similar length. The best copy is marked Keep. Tap a copy to play it; files are never changed.",
                    "分组依据是规范化后的标题、艺术家与相近时长。最佳副本标记为“保留”。点击副本即可播放；不会修改文件。",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            )
            if (groups.isEmpty()) {
                EmptyState(
                    title = t("No duplicates", "没有重复歌曲"),
                    body = t(
                        "Every song has a distinct normalised title/artist or length.",
                        "每首歌的规范化标题、艺术家或时长都不相同。",
                    ),
                )
            } else {
                Text(
                    if (groups.size == 1) {
                        t(
                            "1 group · ${groups.sumOf { it.size }} files",
                            "1 组 · ${groups.sumOf { it.size }} 个文件",
                        )
                    } else {
                        t(
                            "${groups.size} groups · ${groups.sumOf { it.size }} files",
                            "${groups.size} 组 · ${groups.sumOf { it.size }} 个文件",
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(groups, key = { group -> group.first().mediaId }) { group ->
                        DuplicateCard(group) { song -> vm.playSongs(listOf(song), 0) }
                        Spacer(Modifier.height(8.dp))
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DuplicateCard(group: List<SongEntity>, onPlay: (SongEntity) -> Unit) {
    val first = group.first()
    val totalBytes = group.sumOf { it.size }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
    ) {
        Text(first.title.ifBlank { first.displayName }, style = MaterialTheme.typography.titleMedium)
        Text(
            listOf(first.artist, first.album).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            t(
                "${group.size} copies · ${formatDuplicateBytes(totalBytes)}",
                "${group.size} 个副本 · ${formatDuplicateBytes(totalBytes)}",
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(6.dp))
        group.forEachIndexed { index, song ->
            val bitrate = estimatedBitrate(song)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlay(song) }
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (index == 0) {
                            Text(
                                t("KEEP", "保留"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            song.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        listOf(
                            mimeLabel(song.mime),
                            if (bitrate > 0) "$bitrate kbps" else null,
                            formatDuplicateBytes(song.size),
                            formatDuration(song.durationMs),
                        ).filterNotNull().joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (song.folder.isBlank()) song.displayName
                        else "${song.folder}/${song.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Rounded.PlayArrow,
                    t("Play this copy", "播放此副本"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

private fun candidateFor(song: SongEntity): DuplicateCandidate = DuplicateCandidate(
    title = song.title.ifBlank { song.displayName.substringBeforeLast('.') },
    artist = song.artist,
    durationMs = song.durationMs,
    size = song.size,
    bitrate = estimatedBitrate(song),
)

private fun estimatedBitrate(song: SongEntity): Int {
    if (song.durationMs <= 0L) return 0
    return ((song.size * 8) / song.durationMs).toInt()
}

private fun mimeLabel(mime: String): String = when {
    mime.endsWith("mpeg") -> "MP3"
    mime.endsWith("mp4") || mime.endsWith("m4a") -> "M4A"
    mime.endsWith("flac") -> "FLAC"
    mime.endsWith("ogg") || mime.endsWith("opus") -> "OGG"
    mime.endsWith("wav") || mime.endsWith("x-wav") -> "WAV"
    mime.isBlank() -> "AUDIO"
    else -> mime.substringAfterLast('/').uppercase()
}
