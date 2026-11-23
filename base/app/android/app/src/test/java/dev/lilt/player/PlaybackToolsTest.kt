package dev.lilt.player

import dev.lilt.player.library.abLoopReady
import dev.lilt.player.library.abLoopShouldJump
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackToolsTest {
    @Test
    fun abLoopNeedsAAndLaterB() {
        assertFalse(abLoopReady(-1L, 5_000L))
        assertFalse(abLoopReady(5_000L, 5_000L))
        assertFalse(abLoopReady(6_000L, 5_000L))
        assertTrue(abLoopReady(5_000L, 8_000L))
    }

    @Test
    fun abLoopJumpsOnlyAtTheEnd() {
        assertFalse(abLoopShouldJump(5_000L, 8_000L, 4_999L))
        assertFalse(abLoopShouldJump(5_000L, 8_000L, 7_999L))
        assertTrue(abLoopShouldJump(5_000L, 8_000L, 8_000L))
        assertTrue(abLoopShouldJump(5_000L, 8_000L, 9_000L))
        assertFalse(abLoopShouldJump(-1L, 8_000L, 9_000L))
    }
}
