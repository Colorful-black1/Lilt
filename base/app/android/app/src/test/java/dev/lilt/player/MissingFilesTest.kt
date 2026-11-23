package dev.lilt.player

import dev.lilt.player.data.BookmarkEntity
import dev.lilt.player.data.LyricsEntity
import dev.lilt.player.data.MissingSongEntity
import dev.lilt.player.data.PlaylistEntity
import dev.lilt.player.data.PlaylistSongEntity
import dev.lilt.player.data.QueueEntity
import dev.lilt.player.data.QueueSongEntity
import dev.lilt.player.data.SongStateEntity
import dev.lilt.player.data.TagOverrideEntity
import dev.lilt.player.data.buildMissingReferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissingFilesTest {
    private fun missing(id: Long, title: String): MissingSongEntity = MissingSongEntity(
        mediaId = id,
        title = title,
        artist = "Aurora Drift",
        album = "Demo Album",
        folder = "Music/LiltTest",
        displayName = "$title.mp3",
        durationMs = 20_000,
        lastSeen = 1,
    )

    @Test
    fun groupsPlaylistsQueuesAndSavedDataPerMissingTrack() {
        val result = buildMissingReferences(
            missingSongs = listOf(missing(10, "Glass Horizon"), missing(20, "Soft Static")),
            currentSongIds = emptySet(),
            playlists = listOf(PlaylistEntity(1, "Road", 1)),
            playlistSongs = listOf(
                PlaylistSongEntity(1, 10, 0),
                PlaylistSongEntity(1, 20, 1),
            ),
            queues = listOf(QueueEntity(2, "Evening", 2)),
            queueSongs = listOf(QueueSongEntity(2, 20, 0)),
            states = listOf(
                SongStateEntity(10, favorite = true, playCount = 3, lastPlayedAt = 4),
                SongStateEntity(20, favorite = false, playCount = 7, lastPlayedAt = 5),
            ),
            lyrics = listOf(LyricsEntity(10, "[00:01.00]First light", 250, 6)),
            bookmarks = listOf(
                BookmarkEntity(1, 20, 8_000, "Chorus", 7),
                BookmarkEntity(2, 20, 12_000, "Bridge", 8),
            ),
            tags = listOf(TagOverrideEntity(20, "Soft", "Aurora", "Demo", 2026, 1, 9)),
        )

        assertEquals(listOf("Glass Horizon", "Soft Static"), result.map { it.song.title })
        val glass = result.first()
        assertEquals(listOf("Road"), glass.playlists)
        assertTrue(glass.favorite)
        assertEquals(3, glass.plays)
        assertTrue(glass.hasLyrics)
        assertEquals(0, glass.bookmarkCount)
        assertTrue(glass.hasUserData)

        val soft = result.last()
        assertEquals(listOf("Road"), soft.playlists)
        assertEquals(listOf("Evening"), soft.queues)
        assertEquals(2, soft.referenceCount)
        assertEquals(7, soft.plays)
        assertEquals(2, soft.bookmarkCount)
        assertTrue(soft.hasTagOverride)
        assertFalse(soft.favorite)
    }

    @Test
    fun currentIdsAreNotReportedAndUnreferencedRowsStillAppear() {
        val result = buildMissingReferences(
            missingSongs = listOf(
                missing(10, "Glass Horizon"),
                missing(20, "Soft Static"),
                missing(30, "Northlight"),
            ),
            currentSongIds = setOf(10),
            playlists = emptyList(),
            playlistSongs = emptyList(),
            queues = emptyList(),
            queueSongs = emptyList(),
            states = emptyList(),
            lyrics = emptyList(),
            bookmarks = emptyList(),
            tags = emptyList(),
        )
        assertEquals(listOf("Northlight", "Soft Static"), result.map { it.song.title })
        assertTrue(result.all { it.referenceCount == 0 })
        assertFalse(result.any { it.hasUserData })
    }
}
