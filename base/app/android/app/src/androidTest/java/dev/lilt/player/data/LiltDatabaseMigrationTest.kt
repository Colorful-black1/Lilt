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
