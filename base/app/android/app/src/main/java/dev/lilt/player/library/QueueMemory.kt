package dev.lilt.player.library

/** Where a named queue should start when the user taps it again. */
data class QueueStart(val index: Int, val positionMs: Long)

fun queueStart(
    savedIndex: Int,
    savedPositionMs: Long,
    songCount: Int,
    remember: Boolean,
): QueueStart {
    if (!remember || songCount <= 0) return QueueStart(0, 0)
    if (savedIndex !in 0 until songCount) return QueueStart(0, 0)
    return QueueStart(savedIndex, savedPositionMs.coerceAtLeast(0))
}
