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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WP4 slice-1 verify on an API 24+ device: real Room (in-memory), real
 * repositories, real upsert SQL — import twice leaves one book row, and
 * progress round-trips.
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

    private lateinit var db: AuloudDatabase
    private lateinit var library: LibraryRepository
    private lateinit var progress: ProgressRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AuloudDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        library = RoomLibraryRepository(db.bookDao(), EverythingExistsStorage())
        progress = RoomProgressRepository(db.progressDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun importTwice_leavesOneRow() = runBlocking {
        library.importBundle(bundleDir, manifest()).getOrThrow()
        library.importBundle(bundleDir, manifest()).getOrThrow()

        val books = library.books().first()

        assertEquals(1, books.size)
        assertEquals("Example Novel", books[0].title)
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
    }

    private fun manifest() = Manifest(
        specVersion = "1.0",
        id = "8f0c6c1e-3a8f-4c6e-9d54-0b6a3f1a2b77",
        title = "Example Novel",
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
