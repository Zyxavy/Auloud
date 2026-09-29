package app.auloud.player.data

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WP4: [RoomProgressRepository] logic on plain JVM via a fake DAO (no
 * Robolectric): save/load round-trip keyed by manifest `id`, overwrite,
 * unknown book, argument guards.
 */
class ProgressRepositoryTest {

    private var clockMs = 5_000L
    private lateinit var dao: FakeProgressDao
    private lateinit var repo: ProgressRepository

    @Before
    fun setUp() {
        dao = FakeProgressDao()
        repo = RoomProgressRepository(dao, now = { clockMs })
    }

    @Test
    fun save_load_roundTripsChapterAndPosition() = runBlocking {
        repo.save("book-1", 2, 61_000L).getOrThrow()

        val loaded = repo.load("book-1").getOrThrow()

        assertEquals("book-1", loaded?.bookId)
        assertEquals(2, loaded?.chapterIndex)
        assertEquals(61_000L, loaded?.positionMs)
        assertEquals(5_000L, loaded?.updatedAt)
    }

    @Test
    fun load_unknownBook_returnsNullSuccess() = runBlocking {
        val loaded = repo.load("never-saved").getOrThrow()

        assertNull(loaded)
    }

    @Test
    fun save_overwritesPreviousPosition() = runBlocking {
        repo.save("book-1", 1, 1_000L).getOrThrow()
        clockMs = 6_000L
        repo.save("book-1", 2, 61_000L).getOrThrow()

        val loaded = repo.load("book-1").getOrThrow()

        assertEquals(2, loaded?.chapterIndex)
        assertEquals(61_000L, loaded?.positionMs)
        assertEquals(6_000L, loaded?.updatedAt)
    }

    @Test
    fun save_isScopedPerBookId() = runBlocking {
        repo.save("book-1", 1, 1_000L).getOrThrow()
        repo.save("book-2", 3, 2_000L).getOrThrow()

        assertEquals(1, repo.load("book-1").getOrThrow()?.chapterIndex)
        assertEquals(3, repo.load("book-2").getOrThrow()?.chapterIndex)
    }

    @Test(expected = IllegalArgumentException::class)
    fun save_negativeChapter_throws() {
        runBlocking { repo.save("book-1", -1, 0L) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun save_negativePosition_throws() {
        runBlocking { repo.save("book-1", 0, -1L) }
    }

    @Test
    fun daoFailure_surfacesAsDataErrorLocal() = runBlocking {
        dao.fail = true

        val result = repo.load("book-1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DataError.Local)
    }

    /** In-memory [ProgressDao] with an injectable failure for the error-boundary test. */
    private class FakeProgressDao : ProgressDao {
        private val rows = mutableMapOf<String, ProgressEntity>()
        var fail = false

        override suspend fun load(bookId: String): ProgressEntity? {
            if (fail) throw IOException("fake db failure")
            return rows[bookId]
        }

        override suspend fun upsert(progress: ProgressEntity) {
            if (fail) throw IOException("fake db failure")
            rows[progress.bookId] = progress
        }
    }
}
