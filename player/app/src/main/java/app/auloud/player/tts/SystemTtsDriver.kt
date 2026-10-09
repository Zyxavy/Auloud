package app.auloud.player.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PW6: framework seam for the System TTS tier (P4).
 *
 * `android.speech.tts` never runs on JVM unit tests (the android.jar
 * stubs throw), so every framework call lives behind this interface;
 * tests inject [FakeSystemTtsDriver]. All mapping logic (voice-id
 * mapping, speed scaling, file naming) sits in [SystemTtsAdapter],
 * never here.
 *
 * API 24 safe: `TextToSpeech` constructor + API 21 calls only.
 */
interface SystemTtsDriver {
    /** True once the engine reported `SUCCESS` (false = not ready, never block). */
    val isReady: Boolean

    /** Installed voices as pure data (no framework types escape). */
    fun installedVoices(): List<SystemVoice>

    /**
     * Render [text] with the named system voice at [speechRate] into
     * [outFile] (WAV). Blocks up to [RENDER_TIMEOUT_MS]; false means the
     * engine failed, timed out, or was shut down.
     */
    fun renderToFile(
        text: String,
        systemVoiceName: String,
        speechRate: Float,
        outFile: File
    ): Boolean

    fun shutdown()
}

/** One installed system voice, framework-free (P4 delegate data). */
data class SystemVoice(
    /** Engine voice name (the local part of a `system:` id). */
    val name: String,
    /** BCP-47 tag, e.g. `en-US` (display only). */
    val localeTag: String,
    /** True when synthesis needs the network (excluded: offline promise). */
    val requiresNetwork: Boolean,
)

/** Max blocking render per utterance (slow engines fail audibly, not silently). */
const val RENDER_TIMEOUT_MS = 30_000L

/**
 * PW6 production driver on `android.speech.tts.TextToSpeech`.
 *
 * Init is async: [isReady] flips on the `SUCCESS` callback; every
 * method before that is a safe no-op/false. Completion rides an
 * `UtteranceProgressListener` into a latch (never spins on the file).
 * Not unit-testable on JVM (framework); needs the device (PW8).
 */
class AndroidSystemTtsDriver(
    appContext: Context,
    private val ttsFactory: (Context, TextToSpeech.OnInitListener) -> TextToSpeech =
        { ctx, listener -> TextToSpeech(ctx, listener) }
) : SystemTtsDriver {

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    override var isReady: Boolean = false
        private set

    private val lock = Any()
    private var pendingLatch: CountDownLatch? = null
    private var pendingDone: Boolean = false
    // UX1: renders run sequentially with one voice+rate for long
    // stretches, so skip the redundant setter IPCs when unchanged.
    private var lastVoiceName: String? = null
    private var lastRate: Float = Float.NaN

    init {
        val context = appContext.applicationContext
        tts = ttsFactory(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finishUtterance(true)
            override fun onError(utteranceId: String) = finishUtterance(false)
        })
    }

    private fun finishUtterance(ok: Boolean) {
        synchronized(lock) {
            pendingDone = ok
            pendingLatch?.countDown()
        }
    }

    override fun installedVoices(): List<SystemVoice> {
        val engine = tts ?: return emptyList()
        if (!isReady) return emptyList()
        return try {
            engine.voices.orEmpty().map { voice: Voice ->
                SystemVoice(
                    name = voice.name,
                    localeTag = voice.locale?.toLanguageTag() ?: Locale.US.toLanguageTag(),
                    requiresNetwork = voice.isNetworkConnectionRequired
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun renderToFile(
        text: String,
        systemVoiceName: String,
        speechRate: Float,
        outFile: File
    ): Boolean {
        val engine = tts ?: return false
        if (!isReady || text.isBlank()) return false
        val voice = try {
            engine.voices.orEmpty().firstOrNull { it.name == systemVoiceName }
        } catch (_: Exception) {
            null
        } ?: return false
        if (voice.isNetworkConnectionRequired) return false
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
            val utteranceId = UUID.randomUUID().toString()
            val latch = CountDownLatch(1)
            synchronized(lock) {
                pendingDone = false
                pendingLatch = latch
            }
            val status = engine.synthesizeToFile(text, null, outFile, utteranceId)
            if (status != TextToSpeech.SUCCESS) {
                synchronized(lock) { pendingLatch = null }
                return false
            }
            val finished = latch.await(RENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            synchronized(lock) { pendingLatch = null }
            finished && pendingDone && outFile.isFile && outFile.length() > 0
        } catch (_: Exception) {
            synchronized(lock) { pendingLatch = null }
            false
        }
    }

    override fun shutdown() {
        synchronized(lock) { pendingLatch?.countDown() }
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        isReady = false
    }
}
