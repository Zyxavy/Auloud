package app.auloud.player.render

import app.auloud.player.bundle.Block
import app.auloud.player.bundle.Sentence
import app.auloud.player.ingest.SPEAKER_DIALOGUE
import app.auloud.player.ingest.SPEAKER_NARRATOR
import app.auloud.player.tts.EngineRegistry
import app.auloud.player.tts.TtsEngine
import app.auloud.player.tts.TtsRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN4: [SpoolRenderer] tests on plain JVM (fake engines plus mem IO).
 *
 * Covers the plan RN4 verify list (pass ordering, resume skipping, retry
 * and failure, cancellation, fingerprint mismatch invalidation) plus voice
 * resolution reuse, release between passes, per-role peaks, split-pair
 * recompute through the shared tagger, and the job-level chapter loop on
 * the RN3 conventions.
 */
class SpoolRendererTest {

    private val spoolDir = "spool"
    private val versions = mapOf("engA" to "a-1", "engB" to "b-1", "shared" to "s-1")

    private fun storyChapter(number: Int) = blockChapter(
        number,
        listOf(
            SPEAKER_NARRATOR to "Oak.",
            SPEAKER_DIALOGUE to "Flame.",
            SPEAKER_NARRATOR to "Ash.",
            SPEAKER_DIALOGUE to "Ember."
        )
    )

    private fun resolveBoth(
        narratorEngine: TtsEngine,
        dialogueEngine: TtsEngine,
        narratorId: String,
        dialogueId: String,
        narratorSpeed: Float = 1.0f,
        dialogueSpeed: Float = 1.0f
    ): ResolvedRenderVoices {
        val engines = if (narratorEngine === dialogueEngine) {
            listOf(narratorEngine)
        } else {
            listOf(narratorEngine, dialogueEngine)
        }
        return RenderVoices.resolve(
            EngineRegistry(engines),
            MemRenderVoiceStore(narratorId, dialogueId, narratorSpeed, dialogueSpeed),
            versionOf = { versions[it] }
        ).getOrThrow()
    }

    private fun distinctVoices(
        narrAmp: Float = 0.5f,
        dialAmp: Float = 0.8f
    ): Triple<ScriptedSpoolEngine, ScriptedSpoolEngine, ResolvedRenderVoices> {
        val narr = ScriptedSpoolEngine("engA", listOf("engA:narr"), amplitudeFor = { narrAmp })
        val dial = ScriptedSpoolEngine("engB", listOf("engB:dial"), amplitudeFor = { dialAmp })
        val voices = resolveBoth(narr, dial, "engA:narr", "engB:dial")
        return Triple(narr, dial, voices)
    }

    private fun completedOf(outcome: ChapterSpoolOutcome): ChapterSpoolSummary {
        assertTrue(outcome is ChapterSpoolOutcome.Completed)
        return (outcome as ChapterSpoolOutcome.Completed).summary
    }

    @Test
    fun passOrder_narratorSidsThenDialogueSids(): Unit = runBlocking {
        val (narr, dial, voices) = distinctVoices()
        val summary = completedOf(
            SpoolRenderer.renderChapter(0, 1, storyChapter(1), voices, spoolDir, MemSpoolIo())
        )
        assertEquals(listOf("Oak.", "Ash."), narr.calls.map { it.text })
        assertEquals(listOf("Flame.", "Ember."), dial.calls.map { it.text })
        assertEquals(4, summary.spooled)
        assertEquals(0, summary.skipped)
        assertEquals(listOf(1, 2, 3, 4), summary.sentences.map { it.sid })
    }

    @Test
    fun release_calledBetweenPassesForDistinctEngines(): Unit = runBlocking {
        val innerNarr = ScriptedSpoolEngine("engA", listOf("engA:narr"))
        val innerDial = ScriptedSpoolEngine("engB", listOf("engB:dial"))
        val events = ArrayList<String>()
        val narr = object : TtsEngine by innerNarr {
            override suspend fun synthesize(
                text: String,
                voice: app.auloud.player.tts.TtsVoice,
                speed: Float
            ): app.auloud.player.tts.SynthesizedAudio {
                events.add("synth:$text")
                return innerNarr.synthesize(text, voice, speed)
            }
        }
        val dial = object : TtsEngine by innerDial {
            override suspend fun synthesize(
                text: String,
                voice: app.auloud.player.tts.TtsVoice,
                speed: Float
            ): app.auloud.player.tts.SynthesizedAudio {
                events.add("synth:$text")
                return innerDial.synthesize(text, voice, speed)
            }
        }
        val voices = resolveBoth(narr, dial, "engA:narr", "engB:dial")
        val released = ArrayList<TtsEngine>()
        val outcome = SpoolRenderer.renderChapter(
            0, 1, storyChapter(1), voices, spoolDir, MemSpoolIo(),
            onReleasePass = { released.add(it); events.add("release") }
        )
        assertTrue(outcome is ChapterSpoolOutcome.Completed)
        assertEquals(1, released.size)
        assertTrue(released.single() === narr)
        assertEquals(
            listOf("synth:Oak.", "synth:Ash.", "release", "synth:Flame.", "synth:Ember."),
            events
        )
    }

