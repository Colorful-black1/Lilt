package dev.lilt.player

import dev.lilt.player.library.FilenameTags
import dev.lilt.player.library.parseFilenameTags
import dev.lilt.player.library.resolveScannedTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilenameTagsTest {
    @Test
    fun splitsArtistAndTitlePatterns() {
        assertEquals(
            FilenameTags(track = 3, title = "Glass Horizon"),
            parseFilenameTags("03 - Glass Horizon"),
        )
        assertEquals(
            FilenameTags(track = 0, title = "晴天", artist = "周杰伦"),
            parseFilenameTags("周杰伦 - 晴天"),
        )
        assertEquals(
            FilenameTags(track = 1, title = "晴天", artist = "周杰伦"),
            parseFilenameTags("01. 周杰伦 - 晴天"),
        )
        assertEquals(
            FilenameTags(track = 0, title = "After Rain", artist = "Aimer"),
            parseFilenameTags("Aimer-After Rain"),
        )
        assertEquals(
            FilenameTags(track = 0, title = "Time-Bomb", artist = "All Time Low"),
            parseFilenameTags("All Time Low - Time-Bomb"),
        )
        assertEquals(
            FilenameTags(track = 0, title = "Back in Black", artist = "AC-DC"),
            parseFilenameTags("AC-DC - Back in Black"),
        )
        assertEquals(
            FilenameTags(track = 0, title = "Em Dash", artist = "Artist"),
            parseFilenameTags("Artist — Em Dash"),
        )
        assertEquals(
            FilenameTags(track = 0, title = "7 Rings"),
            parseFilenameTags("7 Rings"),
        )
        assertEquals(
            FilenameTags(track = 0, title = "1984"),
            parseFilenameTags("1984"),
        )
        assertEquals(
            FilenameTags(track = 1, title = "Song"),
            parseFilenameTags("01 Song"),
        )
        assertEquals(
            FilenameTags(track = 1, title = "Song"),
            parseFilenameTags("(01) Song"),
        )
        assertEquals(
            FilenameTags(track = 0, title = "忘记时间 20200523220957"),
            parseFilenameTags("忘记时间_20200523220957"),
        )
        assertEquals(
            FilenameTags(track = 0, title = "晴天", artist = "周杰伦"),
            parseFilenameTags("周杰伦-晴天"),
        )
    }

    @Test
    fun resolvesPlaceholderMediaStoreTags() {
        val untagged = resolveScannedTags("Aimer-After Rain.mp3", null, null, 0)
        assertEquals("After Rain", untagged.title)
        assertEquals("Aimer", untagged.artist)
        assertEquals(0, untagged.track)

        val unknownArtist = resolveScannedTags("Aimer-After Rain.mp3", "After Rain", "<unknown>", 0)
        assertEquals("After Rain", unknownArtist.title)
        assertEquals("Aimer", unknownArtist.artist)

        val filenameAsTitle = resolveScannedTags("07 - 周杰伦 - 晴天.mp3", "07 - 周杰伦 - 晴天", null, 0)
        assertEquals("晴天", filenameAsTitle.title)
        assertEquals("周杰伦", filenameAsTitle.artist)
        assertEquals(7, filenameAsTitle.track)

        val filenameWithExtension = resolveScannedTags(
            "Aimer-After Rain.mp3",
            "Aimer-After Rain.mp3",
            null,
            0,
        )
        assertEquals("After Rain", filenameWithExtension.title)
        assertEquals("Aimer", filenameWithExtension.artist)

        val unknownTitle = resolveScannedTags("Aimer-After Rain.mp3", "<unknown>", null, 0)
        assertEquals("After Rain", unknownTitle.title)
        assertEquals("Aimer", unknownTitle.artist)

        val realTagsWin = resolveScannedTags("track01.mp3", "Real title", "Real artist", 4)
        assertEquals("Real title", realTagsWin.title)
        assertEquals("Real artist", realTagsWin.artist)
        assertEquals(4, realTagsWin.track)

        val noSeparator = resolveScannedTags("忘记时间_20200523220957.mp3", null, null, 0)
        assertEquals("忘记时间 20200523220957", noSeparator.title)
        assertEquals("Unknown artist", noSeparator.artist)

        val untitled = resolveScannedTags("", null, null, 0)
        assertEquals("Untitled", untitled.title)
        assertEquals("Unknown artist", untitled.artist)
        assertNull(parseFilenameTags("").artist)
    }
}
