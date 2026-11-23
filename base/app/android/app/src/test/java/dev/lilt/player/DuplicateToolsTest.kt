package dev.lilt.player

import dev.lilt.player.library.DuplicateCandidate
import dev.lilt.player.library.findDuplicateGroups
import dev.lilt.player.library.formatDuplicateBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateToolsTest {
    private fun candidate(
        title: String,
        artist: String,
        durationMs: Long,
        size: Long,
        bitrate: Int,
    ) = DuplicateCandidate(title, artist, durationMs, size, bitrate)

    @Test
    fun groupsNearIdenticalTagsButNotDistantLengths() {
        val groups = findDuplicateGroups(
            listOf(
                candidate("Glass Horizon", "Aurora Drift", 48_000, 1_000, 1_411),
                candidate("glass-horizon!", "Aurora  Drift", 48_400, 1_010, 1_411),
                candidate("Glass Horizon (Live)", "Aurora Drift", 52_000, 1_020, 1_411),
                candidate("Soft Static", "Aurora Drift", 52_000, 1_030, 1_411),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(listOf(1, 0), groups[0].indices)
    }

    @Test
    fun sameSizeAndLengthAloneIsNotEnough() {
        val groups = findDuplicateGroups(
            listOf(
                candidate("First tags", "Artist A", 40_000, 5_000_000, 320),
                candidate("Other tags", "Artist B", 40_100, 5_000_000, 320),
            ),
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun suggestsHighestQualityCopyFirst() {
        val groups = findDuplicateGroups(
            listOf(
                candidate("Same", "Artist", 40_000, 5_000_000, 192),
                candidate("Same", "Artist", 40_200, 9_000_000, 320),
                candidate("Same", "Artist", 40_100, 7_000_000, 256),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(listOf(1, 2, 0), groups[0].indices)
    }

    @Test
    fun ignoresSingletonsMissingLengthsAndTinyTitles() {
        val groups = findDuplicateGroups(
            listOf(
                candidate("Solo", "Artist", 30_000, 0, 128),
                candidate("", "Artist", 0, 0, 0),
                candidate("a", "Artist", 12_000, 0, 0),
                candidate("a", "Artist", 12_000, 0, 0),
            ),
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun formatsBytes() {
        assertEquals("1 KB", formatDuplicateBytes(512))
        assertEquals("10.0 MB", formatDuplicateBytes((10 * 1024 * 1024).toLong()))
    }
}
