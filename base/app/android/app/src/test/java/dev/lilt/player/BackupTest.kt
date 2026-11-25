package dev.lilt.player

import dev.lilt.player.data.BackupBookmark
import dev.lilt.player.data.BackupEq
import dev.lilt.player.data.BackupHistory
import dev.lilt.player.data.BackupLyrics
import dev.lilt.player.data.BackupPlaylist
import dev.lilt.player.data.BackupQueue
import dev.lilt.player.data.BackupRule
import dev.lilt.player.data.BackupSearch
import dev.lilt.player.data.BackupSettings
import dev.lilt.player.data.BackupSongRef
import dev.lilt.player.data.BackupSongState
import dev.lilt.player.data.BackupTag
import dev.lilt.player.data.LiltBackup
import dev.lilt.player.data.SongEntity
import dev.lilt.player.data.resolveBackupSongIds
import dev.lilt.player.data.toProfile
import dev.lilt.player.data.unresolvedCount
import dev.lilt.player.library.EQ_BANDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupTest {
    private fun song(id: Long, title: String = "Glass Horizon"): SongEntity = SongEntity(
        mediaId = id,
        title = title,
        artist = "Aurora Drift",
        album = "Demo Album",
        albumId = 7,
        folder = "Music/LiltTest",
        displayName = "01 - $title.mp3",
        durationMs = 20_088,
        track = 1,
        year = 2026,
        mime = "audio/mpeg",
        dateAdded = 1,
        size = 2,
        lastSeen = 3,
    )

    private fun sampleBackup(): LiltBackup = LiltBackup(
        created = 1_700_000_000_000,
        songs = listOf(
            BackupSongRef(17, "Glass Horizon", "Aurora Drift", "Demo Album", "Music/LiltTest", "01 - Glass Horizon.mp3", 20_088),
            BackupSongRef(18, "Soft Static", "Aurora Drift", "Demo Album", "Music/LiltTest", "02 - Soft Static.mp3", 18_064),
        ),
        playlists = listOf(BackupPlaylist("Night list", 10, listOf(18, 17))),
        queues = listOf(BackupQueue("Evening", 20, 1, 12_500, listOf(17, 18))),
        activeQueueIndex = 0,
        songStates = listOf(BackupSongState(17, true, 4, 123)),
        history = listOf(BackupHistory(17, 124)),
        bookmarks = listOf(BackupBookmark(17, 8_000, "Chorus", 125)),
        lyrics = listOf(BackupLyrics(17, "[00:01.00]First light", 250, 126)),
        tags = listOf(BackupTag(17, "Glass", "Aurora", "Demo", 2026, 1, 127)),
        rules = listOf(BackupRule("Music/LiltTest", "include")),
        searches = listOf(BackupSearch("glass", 128)),
        settings = BackupSettings(
            language = "zh",
            theme = "dark",
            accent = "sage",
            includeOnly = true,
            playbackSpeed = 1.25f,
            fadeInMs = 750,
            floatingFontSize = 31.5f,
            floatingPlayedColor = 0xFF12AB34.toInt(),
            floatingUnplayedColor = 0xFFF0E6DC.toInt(),
            lockScreenPlayer = false,
        ),
        eq = BackupEq(
            enabled = true,
            gains = List(EQ_BANDS) { index -> index.toFloat() },
            preampMode = "headroom",
            headroomDb = 7f,
            perDevice = false,
        ),
    )

    @Test
    fun backupJsonRoundTripKeepsEverything() {
        val original = sampleBackup()
        val decoded = LiltBackup.decode(original.encode())

        assertEquals(LiltBackup.PLATFORM, decoded.platform)
        assertEquals(1_700_000_000_000, decoded.created)
        assertEquals(2, decoded.songs.size)
        assertEquals(listOf(18L, 17L), decoded.playlists.single().songs)
        assertEquals(12_500L, decoded.queues.single().lastPositionMs)
        assertEquals(0, decoded.activeQueueIndex)
        assertTrue(decoded.songStates.single().favorite)
        assertEquals("Chorus", decoded.bookmarks.single().note)
        assertEquals(250, decoded.lyrics.single().offsetMs)
        assertEquals("Glass", decoded.tags.single().title)
        assertEquals("Music/LiltTest", decoded.rules.single().path)
        assertEquals("glass", decoded.searches.single().query)
        assertEquals("zh", decoded.settings.language)
        assertEquals(1.25f, decoded.settings.playbackSpeed, 0.001f)
        assertEquals(31.5f, decoded.settings.floatingFontSize, 0.001f)
        assertEquals(0xFF12AB34.toInt(), decoded.settings.floatingPlayedColor)
        assertEquals(0xFFF0E6DC.toInt(), decoded.settings.floatingUnplayedColor)
        assertFalse(decoded.settings.lockScreenPlayer)
        assertEquals("headroom", decoded.eq?.preampMode)
        assertFalse(decoded.eq?.perDevice ?: true)
    }

    @Test
    fun backupRejectsOtherPlatformsAndFormats() {
        val android = sampleBackup().encode()
        try {
            LiltBackup.decode(android.replace("\"platform\": \"android\"", "\"platform\": \"pc\""))
            fail("a desktop backup should not restore on Android")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("computer"))
        }
        try {
            LiltBackup.decode(android.replace("\"format\": 1", "\"format\": 9"))
            fail("unknown formats must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("format 9"))
        }
    }

    @Test
    fun invalidSettingsAreClampedDuringDecode() {
        val backup = sampleBackup().copy(
            settings = BackupSettings(
                playbackSpeed = 9f,
                lyricsFontSize = 99f,
                fadeInMs = 99_999,
                floatingFontSize = 2f,
            ),
        )
        val decoded = LiltBackup.decode(backup.encode())
        assertEquals(3f, decoded.settings.playbackSpeed, 0.001f)
        assertEquals(24f, decoded.settings.lyricsFontSize, 0.001f)
        assertEquals(4_000, decoded.settings.fadeInMs)
        assertEquals(18f, decoded.settings.floatingFontSize, 0.001f)
    }

    @Test
    fun songReferencesResolveByIdThenFileThenMetadata() {
        val backup = sampleBackup().copy(
            songs = listOf(
                BackupSongRef(17, "Glass Horizon", "Aurora Drift", "Demo Album", "Music/LiltTest", "01 - Glass Horizon.mp3", 20_088),
                BackupSongRef(99, "Soft Static", "Aurora Drift", "Demo Album", "Music/LiltTest", "02 - Soft Static.mp3", 18_064),
                BackupSongRef(55, "Northlight", "Aurora Drift", "Demo Album", "Elsewhere", "other.mp3", 16_000),
                BackupSongRef(44, "Missing", "Nobody", "None", "Elsewhere", "missing.mp3", 9_000),
            ),
        )
        val current = listOf(
            song(17),
            song(18, "Soft Static").copy(
                displayName = "02 - Soft Static.mp3",
                durationMs = 18_064,
            ),
            song(19, "Northlight").copy(durationMs = 16_000),
        )
        val resolved = resolveBackupSongIds(backup, current)
        assertEquals(17L, resolved[17])
        assertEquals(18L, resolved[99])
        assertEquals(19L, resolved[55])
        assertFalse(resolved.containsKey(44))
        assertEquals(1, unresolvedCount(backup, resolved))
    }

    @Test
    fun ambiguousMetadataIsNotGuessed() {
        val backup = sampleBackup().copy(
            songs = listOf(
                BackupSongRef(1, "Same", "Artist", "Album", "", "x.mp3", 10_000),
            ),
        )
        val current = listOf(
            song(2, "Same").copy(displayName = "a.mp3"),
            song(3, "Same").copy(displayName = "b.mp3"),
        )
        assertTrue(resolveBackupSongIds(backup, current).isEmpty())
    }

    @Test
    fun previewCountsWhatTheDialogShows() {
        val preview = sampleBackup().preview()
        assertEquals(2, preview.songs)
        assertEquals(1, preview.playlists)
        assertEquals(1, preview.queues)
        assertEquals(1, preview.favorites)
        assertEquals(1, preview.lyrics)
        assertEquals(1, preview.bookmarks)
        assertTrue(preview.settings)
    }

    @Test
    fun eqBackupBecomesARunnableProfile() {
        val profile = sampleBackup().eq!!.toProfile()
        assertEquals(EQ_BANDS, profile.gains.size)
        assertEquals("headroom", profile.preampMode.id)
        assertEquals(7f, profile.headroomDb, 0.001f)
        assertNotEquals("off", profile.preampMode.id)
    }
}
