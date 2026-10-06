package app.auloud.player.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * WP4: book persistence. Upserts key by manifest `id`, so re-importing a
 * bundle updates its row instead of adding one.
 */
@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    suspend fun getAll(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: String): BookEntity?

    @Upsert
    suspend fun upsert(book: BookEntity)

    @Query("UPDATE books SET isMissing = :missing WHERE id = :id")
    suspend fun setMissing(id: String, missing: Boolean)

    /**
     * IN8: removes the row for [id] (library delete; the book folder is
     * removed through [app.auloud.player.storage.BundleStorage] first).
     */
    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: String)
}