    @Test
    fun release_skippedForSharedEngine(): Unit = runBlocking {
        val shared = ScriptedSpoolEngine("shared", listOf("shared:narr", "shared:dial"))
        val voices = RenderVoices.resolve(
            EngineRegistry(listOf(shared)),
            MemRenderVoiceStore("shared:narr", "shared:dial"),
            versionOf = { versions[it] }
        ).getOrThrow()
        val released = ArrayList<TtsEngine>()
        val summary = completedOf(
            SpoolRenderer.renderChapter(
                0, 1, storyChapter(1), voices, spoolDir, MemSpoolIo(),
                onReleasePass = { released.add(it) }
            )
        )
        assertEquals(4, summary.spooled)
        assertTrue(released.isEmpty())
        assertEquals(4, shared.calls.size)
    }

    @Test
    fun resume_skipsExistingSpoolFiles(): Unit = runBlocking {
        val io = MemSpoolIo()
        val first = distinctVoices()
        completedOf(SpoolRenderer.renderChapter(0, 1, storyChapter(1), first.third, spoolDir, io))
        assertEquals(4, first.first.calls.size + first.second.calls.size)

        val second = distinctVoices()
        val summary = completedOf(
            SpoolRenderer.renderChapter(0, 1, storyChapter(1), second.third, spoolDir, io)
        )
        assertTrue(second.first.calls.isEmpty())
        assertTrue(second.second.calls.isEmpty())
        assertEquals(0, summary.spooled)
        assertEquals(4, summary.skipped)
        assertTrue(summary.sentences.all { it.skipped })
    }

    @Test
    fun fingerprintMismatch_invalidatesSpool(): Unit = runBlocking {
        val io = MemSpoolIo()
        val narr = ScriptedSpoolEngine("engA", listOf("engA:narr"))
        val dial = ScriptedSpoolEngine("engB", listOf("engB:dial"))
        val slow = resolveBoth(narr, dial, "engA:narr", "engB:dial", dialogueSpeed = 1.0f)
        completedOf(SpoolRenderer.renderChapter(0, 1, storyChapter(1), slow, spoolDir, io))
        val oldTag = slow.fingerprint.fileTag()
        assertTrue(io.pcmPaths().all { oldTag in it })

        val fast = resolveBoth(narr, dial, "engA:narr", "engB:dial", dialogueSpeed = 1.05f)
        val before = narr.calls.size + dial.calls.size
        val summary = completedOf(
            SpoolRenderer.renderChapter(0, 1, storyChapter(1), fast, spoolDir, io)
        )
        assertEquals(4, narr.calls.size + dial.calls.size - before)
        assertEquals(4, summary.spooled)
        assertEquals(0, summary.skipped)
        assertTrue(io.pcmPaths().none { oldTag in it })
        assertTrue(io.pcmPaths().all { fast.fingerprint.fileTag() in it })
    }

    @Test
    fun retry_succeedsOnSecondAttempt(): Unit = runBlocking {
        val (narr, dial, voices) = distinctVoices()
        narr.failOnceTexts.add("Ash.")
        val summary = completedOf(
            SpoolRenderer.renderChapter(0, 1, storyChapter(1), voices, spoolDir, MemSpoolIo())
        )
        assertEquals(listOf(3), summary.retried)
        assertEquals(2, narr.calls.count { it.text == "Ash." })
        assertEquals(4, summary.spooled)
    }

