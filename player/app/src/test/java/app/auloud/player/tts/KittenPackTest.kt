package app.auloud.player.tts

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * KT1: Kitten pack detection on plain JVM (tmp dirs, no Android).
 */
class KittenPackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun kittenDir(
        parent: File,
        name: String,
        model: String? = "model.int8.onnx",
        voices: Boolean = true,
        tokens: Boolean = true,
        espeak: Boolean = true,
        packJson: String? = null
    ): File {
        val dir = File(parent, name).apply { mkdirs() }
        if (model != null) File(dir, model).writeBytes(ByteArray(100))
        if (voices) File(dir, KITTEN_VOICES_FILENAME).writeBytes(ByteArray(10))
        if (tokens) File(dir, KITTEN_TOKENS_FILENAME).writeBytes(ByteArray(10))
        if (espeak) File(dir, KITTEN_ESPEAK_DIRNAME).mkdirs()
        if (packJson != null) File(dir, KITTEN_PACK_FILENAME).writeText(packJson)
        return dir
    }

    @Test
    fun detect_completeNanoInt8Pack_listsEightNumericVoices() {
        val dir = kittenDir(tmp.root, "kitten-nano-en-v0_8-int8")
        val pack = detectKittenPack(dir)
        assertTrue(pack != null)
        assertEquals(8, pack!!.speakerCount)
        assertEquals("model.int8.onnx", pack.model.name)
    }

    @Test
    fun detect_microLayout_modelOnnxAccepted() {
        val dir = kittenDir(tmp.root, "kitten-micro-en-v0_8", model = "model.onnx")
        val pack = detectKittenPack(dir)
        assertTrue(pack != null)
        assertEquals("model.onnx", pack!!.model.name)
    }

    @Test
    fun detect_fp16Layout_modelFp16Accepted() {
        val dir = kittenDir(tmp.root, "kitten-nano-en-v0_1-fp16", model = "model.fp16.onnx")
        assertEquals("model.fp16.onnx", detectKittenPack(dir)?.model?.name)
    }

    @Test
    fun detect_missingVoicesBin_refused() {
        assertNull(detectKittenPack(kittenDir(tmp.root, "a", voices = false)))
    }

    @Test
    fun detect_missingTokens_refused() {
        assertNull(detectKittenPack(kittenDir(tmp.root, "b", tokens = false)))
    }

    @Test
    fun detect_missingEspeakDir_refused() {
        assertNull(detectKittenPack(kittenDir(tmp.root, "c", espeak = false)))
    }

    @Test
    fun detect_noModel_refused() {
        assertNull(detectKittenPack(kittenDir(tmp.root, "d", model = null)))
    }

    @Test
    fun detect_nonModelOnnx_ignored() {
        val dir = kittenDir(tmp.root, "e", model = null)
        File(dir, "notes.onnx.txt").writeText("not a model")
        assertNull(detectKittenPack(dir))
    }

    @Test
    fun detect_packJsonCount_used() {
        val dir = kittenDir(tmp.root, "f", packJson = """{"speakers": 4}""")
        assertEquals(4, detectKittenPack(dir)?.speakerCount)
    }

    @Test
    fun detect_badPackJson_fallsBackToEight() {
        val dir = kittenDir(tmp.root, "g", packJson = """{"speakers": "many"}""")
        assertEquals(8, detectKittenPack(dir)?.speakerCount)
        val zero = kittenDir(tmp.root, "h", packJson = """{"speakers": 0}""")
        assertEquals(8, detectKittenPack(zero)?.speakerCount)
    }

    @Test
    fun detect_uppercaseExtension_accepted() {
        val dir = kittenDir(tmp.root, "i", model = null)
        File(dir, "MODEL.ONNX").writeBytes(ByteArray(100))
        assertEquals("MODEL.ONNX", detectKittenPack(dir)?.model?.name)
    }

    @Test
    fun voiceIds_numericSids() {
        assertEquals("kitten:0", kittenVoiceId(0))
        assertEquals("kitten:7", kittenVoiceId(7))
    }

    @Test
    fun parseKittenSid_validAndInvalid() {
        assertEquals(3, parseKittenSid(TtsVoice(id = "kitten:3", engine = "kitten")))
        assertNull(parseKittenSid(TtsVoice(id = "kitten:x", engine = "kitten")))
        assertNull(parseKittenSid(TtsVoice(id = "kitten:-1", engine = "kitten")))
        assertNull(parseKittenSid(TtsVoice(id = "piper:3", engine = "piper")))
        assertNull(parseKittenSid(TtsVoice(id = "kitten:", engine = "kitten")))
    }
}
