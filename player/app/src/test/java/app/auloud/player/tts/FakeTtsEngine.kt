package app.auloud.player.tts

/**
 * PW5 test fake: deterministic engine with scripted voices.
 */
class FakeTtsEngine(
    override val namespace: String,
    private val voiceIds: List<String>,
    private val capabilities: TtsCapabilities = TtsCapabilities(
        multiSpeaker = true,
        loadCostMb = 0,
        sampleRateHz = 24_000
    ),
    /**
     * ST6: peak amplitude per voice id for synthesized audio (tests that
     * pin leveling script unequal voices; absent ids read 0.1).
     */
    private val peakByVoice: Map<String, Float> = emptyMap(),
) : TtsEngine {

    data class Call(val text: String, val voice: TtsVoice, val speed: Float)

    val calls = mutableListOf<Call>()

    override fun voices(): List<TtsVoice> =
        voiceIds.map { TtsVoice(id = it, engine = namespace) }

    override fun capabilities(): TtsCapabilities = capabilities

    override suspend fun synthesize(text: String, voice: TtsVoice, speed: Float): SynthesizedAudio {
        calls.add(Call(text, voice, speed))
        require(text.isNotBlank()) { "blank text" }
        require(voice.engine == namespace) { "voice ${voice.id} not owned by $namespace" }
        val peak = peakByVoice[voice.id] ?: 0.1f
        return SynthesizedAudio(
            sampleRateHz = capabilities.sampleRateHz,
            samples = FloatArray(240) { peak }
        )
    }
}
