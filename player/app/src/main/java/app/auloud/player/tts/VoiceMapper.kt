package app.auloud.player.tts

/**
 * PW8: automatic voice mapping when switching engines (plan: by gender
 * and palette).
 *
 * Rule (deterministic, no metadata needed): keep the current voice when
 * the target engine offers it; else the same local id when the target
 * has one; else the role default — the first sorted voice of the
 * target engine. Gender/palette matching arrives with real engine
 * voice metadata (Slice 7+); until then "first sorted" is honest and
 * the audition screen lets the user correct it in one tap.
 *
 * Pure Kotlin.
 */
object VoiceMapper {

    /** Remap [current] onto [target]; null when the target offers nothing. */
    fun mapVoice(
        current: TtsVoice?,
        target: TtsEngine,
        role: TtsRole
    ): TtsVoice? {
        val offered = target.voices()
        if (offered.isEmpty()) return null
        if (current != null && offered.any { it.id == current.id }) return current
        val local = current?.id?.substringAfter(':')
        if (local != null) {
            offered.firstOrNull { it.id.substringAfter(':') == local }?.let { return it }
        }
        return roleDefault(offered, role)
    }

    /** Deterministic role default: first sorted voice id. */
    fun roleDefault(offered: List<TtsVoice>, role: TtsRole): TtsVoice? {
        if (offered.isEmpty()) return null
        return offered.minByOrNull { it.id }
    }
}

/**
 * Per-device engine recommendation (D-077 gate + Slice 7 numbers).
 *
 * D-077: Piper renders on this tablet, Kokoro is for faster devices —
 * so Piper wins wherever present. Otherwise System TTS (no download)
 * until the Slice 7 measurements land; then this table body takes the
 * measured numbers. Callers read only [namespace] + [reason].
 */
data class EngineRecommendation(
    val namespace: String,
    val reason: String,
)

fun recommendEngine(namespaces: List<String>): EngineRecommendation? {
    if (namespaces.isEmpty()) return null
    if ("piper" in namespaces) {
        return EngineRecommendation(
            namespace = "piper",
            reason = "Neural voices that run on this tablet (D-077 gate)."
        )
    }
    if ("system" in namespaces) {
        return EngineRecommendation(
            namespace = "system",
            reason = "System voices need no download (Slice 7 numbers pending)."
        )
    }
    val first = namespaces.sorted().first()
    return EngineRecommendation(
        namespace = first,
        reason = "Only engine available (Slice 7 numbers pending)."
    )
}
