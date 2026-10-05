package app.auloud.player.tts

/**
 * PW5: hand-built engine directory (P3: no DI framework in the app).
 *
 * Routes a namespaced voice id to its engine on the namespace prefix;
 * unparseable ids and unknown namespaces resolve to null (callers fall
 * back, never crash). Constructed where needed — `MainActivity`,
 * audition screen (PW8) — with the available engines, fakes in JVM tests.
 *
 * Pure Kotlin.
 */
class EngineRegistry(
    engines: List<TtsEngine>,
) {
    private val byNamespace: Map<String, TtsEngine> = engines.associateBy { it.namespace }

    /** Namespaces present, sorted (settings lists engines in stable order). */
    fun namespaces(): List<String> = byNamespace.keys.sorted()

    /** Engine owning [voiceId], or null when the id has no known namespace. */
    fun engineFor(voiceId: String): TtsEngine? {
        val voice = TtsVoice.parse(voiceId) ?: return null
        return byNamespace[voice.engine]
    }

    /** Every voice every engine currently offers. */
    fun allVoices(): List<TtsVoice> = byNamespace.values.flatMap { it.voices() }
}
