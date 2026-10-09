package app.auloud.player.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PW6: System TTS tier as a [TtsEngine] (P4: logic here, framework in the driver).
 *
 * Voice ids are `system:<engine voice name>`; network-dependent voices
 * are excluded (offline promise). Synthesis renders to a temp WAV in
 * [scratchDir] and decodes it to PCM here, so callers see the uniform
 * [SynthesizedAudio] contract with the exact rate the engine produced
 * (timings derive from sample counts downstream).
 *
 * Speeds pass through clamped ([clampTtsSpeed]); the engine interprets
 * them (2x rarely means exactly 2x audio — audition and leveling absorb
 * that, timings never assume it).
 *
 * Pure except for the injected [SystemTtsDriver] and [scratchDir] files:
 * JVM-testable with a fake driver.
 */
class SystemTtsAdapter(
    private val driver: SystemTtsDriver,
    private val scratchDir: File,
) : TtsEngine {

    override val namespace: String = SYSTEM_NAMESPACE

    override fun voices(): List<TtsVoice> {
        if (!driver.isReady) return emptyList()
        return driver.installedVoices()
            .filter { !it.requiresNetwork }
            .map { TtsVoice(id = "$SYSTEM_NAMESPACE:${it.name}", engine = SYSTEM_NAMESPACE) }
    }

    override fun capabilities(): TtsCapabilities = TtsCapabilities(
        multiSpeaker = true,
        loadCostMb = 0,
        sampleRateHz = TYPICAL_SYSTEM_RATE_HZ
    )

    override suspend fun synthesize(
        text: String,
        voice: TtsVoice,
        speed: Float
    ): SynthesizedAudio {
        require(text.isNotBlank()) { "blank text" }
        require(voice.engine == SYSTEM_NAMESPACE) { "voice ${voice.id} is not a system voice" }
        if (!driver.isReady) throw IllegalStateException("system TTS not ready")
        // UX1: no installedVoices() lookup here (an IPC per sentence);
        // the driver validates the name and fails the render the same way.
        val localName = voice.id.substringAfter(':')
        if (!scratchDir.isDirectory && !scratchDir.mkdirs()) {
            throw IllegalStateException("scratch dir unavailable: $scratchDir")
        }
        val wav = File.createTempFile("auloud-tts-", ".wav", scratchDir)
        try {
            val ok = driver.renderToFile(text, localName, clampTtsSpeed(speed), wav)
            if (!ok) throw IllegalStateException("system TTS render failed for ${voice.id}")
            return decodeWav16Mono(wav.readBytes())
        } finally {
            wav.delete()
        }
    }

    companion object {
        const val SYSTEM_NAMESPACE = "system"

        /**
         * Typical `synthesizeToFile` rate (Google TTS writes 22.05 kHz;
         * Samsung varies). The real rate rides per call in
         * [SynthesizedAudio.sampleRateHz]; this only feeds the static
         * [capabilities] until the Slice 7 measurements land.
         */
        const val TYPICAL_SYSTEM_RATE_HZ = 22_050
    }
}

/**
 * Decode 16-bit PCM mono WAV bytes (what `synthesizeToFile` writes).
 *
 * Skips unknown chunks to the `data` chunk (engines may emit cue/list
 * chunks); throws [IllegalArgumentException] on anything else (not a
 * WAV, not PCM-16, not mono). Pure: unit-tested with crafted bytes.
 */
fun decodeWav16Mono(bytes: ByteArray): SynthesizedAudio {
    fun tagAt(offset: Int): String {
        require(offset + 4 <= bytes.size) { "truncated WAV header" }
        return String(bytes, offset, 4, Charsets.US_ASCII)
    }
    fun u32At(offset: Int): Int {
        require(offset + 4 <= bytes.size) { "truncated WAV header" }
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }
    fun u16At(offset: Int): Int {
        require(offset + 2 <= bytes.size) { "truncated WAV header" }
        return ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            .and(0xFFFF)
    }
    require(bytes.size >= 12) { "too short for a WAV" }
    require(tagAt(0) == "RIFF" && tagAt(8) == "WAVE") { "not a RIFF/WAVE file" }
    var audioFormat = -1
    var channels = -1
    var sampleRate = -1
    var dataOffset = -1
    var dataSize = -1
    var offset = 12
    while (offset + 8 <= bytes.size) {
        val tag = tagAt(offset)
        val size = u32At(offset + 4)
        val body = offset + 8
        when (tag) {
            "fmt " -> {
                audioFormat = u16At(body)
                channels = u16At(body + 2)
                sampleRate = u32At(body + 4)
            }
            "data" -> {
                dataOffset = body
                dataSize = size
            }
        }
        if (tag == "data") break
        offset = body + size + (size % 2)
    }
    require(audioFormat == 1) { "only PCM WAV supported (format=$audioFormat)" }
    require(channels == 1) { "only mono WAV supported (channels=$channels)" }
    require(sampleRate > 0) { "bad sample rate $sampleRate" }
    require(dataOffset >= 0 && dataOffset + dataSize <= bytes.size) { "bad data chunk" }
    val count = dataSize / 2
    val buffer = ByteBuffer.wrap(bytes, dataOffset, count * 2).order(ByteOrder.LITTLE_ENDIAN)
    return SynthesizedAudio(
        sampleRateHz = sampleRate,
        samples = FloatArray(count) { buffer.short / 32768f }
    )
}
