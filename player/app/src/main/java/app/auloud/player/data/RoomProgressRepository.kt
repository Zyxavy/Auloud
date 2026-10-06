package app.auloud.player.data

/**
 * WP4: [ProgressRepository] over Room. [now] supplies epoch millis for
 * [ProgressEntity.updatedAt] (injectable for deterministic tests).
 *
 * API 24 safe: no `java.time`.
 */
class RoomProgressRepository(
    private val progressDao: ProgressDao,
    private val now: () -> Long = System::currentTimeMillis
) : ProgressRepository {

    override suspend fun save(bookId: String, chapterIndex: Int, positionMs: Long): Result<Unit> {
        require(bookId.isNotBlank()) { "bookId must not be blank" }
        require(chapterIndex >= 0) { "chapterIndex must be >= 0, was $chapterIndex" }
        require(positionMs >= 0) { "positionMs must be >= 0, was $positionMs" }
        return runBoundary {
            progressDao.upsert(ProgressEntity(bookId, chapterIndex, positionMs, now()))
        }
    }

    override suspend fun load(bookId: String): Result<ProgressEntity?> =
        runBoundary { progressDao.load(bookId) }

    override suspend fun delete(bookId: String): Result<Unit> =
        runBoundary { progressDao.deleteById(bookId) }
}
