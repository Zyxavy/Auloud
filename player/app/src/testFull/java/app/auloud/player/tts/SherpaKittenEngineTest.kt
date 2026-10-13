package app.auloud.player.tts

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * KT1: sherpa Kitten routing on plain JVM (fake handles, tmp packs).
 *
 * The JNI boundary (`SherpaKittenHandle`) never loads here — only the
 * pack-completeness, sid routing, lazy load and release logic runs.
 * Real synthesis is a device check (KT0 bench, KT3 Voice lab).
 */
class FakeKittenHandle(
    private val rate: Int = KITTEN_NATIVE_HZ
) : KittenHandle {
    data class Call(val text: String, val sid: Int, val speed: Float)

    val calls = mutableListOf<Call>()
    var releases = 0

    override fun generate(text: String, sid: Int, speed: Float): Pair<FloatArray, Int> {
        calls.add(Call(text, sid, speed))
        require(text.isNotBlank()) { "blank text" }
        return FloatArray(240) { 0.3f } to rate
    }

    override fun release() {
        releases += 1
    }
}

class SherpaKittenEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun packDir(name: String, complete: Boolean = true): ModelPack {
        val dir = File(tmp.root, name).apply { mkdirs() }
        File(dir, "model.int8.onnx").writeBytes(ByteArray(10))
        if (complete) {
            File(dir, KITTEN_VOICES_FILENAME).writeBytes(ByteArray(10))
            File(dir, KITTEN_TOKENS_FILENAME).writeBytes(ByteArray(10))
            File(dir, KITTEN_ESPEAK_DIRNAME).mkdirs()
        }
        return ModelPack(
            label = name,
            dirPath = dir.absolutePath,
            voices = listOf("model"),
            bytesTotal = 10L
        )
    }

    private val opened = mutableListOf<FakeKittenHandle>()
    private val openedThreads = mutableListOf<Int>()

    private fun engine(vararg packs: ModelPack, threads: Int = 2): SherpaKittenEngine {
        opened.clear()
        openedThreads.clear()
        return SherpaKittenEngine(packs.toList(), threads) { _, threadCount ->
            openedThreads += threadCount
            FakeKittenHandle().also { opened += it }
        }
    }

    @Test
    fun voices_listsNumericSidsFromFirstCompletePack() {
        val good = packDir("kitten-nano-en-v0_8-int8")
        val broken = packDir("broken", complete = false)
        val ids = engine(broken, good).voices().map { it.id }
        assertEquals((0 until 8).map { "kitten:$it" }, ids)
    }

    @Test
    fun voices_emptyWithoutPacks() {
        assertTrue(engine().voices().isEmpty())
    }

    @Test
    fun capabilities_areMultiSpeakerKittenTier() {
        val caps = engine(packDir("k")).capabilities()
        assertEquals(true, caps.multiSpeaker)
        assertEquals(KITTEN_LOAD_MB, caps.loadCostMb)
        assertEquals(KITTEN_NATIVE_HZ, caps.sampleRateHz)
    }

    @Test
    fun synthesize_routesSidToSharedInstanceLazily(): Unit = runBlocking {
        val engine = engine(packDir("k"))
        val audio = engine.synthesize("Hello world.", TtsVoice(id = "kitten:2", engine = "kitten"), 1.5f)
        assertEquals(KITTEN_NATIVE_HZ, audio.sampleRateHz)
        assertEquals(240, audio.samples.size)
        assertEquals(1, opened.size)
        assertEquals(
            listOf(FakeKittenHandle.Call("Hello world.", 2, 1.5f)),
            opened.single().calls
        )
        // Second sid reuses the one loaded model, not a second instance.
        engine.synthesize("Hi again.", TtsVoice(id = "kitten:5", engine = "kitten"), 1.0f)
        assertEquals(1, opened.size)
        assertEquals(2, opened.single().calls.size)
    }

    @Test
    fun synthesize_clampsSpeed(): Unit = runBlocking {
        val engine = engine(packDir("k"))
        engine.synthesize("Hi.", TtsVoice(id = "kitten:0", engine = "kitten"), 9.0f)
        assertEquals(MAX_TTS_SPEED, opened.single().calls.single().speed, 0f)
    }

    @Test
    fun synthesize_unknownVoice_throws(): Unit = runBlocking {
        val engine = engine(packDir("k"))
        try {
            engine.synthesize("Hi.", TtsVoice(id = "kitten:8", engine = "kitten"), 1.0f)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            engine.synthesize("Hi.", TtsVoice(id = "piper:x", engine = "piper"), 1.0f)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(opened.isEmpty())
    }

    @Test
    fun synthesize_blankText_throws(): Unit = runBlocking {
        val engine = engine(packDir("k"))
        try {
            engine.synthesize("  ", TtsVoice(id = "kitten:0", engine = "kitten"), 1.0f)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(opened.isEmpty())
    }

    @Test
    fun synthesize_threadCountReachesHandle(): Unit = runBlocking {
        val engine = engine(packDir("k"), threads = 4)
        engine.synthesize("Hi.", TtsVoice(id = "kitten:0", engine = "kitten"), 1.0f)
        assertEquals(listOf(4), openedThreads)
    }

    @Test
    fun voices_firstCompletePackWins(): Unit = runBlocking {
        val first = packDir("a-first")
        val second = packDir("b-second")
        val engine = engine(first, second)
        assertEquals(8, engine.voices().size)
        engine.synthesize("Hi.", TtsVoice(id = "kitten:0", engine = "kitten"), 1.0f)
        assertEquals(1, opened.size)
    }

    @Test
    fun voices_packJsonCountBoundsVoices(): Unit = runBlocking {
        val dir = File(tmp.root, "k-counted").apply { mkdirs() }
        File(dir, "model.onnx").writeBytes(ByteArray(10))
        File(dir, KITTEN_VOICES_FILENAME).writeBytes(ByteArray(10))
        File(dir, KITTEN_TOKENS_FILENAME).writeBytes(ByteArray(10))
        File(dir, KITTEN_ESPEAK_DIRNAME).mkdirs()
        File(dir, KITTEN_PACK_FILENAME).writeText("""{"speakers": 2}""")
        val pack = ModelPack(dir.name, dir.absolutePath, listOf("model"), 10L)
        val engine = engine(pack)
        assertEquals(listOf("kitten:0", "kitten:1"), engine.voices().map { it.id })
        try {
            engine.synthesize("Hi.", TtsVoice(id = "kitten:2", engine = "kitten"), 1.0f)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(opened.isEmpty())
    }

    @Test
    fun synthesize_afterRelease_reportsReleased(): Unit = runBlocking {
        val engine = engine(packDir("k"))
        engine.synthesize("Hi.", TtsVoice(id = "kitten:0", engine = "kitten"), 1.0f)
        engine.release()
        try {
            engine.synthesize("Hi.", TtsVoice(id = "kitten:0", engine = "kitten"), 1.0f)
            throw AssertionError("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun release_dropsInstanceAndEmptiesVoices(): Unit = runBlocking {
        val engine = engine(packDir("k"))
        engine.synthesize("Hi.", TtsVoice(id = "kitten:0", engine = "kitten"), 1.0f)
        engine.release()
        assertEquals(1, opened.single().releases)
        assertTrue(engine.voices().isEmpty())
        engine.release() // idempotent
    }

    @Test
    fun helper_kittenEngineOrNull_needsCompletePack() {
        assertTrue(emptyList<ModelPack>().kittenEngineOrNull() == null)
        val broken = ModelPack("broken", tmp.root.absolutePath, emptyList(), 0L)
        assertTrue(listOf(broken).kittenEngineOrNull() == null)
        val voices = listOf(packDir("k"))
            .kittenEngineOrNull()?.voices().orEmpty()
        assertEquals(8, voices.size)
    }

    @Test
    fun registry_kittenJoinsByNamespace(): Unit = runBlocking {
        val engine = engine(packDir("k"))
        val registry = EngineRegistry(listOf(engine))
        assertEquals(listOf("kitten"), registry.namespaces())
        assertTrue(registry.engineFor("kitten:3") === engine)
        assertTrue(registry.engineFor("piper:x") == null)
        assertEquals(8, registry.allVoices().size)
    }
}
