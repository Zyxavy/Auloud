package app.auloud.player.ingest

import android.net.Uri
import app.auloud.player.storage.BundleStorage
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IN8: [StrayTempSweep] on plain JVM through a recording fake (Slice 9).
 *
 * The fake implements only the two ops the sweep touches; everything else
 * throws. Covers: temp deletion, real books untouched, listing failure
 * tolerated, and the stray-name rule itself.
 */
class StrayTempSweepTest {

    private class RecordingStorage(
        var strays: List<String> = emptyList(),
        var failListing: Boolean = false
    ) : BundleStorage {
        val deleted = mutableListOf<String>()

        override fun listBundleDirs(root: String): List<String> =
            throw UnsupportedOperationException("not used by the sweep")

        override fun readText(path: String): String =
            throw UnsupportedOperationException("not used by the sweep")

        override fun exists(path: String): Boolean =
            throw UnsupportedOperationException("not used by the sweep")

        override fun audioUri(bundleDir: String, relPath: String): Uri =
            throw UnsupportedOperationException("not used by the sweep")

        override fun coverUri(bundleDirPath: String, coverRel: String): String? = null

        override fun listStrayTempDirs(root: String): List<String> {
            if (failListing) throw IOException("$root: cannot list temp folders")
            return strays
        }

        override fun deleteRecursively(path: String) {
            deleted.add(path)
        }
    }

    @Test
    fun sweep_deletesEveryStrayTemp() {
        val storage = RecordingStorage(
            strays = listOf("/books/.tmp-b", "/books/.tmp-a")
        )

        val deleted = StrayTempSweep.sweep("/books", storage)

        assertEquals(listOf("/books/.tmp-a", "/books/.tmp-b"), deleted)
        assertEquals(listOf("/books/.tmp-a", "/books/.tmp-b"), storage.deleted)
    }

    @Test
    fun sweep_noStrays_deletesNothing() {
        val storage = RecordingStorage()

        assertTrue(StrayTempSweep.sweep("/books", storage).isEmpty())
        assertTrue(storage.deleted.isEmpty())
    }

    @Test
    fun sweep_listingFailure_toleratedAsNoStrays() {
        val storage = RecordingStorage(
            strays = listOf("/books/.tmp-a"),
            failListing = true
        )

        assertTrue(StrayTempSweep.sweep("/books", storage).isEmpty())
        assertTrue(storage.deleted.isEmpty())
    }

    @Test
    fun isStrayDir_matchesTempPrefixOnly() {
        assertTrue(StrayTempSweep.isStrayDir("/books/.tmp-abc"))
        assertTrue(StrayTempSweep.isStrayDir("/books/.tmp-abc/"))
        assertFalse(StrayTempSweep.isStrayDir("/books/abc"))
        assertFalse(StrayTempSweep.isStrayDir("/books/tmp-abc"))
        assertFalse(StrayTempSweep.isStrayDir("/books/.tmp-abc/manifest.json"))
        assertFalse(StrayTempSweep.isStrayDir("/books"))
    }
}
