package app.auloud.player.tts

/**
 * VC1 (D-126 option A) plus Slice 14: `core`-flavor stand-in for the
 * bundled Kitten tier. Same name and surface as the `full` engine so
 * every caller in `main` compiles unchanged, but it binds no models and
 * offers no voices: `core` ships no bundled speech runtime. No native
 * imports here (the sherpa-onnx dependency is `fullImplementation`
 * only).
 *
 * With no voices, the engine never joins a registry (`takeIf` at the
 * call sites drops it), so [synthesize] is unreachable in `core`.
 */
class SherpaKittenEngine(
    packs: List<ModelPack>,
    numThreads: Int = 2
) : TtsEngine {

    override val namespace: String = KITTEN_NAMESPACE

    override fun voices(): List<TtsVoice> = emptyList()

    override fun capabilities(): TtsCapabilities = TtsCapabilities(
        multiSpeaker = true,
        loadCostMb = KITTEN_LOAD_MB,
        sampleRateHz = KITTEN_NATIVE_HZ
    )

    override suspend fun synthesize(
        text: String,
        voice: TtsVoice,
        speed: Float
    ): SynthesizedAudio {
        throw UnsupportedOperationException("no bundled Kitten engine in the core flavor (see the full release)")
    }

    /** Nothing loaded, nothing to drop (idempotent by construction). */
    fun release() {
    }
}