    @Test
    fun failure_afterRetryNamesChapterAndSentence(): Unit = runBlocking {
        val (narr, dial, voices) = distinctVoices()
        dial.failAlwaysTexts.add("Flame.")
        val outcome = SpoolRenderer.renderChapter(0, 7, storyChapter(7), voices, spoolDir, MemSpoolIo())
        assertTrue(outcome is ChapterSpoolOutcome.Failed)
        val failed = outcome as ChapterSpoolOutcome.Failed
        assertEquals(7, failed.chapterNumber)
        assertEquals(2, failed.sid)
        assertTrue("7" in failed.reason && "2" in failed.reason)
        assertEquals(2, dial.calls.count { it.text == "Flame." })
    }

    @Test
    fun cancel_betweenSentencesThenResume(): Unit = runBlocking {
        val io = MemSpoolIo()
        val (narr, dial, voices) = distinctVoices()
        var done = 0
        var stop = false
        val first = SpoolRenderer.renderChapter(
            0, 1, storyChapter(1), voices, spoolDir, io,
            shouldCancel = { stop },
            onSentenceDone = { done++; if (done >= 2) stop = true }
        )
        assertTrue(first is ChapterSpoolOutcome.Cancelled)
        assertEquals(2, (first as ChapterSpoolOutcome.Cancelled).sidsDone)
        assertEquals(2, narr.calls.size + dial.calls.size)

        val (narr2, dial2, voices2) = distinctVoices()
        val summary = completedOf(
            SpoolRenderer.renderChapter(0, 1, storyChapter(1), voices2, spoolDir, io)
        )
        assertEquals(2, summary.spooled)
        assertEquals(2, summary.skipped)
        assertEquals(2, narr2.calls.size + dial2.calls.size)
    }

    @Test
    fun peaks_measuredPerRoleWithoutGain(): Unit = runBlocking {
        val io = MemSpoolIo()
        val (_, _, voices) = distinctVoices(narrAmp = 0.5f, dialAmp = 0.8f)
        val summary = completedOf(
            SpoolRenderer.renderChapter(0, 1, storyChapter(1), voices, spoolDir, io)
        )
        assertEquals(0.5f, summary.peakNarrator)
        assertEquals(0.8f, summary.peakDialogue)
        for (spooled in summary.sentences) {
            val raw = io.bytes[SpoolFiles.path(spoolDir, spooled.file)]
                ?: throw AssertionError("missing ${spooled.file}")
            val decodedPeak = SpoolPcm.peakOf(SpoolPcm.decodePcm16(raw))
            val expected = if (spooled.role == TtsRole.Narrator) 0.5f else 0.8f
            assertTrue(kotlin.math.abs(decodedPeak - expected) < 0.001f)
        }
    }

    @Test
    fun kindSidRate_preservedForAssembly(): Unit = runBlocking {
        val (_, _, voices) = distinctVoices()
        val summary = completedOf(
            SpoolRenderer.renderChapter(0, 1, storyChapter(1), voices, spoolDir, MemSpoolIo())
        )
        val bySid = summary.sentences.associateBy { it.sid }
        assertEquals(TtsRole.Narrator, bySid[1]?.role)
        assertEquals(TtsRole.Dialogue, bySid[2]?.role)
        for (spooled in summary.sentences) {
            assertEquals(22050, spooled.sampleRateHz)
            assertTrue(spooled.samples > 0)
        }
        assertEquals("Oak.".length * 100, bySid[1]?.samples)
    }

    @Test
    fun splitPair_recomputedThroughSharedTagger(): Unit = runBlocking {
        val (_, _, voices) = distinctVoices()
        val chapter = app.auloud.player.bundle.ChapterText(
            specVersion = "2.0",
            chapter = 1,
            title = "Split",
            blocks = listOf(
                Block(
                    id = 1,
                    type = "para",
                    sentences = listOf(
                        Sentence(sid = 1, speaker = SPEAKER_DIALOGUE, text = "\"Run!\" "),
                        Sentence(sid = 2, speaker = SPEAKER_NARRATOR, text = "he shouted.")
                    )
                ),
                Block(
                    id = 2,
                    type = "para",
                    sentences = listOf(
                        Sentence(sid = 3, speaker = SPEAKER_NARRATOR, text = "The bell rang.")
                    )
                )
            )
        )
        val summary = completedOf(
            SpoolRenderer.renderChapter(0, 1, chapter, voices, spoolDir, MemSpoolIo())
        )
        val bySid = summary.sentences.associateBy { it.sid }
        val first = bySid[1]?.splitPair
        val second = bySid[2]?.splitPair
        assertTrue(first != null && second != null)
        assertEquals(first, second)
        assertNull(bySid[3]?.splitPair)
        assertTrue(summary.splitFallbackBlocks.isEmpty())
    }

