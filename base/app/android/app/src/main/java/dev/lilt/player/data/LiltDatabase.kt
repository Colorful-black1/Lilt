package dev.lilt.player.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SongEntity::class,
        MissingSongEntity::class,
        SongStateEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        QueueEntity::class,
        QueueSongEntity::class,
        HistoryEntity::class,
        BookmarkEntity::class,
        SearchHistoryEntity::class,
        LyricsEntity::class,
        LyricsFetchEntity::class,
        FolderRuleEntity::class,
        TagOverrideEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class LiltDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao
    abstract fun songStateDao(): SongStateDao
    abstract fun tagOverrideDao(): TagOverrideDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun lyricsFetchDao(): LyricsFetchDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun queueDao(): QueueDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `song_state` (
                        `mediaId` INTEGER NOT NULL,
                        `favorite` INTEGER NOT NULL,
                        `playCount` INTEGER NOT NULL,
                        `lastPlayedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`mediaId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `bookmarks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `mediaId` INTEGER NOT NULL,
                        `positionMs` INTEGER NOT NULL,
                        `note` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_bookmarks_mediaId_positionMs` ON `bookmarks` (`mediaId`, `positionMs`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `search_history` (
                        `query` TEXT NOT NULL,
                        `searchedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`query`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `lyrics` (
                        `mediaId` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`mediaId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE queues ADD COLUMN lastIndex INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE queues ADD COLUMN lastPositionMs INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tag_overrides` (
                        `mediaId` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `year` INTEGER NOT NULL,
                        `track` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`mediaId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE lyrics ADD COLUMN offsetMs INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `missing_songs` (
                        `mediaId` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `folder` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `lastSeen` INTEGER NOT NULL,
                        PRIMARY KEY(`mediaId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE lyrics ADD COLUMN source TEXT NOT NULL DEFAULT 'local'",
                )
                db.execSQL(
                    "ALTER TABLE lyrics ADD COLUMN fetchedAt INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `lyrics_fetch_state` (
                        `mediaId` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `lastAttemptAt` INTEGER NOT NULL,
                        `nextRetryAt` INTEGER NOT NULL,
                        `candidateCount` INTEGER NOT NULL DEFAULT 0,
                        `candidates` TEXT NOT NULL DEFAULT '',
                        `error` TEXT NOT NULL DEFAULT '',
                        `autoDisabled` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`mediaId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        @Volatile
        private var instance: LiltDatabase? = null

        fun get(context: Context): LiltDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LiltDatabase::class.java,
                    "lilt.db",
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                )
                    .build()
                    .also { instance = it }
            }
    }
}
