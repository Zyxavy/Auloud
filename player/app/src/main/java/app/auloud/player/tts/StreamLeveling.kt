package app.auloud.player.tts

/**
 * ST6: live-stream leveling (peak-metric, D-037 house rule).
 *
 * Rendered chapters level voices with peak amplitude
 * (`AssemblyMath.voicePeakLevels` + `gainsForPeaks`: target over peak,
 * silence gives 1.0). Streaming has no absolute target: the louder role
 * is attenuated to the quieter one through the per-utterance volume
 * param (which attenuates only), so the map is quiet-over-peak per
 * role. Silence on either side yields full volume (never a wrong
 * correction).
 *
 * The calibration sentence is the audition line (single standard
 * sentence, same package): one synth per role, peaks compared.
 * Calibration is keyed by voice id ([volumesFor] applies stored volumes
 * only when both roles still use the measured voices; anything else
 * plays full until recalibrated).
 *
 * Pure Kotlin (`calibrate` suspends only for the engine): JVM-tested
 * (`StreamLevelingTest`). Pause lengths are NOT here: the planner
 * already emits the rendered-rule pauses (250/500/800/1000 ms) from the
 * block table, and the 100 ms quote-tag pause stays render-only (the
 * bundle carries no split-pair fields by design; recomputing them would
 * need the ingest pipeline, so the stream keeps the 250 ms sentence
 * pause at role boundaries instead).
 */
object StreamLeveling {

    /** Standard calibration sentence (the audition line, self-made). */
    const val CALIBRATION_SENTENCE = VoiceAuditionViewModel.AUDITION_TEXT

    /** Representative loudness: peak absolute sample (D-037). */
    fun peakAmplitude(samples: FloatArray): Float {
        var peak = 0.0f
        for (sample in samples) {
            if (!sample.isFinite()) continue
            val magnitude = if (sample < 0.0f) -sample else sample
            if (magnitude > peak) peak = magnitude
        }
        return peak
    }

    /**
     * Per-role volumes from measured peaks: quiet-over-peak, so the
     * quieter role stays 1.0 and the louder one comes down to it.
     * Non-positive peaks (silence) read as full volume.
     */
    fun calibrationVolumes(narratorPeak: Float, dialoguePeak: Float): Map<TtsRole, Float> {
        val quiet = minOf(
            narratorPeak.takeIf { it > 0.0f } ?: Float.POSITIVE_INFINITY,
            dialoguePeak.takeIf { it > 0.0f } ?: Float.POSITIVE_INFINITY
        )
        if (!quiet.isFinite()) {
            return mapOf(TtsRole.Narrator to 1.0f, TtsRole.Dialogue to 1.0f)
        }
        fun level(peak: Float): Float =
            if (peak <= 0.0f || !peak.isFinite()) 1.0f else (quiet / peak).coerceIn(0.0f, 1.0f)
        return mapOf(
            TtsRole.Narrator to level(narratorPeak),
            TtsRole.Dialogue to level(dialoguePeak)
        )
    }

    /**
     * Synthesize the standard sentence per role and map the peaks to
     * volumes ([calibrationVolumes]). One engine call per role at 1x.
     */
    suspend fun calibrate(
        engine: TtsEngine,
        narrator: TtsVoice,
        dialogue: TtsVoice
    ): Map<TtsRole, Float> {
        val narratorPeak =
            peakAmplitude(engine.synthesize(CALIBRATION_SENTENCE, narrator, 1.0f).samples)
        val dialoguePeak =
            peakAmplitude(engine.synthesize(CALIBRATION_SENTENCE, dialogue, 1.0f).samples)
        return calibrationVolumes(narratorPeak, dialoguePeak)
    }

    /**
     * Volumes to stream with: the stored calibration when both roles
     * still use the measured voices, else full volume (recalibrate after
     * changing voices; book-level overrides that differ from the measured
     * pair also play full until a matching calibration exists).
     */
    fun volumesFor(
        currentVoices: Map<TtsRole, String>,
        calibratedVoices: Map<TtsRole, String>,
        calibratedVolumes: Map<TtsRole, Float>
    ): Map<TtsRole, Float> {
        val full = mapOf(TtsRole.Narrator to 1.0f, TtsRole.Dialogue to 1.0f)
        for (role in TtsRole.entries) {
            val current = currentVoices[role]
            val calibrated = calibratedVoices[role]
            if (current.isNullOrBlank() || calibrated.isNullOrBlank() || current != calibrated) {
                return full
            }
        }
        return mapOf(
            TtsRole.Narrator to (calibratedVolumes[TtsRole.Narrator]?.takeIf { it.isFinite() } ?: 1.0f),
            TtsRole.Dialogue to (calibratedVolumes[TtsRole.Dialogue]?.takeIf { it.isFinite() } ?: 1.0f)
        )
    }

    /** One-line calibration result for the voices screen. */
    fun describe(volumes: Map<TtsRole, Float>): String {
        fun percent(role: TtsRole): Int =
            ((volumes[role] ?: 1.0f).coerceIn(0.0f, 1.0f) * 100.0f + 0.5f).toInt()
        if (percent(TtsRole.Narrator) >= 100 && percent(TtsRole.Dialogue) >= 100) {
            return "Voices already level"
        }
        return "Levels matched (narrator ${percent(TtsRole.Narrator)}%, " +
            "dialogue ${percent(TtsRole.Dialogue)}%)"
    }
}
