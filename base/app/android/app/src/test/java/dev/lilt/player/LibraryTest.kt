package dev.lilt.player

import dev.lilt.player.data.FolderRuleEntity
import dev.lilt.player.data.MusicRepository
import dev.lilt.player.data.SongEntity
import dev.lilt.player.library.M3u
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    private fun song(id: Long, folder: String = "Music/Rock", title: String = "Song $id") =
        SongEntity(
            mediaId = id,
            title = title,
            artist = "Artist",
            album = "Album",
            albumId = 1,
            folder = folder,
            displayName = "track$id.mp3",
            durationMs = 185_000,
            track = 1,
            year = 2020,
            mime = "audio/mpeg",
            dateAdded = 1,
            size = 10,
            lastSeen = 1,
        )

    @Test
    fun m3uRoundTripKeepsEveryIdInOrder() {
        val songs = listOf(song(4), song(9), song(12))
        val text = M3u.write(songs)
        assertEquals(listOf(4L, 9L, 12L), M3u.parseIds(text))
        assertTrue(text.startsWith("#EXTM3U"))
        assertTrue(text.contains("#EXTINF:185,Artist - Song 4"))
    }

    @Test
    fun m3uReadsPlainPathsAndSkipsComments() {
        val text = "#EXTM3U\r\n#EXTINF:3,One\r\nlilt:7\r\n\r\ncontent://media/external/audio/media/8\r\nMusic/two.mp3"
        assertEquals(listOf(7L, 8L), M3u.parseIds(text))
        assertEquals(3, M3u.parseTargets(text).size)
    }

    @Test
    fun excludeRuleRemovesFolderAndChildrenOnly() {
        val songs = listOf(
            song(1, "Music/Rock"),
            song(2, "Music/Rock/Live"),
            song(3, "Music/Jazz"),
        )
        val visible = MusicRepository.filterSongs(
            songs,
            listOf(FolderRuleEntity("Music/Rock", "exclude")),
            onlyIncluded = false,
        )
        assertEquals(listOf(3L), visible.map { it.mediaId })
    }

    @Test
    fun includeOnlyKeepsJustTheChosenTree() {
        val songs = listOf(
            song(1, "Music/Rock"),
            song(2, "Music/Jazz/Live"),
            song(3, "Podcasts"),
        )
        val visible = MusicRepository.filterSongs(
            songs,
            listOf(FolderRuleEntity("Music/Jazz", "include")),
            onlyIncluded = true,
        )
        assertEquals(listOf(2L), visible.map { it.mediaId })
        assertFalse(visible.any { it.folder == "Podcasts" })
    }

    @Test
    fun includeOnlyWithNoRulesHidesEverything() {
        val visible = MusicRepository.filterSongs(
            listOf(song(1), song(2, "Music/Jazz")),
            emptyList(),
            onlyIncluded = true,
        )
        assertTrue(visible.isEmpty())
    }
}
