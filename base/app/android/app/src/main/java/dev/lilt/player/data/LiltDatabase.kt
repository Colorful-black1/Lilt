package dev.lilt.player.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        QueueEntity::class,
        QueueSongEntity::class,
        HistoryEntity::class,
        FolderRuleEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class LiltDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun queueDao(): QueueDao

    companion object {
        @Volatile
        private var instance: LiltDatabase? = null

        fun get(context: Context): LiltDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LiltDatabase::class.java,
                    "lilt.db",
                ).build().also { instance = it }
            }
    }
}
