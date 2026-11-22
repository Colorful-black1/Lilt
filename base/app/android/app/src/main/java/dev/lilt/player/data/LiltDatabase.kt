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
        SongStateEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        QueueEntity::class,
        QueueSongEntity::class,
        HistoryEntity::class,
        FolderRuleEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class LiltDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao
    abstract fun songStateDao(): SongStateDao
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

        @Volatile
        private var instance: LiltDatabase? = null

        fun get(context: Context): LiltDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LiltDatabase::class.java,
                    "lilt.db",
                ).addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
