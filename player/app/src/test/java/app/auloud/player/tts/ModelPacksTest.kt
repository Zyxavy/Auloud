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

    @Test
    fun problems_completePacksSilent() {
        val root = tmp.newFolder("models")
        packDir(root, "piper", "en_US-lessac-low")
        File(File(root, "piper"), "tokens.txt").writeText("a 0\n")
        File(File(root, "piper"), "espeak-ng-data").mkdirs()
        val kitten = File(root, "kitten-nano-en-v0_8-int8").apply { mkdirs() }
        File(kitten, "model.int8.onnx").writeBytes(ByteArray(10))
        File(kitten, KITTEN_VOICES_FILENAME).writeBytes(ByteArray(10))
        File(kitten, KITTEN_TOKENS_FILENAME).writeBytes(ByteArray(10))
        File(kitten, KITTEN_ESPEAK_DIRNAME).mkdirs()
        assertTrue(ModelPacks.scanProblems(listOf(root)).isEmpty())
    }

    @Test
    fun problems_onnxWithoutTokensOrEspeak() {
        val root = tmp.newFolder("models")
        packDir(root, "half", "voice")
        val byLabel = ModelPacks.scanProblems(listOf(root)).associate { it.label to it.reason }
        assertEquals("missing tokens.txt", byLabel["half"])
        File(File(root, "half"), "tokens.txt").writeText("a 0\n")
        val again = ModelPacks.scanProblems(listOf(root)).associate { it.label to it.reason }
        assertEquals("missing espeak-ng-data", again["half"])
    }

    @Test
    fun problems_kittenAttemptMissingModel() {
        val root = tmp.newFolder("models")
        val dir = File(root, "kitten-nano").apply { mkdirs() }
        File(dir, KITTEN_VOICES_FILENAME).writeBytes(ByteArray(10))
        val problems = ModelPacks.scanProblems(listOf(root))
        assertEquals(listOf("kitten-nano"), problems.map { it.label })
        assertEquals("no .onnx model file", problems.single().reason)
    }

    @Test
    fun problems_kittenAttemptMissingVoicesBin() {
        val root = tmp.newFolder("models")
        val dir = File(root, "kitten-micro").apply { mkdirs() }
        File(dir, "model.onnx").writeBytes(ByteArray(10))
        File(dir, KITTEN_TOKENS_FILENAME).writeBytes(ByteArray(10))
        File(dir, KITTEN_ESPEAK_DIRNAME).mkdirs()
        File(dir, KITTEN_PACK_FILENAME).writeText("""{"speakers": 8}""")
        val problems = ModelPacks.scanProblems(listOf(root))
        assertEquals("missing voices.bin", problems.single().reason)
    }

    @Test
    fun problems_plainDirsSilentAndNestedIgnored() {
        val root = tmp.newFolder("models")
        File(root, "empty").mkdirs()
        File(root, "notes").mkdirs()
        File(File(root, "notes"), "readme.txt").writeText("hi")
        val nested = File(File(root, "outer"), "inner").apply { mkdirs() }
        File(nested, "voice.onnx").writeBytes(ByteArray(10))
        // Depth rule matches scan(): the root itself plus immediate
        // children only, so the grandchild attempt stays silent.
        assertTrue(ModelPacks.scanProblems(listOf(root)).isEmpty())
    }

    @Test
    fun problems_mixedRoots_sortedByLabel() {
        val root = tmp.newFolder("models")
        packDir(root, "zeta-voice", "v")
        val kitten = File(root, "alpha-kitten").apply { mkdirs() }
        File(kitten, KITTEN_VOICES_FILENAME).writeBytes(ByteArray(10))
        assertEquals(
            listOf("alpha-kitten", "zeta-voice"),
            ModelPacks.scanProblems(listOf(root)).map { it.label }
        )
    }
}
