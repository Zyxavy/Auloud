package app.auloud.player.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * IN7: file-branch write ops for the import pipeline (JVM only).
 *
 * Round-trips `writeBytes`/`writeText`/`copySourceFile`/`movePath`/
 * `deleteRecursively` through a real temp dir; the SAF branch keeps the
 * read-only default (import targets are always file folders).
 */
class FileBundleStorageWriteTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun path(vararg parts: String): String =
        parts.joinToString(File.separator)

    @Test
    fun writeBytes_createsParentsAndReadsBack() {
        val storage = FileBundleStorage()
        val target = path(temp.root.absolutePath, "Auloud", "book", "text", "ch001.json")
        storage.writeBytes(target, "hello".toByteArray(Charsets.UTF_8))
        assertTrue(storage.exists(target))
        assertEquals("hello", storage.readText(target))
    }

    @Test
    fun writeText_defaultsToUtf8Bytes() {
        val storage = FileBundleStorage()
        val target = path(temp.root.absolutePath, "note.txt")
        storage.writeText(target, "caf\u00E9")
        assertEquals("caf\u00E9", storage.readText(target))
    }

    @Test
    fun copySourceFile_streamsWithoutHoldingBytes() {
        val storage = FileBundleStorage()
        val src = temp.newFile("src.epub")
        src.writeBytes(ByteArray(20000) { (it % 251).toByte() })
        val dst = path(temp.root.absolutePath, "Auloud", "book", "source", "book.epub")
        storage.copySourceFile(src.absolutePath, dst)
        assertTrue(src.readBytes().contentEquals(File(dst).readBytes()))
    }

    @Test
    fun movePath_renamesTempToFinal() {
        val storage = FileBundleStorage()
        val from = path(temp.root.absolutePath, "Auloud", ".tmp-x")
        storage.writeText(path(from, "manifest.json"), "{}")
        val to = path(temp.root.absolutePath, "Auloud", "x")
        storage.movePath(from, to)
        assertFalse(File(from).exists())
        assertTrue(storage.exists(path(to, "manifest.json")))
    }

    @Test
    fun movePath_refusesExistingTarget() {
        val storage = FileBundleStorage()
        val from = path(temp.root.absolutePath, "Auloud", ".tmp-x")
        val to = path(temp.root.absolutePath, "Auloud", "x")
        storage.writeText(path(from, "m.json"), "{}")
        storage.writeText(path(to, "m.json"), "{}")
        try {
            storage.movePath(from, to)
            fail("want move to refuse an existing target")
        } catch (e: java.io.IOException) {
            assertTrue("names the target, got: ${e.message}", e.message!!.contains("x"))
        }
    }

    @Test
    fun deleteRecursively_removesTreeAndNeverThrows() {
        val storage = FileBundleStorage()
        val dir = path(temp.root.absolutePath, "gone")
        storage.writeText(path(dir, "sub", "f.txt"), "x")
        storage.deleteRecursively(dir)
        assertFalse(File(dir).exists())
        storage.deleteRecursively(path(temp.root.absolutePath, "absent"))
    }

    @Test
    fun routing_routesFileWritesAndRefusesSafWrites() {
        val routing = RoutingBundleStorage(FileBundleStorage()) { _ ->
            throw UnsupportedOperationException("no SAF tree in this test")
        }
        val target = path(temp.root.absolutePath, "r.txt")
        routing.writeText(target, "ok")
        assertEquals("ok", routing.readText(target))
        try {
            routing.writeText("content://tree|Auloud/r.txt", "no")
            fail("want SAF writes refused")
        } catch (e: java.io.IOException) {
            assertTrue("clear message, got: ${e.message}", e.message!!.contains("picked folder"))
        }
    }
}
