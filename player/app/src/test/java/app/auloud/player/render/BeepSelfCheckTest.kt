package app.auloud.player.render

import app.auloud.player.bundle.BundleValidator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * RN10: [BeepSelfCheck] unit tests (pure JVM).
 *
 * Runs the debug action through the real RN4 spool plus RN5 assembly
 * plus RN6 encoder chain with the JVM fakes ([MemSpoolIo] spool,
 * [FakeAudioEncoder] encoder): pins known tone positions (first start
 * 0, 500 ms tones on 1000 ms centers), the encoded handshake, the beep
 * fingerprint, and that the emitted chapter validates against the RN1
 * spec rules with zero errors. Also pins the release refusal.
 */
class BeepSelfCheckTest {

    private fun readFrom(io: MemSpoolIo, spoolDir: String): (String) -> ByteArray =
        { fileName -> io.bytes["$spoolDir/$fileName"] ?: error("no spool bytes for $fileName") }

    @Test
    fun chapter_isUntimedAlternatingBeepLines(): Unit = runBlocking {
        val chapter = BeepSelfCheck.buildChapter()
        assertEquals("2.0", chapter.specVersion)
        assertEquals(4, chapter.blocks.size)
        assertEquals(listOf(1, 2, 3, 4), chapter.sentencesInOrder().map { it.sid })
        assertEquals(
            listOf("narrator", "dialogue", "narrator", "dialogue"),
            chapter.sentencesInOrder().map { it.speaker }
        )
        assertEquals(
            listOf("Beep 1", "Beep 2", "Beep 3", "Beep 4"),
            chapter.sentencesInOrder().map { it.text }
        )
        assertTrue(chapter.durationMs == null)
        assertTrue(chapter.sentencesInOrder().all { it.startMs == null && it.endMs == null })
    }

    @Test
    fun textForSid_rejectsZero(): Unit = runBlocking {
        try {
            BeepSelfCheck.textForSid(0)
            fail("sid 0 must fail")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun run_rendersKnownPositionsThroughTheRealChain(): Unit = runBlocking {
        val io = MemSpoolIo()
        val encoder = FakeAudioEncoder(chapterNumber = 1)
        val outcome = BeepSelfCheck.run(
            spoolDir = "spool",
            spoolIo = io,
            readSpoolBytes = readFrom(io, "spool"),
            encoder = encoder,
            isDebugBuild = true
        )
        if (outcome !is BeepSelfCheck.Outcome.Success) {
            fail("beep render must succeed, got $outcome")
            return@runBlocking
        }
        val bundle = outcome.bundle
        // Tone positions known: 500 ms tones on 1000 ms centers (500 ms para pauses).
        assertEquals(
            listOf(0, 1000, 2000, 3000),
            bundle.timings.map { it.startMs }
        )
        assertEquals(
            listOf(500, 1500, 2500, 3500),
            bundle.timings.map { it.endMs }
        )
        assertEquals(4000, bundle.durationMs)
        // Encoder handshake: fake file agrees with the assembly.
        assertEquals("audio/ch001.m4a", bundle.encoded.filePath)
        assertEquals(96000L, bundle.encoded.sampleCount)
        assertEquals(4000, bundle.encoded.durationMs)
        assertTrue("encoder must see EOS", encoder.eosSignaled)
        // Beep fingerprint rides along for the manifest write.
        assertEquals("beep", bundle.fingerprint.engine)
    }

    @Test
    fun run_outputValidatesWithZeroErrors(): Unit = runBlocking {
        val io = MemSpoolIo()
        val outcome = BeepSelfCheck.run(
            spoolDir = "spool",
            spoolIo = io,
            readSpoolBytes = readFrom(io, "spool"),
            encoder = FakeAudioEncoder(chapterNumber = 1),
            isDebugBuild = true
        )
        if (outcome !is BeepSelfCheck.Outcome.Success) {
            fail("beep render must succeed, got $outcome")
            return@runBlocking
        }
        assertEquals(
            "beep chapter must validate, got ${outcome.bundle.validationErrors}",
            emptyList<String>(),
            outcome.bundle.validationErrors
        )
        // Independent re-check through the validator entry point.
        val recheck = BundleValidator.validate(
            "beep-self-check",
            outcome.bundle.manifest,
            exists = { path ->
                path.endsWith(BeepSelfCheck.CHAPTER_TEXT_PATH) ||
                    path.endsWith(BeepSelfCheck.CHAPTER_AUDIO_PATH)
            },
            readText = { path ->
                if (path.endsWith(BeepSelfCheck.CHAPTER_TEXT_PATH)) {
                    outcome.bundle.chapterJson
                } else {
                    null
                }
            }
        )
        assertEquals(emptyList<String>(), recheck)
    }

    @Test
    fun run_refusesReleaseBuilds(): Unit = runBlocking {
        val io = MemSpoolIo()
        val outcome = BeepSelfCheck.run(
            spoolDir = "spool",
            spoolIo = io,
            readSpoolBytes = readFrom(io, "spool"),
            encoder = FakeAudioEncoder(chapterNumber = 1),
            isDebugBuild = false
        )
        if (outcome !is BeepSelfCheck.Outcome.Failure) {
            fail("release build must refuse the beep check, got $outcome")
            return@runBlocking
        }
        assertTrue(
            "refusal names debug-only, got: ${outcome.reason}",
            outcome.reason.contains("debug-only")
        )
        assertTrue("release refusal must spool nothing", io.bytes.isEmpty())
        assertTrue("release refusal must index nothing", io.texts.isEmpty())
    }
}
