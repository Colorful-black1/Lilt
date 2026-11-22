package dev.lilt.player

import dev.lilt.player.data.FolderRuleEntity
import dev.lilt.player.data.MusicRepository
import dev.lilt.player.data.PlaylistSummary
import dev.lilt.player.data.SongEntity
import dev.lilt.player.data.SongStateEntity
import dev.lilt.player.library.LibraryGroups
import dev.lilt.player.library.M3u
import dev.lilt.player.library.SongSort
import dev.lilt.player.library.UNKNOWN_ALBUM
import dev.lilt.player.library.UNKNOWN_ARTIST
import dev.lilt.player.library.letterOf
import dev.lilt.player.library.searchLibrary
import dev.lilt.player.library.sortSongs
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
}
