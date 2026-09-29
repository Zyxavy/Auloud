package app.auloud.player.storage

import android.net.Uri
import io.mockk.mockk
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WP3/WP5 refinement: [RoutingBundleStorage] verifies on plain JVM — file
 * paths reach the file delegate, `content://` roots/tokens reach the SAF
 * delegate for the right tree, and SAF `exists` failures degrade to false
 * (never a crash).
 */
class RoutingBundleStorageTest {

    private val treeA = "content://com.android.externalstorage.documents/tree/primary%3AAuloud"
    private val treeB = "content://com.android.externalstorage.documents/tree/1234-ABCD%3AAuloud"

    private lateinit var file: RecordingStorage
    private lateinit var safA: RecordingStorage
    private lateinit var safB: RecordingStorage
    private lateinit var routing: RoutingBundleStorage

    @Before
    fun setUp() {
        file = RecordingStorage("file")
        safA = RecordingStorage("safA")
        safB = RecordingStorage("safB")
        routing = RoutingBundleStorage(file) { tree ->
            when (tree) {
                treeA -> safA
                treeB -> safB
                else -> throw IllegalArgumentException("$tree: unknown tree")
            }
        }
    }

    @Test
    fun listBundleDirs_fileRoot_goesToFile() {
        routing.listBundleDirs("/storage/emulated/0/Auloud")

        assertEquals(listOf("/storage/emulated/0/Auloud"), file.listedRoots)
        assertTrue(safA.listedRoots.isEmpty())
    }

    @Test
    fun listBundleDirs_treeRoot_goesToMatchingSaf() {
        routing.listBundleDirs(treeB)

        assertEquals(listOf(treeB), safB.listedRoots)
        assertTrue(safA.listedRoots.isEmpty())
        assertTrue(file.listedRoots.isEmpty())
    }

    @Test
    fun readText_token_goesToOwningTree() {
        routing.readText("$treeA|book1/manifest.json")

        assertEquals(listOf("$treeA|book1/manifest.json"), safA.readPaths)
        assertTrue(file.readPaths.isEmpty())
    }

    @Test
    fun readText_filePath_goesToFile() {
        routing.readText("/books/book1/manifest.json")

        assertEquals(listOf("/books/book1/manifest.json"), file.readPaths)
    }

    @Test
    fun exists_safThrowing_degradesToFalse() {
        val throwing = object : BundleStorage by file {
            override fun exists(path: String): Boolean = throw IOException("lost")
        }
        val underTest = RoutingBundleStorage(throwing) { safA }

        assertFalse(underTest.exists("$treeA|book1/manifest.json"))
    }

    @Test
    fun exists_filePath_delegates() {
        file.existing = setOf("/books/manifest.json")

        assertTrue(routing.exists("/books/manifest.json"))
        assertFalse(routing.exists("/books/missing.json"))
    }

    @Test
    fun audioUri_routesByBundleDir() {
        val fileUri = mockk<Uri>()
        val safUri = mockk<Uri>()
        file.audioResult = fileUri
        safB.audioResult = safUri

        assertSame(
            fileUri,
            routing.audioUri("/storage/emulated/0/Auloud/book1", "audio/ch001.mp3")
        )
        assertSame(
            safUri,
            routing.audioUri("$treeB|book1", "audio/ch001.mp3")
        )
        assertEquals(
            listOf("/storage/emulated/0/Auloud/book1" to "audio/ch001.mp3"),
            file.audioCalls
        )
        assertEquals(listOf("$treeB|book1" to "audio/ch001.mp3"), safB.audioCalls)
    }

    private class RecordingStorage(val name: String) : BundleStorage {
        val listedRoots = mutableListOf<String>()
        val readPaths = mutableListOf<String>()
        val audioCalls = mutableListOf<Pair<String, String>>()
        var existing: Set<String> = emptySet()
        var audioResult: Uri? = null

        override fun listBundleDirs(root: String): List<String> {
            listedRoots.add(root)
            return emptyList()
        }

        override fun readText(path: String): String {
            readPaths.add(path)
            return "$name:$path"
        }

        override fun exists(path: String): Boolean = path in existing

        override fun audioUri(bundleDir: String, relPath: String): Uri {
            audioCalls.add(bundleDir to relPath)
            return audioResult ?: throw UnsupportedOperationException(name)
        }
    }
}
