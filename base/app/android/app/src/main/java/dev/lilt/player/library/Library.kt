package dev.lilt.player.library

import dev.lilt.player.data.SongEntity

/**
 * M3U is intentionally simple: an extended header, one metadata line per
 * track, then the track target. Lilt writes media-content URIs so a file can
 * be imported on any device that can still resolve the same MediaStore row.
 */
object M3u {
    fun write(songs: List<SongEntity>): String = buildString {
        append("#EXTM3U\n")
        songs.forEach { song ->
            val seconds = song.durationMs / 1000
            val label = listOf(song.artist, song.title)
                .filter { it.isNotBlank() }
                .joinToString(" - ")
            append("#EXTINF:$seconds,$label\n")
            append("content://media/external/audio/media/${song.mediaId}\n")
        }
    }

    fun parseIds(text: String): List<Long> = text.lineSequence()
        .mapNotNull { parseTarget(it) }
        .toList()

    fun parseTargets(text: String): List<String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toList()

    fun parseTarget(raw: String): Long? {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return null
        return when {
            line.startsWith("content://media/external/audio/media/") ->
                line.substringAfterLast('/').toLongOrNull()
            line.startsWith("lilt:") -> line.removePrefix("lilt:").toLongOrNull()
            else -> line.toLongOrNull()
        }
    }
}

data class FolderNode(
    val path: String,
    val name: String,
    val directSongs: List<SongEntity>,
    val children: List<FolderNode>,
)

object FolderTree {
    fun build(songs: List<SongEntity>): List<FolderNode> {
        class MutableNode(val path: String, val name: String) {
            val direct = mutableListOf<SongEntity>()
            val children = linkedMapOf<String, MutableNode>()
        }

        val roots = linkedMapOf<String, MutableNode>()
        songs.forEach { song ->
            val segments = song.folder.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return@forEach
            var bucket = roots
            var current: MutableNode? = null
            var path = ""
            segments.forEach { segment ->
                path = if (path.isEmpty()) segment else "$path/$segment"
                current = bucket.getOrPut(segment) { MutableNode(path, segment) }
                bucket = current!!.children
            }
            current?.direct?.add(song)
        }

        fun freeze(node: MutableNode): FolderNode = FolderNode(
            path = node.path,
            name = node.name,
            directSongs = node.direct.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            ),
            children = node.children.values
                .sortedBy { it.name.lowercase() }
                .map(::freeze),
        )

        return roots.values.sortedBy { it.name.lowercase() }.map(::freeze)
    }

    fun find(nodes: List<FolderNode>, path: String): FolderNode? {
        if (path.isBlank()) return null
        nodes.forEach { node ->
            if (node.path == path) return node
            find(node.children, path)?.let { return it }
        }
        return null
    }

    /** Every song in this folder and below, in folder order. */
    fun recursiveSongs(node: FolderNode): List<SongEntity> {
        val result = mutableListOf<SongEntity>()
        result += node.directSongs
        node.children.forEach { result += recursiveSongs(it) }
        return result
    }

    fun breadcrumb(path: String): List<Pair<String, String>> {
        if (path.isBlank()) return emptyList()
        val parts = path.split('/').filter { it.isNotBlank() }
        var acc = ""
        return parts.map { part ->
            acc = if (acc.isEmpty()) part else "$acc/$part"
            acc to part
        }
    }
}