    @Test
    fun unknownSpeaker_failsChapterNamingSid(): Unit = runBlocking {
        val (_, _, voices) = distinctVoices()
        val chapter = blockChapter(
            1,
            listOf(
                SPEAKER_NARRATOR to "Oak.",
                "ana" to "Someone."
            )
        )
        val outcome = SpoolRenderer.renderChapter(0, 1, chapter, voices, spoolDir, MemSpoolIo())
        assertTrue(outcome is ChapterSpoolOutcome.Failed)
        val failed = outcome as ChapterSpoolOutcome.Failed
        assertEquals(2, failed.sid)
        assertTrue("ana" in failed.reason)
    }

    @Test
    fun blankText_failsChapter(): Unit = runBlocking {
        val (_, _, voices) = distinctVoices()
        val chapter = blockChapter(1, listOf(SPEAKER_NARRATOR to "   "))
        val outcome = SpoolRenderer.renderChapter(0, 1, chapter, voices, spoolDir, MemSpoolIo())
        assertTrue(outcome is ChapterSpoolOutcome.Failed)
    }

    @Test
    fun emptyChapter_completes(): Unit = runBlocking {
        val (_, _, voices) = distinctVoices()
        val chapter = app.auloud.player.bundle.ChapterText(
            specVersion = "2.0",
            chapter = 1,
            title = "Empty",
            blocks = emptyList()
        )
        val summary = completedOf(
            SpoolRenderer.renderChapter(0, 1, chapter, voices, spoolDir, MemSpoolIo())
        )
        assertTrue(summary.sentences.isEmpty())
        assertEquals(0f, summary.peakNarrator)
        assertEquals(0f, summary.peakDialogue)
    }

    private fun runningJob(positions: List<Int>): RenderJob {
        val plan = RenderPlan(
            bookId = "b1", chapterCount = 3, startChapter = 0,
            scope = RenderScope.FromHere, orderedChapters = positions, createdAt = 1L
        )
        val queued = RenderJob(
            bookId = "b1", state = RenderJobState.QUEUED,
            plan = plan, createdAt = 1L, updatedAt = 1L
        )
        return RenderJobs.transition(queued, RenderJobState.RUNNING, clock = { 2L })
    }

    @Test
    fun bookLoop_completesThroughChapterDone(): Unit = runBlocking {
        val (_, _, voices) = distinctVoices()
        val io = MemSpoolIo()
        val chapters = mapOf(1 to storyChapter(1), 2 to storyChapter(2))
        val outcome = SpoolRenderer.renderJobChapters(
            runningJob(listOf(0, 1)), voices, spoolDir, io,
            loadChapter = { chapters.getValue(it) }
        )
        assertTrue(outcome is JobSpoolOutcome.Finished)
        val job = (outcome as JobSpoolOutcome.Finished).job
        assertEquals(RenderJobState.DONE, job.state)
        assertEquals(listOf(0, 1), job.completedChapters)
    }

    @Test
    fun bookLoop_stopsOnChapterFailure(): Unit = runBlocking {
        val narr = ScriptedSpoolEngine("engA", listOf("engA:narr"))
        val dial = ScriptedSpoolEngine("engB", listOf("engB:dial"))
        dial.failAlwaysTexts.add("Flame.")
        val voices = resolveBoth(narr, dial, "engA:narr", "engB:dial")
        val io = MemSpoolIo()
        val first = blockChapter(
            1,
            listOf(
                SPEAKER_NARRATOR to "Oak.",
                SPEAKER_DIALOGUE to "Brook.",
                SPEAKER_NARRATOR to "Ash.",
                SPEAKER_DIALOGUE to "Pond."
            )
        )
        val chapters = mapOf(1 to first, 2 to storyChapter(2))
        val outcome = SpoolRenderer.renderJobChapters(
            runningJob(listOf(0, 1)), voices, spoolDir, io,
            loadChapter = { chapters.getValue(it) }
        )
        assertTrue(outcome is JobSpoolOutcome.ChapterFailed)
        val stopped = outcome as JobSpoolOutcome.ChapterFailed
        assertEquals(RenderJobState.RUNNING, stopped.job.state)
        assertEquals(listOf(0), stopped.job.completedChapters)
        assertEquals(2, stopped.failure.chapterNumber)
    }
}
