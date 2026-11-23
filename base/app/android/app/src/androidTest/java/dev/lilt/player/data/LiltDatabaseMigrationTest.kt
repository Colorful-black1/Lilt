package dev.lilt.player.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiltDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LiltDatabase::class.java,
    )

    private val databaseName = "lilt-migration-test.db"

    @Test
    fun migrate2To3AddsBookmarksAndSearchHistory() {
        val name = "lilt-migration-2-3.db"
        helper.createDatabase(name, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO songs
                    (mediaId, title, artist, album, albumId, folder, displayName,
                     durationMs, track, year, mime, dateAdded, size, lastSeen)
                VALUES
                    (42, 'Track', 'Artist', 'Album', 7, 'Music/Rock', 'track.mp3',
                     185000, 1, 2020, 'audio/mpeg', 1, 10, 1)
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO song_state (mediaId, favorite, playCount, lastPlayedAt) " +
                    "VALUES (42, 1, 5, 123)",
            )
        }

        helper.runMigrationsAndValidate(
            name,
            3,
            true,
            LiltDatabase.MIGRATION_2_3,
        ).use { db ->
            db.query("SELECT favorite, playCount FROM song_state WHERE mediaId = 42").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals(5, cursor.getInt(1))
            }
            db.execSQL(
                "INSERT INTO bookmarks (mediaId, positionMs, note, createdAt) " +
                    "VALUES (42, 61000, 'Chorus', 456)",
            )
            db.query(
                "SELECT note, positionMs FROM bookmarks WHERE mediaId = 42",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Chorus", cursor.getString(0))
                assertEquals(61000L, cursor.getLong(1))
            }
            db.execSQL(
                "INSERT INTO search_history (query, searchedAt) VALUES ('north', 789)",
            )
            db.query("SELECT COUNT(*) FROM search_history").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrate3To4AddsLyricsAndKeepsBookmarks() {
        val name = "lilt-migration-3-4.db"
        helper.createDatabase(name, 3).use { db ->
            db.execSQL(
                """
                INSERT INTO songs
                    (mediaId, title, artist, album, albumId, folder, displayName,
                     durationMs, track, year, mime, dateAdded, size, lastSeen)
                VALUES
                    (42, 'Track', 'Artist', 'Album', 7, 'Music/Rock', 'track.mp3',
                     185000, 1, 2020, 'audio/mpeg', 1, 10, 1)
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO bookmarks (mediaId, positionMs, note, createdAt) " +
                    "VALUES (42, 61000, 'Chorus', 456)",
            )
        }

        helper.runMigrationsAndValidate(
            name,
            4,
            true,
            LiltDatabase.MIGRATION_3_4,
        ).use { db ->
            db.query("SELECT note FROM bookmarks WHERE mediaId = 42").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Chorus", cursor.getString(0))
            }
            db.execSQL(
                "INSERT INTO lyrics (mediaId, text, updatedAt) VALUES (42, '[00:01.00]Hello', 789)",
            )
            db.query("SELECT text FROM lyrics WHERE mediaId = 42").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("[00:01.00]Hello", cursor.getString(0))
            }
        }
    }

    @Test
    fun migrate4To5AddsQueuePositionColumns() {
        val name = "lilt-migration-4-5.db"
        helper.createDatabase(name, 4).use { db ->
            db.execSQL("INSERT INTO queues (id, name, createdAt) VALUES (7, 'Trip', 123)")
            db.execSQL(
                "INSERT INTO queue_songs (queueId, mediaId, position) VALUES (7, 42, 0)",
            )
        }

        helper.runMigrationsAndValidate(
            name,
            5,
            true,
            LiltDatabase.MIGRATION_4_5,
        ).use { db ->
            db.query(
                "SELECT name, lastIndex, lastPositionMs FROM queues WHERE id = 7",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Trip", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0L, cursor.getLong(2))
            }
            db.execSQL(
                "UPDATE queues SET lastIndex = 3, lastPositionMs = 61234 WHERE id = 7",
            )
            db.query(
                "SELECT lastIndex, lastPositionMs FROM queues WHERE id = 7",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3, cursor.getInt(0))
                assertEquals(61234L, cursor.getLong(1))
            }
        }
    }

    @Test
    fun migrate5To6AddsTagOverridesAndKeepsUserData() {
        val name = "lilt-migration-5-6.db"
        helper.createDatabase(name, 5).use { db ->
            db.execSQL(
                """
                INSERT INTO songs
                    (mediaId, title, artist, album, albumId, folder, displayName,
                     durationMs, track, year, mime, dateAdded, size, lastSeen)
                VALUES
                    (42, 'Track', 'Artist', 'Album', 7, 'Music/Rock', 'track.mp3',
                     185000, 1, 2020, 'audio/mpeg', 1, 10, 1)
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO song_state (mediaId, favorite, playCount, lastPlayedAt) " +
                    "VALUES (42, 1, 5, 123)",
            )
            db.execSQL(
                "INSERT INTO lyrics (mediaId, text, updatedAt) VALUES (42, '[00:01.00]Hello', 789)",
            )
        }

        helper.runMigrationsAndValidate(
            name,
            6,
            true,
            LiltDatabase.MIGRATION_5_6,
        ).use { db ->
            db.query("SELECT title FROM songs WHERE mediaId = 42").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Track", cursor.getString(0))
            }
            db.query("SELECT favorite, playCount FROM song_state WHERE mediaId = 42").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals(5, cursor.getInt(1))
            }
            db.execSQL(
                "INSERT INTO tag_overrides " +
                    "(mediaId, title, artist, album, year, track, updatedAt) " +
                    "VALUES (42, 'Custom', 'Artist 2', 'Album 2', 2024, 3, 456)",
            )
            db.query(
                "SELECT title, artist, album, year, track FROM tag_overrides WHERE mediaId = 42",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Custom", cursor.getString(0))
                assertEquals("Artist 2", cursor.getString(1))
                assertEquals("Album 2", cursor.getString(2))
                assertEquals(2024, cursor.getInt(3))
                assertEquals(3, cursor.getInt(4))
            }
        }
    }

    @Test
    fun migrate1To2KeepsLibraryAndAddsSongState() {
        helper.createDatabase(databaseName, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO songs
                    (mediaId, title, artist, album, albumId, folder, displayName,
                     durationMs, track, year, mime, dateAdded, size, lastSeen)
                VALUES
                    (42, 'Track', 'Artist', 'Album', 7, 'Music/Rock', 'track.mp3',
                     185000, 1, 2020, 'audio/mpeg', 1, 10, 1)
                """.trimIndent(),
            )
            db.execSQL("INSERT INTO playlists (id, name, createdAt) VALUES (1, 'Road', 123)")
            db.execSQL("INSERT INTO playlist_songs (playlistId, mediaId, position) VALUES (1, 42, 0)")
            db.execSQL("INSERT INTO queues (id, name, createdAt) VALUES (1, 'Queue', 123)")
            db.execSQL("INSERT INTO queue_songs (queueId, mediaId, position) VALUES (1, 42, 0)")
        }

        helper.runMigrationsAndValidate(
            databaseName,
            2,
            true,
            LiltDatabase.MIGRATION_1_2,
        ).use { db ->
            db.query("SELECT title FROM songs WHERE mediaId = 42").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Track", cursor.getString(0))
            }
            db.query("SELECT COUNT(*) FROM playlists").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM queues").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            db.query("PRAGMA table_info(song_state)").use { cursor ->
                assertTrue(cursor.count >= 4)
            }
            db.execSQL(
                "INSERT INTO song_state (mediaId, favorite, playCount, lastPlayedAt) VALUES (42, 1, 3, 123)",
            )
            db.query("SELECT favorite, playCount FROM song_state WHERE mediaId = 42").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals(3, cursor.getInt(1))
            }
        }
    }
}
