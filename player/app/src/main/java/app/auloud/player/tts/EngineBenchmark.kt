package app.auloud.player.tts

/**
 * VS4: engine speed categories for the voice picker (D-122 slow-engine
 * warning).
 *
 * Picking an engine categorized [TOO_SLOW] or [BACKGROUND] shows the
 * estimated render time per hour of audio before confirmation. The
 * categories are provisional until retuned from real renders:
 *
 * - `system` renders on the tablet with the Slice 10 benchmark RTF band
 *   ([SYSTEM_RTF_LOW] to [SYSTEM_RTF_HIGH], mirroring the RN9 estimate
 *   constants) and is [STANDARD].
 * - `piper` measured about 0.36x warmed on the Tab E (Slice 10 plan
 *   section 2) and is [TOO_SLOW]: one hour of audio needs about
 *   2 h 47 min of rendering.
 * - `beep` is the debug-only tone engine (fast, no TTS) and is
 *   [STANDARD]: no warning.
 * - Any other namespace is [BACKGROUND] (conservative): unknown engines
 *   warn with the slow end of the system band until measured.
 *
 * Pure Kotlin, API 24 safe, no new dependency.
 */
enum class EngineSpeedCategory {
    STANDARD,
    TOO_SLOW,
    BACKGROUND
}

/**
 * VS4: per-hour-of-audio wall times for one engine namespace.
 *
 * [wallFastMsPerHour] is the fast-end wall time for one hour of audio,
 * [wallSlowMsPerHour] the slow end (equal for single-RTF engines like
 * Piper). The picker formats these, never single render totals.
 */
data class EngineSpeedInfo(
    val namespace: String,
    val category: EngineSpeedCategory,
    val wallFastMsPerHour: Long,
    val wallSlowMsPerHour: Long
)

object EngineBenchmark {

    /** One hour of audio in milliseconds. */
    const val AUDIO_HOUR_MS = 3_600_000L

    /**
     * Piper warmed RTF on the Tab E (Slice 10 plan section 2,
     * provisional until RN11 retunes it).
     */
    const val PIPER_RTF = 0.36

    /**
     * System TTS benchmark ends, mirroring the RN9 estimate constants
     * (`RENDER_RTF_LOW` 0.86, `RENDER_RTF_HIGH` 2.56). Duplicated here
     * so the tts package does not import the render package; keep the
     * numbers in sync with `RenderUiState` until RN11 retunes both.
     */
    const val SYSTEM_RTF_LOW = 0.86
    const val SYSTEM_RTF_HIGH = 2.56

    /** Category for [namespace] (see file KDoc). */
    fun categoryFor(namespace: String): EngineSpeedCategory = when (namespace) {
        "system" -> EngineSpeedCategory.STANDARD
        "beep" -> EngineSpeedCategory.STANDARD
        "piper" -> EngineSpeedCategory.TOO_SLOW
        else -> EngineSpeedCategory.BACKGROUND
    }

    /** True when picking [namespace] must warn before confirmation. */
    fun isSlow(namespace: String): Boolean =
        categoryFor(namespace) != EngineSpeedCategory.STANDARD

    /** Per-hour wall times for [namespace] (see [EngineSpeedInfo]). */
    fun infoFor(namespace: String): EngineSpeedInfo {
        val category = categoryFor(namespace)
        return when (category) {
            EngineSpeedCategory.TOO_SLOW -> {
                val wall = (AUDIO_HOUR_MS / PIPER_RTF).toLong()
                EngineSpeedInfo(namespace, category, wall, wall)
            }
            EngineSpeedCategory.BACKGROUND -> {
                val fast = (AUDIO_HOUR_MS / SYSTEM_RTF_HIGH).toLong()
                val slow = (AUDIO_HOUR_MS / SYSTEM_RTF_LOW).toLong()
                EngineSpeedInfo(namespace, category, fast, slow)
            }
            EngineSpeedCategory.STANDARD -> {
                val fast = (AUDIO_HOUR_MS / SYSTEM_RTF_HIGH).toLong()
                val slow = (AUDIO_HOUR_MS / SYSTEM_RTF_LOW).toLong()
                EngineSpeedInfo(namespace, category, fast, slow)
            }
        }
    }

    /**
     * Warning info for [namespace], or null when the engine is standard
     * (no warning needed). The picker shows this before confirmation.
     */
    fun slowWarningFor(namespace: String): EngineSpeedInfo? {
        if (!isSlow(namespace)) return null
        return infoFor(namespace)
    }
}
