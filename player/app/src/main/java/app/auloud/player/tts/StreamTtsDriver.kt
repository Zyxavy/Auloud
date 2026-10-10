package app.auloud.player.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice

/**
 * ST3: utterance callbacks for live `speak()` streaming.
 *
 * Pure Kotlin: the core ([StreamCore]) and tests talk to the driver
 * through [StreamTtsListener]; only the Android implementation touches
 * the framework.
 */
interface StreamTtsListener {
    fun onStart(utteranceId: String)
    fun onDone(utteranceId: String)
    fun onError(utteranceId: String)
}

/**
 * ST3: seam for live `speak()` streaming over the system TTS engine.
 *
 * Single `TextToSpeech` instance, voice set per utterance (plan variant
 * (a)); a two-instance variant (b) implements this same seam later only
 * if the ST0 gate chooses it. Pure Kotlin except the Android
 * implementation below; tests inject a fake.
 *
 * Returning false means the utterance was not queued (not ready, blank
 * text, unknown or online-only voice, engine error): callers map it to
 * an error event on that id. `stop()` clears the engine queue; dropped
 * utterances produce no callbacks, and the core's attempt bump makes
 * stragglers stale anyway.
 *
 * API 24 safe: `TextToSpeech` constructor plus API 21 calls only
 * (`speak` with params, `playSilentUtterance`, `stop`).
 */
interface StreamTtsDriver {
    /** True once the engine reported `SUCCESS` (false = not ready). */
    val isReady: Boolean

    /**
     * Offline system voice names usable by [speak] (empty when not
     * ready): the service refuses the stream before any silence when
     * the needed voice is absent.
     */
    fun offlineVoiceNames(): Set<String>

    fun setListener(listener: StreamTtsListener?)

    /**
     * Queue [text] with the named offline system voice at [speechRate]
     * (clamped to [MIN_TTS_SPEED]..[MAX_TTS_SPEED]) and per-utterance
     * [volume] (0..1, ST6 calibrates it; the param attenuates only).
     */
    fun speak(
        text: String,
        systemVoiceName: String,
        speechRate: Float,
        volume: Float,
        utteranceId: String,
    ): Boolean

    /** Queue a silent pause (rendered-rule lengths, LiveSil-measured). */
    fun playSilence(durationMs: Long, utteranceId: String): Boolean

    /** Clear the engine queue (pause/seek/speed change path). */
    fun stop(): Boolean

    /** Drop the engine connection and init again (decision 8 recovery). */
    fun rebind(): Boolean

    fun shutdown()
}

/**
 * ST3 production driver on `android.speech.tts.TextToSpeech`.
 *
 * Init is async like [AndroidSystemTtsDriver]: [isReady] flips on the
 * `SUCCESS` callback; every method before that is a safe no-op/false.
 * Voice and rate setters are skipped when unchanged (same UX1
 * rationale: streaming alternates two voices, so only real switches
 * pay the IPC). Not unit-testable on JVM (framework); needs the device
 * (gate plus ST4/ST7).
 */
class AndroidStreamTtsDriver(
    appContext: Context,
    private val ttsFactory: (Context, TextToSpeech.OnInitListener) -> TextToSpeech =
        { ctx, listener -> TextToSpeech(ctx, listener) },
) : StreamTtsDriver {

    private val appContext = appContext.applicationContext

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    override var isReady: Boolean = false
        private set

    @Volatile
    private var listener: StreamTtsListener? = null

    // Same skip-redundant-setter caching as AndroidSystemTtsDriver.
    private var lastVoiceName: String? = null
    private var lastRate: Float = Float.NaN

    init {
        bind()
    }

    private fun bind() {
        val engine = ttsFactory(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
            }
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                listener?.onStart(utteranceId)
            }

            override fun onDone(utteranceId: String) {
                listener?.onDone(utteranceId)
            }

            override fun onError(utteranceId: String) {
                listener?.onError(utteranceId)
            }
        })
        tts = engine
        lastVoiceName = null
        lastRate = Float.NaN
    }

    override fun setListener(listener: StreamTtsListener?) {
        this.listener = listener
    }

    override fun offlineVoiceNames(): Set<String> {
        val engine = tts ?: return emptySet()
        if (!isReady) return emptySet()
        return try {
            engine.voices.orEmpty()
                .filter { !it.isNetworkConnectionRequired }
                .map { it.name }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun offlineVoice(engine: TextToSpeech, name: String): Voice? =
        try {
            engine.voices.orEmpty().firstOrNull { it.name == name }
                ?.takeUnless { it.isNetworkConnectionRequired }
        } catch (_: Exception) {
            null
        }

    override fun speak(
        text: String,
        systemVoiceName: String,
        speechRate: Float,
        volume: Float,
        utteranceId: String,
    ): Boolean {
        val engine = tts ?: return false
        if (!isReady || text.isBlank()) return false
        val voice = offlineVoice(engine, systemVoiceName) ?: return false
        return try {
            val rate = speechRate.coerceIn(MIN_TTS_SPEED, MAX_TTS_SPEED)
            if (rate != lastRate) {
                engine.setSpeechRate(rate)
                lastRate = rate
            }
            if (systemVoiceName != lastVoiceName) {
                engine.voice = voice
                lastVoiceName = systemVoiceName
            }
            val params = Bundle().apply {
                putFloat(
                    TextToSpeech.Engine.KEY_PARAM_VOLUME,
                    volume.coerceIn(0.0f, 1.0f),
                )
            }
            engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId) ==
                TextToSpeech.SUCCESS
        } catch (_: Exception) {
            false
        }
    }

    override fun playSilence(durationMs: Long, utteranceId: String): Boolean {
        val engine = tts ?: return false
        if (!isReady || durationMs <= 0) return false
        return try {
            engine.playSilentUtterance(
                durationMs, TextToSpeech.QUEUE_ADD, utteranceId
            ) == TextToSpeech.SUCCESS
        } catch (_: Exception) {
            false
        }
    }

    override fun stop(): Boolean {
        val engine = tts ?: return false
        return try {
            engine.stop() == TextToSpeech.SUCCESS
        } catch (_: Exception) {
            false
        }
    }

    override fun rebind(): Boolean {
        return try {
            try {
                tts?.shutdown()
            } catch (_: Exception) {
            }
            tts = null
            isReady = false
            bind()
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun shutdown() {
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        isReady = false
        listener = null
    }
}
