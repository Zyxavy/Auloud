package app.auloud.player.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * WP4: progress persistence. One row per manifest `id`; saves overwrite it.
 */
@Dao
interface ProgressDao {

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun load(bookId: String): ProgressEntity?

    @Upsert
    suspend fun upsert(progress: ProgressEntity)
}
