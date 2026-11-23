package dev.lilt.player.library

/** True when both markers are present and B is later than A. */
fun abLoopReady(startMs: Long, endMs: Long): Boolean =
    startMs >= 0L && endMs > startMs

/** The service ticker asks this before seeking back to A. */
fun abLoopShouldJump(startMs: Long, endMs: Long, positionMs: Long): Boolean =
    abLoopReady(startMs, endMs) && positionMs >= endMs

/**
 * True when the one-shot stop-after flag should pause the current track. The
 * headroom lets the 100 ms ticker pause before ExoPlayer advances.
 */
fun stopAfterShouldStop(
    enabled: Boolean,
    isPlaying: Boolean,
    durationMs: Long,
    positionMs: Long,
): Boolean =
    enabled &&
        isPlaying &&
        durationMs > 0L &&
        positionMs >= 0L &&
        durationMs - positionMs <= 180L
