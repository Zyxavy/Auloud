package app.auloud.player.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ST3: controllable fake for the live-stream driver seam. */
class FakeStreamTtsDriver : StreamTtsDriver {

    data class SpeakCall(
        val text: String,
        val voiceName: String,
        val rate: Float,
        val volume: Float,
        val id: String,
    )

    data class SilenceCall(val durationMs: Long, val id: String)

    override var isReady: Boolean = true
    val speaks = mutableListOf<SpeakCall>()
    val silences = mutableListOf<SilenceCall>()
    var stops = 0
    var rebinds = 0
    var shutDown = false

    /** Ids for which `speak` reports failure (not queued). */
    val failSpeaks = mutableSetOf<String>()

    private var listener: StreamTtsListener? = null

    override fun setListener(listener: StreamTtsListener?) {
        this.listener = listener
    }

    override fun speak(
        text: String,
        systemVoiceName: String,
        speechRate: Float,
        volume: Float,
        utteranceId: String,
    ): Boolean {
        if (!isReady) return false
        if (utteranceId in failSpeaks) return false
        speaks += SpeakCall(text, systemVoiceName, speechRate, volume, utteranceId)
        return true
    }

    override fun playSilence(durationMs: Long, utteranceId: String): Boolean {
        if (!isReady) return false
        silences += SilenceCall(durationMs, utteranceId)
        return true
    }

    override fun stop(): Boolean {
        if (!isReady) return false
        stops++
        return true
    }

    override fun rebind(): Boolean {
        rebinds++
        isReady = true
        return true
    }

    override fun shutdown() {
        shutDown = true
        isReady = false
        listener = null
    }

    fun emitStart(id: String) = listener?.onStart(id)

    fun emitDone(id: String) = listener?.onDone(id)

    fun emitError(id: String) = listener?.onError(id)
}

/**
 * ST3: driver seam behavior plus core-through-seam integration.
 *
 * The Android implementation is framework-only (needs the tablet); what
 * is pinned here is the contract the core relies on: queued calls with
 * ids, callbacks routed to the listener, stop/rebind accounting, and
 * false mapping to a core error event.
 *
 * Pure JVM, no Android, no storage.
 */
class StreamTtsDriverTest {

    private val voices = mapOf(TtsRole.Narrator to "narr", TtsRole.Dialogue to "dial")

    private fun sentences() = listOf(
        StreamSentence(1, "One.", TtsRole.Narrator),
        StreamSentence(2, "Two?", TtsRole.Dialogue),
        StreamSentence(3, "Three.", TtsRole.Narrator),
    )

    /** Feed one core plan into the fake (role resolves to voice here). */
    private fun feed(
        fake: FakeStreamTtsDriver,
        plan: List<StreamUtterance>,
        rate: Float = 1.0f,
    ) {
        plan.forEach { utterance ->
            when (utterance) {
                is SpeakUtterance -> fake.speak(
                    utterance.text,
                    requireNotNull(voices[utterance.role]),
                    rate,
                    utterance.volume,
                    utterance.id,
                )
                is PauseUtterance -> fake.playSilence(utterance.durationMs, utterance.id)
            }
        }
    }

    @Test
    fun speakRecordsVoiceRateVolumeAndId() {
        val fake = FakeStreamTtsDriver()
        assertTrue(fake.speak("Hi.", "narr", 1.5f, 0.8f, "s0:1"))
        assertEquals(
            listOf(FakeStreamTtsDriver.SpeakCall("Hi.", "narr", 1.5f, 0.8f, "s0:1")),
            fake.speaks,
        )
    }

    @Test
    fun silenceAndStopRecorded() {
        val fake = FakeStreamTtsDriver()
        assertTrue(fake.playSilence(250L, "s0:1:p"))
        assertTrue(fake.stop())
        assertEquals(
            listOf(FakeStreamTtsDriver.SilenceCall(250L, "s0:1:p")),
            fake.silences,
        )
        assertEquals(1, fake.stops)
    }

