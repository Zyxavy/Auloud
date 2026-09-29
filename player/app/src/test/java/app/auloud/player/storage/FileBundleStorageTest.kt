package app.auloud.player.storage

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * WP3: storage-layer verifies (list/read/exists/audioUri, incl. missing file).
 * `java.io.File` appears here only to set up temp fixtures; app code behind
 * [BundleStorage] never exposes it.
 */
class FileBundleStorageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val storage = FileBundleStorage()

    private fun bundleDir(root: File, name: String): File {
        val dir = File(root, name)
        assertTrue(dir.mkdirs())
        File(dir, "manifest.json").writeText("{}", Charsets.UTF_8)
        return dir
    }

    @Test
    fun listBundleDirs_listsOnlyDirsWithManifest() {
        val root = temp.root
        val bookB = bundleDir(root, "book-b")
        val bookA = bundleDir(root, "book-a")
        File(root, "not-a-bundle").mkdir()
        File(root, "stray.txt").writeText("x", Charsets.UTF_8)

        val listed = storage.listBundleDirs(root.absolutePath)

        assertEquals(
            listOf(bookA.absolutePath, bookB.absolutePath).sorted(),
            listed
        )
    }

    @Test
    fun listBundleDirs_missingRoot_returnsEmpty() {
        assertTrue(
            storage.listBundleDirs(File(temp.root, "does-not-exist").absolutePath).isEmpty()
        )
    }

    @Test
    fun readText_returnsUtf8Content() {
        val file = File(temp.root, "note.txt")
        file.writeText("héllo — ütf8", Charsets.UTF_8)

        assertEquals("héllo — ütf8", storage.readText(file.absolutePath))
    }

    @Test(expected = IOException::class)
    fun readText_missingFile_throws() {
        storage.readText(File(temp.root, "missing.txt").absolutePath)
    }

    @Test
    fun exists_trueFalseAndMissing() {
        val present = File(temp.root, "here.txt")
        present.writeText("x", Charsets.UTF_8)

        assertTrue(storage.exists(present.absolutePath))
        assertFalse(storage.exists(File(temp.root, "gone.txt").absolutePath))
    }

    @Test
    fun audioUri_resolvesInsideBundleDir() {
        val bundle = bundleDir(temp.root, "book")
        val captured = slot<File>()
        mockkStatic(Uri::class)
        try {
            val fake = mockk<Uri>()
            every { Uri.fromFile(capture(captured)) } returns fake

            val result = storage.audioUri(bundle.absolutePath, "audio/ch001.mp3")

            assertSame(fake, result)
            assertEquals(
                File(bundle, "audio/ch001.mp3").canonicalPath,
                captured.captured.canonicalPath
            )
            assertTrue(
                "resolved file must stay inside the bundle dir",
                captured.captured.canonicalPath.startsWith(
                    bundle.canonicalPath + File.separatorChar
                )
            )
        } finally {
            unmockkStatic(Uri::class)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun audioUri_dotDotEscape_throws() {
        val bundle = bundleDir(temp.root, "book")
        storage.audioUri(bundle.absolutePath, "../evil.mp3")
    }
}
