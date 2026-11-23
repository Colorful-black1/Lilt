package dev.lilt.player.library

import kotlin.math.abs

/**
 * Lightweight view of a song used by duplicate detection. Keeping the text,
 * length and size separate makes the rules easy to unit-test.
 */
data class DuplicateCandidate(
    val title: String,
    val artist: String,
    val durationMs: Long,
    val size: Long,
    val bitrate: Int,
)

/** Indices into the candidate list, best copy first. */
data class DuplicateGroup(val indices: List<Int>)

/**
 * Two songs are treated as the same recording when normalised title and artist
 * match and the lengths are within two seconds. Size and duration are never
 * enough on their own: constant-bitrate files of the same length would
 * otherwise look identical. Only groups with at least two songs are returned.
 */
fun findDuplicateGroups(candidates: List<DuplicateCandidate>): List<DuplicateGroup> {
    if (candidates.size < 2) return emptyList()
    val union = UnionFind(candidates.size)

    val byTags = LinkedHashMap<Pair<String, String>, MutableList<Int>>()
    candidates.forEachIndexed { index, candidate ->
        if (candidate.durationMs <= 0L) return@forEachIndexed
        val title = normalizeDuplicateText(candidate.title)
        if (title.length < 2) return@forEachIndexed
        val key = title to normalizeDuplicateText(candidate.artist)
        byTags.getOrPut(key) { mutableListOf() }.add(index)
    }
    byTags.values.forEach { indices ->
        unionByDuration(union, indices, candidates, 2_000L)
    }

    val grouped = LinkedHashMap<Int, MutableList<Int>>()
    candidates.indices.forEach { index ->
        grouped.getOrPut(union.find(index)) { mutableListOf() }.add(index)
    }
    return grouped.values
        .filter { it.size >= 2 }
        .map { indices ->
            DuplicateGroup(
                indices.sortedWith(
                    compareByDescending<Int> { candidates[it].bitrate }
                        .thenByDescending { candidates[it].size }
                        .thenBy { candidates[it].title.lowercase() }
                        .thenBy { it },
                ),
            )
        }
        .sortedWith(
            compareBy<DuplicateGroup> {
                candidates[it.indices.first()].title.lowercase()
            }.thenBy {
                candidates[it.indices.first()].artist.lowercase()
            }.thenBy { it.indices.first() },
        )
}

private fun unionByDuration(
    union: UnionFind,
    indices: List<Int>,
    candidates: List<DuplicateCandidate>,
    toleranceMs: Long,
) {
    if (indices.size < 2) return
    val sorted = indices.sortedWith(
        compareBy<Int> { candidates[it].durationMs }.thenBy { it },
    )
    val anchor = sorted.first()
    sorted.drop(1).forEach { index ->
        if (abs(candidates[index].durationMs - candidates[anchor].durationMs) <= toleranceMs) {
            union.union(anchor, index)
        }
    }
}

private fun normalizeDuplicateText(value: String): String =
    value.lowercase().filter { it.isLetterOrDigit() }

fun formatDuplicateBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 0.1) "%.1f MB".format(mb) else "${(bytes / 1024).coerceAtLeast(1)} KB"
}

private class UnionFind(size: Int) {
    private val parent = IntArray(size) { it }
    private val rank = ByteArray(size)

    fun find(value: Int): Int {
        var root = value
        while (parent[root] != root) root = parent[root]
        var current = value
        while (parent[current] != current) {
            val next = parent[current]
            parent[current] = root
            current = next
        }
        return root
    }

    fun union(a: Int, b: Int) {
        val left = find(a)
        val right = find(b)
        if (left == right) return
        when {
            rank[left] < rank[right] -> parent[left] = right
            rank[left] > rank[right] -> parent[right] = left
            else -> {
                parent[right] = left
                rank[left] = (rank[left] + 1).toByte()
            }
        }
    }
}
