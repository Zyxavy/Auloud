package app.auloud.player.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * WP4: Room database (pure-Android setup; the KMP `@ConstructedBy` shape is
 * opt-in only when the data layer moves to `commonMain`).
 *
 * Version 1, no migrations yet. `exportSchema = false`: schema export is
 * enabled once migrations exist.
 */
@Database(entities = [BookEntity::class, ProgressEntity::class], version = 1, exportSchema = false)
abstract class AuloudDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao

    abstract fun progressDao(): ProgressDao

    companion object {
        private const val NAME = "auloud.db"

        fun open(context: Context): AuloudDatabase =
            Room.databaseBuilder(context, AuloudDatabase::class.java, NAME).build()
    }
}
