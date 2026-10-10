package app.auloud.player.tts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PW6: adapter logic on plain JVM (fake driver, tmp scratch dir).
 *
 * The production driver (`AndroidSystemTtsDriver`) holds framework
 * calls only and needs the device (PW8); everything asserted here —
 * id mapping, network exclusion, speed clamping, WAV decode, failure
 * shapes — runs without Android.
 */
class FakeSystemTtsDriver(
    var ready: Boolean = true,
    var installed: List<SystemVoice> = listOf(
        SystemVoice(name = "en-us-x-sfg#female", localeTag = "en-US", requiresNetwork = false),
        SystemVoice(name = "net-voice", localeTag = "en-US", requiresNetwork = true)
    ),
    var sampleRateHz: Int = 22_050,
) : SystemTtsDriver {

    data class RenderCall(
        val text: String,
        val voiceName: String,
        val rate: Float,
        val outFile: File
    )

    val renders = mutableListOf<RenderCall>()
    var renderResult = true
    var shutDown = false
    // UX1: counts voice-list reads so tests pin the per-sentence IPC saving.
    var installedVoicesCalls = 0

    override val isReady: Boolean get() = ready

    override fun installedVoices(): List<SystemVoice> {
        installedVoicesCalls++
        return installed
    }

    override fun renderToFile(
        text: String,
        systemVoiceName: String,
        speechRate: Float,
        outFile: File
    ): Boolean {
        renders.add(RenderCall(text, systemVoiceName, speechRate, outFile))
        if (!renderResult) return false
        // Mirrors the production driver: unknown or network-dependent
        // names refuse (the adapter no longer pre-validates, UX1).
        if (installed.none { it.name == systemVoiceName && !it.requiresNetwork }) return false
        outFile.writeBytes(testWav16Mono(sampleRateHz, FloatArray(220) { 0.2f }))
        return true
    }

    override fun shutdown() {
        shutDown = true
    }
}

/** Minimal PCM-16 mono WAV writer (mirrors what engines emit). */
fun testWav16Mono(sampleRateHz: Int, samples: FloatArray): ByteArray {
    val data = ByteArray(samples.size * 2)
    val view = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { view.putShort((it * 32767f).toInt().coerceIn(-32768, 32767).toShort()) }
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray(Charsets.US_ASCII))
    header.putInt(36 + data.size)
    header.put("WAVE".toByteArray(Charsets.US_ASCII))
    header.put("fmt ".toByteArray(Charsets.US_ASCII))
    header.putInt(16)
    header.putShort(1) // PCM
    header.putShort(1) // mono
    header.putInt(sampleRateHz)
    header.putInt(sampleRateHz * 2)
    header.putShort(2) // block align
    header.putShort(16) // bits
    header.put("data".toByteArray(Charsets.US_ASCII))
    header.putInt(data.size)
    return header.array() + data
}

class SystemTtsAdapterTest {

    @get:Rule
    val scratchParent = TemporaryFolder()

    private fun adapter(driver: FakeSystemTtsDriver = FakeSystemTtsDriver()): SystemTtsAdapter {
        val dir = scratchParent.newFolder("scratch")
        return SystemTtsAdapter(driver, dir)
    }

    @Test
    fun voices_mapsIdsAndExcludesNetwork() {
        val ids = adapter().voices().map { it.id }
        assertEquals(listOf("system:en-us-x-sfg#female"), ids)
    }

    @Test
    fun voices_notReady_isEmpty() {
        assertTrue(adapter(FakeSystemTtsDriver(ready = false)).voices().isEmpty())
    }

    @Test
    fun capabilities_areSystemTier() {
        val caps = adapter().capabilities()
        assertTrue(caps.multiSpeaker)
        assertEquals(0, caps.loadCostMb)
    }

    @Test
    fun synthesize_decodesEngineWav() = runBlocking {
        val driver = FakeSystemTtsDriver()
        val audio = adapter(driver).synthesize(
            "Hello world.",
            TtsVoice(id = "system:en-us-x-sfg#female", engine = "system"),
            1.0f
        )
        assertEquals(22_050, audio.sampleRateHz)
        assertEquals(220, audio.samples.size)
        assertEquals(1, driver.renders.size)
        val call = driver.renders.single()
        assertEquals("Hello world.", call.text)
        assertEquals("en-us-x-sfg#female", call.voiceName)
        assertEquals(1.0f, call.rate, 0f)
    }

    @Test
    fun synthesize_clampsSpeed() = runBlocking {
        val driver = FakeSystemTtsDriver()
        adapter(driver).synthesize(
            "Hi.",
            TtsVoice(id = "system:en-us-x-sfg#female", engine = "system"),
            9.0f
        )
        assertEquals(MAX_TTS_SPEED, driver.renders.single().rate, 0f)
    }

    @Test
    fun synthesize_skipsVoiceListLookup() = runBlocking {
        val driver = FakeSystemTtsDriver()
        adapter(driver).synthesize(
            "Hello world.",
            TtsVoice(id = "system:en-us-x-sfg#female", engine = "system"),
            1.0f
        )
        assertEquals(0, driver.installedVoicesCalls)
        assertEquals(1, driver.renders.size)
    }

    @Test
    fun synthesize_unknownVoice_failsRender() = runBlocking {
        // UX1: no adapter-side lookup anymore; the driver refuses the
        // unknown name and the render fails the same way (one IPC saved).
        val driver = FakeSystemTtsDriver()
        try {
            adapter(driver).synthesize("Hi.", TtsVoice(id = "system:nope", engine = "system"), 1.0f)
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
        assertEquals(0, driver.installedVoicesCalls)
    }

    @Test
    fun synthesize_networkVoice_failsRender() = runBlocking {
        // UX1: same path as unknown (the fake refuses names outside its
        // list; the production driver refuses network voices itself).
        try {
            adapter().synthesize("Hi.", TtsVoice(id = "system:net-voice", engine = "system"), 1.0f)
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun synthesize_wrongEngine_throws() = runBlocking {
        try {
            adapter().synthesize("Hi.", TtsVoice(id = "kokoro:af_heart", engine = "kokoro"), 1.0f)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun synthesize_driverFailure_throws() = runBlocking {
        val driver = FakeSystemTtsDriver()
        driver.renderResult = false
        try {
            adapter(driver).synthesize(
                "Hi.", TtsVoice(id = "system:en-us-x-sfg#female", engine = "system"), 1.0f
            )
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun decodeWav_roundTripsCraftedBytes() {
        val samples = FloatArray(100) { it / 100f }
        val audio = decodeWav16Mono(testWav16Mono(16_000, samples))
        assertEquals(16_000, audio.sampleRateHz)
        assertEquals(100, audio.samples.size)
        audio.samples.forEachIndexed { i, v ->
            assertTrue("sample $i off: $v", kotlin.math.abs(v - samples[i]) < 0.001f)
        }
    }

    @Test
    fun decodeWav_rejectsGarbage() {
        listOf(
            byteArrayOf(),
            "RIFFxxxxWAVE".toByteArray(Charsets.US_ASCII),
            testWav16Mono(22_050, FloatArray(10) { 0f }).copyOf(20)
        ).forEach { bad ->
            try {
                decodeWav16Mono(bad)
                fail("expected IllegalArgumentException for ${bad.size} bytes")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
