package app.auloud.player.data

/**
 * WP4: saved listening positions, keyed by manifest `id` (chapter index +
 * position in ms).
 *
 * IN9 (spec 2.0 section 8): unrendered books have no milliseconds, so their
 * position is chapter index + sentence sid ([sentenceSid], 1-based). Rendered
 * saves pass null (the default); unrendered saves pass the sid with
 * `positionMs` 0. The sid column exists since the IN1 migration; this is the
 * reader path that fills it (Slice 10 converts sid to ms on render).
 */
interface ProgressRepository {

    /**
     * Overwrites the position for [bookId]. Negative [chapterIndex] /
     * [positionMs] are caller bugs and throw `IllegalArgumentException`
     * directly (not wrapped in `Result`). A non-null [sentenceSid] must be
     * >= 1 (same guard style).
     */
    suspend fun save(
        bookId: String,
        chapterIndex: Int,
        positionMs: Long,
        sentenceSid: Int? = null
    ): Result<Unit>

    /** Returns the saved position, or success-with-null when never saved. */
    suspend fun load(bookId: String): Result<ProgressEntity?>

    /**
     * IN8: removes the saved position for [bookId]. Deleting a missing
     * position still succeeds (there is nothing to resurrect).
     */
    suspend fun delete(bookId: String): Result<Unit>
}
