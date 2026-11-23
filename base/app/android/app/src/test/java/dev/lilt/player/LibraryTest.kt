package dev.lilt.player

import dev.lilt.player.data.FolderRuleEntity
import dev.lilt.player.data.MusicRepository
import dev.lilt.player.data.PlaylistSummary
import dev.lilt.player.data.SongEntity
import dev.lilt.player.data.SongStateEntity
import dev.lilt.player.data.TagOverrideEntity
import dev.lilt.player.library.EQ_PRESETS
import dev.lilt.player.library.EqProfile
import dev.lilt.player.library.LibraryGroups
import dev.lilt.player.library.PreampMode
import dev.lilt.player.library.M3u
import dev.lilt.player.library.SongSort
import dev.lilt.player.library.UNKNOWN_ALBUM
import dev.lilt.player.library.UNKNOWN_ARTIST
import dev.lilt.player.library.activeLrcLine
import dev.lilt.player.library.balanceOffsetsDb
import dev.lilt.player.library.letterOf
import dev.lilt.player.library.movedItem
import dev.lilt.player.library.parseLrc
import dev.lilt.player.library.parseTagNumber
import dev.lilt.player.library.presetNameFor
import dev.lilt.player.library.queueStart
import dev.lilt.player.library.searchLibrary
import dev.lilt.player.library.sortSongs
import dev.lilt.player.library.withTagOverride
import dev.lilt.player.library.withTagOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun albumsGroupByTagAndOrderTracks() {
        val songs = listOf(
            song(1, title = "Third").copy(album = "Record", artist = "Band", track = 3),
            song(2, title = "First").copy(album = "Record", artist = "Band", track = 1),
            song(3, title = "Other").copy(album = "Another", artist = "Solo", track = 1),
        )
        val albums = LibraryGroups.albums(songs)
        assertEquals(listOf("Another", "Record"), albums.map { it.name })
        val record = albums.first { it.name == "Record" }
        assertEquals(listOf("First", "Third"), record.songs.map { it.title })
        assertEquals(2, record.trackCount)
        assertEquals("Band", record.artist)
    }

    @Test
    fun artistsGroupByNameAndCountAlbums() {
        val songs = listOf(
            song(1).copy(artist = "Band", album = "One"),
            song(2).copy(artist = "Band", album = "Two"),
            song(3).copy(artist = "Solo", album = "One"),
        )
        val artists = LibraryGroups.artists(songs)
        assertEquals(listOf("Band", "Solo"), artists.map { it.name })
        assertEquals(2, artists.first().trackCount)
        assertEquals(2, artists.first().albumCount)
    }

    @Test
    fun blankTagsGetReadableFallbacks() {
        val songs = listOf(song(1).copy(artist = "", album = ""))
        assertEquals(UNKNOWN_ALBUM, LibraryGroups.albums(songs).single().name)
        assertEquals(UNKNOWN_ARTIST, LibraryGroups.artists(songs).single().name)
    }

    @Test
    fun sortByTitleSwitchesDirection() {
        val songs = listOf(
            song(1, title = "Beta"),
            song(2, title = "alpha"),
            song(3, title = "Gamma"),
        )
        val ascending = sortSongs(songs, emptyMap(), SongSort.Title, descending = false)
        assertEquals(listOf("alpha", "Beta", "Gamma"), ascending.map { it.title })
        val descending = sortSongs(songs, emptyMap(), SongSort.Title, descending = true)
        assertEquals(listOf("Gamma", "Beta", "alpha"), descending.map { it.title })
    }

    @Test
    fun sortByPlayCountUsesUserState() {
        val songs = listOf(song(1), song(2), song(3))
        val states = mapOf(
            1L to SongStateEntity(1, playCount = 2, lastPlayedAt = 100),
            2L to SongStateEntity(2, playCount = 9, lastPlayedAt = 300),
        )
        val sorted = sortSongs(songs, states, SongSort.PlayCount, descending = true)
        assertEquals(listOf(2L, 1L, 3L), sorted.map { it.mediaId })
    }

    @Test
    fun sortByDateAddedPutsNewestFirst() {
        val songs = listOf(
            song(1).copy(dateAdded = 100),
            song(2).copy(dateAdded = 300),
            song(3).copy(dateAdded = 200),
        )
        val sorted = sortSongs(songs, emptyMap(), SongSort.DateAdded, descending = true)
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.mediaId })
    }

    @Test
    fun letterIndexBucketsLatinAndEverythingElse() {
        assertEquals('G', letterOf("glass horizon"))
        assertEquals('A', letterOf("  Aurora"))
        assertEquals('#', letterOf("北方的风"))
        assertEquals('#', letterOf("42"))
        assertEquals('#', letterOf("   "))
    }

    @Test
    fun searchGroupsSongsAlbumsArtistsFoldersAndPlaylists() {
        val songs = listOf(
            song(1, folder = "Music/LiltTest", title = "Glass Horizon")
                .copy(artist = "Aurora Drift", album = "Demo Album"),
            song(2, folder = "Music/LiltTest", title = "Last Train")
                .copy(artist = "Night Transit", album = "Night Tracks"),
            song(3, folder = "Music/Other", title = "Northlight")
                .copy(artist = "Aurora Drift", album = "Demo Album"),
        )
        val playlists = listOf(PlaylistSummary(1, "Night list", 1, 2))

        val night = searchLibrary(songs, playlists, "night")
        assertEquals(listOf(2L), night.songs.map { it.mediaId })
        assertEquals(listOf("Night Tracks"), night.albums.map { it.name })
        assertEquals(listOf("Night Transit"), night.artists.map { it.name })
        assertEquals(listOf("Night list"), night.playlists.map { it.name })
        assertTrue(night.folders.isEmpty())

        val music = searchLibrary(songs, playlists, "music")
        assertEquals(3, music.songs.size)
        assertEquals(listOf("Music/LiltTest", "Music/Other"), music.folders.map { it.path })
        assertEquals(listOf(2, 1), music.folders.map { it.songs.size })

        assertTrue(searchLibrary(songs, playlists, "   ").isEmpty)
    }

    @Test
    fun lrcParsesTimestampsFractionsAndMultipleTags() {
        val document = parseLrc(
            """
            [ar:Demo]
            [00:12.50]First line
            [00:18,250][01:02.001]Shared chorus
            [01:30]Last line
            """.trimIndent(),
        )
        assertTrue(document.timed)
        assertEquals(4, document.lines.size)
        assertFalse(document.lines.any { it.text.contains("Demo") })
        assertEquals(12_500L, document.lines[0].timeMs)
        assertEquals("First line", document.lines[0].text)
        assertEquals(18_250L, document.lines[1].timeMs)
        assertEquals(62_001L, document.lines[2].timeMs)
        assertEquals("Shared chorus", document.lines[2].text)
        assertEquals(90_000L, document.lines[3].timeMs)
    }

    @Test
    fun lrcKeepsPlainTextAndSortsOutOfOrderTags() {
        val plain = parseLrc("Just a line\nAnother line")
        assertFalse(plain.timed)
        assertEquals(listOf("Just a line", "Another line"), plain.lines.map { it.text })
        assertEquals(-1, activeLrcLine(plain, 30_000))

        val shuffled = parseLrc("[00:20.00]Later\n[00:05.00]Earlier")
        assertEquals(listOf("Earlier", "Later"), shuffled.lines.map { it.text })
        assertEquals(-1, activeLrcLine(shuffled, 4_999))
        assertEquals(0, activeLrcLine(shuffled, 5_000))
        assertEquals(1, activeLrcLine(shuffled, 45_000))
    }

    @Test
    fun eqPreampModesAndHeadroomSuggestionMatchDesktop() {
        val flat = EqProfile()
        assertEquals(0f, flat.preampDb(), 0.001f)
        assertEquals(-6f, flat.copy(preampMode = PreampMode.Headroom).preampDb(), 0.001f)
        assertEquals(2f, flat.copy(preampMode = PreampMode.Boost).preampDb(), 0.001f)
        assertNull(flat.suggestedHeadroomDb())

        val loud = flat.copy(
            gains = listOf(12f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            bass = 0.5f,
        )
        assertEquals(13.5f, loud.suggestedHeadroomDb()!!, 0.01f)
    }

    @Test
    fun eqProfileStorageRoundTripKeepsEveryField() {
        val source = EqProfile(
            enabled = false,
            gains = listOf(1f, -2f, 3f, -4f, 5f, -6f, 7f, -8f, 9f, -10f),
            preset = "Rock",
            preampMode = PreampMode.Headroom,
            headroomDb = 8f,
            boostDb = 4f,
            bass = 0.4f,
            virtualizer = 0.7f,
            balance = 130f,
        )
        val restored = EqProfile.decode(source.encode())
        assertEquals(source, restored)
        assertNull(EqProfile.decode("broken"))
        assertNull(EqProfile.decode(null))
    }

    @Test
    fun eqBalanceKeepsOneChannelAndAttenuatesTheOther() {
        val center = balanceOffsetsDb(100f)
        assertEquals(0f, center.first, 0.01f)
        assertEquals(0f, center.second, 0.01f)
        val left = balanceOffsetsDb(0f)
        assertEquals(0f, left.first, 0.01f)
        assertTrue(left.second < -59f)
        val right = balanceOffsetsDb(200f)
        assertTrue(right.first < -59f)
        assertEquals(0f, right.second, 0.01f)
    }

    @Test
    fun eqPresetsKeepTheDesktopTwentyTwoCurves() {
        assertEquals(22, EQ_PRESETS.size)
        assertEquals(
            listOf(5.80f, 3.20f, 1.30f, -3f, -2.30f, 2.30f, 3.60f, 5.80f, 5.80f, 5.80f),
            EQ_PRESETS["Rock"],
        )
        assertEquals("Rock", presetNameFor(EQ_PRESETS.getValue("Rock")))
        assertEquals("Custom", presetNameFor(listOf(9f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)))
    }

    @Test
    fun tagOverrideReplacesDisplayFieldsAndKeepsScanFields() {
        val original = song(7)
        val override = TagOverrideEntity(
            mediaId = 7,
            title = "New title",
            artist = "New artist",
            album = "New album",
            year = 2024,
            track = 5,
            updatedAt = 1,
        )
        val merged = original.withTagOverride(override)
        assertEquals("New title", merged.title)
        assertEquals("New artist", merged.artist)
        assertEquals("New album", merged.album)
        assertEquals(2024, merged.year)
        assertEquals(5, merged.track)
        assertEquals(original.albumId, merged.albumId)
        assertEquals(original.folder, merged.folder)
        assertEquals(original.durationMs, merged.durationMs)
        assertEquals(original, original.withTagOverride(null))
    }

    @Test
    fun tagOverrideBlanksFallBackAndListMappingWorks() {
        val original = song(1)
        val partial = TagOverrideEntity(1, "", "", "", 0, 0, 1)
        assertEquals(original, original.withTagOverride(partial))
        val mapped = listOf(original, song(2)).withTagOverrides(
            mapOf(1L to partial.copy(title = "One")),
        )
        assertEquals("One", mapped[0].title)
        assertEquals("Song 2", mapped[1].title)
    }

    @Test
    fun tagNumberParserAcceptsBlankAndRejectsInvalidValues() {
        assertEquals(0, parseTagNumber(" ")!!)
        assertEquals(2024, parseTagNumber(" 2024 ")!!)
        assertNull(parseTagNumber("-1"))
        assertNull(parseTagNumber("abc"))
        assertNull(parseTagNumber("999999999999"))
    }

    @Test
    fun movedItemReordersAndIgnoresInvalidPositions() {
        val source = listOf("A", "B", "C", "D")
        assertEquals(listOf("B", "A", "C", "D"), movedItem(source, 0, 1))
        assertEquals(listOf("A", "C", "D", "B"), movedItem(source, 1, 3))
        assertEquals(source, movedItem(source, 2, 2))
        assertEquals(source, movedItem(source, -1, 3))
        assertEquals(source, movedItem(source, 0, 4))
        assertEquals(emptyList<String>(), movedItem<String>(emptyList(), 0, 0))
    }

    @Test
    fun queueStartResumesClampsOrResets() {
        val resumed = queueStart(4, 61_000, songCount = 9, remember = true)
        assertEquals(4, resumed.index)
        assertEquals(61_000L, resumed.positionMs)

        val forgotten = queueStart(4, 61_000, songCount = 9, remember = false)
        assertEquals(0, forgotten.index)
        assertEquals(0L, forgotten.positionMs)

        val stale = queueStart(12, 61_000, songCount = 9, remember = true)
        assertEquals(0, stale.index)
        assertEquals(0L, stale.positionMs)

        val empty = queueStart(0, 1_000, songCount = 0, remember = true)
        assertEquals(0, empty.index)
        assertEquals(0L, empty.positionMs)
    }
}
