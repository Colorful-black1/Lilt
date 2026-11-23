package dev.lilt.player.library

/** True when both markers are present and B is later than A. */
fun abLoopReady(startMs: Long, endMs: Long): Boolean =
    startMs >= 0L && endMs > startMs

/** The service ticker asks this before seeking back to A. */
fun abLoopShouldJump(startMs: Long, endMs: Long, positionMs: Long): Boolean =
    abLoopReady(startMs, endMs) && positionMs >= endMs
