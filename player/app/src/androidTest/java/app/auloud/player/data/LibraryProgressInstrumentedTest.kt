package app.auloud.player.data

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.auloud.player.bundle.AudioInfo
import app.auloud.player.bundle.ChapterInfo
import app.auloud.player.bundle.Manifest
import app.auloud.player.storage.BundleStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WP4 slice-1 verify on an API 24+ device: real Room (in-memory), real
 * repositories, real upsert SQL — import twice leaves one book row with the
 * original `addedAt`, updated title, summed `durationMs` and resolved
 * `coverPath`; progress round-trips per book with overwrite; a vanished
 * folder flags the book missing without deleting it.
 *
 * Self-contained on purpose: the manifest is built inline (same shape as
 * `spec/fixtures/valid-bundle/manifest.json`) and storage is faked as
 * "everything exists", so no fixture files or SD card are needed. Needs the
 * standard test instrumentation artifacts (`androidx.test:runner`,
 * `androidx.test.ext:junit`) on the `androidTestImplementation` classpath;
 * Room itself comes from the app's dependencies. The controller runs this
 * (connected test); it is not executed by the unit-test task.
 */
@RunWith(AndroidJUnit4::class)
class LibraryProgressInstrumentedTest {

    private val bundleDir = "/storage/1234-ABCD/Auloud/example-novel"
    private var clockMs = 1_000L

    private lateinit var db: AuloudDatabase
    private lateinit var library: LibraryRepository
    private lateinit var progress: ProgressRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AuloudDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        library = RoomLibraryRepository(db.bookDao(), EverythingExistsStorage(), now = { clockMs })
        progress = RoomProgressRepository(db.progressDao(), now = { clockMs })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun importTwice_leavesOneRow() = runBlocking {
        library.importBundle(bundleDir, manifest()).getOrThrow()
        clockMs = 9_999L
        library.importBundle(bundleDir, manifest(title = "Example Novel (revised)")).getOrThrow()

        val books = library.books().first()

        assertEquals(1, books.size)
        assertEquals("Example Novel (revised)", books[0].title)
        assertEquals(1_000L, books[0].addedAt)
        assertEquals(1832400L + 1640100L, books[0].durationMs)
        assertEquals("$bundleDir/cover.jpg", books[0].coverPath)
        assertFalse(books[0].isMissing)
    }

    @Test
    fun progress_roundTrips() = runBlocking {
        val id = library.importBundle(bundleDir, manifest()).getOrThrow().id

        progress.save(id, 2, 61_000L).getOrThrow()
        val loaded = progress.load(id).getOrThrow()

        assertEquals(id, loaded?.bookId)
        assertEquals(2, loaded?.chapterIndex)
        assertEquals(61_000L, loaded?.positionMs)
        assertEquals(1_000L, loaded?.updatedAt)

        clockMs = 2_000L
        progress.save(id, 1, 5_000L).getOrThrow()
        val overwritten = progress.load(id).getOrThrow()
        assertEquals(1, overwritten?.chapterIndex)
        assertEquals(5_000L, overwritten?.positionMs)
        assertEquals(2_000L, overwritten?.updatedAt)

        progress.save("other-book", 3, 7_000L).getOrThrow()
        assertEquals(1, progress.load(id).getOrThrow()?.chapterIndex)
        assertEquals(3, progress.load("other-book").getOrThrow()?.chapterIndex)
        assertNull(progress.load("never-saved").getOrThrow())
    }

    @Test
    fun missingBundle_flaggedNotDeleted() = runBlocking {
        library.importBundle(bundleDir, manifest()).getOrThrow()

        library.refreshMissing(emptyList()).getOrThrow()

        val books = library.books().first()
        assertEquals(1, books.size)
        assertTrue(books[0].isMissing)

        library.refreshMissing(listOf(bundleDir)).getOrThrow()
        assertFalse(library.books().first().single().isMissing)
    }

    private fun manifest(title: String = "Example Novel") = Manifest(
        specVersion = "1.0",
        id = "8f0c6c1e-3a8f-4c6e-9d54-0b6a3f1a2b77",
        title = title,
        type = "epub",
        audio = AudioInfo(),
        chapters = listOf(
            ChapterInfo(1, "Chapter One", "audio/ch001.mp3", "text/ch001.json", 1832400L),
            ChapterInfo(2, "Chapter Two", "audio/ch002.mp3", "text/ch002.json", 1640100L)
        ),
        author = "A. Author",
        cover = "cover.jpg"
    )

    /** Repositories only use `exists`; everything else is unsupported here. */
    private class EverythingExistsStorage : BundleStorage {
        override fun listBundleDirs(root: String): List<String> = emptyList()
        override fun readText(path: String): String = throw UnsupportedOperationException()
        override fun exists(path: String): Boolean = true
        override fun audioUri(bundleDir: String, relPath: String): Uri =
            throw UnsupportedOperationException()
    }
}
