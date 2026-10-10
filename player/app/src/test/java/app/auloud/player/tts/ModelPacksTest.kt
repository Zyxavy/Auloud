package app.auloud.player.tts

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PW7a: pack discovery on plain JVM (tmp dirs, no Android).
 */
class ModelPacksTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun packDir(parent: File, name: String, vararg models: String): File {
        val dir = File(parent, name).apply { mkdirs() }
        models.forEach { File(dir, "$it.onnx").writeBytes(ByteArray(100)) }
        return dir
    }

    @Test
    fun scan_findsPackWithVoicesAndBytes() {
        val root = tmp.newFolder("models")
        packDir(root, "piper", "en_US-lessac-low", "en_US-ryan-low")
        val packs = ModelPacks.scan(listOf(root))
        assertEquals(1, packs.size)
        assertEquals("piper", packs[0].label)
        assertEquals(listOf("en_US-lessac-low", "en_US-ryan-low"), packs[0].voices)
        assertTrue(packs[0].bytesTotal >= 200)
    }

    @Test
    fun scan_rootItselfCanBeAPack() {
        val root = tmp.newFolder("models")
        File(root, "solo.onnx").writeBytes(ByteArray(10))
        val packs = ModelPacks.scan(listOf(root))
        assertEquals(listOf("models"), packs.map { it.label })
    }

    @Test
    fun scan_skipsEmptyAndModelLessDirs() {
        val root = tmp.newFolder("models")
        File(root, "empty").mkdirs()
        File(root, "notes").mkdirs()
        File(File(root, "notes"), "readme.txt").writeText("hi")
        assertTrue(ModelPacks.scan(listOf(root)).isEmpty())
    }

    @Test
    fun scan_skipsMissingRoots() {
        val missing = ModelPacks.scan(listOf(File(tmp.root, "nope")))
        assertTrue(missing.isEmpty())
    }

    @Test
    fun scan_sortsByLabel() {
        val root = tmp.newFolder("models")
        packDir(root, "zeta", "z")
        packDir(root, "alpha", "a")
        assertEquals(listOf("alpha", "zeta"), ModelPacks.scan(listOf(root)).map { it.label })
    }

    @Test
    fun roots_internalAlwaysSdWhenPresent() {
        val internal = File("/storage/emulated/0/Auloud")
        assertEquals(
            listOf(File("/storage/emulated/0/Auloud/models")),
            ModelPacks.roots(internal, null)
        )
        assertEquals(
            listOf(
                File("/storage/emulated/0/Auloud/models"),
                File("/storage/ABCD-1234/Auloud/models")
            ),
            ModelPacks.roots(internal, "/storage/ABCD-1234/")
        )
    }
}
