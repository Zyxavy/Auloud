package app.auloud.player.tts

/**
 * VC1 (D-126 option A): `core`-flavor stand-in for the bundled Piper
 * tier. Same name and surface as the `full` engine so every caller in
 * `main` compiles unchanged, but it binds no models and offers no
 * voices: `core` ships no bundled speech runtime. No native imports
 * here (the sherpa-onnx dependency is `fullImplementation` only).
 *
 * With no voices, the engine never joins a registry (`takeIf` at the
 * call sites drops it), so [synthesize] is unreachable in `core`.
 */
class SherpaPiperEngine(
    packs: List<ModelPack>,
    numThreads: Int = 2
) : TtsEngine {

    override val namespace: String = PIPER_NAMESPACE

    override fun voices(): List<TtsVoice> = emptyList()

    override fun capabilities(): TtsCapabilities = TtsCapabilities(
        multiSpeaker = false,
        loadCostMb = PIPER_LOAD_MB,
        sampleRateHz = PIPER_NATIVE_HZ
    )

    override suspend fun synthesize(
        text: String,
        voice: TtsVoice,
        speed: Float
    ): SynthesizedAudio {
        throw UnsupportedOperationException("no bundled Piper engine in the core flavor (see the full release)")
    }

    /** Nothing loaded, nothing to drop (idempotent by construction). */
    fun release() {
    }

    companion object {
        const val PIPER_NAMESPACE = "piper"

        /**
         * Per-model RAM on the Tab E (Slice 7 spike: 133 MB process PSS
         * before, 304 MB with one model). Static until per-pack
         * measurement exists.
         */
        const val PIPER_LOAD_MB = 170

        /** Typical VITS pack rate (lessac-medium is 22050; actual rides per call). */
        const val PIPER_NATIVE_HZ = 22_050
    }
}
