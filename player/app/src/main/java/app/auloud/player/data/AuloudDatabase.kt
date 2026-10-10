package app.auloud.player.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * WP4: Room database (pure-Android setup; the KMP `@ConstructedBy` shape is
 * opt-in only when the data layer moves to `commonMain`).
 *
 * Version 2 adds the sid-based progress column for unrendered 2.0 books
 * (IN1, spec 2.0 section 8: position is chapter + sentence sid when there
 * are no milliseconds). Version 1 schemas were never exported
 * (`exportSchema = false`); from v2 the schema is exported to
 * `player/app/schemas/` so future migrations have history to diff against.
 *
 * The v1 to v2 migration cannot run on the JVM here; it needs the on-device
 * check (fresh install plus upgrade over an existing v1 library).
 */
@Database(entities = [BookEntity::class, ProgressEntity::class], version = 2, exportSchema = true)
abstract class AuloudDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao

    abstract fun progressDao(): ProgressDao

    companion object {
        private const val NAME = "auloud.db"

        /**
         * IN1: v1 to v2 adds the nullable `sentenceSid` column to `progress`.
         * Existing ms-based rows read back as NULL (still ms positions);
         * unrendered-book saves fill it from IN9 on.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE progress ADD COLUMN sentenceSid INTEGER")
            }
        }

        fun open(context: Context): AuloudDatabase =
            Room.databaseBuilder(context, AuloudDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
