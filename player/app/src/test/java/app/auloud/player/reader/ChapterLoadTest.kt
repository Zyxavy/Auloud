package app.auloud.player.reader

import android.net.Uri
import app.auloud.player.ingest.IngestWriter
import app.auloud.player.storage.BundleStorage
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPEN1: [loadChapterList] over a map-backed fake (the Compose effect
 * calls this, so every failure mode here is a mode the hub survives).
 * Pins the ready path, the timeout naming slow storage, and the rogue
 * `Throwable` (a device linking/native error bypasses `Exception`
 * catches) landing with its class name instead of killing the loader
 * silently.
 */
class ChapterLoadTest {

    private fun storageOf(read: (String) -> String): BundleStorage =
        object : BundleStorage {
            override fun listBundleDirs(root: String): List<String> = emptyList()
            override fun readText(path: String): String = read(path)
            override fun exists(path: String): Boolean = true
            override fun audioUri(bundleDir: String, relPath: String): Uri =
                throw UnsupportedOperationException("not used by the chapter load")
            override fun coverUri(bundleDirPath: String, coverRel: String): String? = null
        }

    private fun freshManifestJson(): String =
        IngestWriter.renderManifestJson(
            IngestWriter.ManifestInput(
                bookId = "b1",
                title = "Tiny",
                author = null,
                language = null,
                sha256Hex = "aa",
                coverRel = null,
                chapters = listOf(
                    IngestWriter.ChapterEntry(1, "Ch 1", "text/ch001.json"),
                    IngestWriter.ChapterEntry(2, "Ch 2", "text/ch002.json")
                ),
                createdAt = "2026-10-16T00:00:00Z"
            )
        )

    @Test
    fun freshManifest_loadsEntriesAndState() = runBlocking {
        val storage = storageOf { freshManifestJson() }
        val result = loadChapterList(storage, "/books/b1")
        val ready = result as? ChapterLoadResult.Ready
            ?: throw AssertionError("want Ready, got $result")
        assertEquals(listOf("Ch 1", "Ch 2"), ready.chapters.map { it.title })
        assertEquals("none", ready.renderState)
    }

    @Test
    fun slowStorage_timesOutFast() = runBlocking {
        val closed = CountDownLatch(1)
        val storage = storageOf { closed.await(); "" }
        val started = System.currentTimeMillis()
        val result = try {
            loadChapterList(storage, "/books/b1", timeoutMs = 100L)
        } finally {
            closed.countDown()
        }
        val elapsed = System.currentTimeMillis() - started
        // OPEN3: the old `withTimeout` over a blocking read resumed only
        // when the read returned (measured 5.016s). A blocked read must
        // now report the stall without waiting for it.
        assertTrue("must not wait for the blocked read, took ${elapsed}ms", elapsed < 5_000L)
        val failed = result as? ChapterLoadResult.Failed
            ?: throw AssertionError("want Failed, got $result")
        assertTrue("blocked storage must read timed out, got: ${failed.message}", failed.timedOut)
        assertTrue("must name the path, got: ${failed.message}", "/books/b1/manifest.json" in failed.message)
    }

    @Test
    fun linkError_surfacesClassName() = runBlocking {
        val storage = storageOf { throw UnsatisfiedLinkError("no native lib here") }
        val result = loadChapterList(storage, "/books/b1")
        val failed = result as? ChapterLoadResult.Failed
            ?: throw AssertionError("want Failed, got $result")
        assertTrue("must name the layer, got: ${failed.message}", "UnsatisfiedLinkError" in failed.message)
        assertTrue(!failed.timedOut)
    }

    @Test
    fun corruptManifest_readsEmpty() = runBlocking {
        val storage = storageOf { "{broken" }
        val result = loadChapterList(storage, "/books/b1")
        val ready = result as? ChapterLoadResult.Ready
            ?: throw AssertionError("want Ready, got $result")
        assertTrue(ready.chapters.isEmpty())
        assertNull(ready.renderState)
    }

    @Test
    fun missingManifest_readsEmpty() = runBlocking {
        val storage = storageOf { throw IOException("missing: manifest.json") }
        val result = loadChapterList(storage, "/books/b1")
        val ready = result as? ChapterLoadResult.Ready
            ?: throw AssertionError("want Ready, got $result")
        assertTrue(ready.chapters.isEmpty())
    }
}
