package dev.lilt.player.library

import kotlin.math.roundToLong

/** One parsed LRC line. Plain-text lyrics use [timeMs] = 0 for every line. */
data class LrcLine(val timeMs: Long, val text: String)

data class LrcDocument(
    val lines: List<LrcLine>,
    val timed: Boolean,
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * Parses standard LRC time tags such as `[01:23.45]`, `[01:23,450]` and
 * `[1:23]`. Several tags may share one line. Metadata tags (`[ar:...]`) are
 * ignored. Lines without any tag are kept as plain lyrics at time zero.
 */
fun parseLrc(text: String): LrcDocument {
    val parsed = mutableListOf<LrcLine>()
    var sawRealTime = false
    text.removePrefix("\uFEFF").lineSequence().forEach { raw ->
        var rest = raw.trim()
        if (rest.isEmpty()) return@forEach
        val times = mutableListOf<Long>()
        while (rest.startsWith("[")) {
            val end = rest.indexOf(']')
            if (end <= 0) break
            val tag = rest.substring(1, end)
            rest = rest.substring(end + 1).trimStart()
            val parts = tag.split(':', limit = 2)
            if (parts.size != 2) continue
            val minutes = parts[0].trim().toLongOrNull() ?: continue
            val seconds = parts[1].trim().replace(',', '.').toDoubleOrNull() ?: continue
            if (minutes < 0 || seconds < 0.0) continue
            times += (minutes * 60_000L + (seconds * 1000.0).roundToLong()).coerceAtLeast(0)
        }
        val line = rest.trim()
        if (line.isEmpty()) return@forEach
        if (times.isEmpty()) {
            parsed += LrcLine(0, line)
        } else {
            sawRealTime = true
            times.forEach { parsed += LrcLine(it, line) }
        }
    }
    return LrcDocument(
        lines = parsed.sortedBy { it.timeMs },
        timed = sawRealTime,
    )
}

/** Index of the line that should be highlighted, or -1 before the first tag. */
fun activeLrcLine(document: LrcDocument, positionMs: Long): Int {
    if (!document.timed) return -1
    var active = -1
    document.lines.forEachIndexed { index, line ->
        if (line.timeMs <= positionMs) active = index else return@forEachIndexed
    }
    return active
}

fun formatLrcTimestamp(timeMs: Long): String {
    val totalSeconds = (timeMs.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