    @Test
    fun notReadyRefusesEverything() {
        val fake = FakeStreamTtsDriver().apply { isReady = false }
        assertFalse(fake.speak("Hi.", "narr", 1.0f, 1.0f, "s0:1"))
        assertFalse(fake.playSilence(250L, "s0:1:p"))
        assertFalse(fake.stop())
        assertTrue(fake.speaks.isEmpty())
    }

    @Test
    fun rebindAndShutdownAccounted() {
        val fake = FakeStreamTtsDriver()
        assertTrue(fake.rebind())
        assertEquals(1, fake.rebinds)
        assertTrue(fake.isReady)
        fake.shutdown()
        assertTrue(fake.shutDown)
        assertFalse(fake.isReady)
    }

    @Test
    fun coreStreamsThroughSeamWithRoleVoices() {
        val fake = FakeStreamTtsDriver()
        val core = StreamCore(
            sentences(),
            mapOf(TtsRole.Narrator to 1.0f, TtsRole.Dialogue to 0.7f),
            mapOf(1 to 250L),
        )
        val bridge = object : StreamTtsListener {
            override fun onStart(id: String) = core.onStart(id)
            override fun onDone(id: String) {
                feed(fake, core.onDone(id))
            }

            override fun onError(id: String) {
                feed(fake, core.onError(id))
            }
        }
        fake.setListener(bridge)

        feed(fake, core.start(1))
        // s1 (narr) + pause, s2 (dial), s3 (narr, last: no pause).
        assertEquals(listOf("One.", "Two?", "Three."), fake.speaks.map { it.text })
        assertEquals(listOf("narr", "dial", "narr"), fake.speaks.map { it.voiceName })
        assertEquals(listOf(1.0f, 0.7f, 1.0f), fake.speaks.map { it.volume })
        assertEquals(listOf(250L), fake.silences.map { it.durationMs })

        fake.emitStart("s0:1")
        assertEquals(1, core.currentSid)
        fake.emitDone("s0:1")
        fake.emitDone("s0:1:p")
        fake.emitStart("s0:2")
        fake.emitDone("s0:2")
        fake.emitStart("s0:3")
        fake.emitDone("s0:3")
        assertEquals(StreamCore.Status.Done, core.status)
        assertEquals(3, core.currentSid)
    }

    @Test
    fun failedSpeakMapsToCoreErrorAndSkips() {
        val fake = FakeStreamTtsDriver()
        val core = StreamCore(sentences())
        fake.setListener(object : StreamTtsListener {
            override fun onStart(id: String) = core.onStart(id)
            override fun onDone(id: String) {
                core.onDone(id)
            }
            override fun onError(id: String) {
                core.onError(id)
            }
        })
        // s2's speak fails to queue: report it as an engine error event.
        fake.failSpeaks += "s0:2"
        core.start(1).forEach { utterance ->
            if (utterance is SpeakUtterance) {
                val ok = fake.speak(utterance.text, "v", 1.0f, 1.0f, utterance.id)
                if (!ok) core.onError(utterance.id)
            }
        }
        assertEquals(1, core.consecutiveErrors)
        // s1 and s3 still complete; the stream survives one skip.
        core.onStart("s0:1")
        core.onDone("s0:1")
        assertEquals(0, core.consecutiveErrors)
    }

    @Test
    fun pauseStopsDriverAndStaleCallbacksIgnored() {
        val fake = FakeStreamTtsDriver()
        val core = StreamCore(sentences())
        fake.setListener(object : StreamTtsListener {
            override fun onStart(id: String) = core.onStart(id)
            override fun onDone(id: String) {
                core.onDone(id)
            }
            override fun onError(id: String) {
                core.onError(id)
            }
        })
        feed(fake, core.start(1))
        fake.emitStart("s0:1")
        assertEquals(1, core.pause())
        assertTrue(fake.stop())
        // The stopped utterance's late done is stale: resume is unaffected.
        fake.emitDone("s0:1")
        val resumed = core.resume()
        assertEquals(1, speaks(resumed)[0].sid)
        assertEquals("s1:1", resumed[0].id)
    }

    private fun speaks(plan: List<StreamUtterance>): List<SpeakUtterance> =
        plan.filterIsInstance<SpeakUtterance>()
}
