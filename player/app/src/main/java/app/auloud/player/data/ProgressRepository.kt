package app.auloud.player.data

/**
 * WP4: saved listening positions, keyed by manifest `id` (chapter index +
 * position in ms).
 */
interface ProgressRepository {

    /**
     * Overwrites the position for [bookId]. Negative [chapterIndex] /
     * [positionMs] are caller bugs and throw `IllegalArgumentException`
     * directly (not wrapped in `Result`).
     */
    suspend fun save(bookId: String, chapterIndex: Int, positionMs: Long): Result<Unit>

    /** Returns the saved position, or success-with-null when never saved. */
    suspend fun load(bookId: String): Result<ProgressEntity?>
}
