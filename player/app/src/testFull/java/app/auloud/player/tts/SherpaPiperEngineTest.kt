package app.auloud.player.tts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PW7b: sherpa Piper routing on plain JVM (fake handles, tmp packs).
 *
 * The JNI boundary (`SherpaPiperHandle`) never loads here — only the
 * pack-completeness, routing, lazy-instance and release logic runs.
 * Real synthesis is a device check (audition a Piper voice, PW8 host).
 */
class FakePiperHandle(
    private val rate: Int = 22_050
) : PiperHandle {
    data class Call(val text: String, val speed: Float)

    val calls = mutableListOf<Call>()
    var releases = 0
    var openedFor: Pair<File, File>? = null

    override fun generate(text: String, speed: Float): Pair<FloatArray, Int> {
        calls.add(Call(text, speed))
        require(text.isNotBlank()) { "blank text" }
        return FloatArray(220) { 0.3f } to rate
    }

    override fun release() {
        releases += 1
    }
}

class SherpaPiperEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun packDir(name: String, voices: List<String>, complete: Boolean = true): ModelPack {
        val dir = File(tmp.root, name).apply { mkdirs() }
        voices.forEach { File(dir, "$it.onnx").writeBytes(ByteArray(10)) }
        if (complete) {
            File(dir, "tokens.txt").writeText("a 0\n")
            File(dir, "espeak-ng-data").mkdirs()
        }
        val files = dir.listFiles().orEmpty()
        return ModelPack(
            label = name,
            dirPath = dir.absolutePath,
            voices = files.filter { it.extension == "onnx" }.map { it.nameWithoutExtension }.sorted(),
            bytesTotal = files.map { it.length() }.sum()
        )
    }

    private val opened = mutableMapOf<String, FakePiperHandle>()

    private fun engine(vararg packs: ModelPack): SherpaPiperEngine {
        opened.clear()
        return SherpaPiperEngine(packs.toList()) { packDir, model ->
            FakePiperHandle().also { opened[model.nameWithoutExtension] = it }
        }
    }

    @Test
    fun voices_listsOnlyCompletePacks() {
        val good = packDir("piper", listOf("en_US-lessac-low"))
        val noTokens = packDir("broken", listOf("x-voice"), complete = false)
        val ids = engine(good, noTokens).voices().map { it.id }
        assertEquals(listOf("piper:en_US-lessac-low"), ids)
    }

    @Test
    fun voices_emptyWithoutPacks() {
        assertTrue(engine().voices().isEmpty())
    }

    @Test
    fun capabilities_arePiperTier() {
        val engine = engine(packDir("piper", listOf("v")))
        val caps = engine.capabilities()
        assertEquals(false, caps.multiSpeaker)
        assertEquals(170, caps.loadCostMb)
        assertEquals(22_050, caps.sampleRateHz)
    }

    @Test
    fun synthesize_routesToVoiceInstanceLazily() = runBlocking {
        val engine = engine(packDir("piper", listOf("a-voice", "b-voice")))
        val voice = TtsVoice(id = "piper:a-voice", engine = "piper")
        val audio = engine.synthesize("Hello world.", voice, 1.5f)
        assertEquals(22_050, audio.sampleRateHz)
        assertEquals(220, audio.samples.size)
        assertEquals(listOf("a-voice"), opened.keys.sorted())
        assertEquals(
            listOf(FakePiperHandle.Call("Hello world.", 1.5f)),
            opened.getValue("a-voice").calls
        )
        // Second voice loads its own instance; first is reused, not reopened.
        engine.synthesize("Hi again.", TtsVoice(id = "piper:b-voice", engine = "piper"), 1.0f)
        engine.synthesize("Hello again.", voice, 1.0f)
        assertEquals(listOf("a-voice", "b-voice"), opened.keys.sorted())
        assertEquals(2, opened.getValue("a-voice").calls.size)
    }

    @Test
    fun synthesize_clampsSpeed() = runBlocking {
        val engine = engine(packDir("piper", listOf("v")))
        engine.synthesize("Hi.", TtsVoice(id = "piper:v", engine = "piper"), 9.0f)
        assertEquals(MAX_TTS_SPEED, opened.getValue("v").calls.single().speed, 0f)
    }

    @Test
    fun synthesize_unknownVoice_throws(): Unit = runBlocking {
        val engine = engine(packDir("piper", listOf("v")))
        try {
            engine.synthesize("Hi.", TtsVoice(id = "piper:nope", engine = "piper"), 1.0f)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(opened.isEmpty())
    }

    @Test
    fun synthesize_wrongEngine_throws(): Unit = runBlocking {
        val engine = engine(packDir("piper", listOf("v")))
        try {
            engine.synthesize("Hi.", TtsVoice(id = "kokoro:af_heart", engine = "kokoro"), 1.0f)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun voices_kittenLayoutSkippedForPiper() {
        // KT2: a Kitten folder (model + voices.bin + tokens + espeak)
        // belongs to the Kitten engine; without the skip its model file
        // would surface as a bogus piper voice.
        val dir = File(tmp.root, "kitten-nano-en-v0_8-int8").apply { mkdirs() }
        File(dir, "model.int8.onnx").writeBytes(ByteArray(10))
        File(dir, "voices.bin").writeBytes(ByteArray(10))
        File(dir, "tokens.txt").writeText("a 0\n")
        File(dir, "espeak-ng-data").mkdirs()
        val pack = ModelPack("kitten-nano-en-v0_8-int8", dir.absolutePath, listOf("model"), 10L)
        assertTrue(engine(pack).voices().isEmpty())
    }

    @Test
    fun release_dropsInstancesAndEmptiesVoices() = runBlocking {
        val engine = engine(packDir("piper", listOf("v")))
        engine.synthesize("Hi.", TtsVoice(id = "piper:v", engine = "piper"), 1.0f)
        engine.release()
        assertEquals(1, opened.getValue("v").releases)
        assertTrue(engine.voices().isEmpty())
        engine.release() // idempotent
    }
}
