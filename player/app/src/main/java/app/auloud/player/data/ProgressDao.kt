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

    /**
     * FP2: every saved position in one query (the library screen reads all
     * rows per books emission; per-book loads were N+1).
     */
    @Query("SELECT * FROM progress")
    suspend fun getAll(): List<ProgressEntity>

    @Upsert
    suspend fun upsert(progress: ProgressEntity)

    /**
     * IN8: removes the saved position for [bookId] (library delete must
     * not leave an orphan that a later re-import would resurrect).
     */
    @Query("DELETE FROM progress WHERE bookId = :bookId")
    suspend fun deleteById(bookId: String)
}
