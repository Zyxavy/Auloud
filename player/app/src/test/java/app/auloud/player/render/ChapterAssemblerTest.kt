package app.auloud.player.render

import app.auloud.player.bundle.Block
import app.auloud.player.bundle.ChapterText
import app.auloud.player.bundle.Sentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * RN5: [ChapterAssembler] plus [BookGains] unit tests (pure JVM).
 *
 * Covers the plan RN5 verify list beyond the shared vectors: book gain
 * derivation from first-chapter peaks (pure, RN7 stores the decibel map),
 * the attenuate-only chapter cap (gain only, durations unchanged),
 * mixed native rates through resample-then-count, the encoder seam
 * handshake, and edge rules (leading-break drop, empty chapter, sid
 * order, unknown block, degenerate timings, spool helper paths).
 */
class ChapterAssemblerTest {

    private fun meta(
        sid: Int,
        role: String = "narrator",
        rate: Int = 24000,
        samples: Int = 2400,
        splitPair: Int? = null
    ) = AssemblySentenceMeta(
        sid = sid,
        role = role,
        nativeRateHz = rate,
        nativeSamplesExpected = samples,
        splitPair = splitPair
    )

    private fun paraBlock(id: Int, vararg sentences: AssemblySentenceMeta) =
        AssemblyBlockMeta(id = id, type = "para", sentences = sentences.toList())

    private fun assembleSimple(
        blocks: List<AssemblyBlockMeta>,
        pcm: Map<Int, FloatArray>,
        gains: Map<String, Double> = emptyMap(),
        target: Double = AssemblyMath.PEAK_TARGET
    ): Pair<AssembledChapterResult, CollectingEncoderSink> {
        val sink = CollectingEncoderSink()
        val result = ChapterAssembler.assemble(
            chapterNumber = 1,
            blocks = blocks,
            pcmFor = { meta -> pcm[meta.sid] ?: error("no pcm sid ${meta.sid}") },
            bookGainsLinear = gains,
            targetPeak = target,
            sink = sink
        )
        return result to sink
    }

    @Test
    fun bookGains_deriveMatchesTargetOverPeak() {
        val derived = BookGains.derive(mapOf("narrator" to 0.5f, "dialogue" to 0.8f))
        assertEquals(AssemblyMath.PEAK_TARGET / 0.5f.toDouble(), derived.linear["narrator"]!!, 1e-9)
        assertEquals(AssemblyMath.PEAK_TARGET / 0.8f.toDouble(), derived.linear["dialogue"]!!, 1e-9)
        assertEquals(
            AssemblyMath.dbForGain(derived.linear["narrator"]!!),
            derived.db["narrator"]!!, 1e-12
        )
    }

    @Test
    fun bookGains_silenceGivesUnityAndZeroDb() {
        val derived = BookGains.derive(mapOf("narrator" to 0f, "dialogue" to 0f))
        assertEquals(1.0, derived.linear["narrator"]!!, 1e-12)
        assertEquals(1.0, derived.linear["dialogue"]!!, 1e-12)
        assertEquals(0.0, derived.db["narrator"]!!, 1e-12)
        assertEquals(0.0, derived.db["dialogue"]!!, 1e-12)
    }

    @Test
    fun cap_quietChapterIsNoOp() {
        val pcm = mapOf(1 to FloatArray(2400) { 0.1f })
        val (result, sink) = assembleSimple(listOf(paraBlock(1, meta(1))), pcm)
        assertEquals(1.0, result.capGain, 1e-12)
        assertTrue(result.peakAfterCap < AssemblyMath.PEAK_TARGET)
        assertTrue(sink.finished)
        assertEquals(result.sampleCount, sink.finishSamples)
        assertEquals(result.durationMs, sink.finishDurationMs)
    }

    @Test
    fun cap_loudChapterAttenuatesToTargetWithoutMovingTimings() {
        val loudPcm = mapOf(1 to FloatArray(2400) { 1.0f })
        val (capped, _) = assembleSimple(listOf(paraBlock(1, meta(1))), loudPcm)
        assertTrue(capped.capGain < 1.0)
        assertEquals(AssemblyMath.PEAK_TARGET, capped.peakAfterCap, 1e-6)
        val quietPcm = mapOf(1 to FloatArray(2400) { 0.1f })
        val (quiet, _) = assembleSimple(listOf(paraBlock(1, meta(1))), quietPcm)
        assertEquals(quiet.durationMs, capped.durationMs)
        assertEquals(quiet.sampleCount, capped.sampleCount)
        assertEquals(quiet.timings, capped.timings)
    }

    @Test
    fun bookGain_levelsSecondChapterToFirstReference() {
        val first = BookGains.derive(mapOf("narrator" to 0.5f, "dialogue" to 0.8f))
        val pcmNarr = mapOf(1 to FloatArray(2400) { 0.5f })
        val (second, _) = assembleSimple(
            listOf(paraBlock(1, meta(1, role = "narrator"))),
            pcmNarr,
            gains = first.linear
        )
        assertEquals(AssemblyMath.PEAK_TARGET, second.peakAfterCap, 1e-6)
        assertEquals(first.linear["narrator"], second.bookGainsApplied["narrator"])
    }

    @Test
    fun mixedRates_resampleThenCountLandsOnExactMs() {
        val blocks = listOf(
            AssemblyBlockMeta(
                id = 1,
                type = "para",
                sentences = listOf(
                    meta(1, rate = 16000, samples = 8000),
                    meta(2, rate = 8000, samples = 4000)
                )
            )
        )
        val pcm = mapOf(
            1 to FloatArray(8000) { 0.4f },
            2 to FloatArray(4000) { 0.4f }
        )
        val (result, _) = assembleSimple(blocks, pcm)
        assertEquals(0, result.timings[0].startMs)
        assertEquals(500, result.timings[0].endMs)
        assertEquals(750, result.timings[1].startMs)
        assertEquals(1250, result.timings[1].endMs)
    }

    @Test
    fun encoderSeam_finishHandshakeMatchesResult() {
        val pcm = mapOf(1 to FloatArray(2400) { 0.3f })
        val (result, sink) = assembleSimple(listOf(paraBlock(1, meta(1))), pcm)
        assertEquals(24000, sink.sampleRateHz)
        assertEquals(result.sampleCount, sink.totalSamples)
        assertTrue(sink.chunks >= 2)
        assertTrue(sink.peak > 0.0)
    }

    @Test
    fun leadingBreak_droppedSoFirstStartIsZero() {
        val blocks = listOf(
            AssemblyBlockMeta(id = 1, type = "break", sentences = emptyList()),
            paraBlock(2, meta(1))
        )
        val pcm = mapOf(1 to FloatArray(2400) { 0.5f })
        val (result, _) = assembleSimple(blocks, pcm)
        assertEquals(0, result.timings[0].startMs)
        assertEquals(100, result.timings[0].endMs)
        assertEquals(600, result.durationMs)
    }

    @Test
    fun emptyChapter_failsNamingChapter() {
        try {
            assembleSimple(
                listOf(paraBlock(1)),
                emptyMap()
            )
            fail("expected empty chapter failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("chapter 1"))
        }
    }

    @Test
    fun sidOrder_mustBeOneBasedConsecutive() {
        val blocks = listOf(paraBlock(1, meta(2, samples = 2400), meta(1, samples = 2400)))
        val pcm = mapOf(
            1 to FloatArray(2400) { 0.2f },
            2 to FloatArray(2400) { 0.2f }
        )
        try {
            assembleSimple(blocks, pcm)
            fail("expected sid order failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sids out of order"))
        }
    }

    @Test
    fun unknownBlock_fails() {
        val blocks = listOf(
            AssemblyBlockMeta(id = 1, type = "verse", sentences = listOf(meta(1)))
        )
        val pcm = mapOf(1 to FloatArray(2400) { 0.2f })
        try {
            assembleSimple(blocks, pcm)
            fail("expected unknown block failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unknown block type"))
        }
    }

    @Test
    fun spoolHelper_roundTripsRolesAndSplitFlags() {
        val chapter = ChapterText(
            specVersion = "2.0",
            chapter = 1,
            title = "Ch",
            blocks = listOf(
                Block(
                    id = 1,
                    type = "para",
                    sentences = listOf(
                        Sentence(sid = 1, speaker = "narrator", text = "Oak."),
                        Sentence(sid = 2, speaker = "dialogue", text = "Flame.")
                    )
                )
            )
        )
        val fingerprint = RenderFingerprint(
            engine = "system",
            voices = mapOf("narrator" to "system:n", "dialogue" to "system:d"),
            speeds = mapOf("narrator" to 1.0f, "dialogue" to 1.0f),
            engineVersions = mapOf("system" to "v")
        )
        val index = SpoolChapterIndex(
            chapter = 1,
            fingerprint = fingerprint,
            sentences = listOf(
                SpoolSentenceEntry(
                    sid = 1, role = "narrator", file = "ch001-s001-ab.pcm",
                    sampleRateHz = 24000, samples = 2400, splitPair = null, peak = 0.5f
                ),
                SpoolSentenceEntry(
                    sid = 2, role = "dialogue", file = "ch001-s002-ab.pcm",
                    sampleRateHz = 24000, samples = 2400, splitPair = null, peak = 0.8f
                )
            ),
            peaks = mapOf("narrator" to 0.5f, "dialogue" to 0.8f)
        )
        val sink = CollectingEncoderSink()
        val result = ChapterAssembler.assembleFromSpool(
            chapterNumber = 1,
            chapter = chapter,
            index = index,
            loadPcm = { entry ->
                FloatArray(entry.samples) { 0.4f }
            },
            sink = sink
        )
        assertEquals(2, result.timings.size)
        assertEquals(0, result.timings[0].startMs)
        assertTrue(sink.finished)
    }

    @Test
    fun spoolHelper_missingEntryFailsNamingSid() {
        val chapter = ChapterText(
            specVersion = "2.0",
            chapter = 1,
            title = "Ch",
            blocks = listOf(
                Block(
                    id = 1,
                    type = "para",
                    sentences = listOf(Sentence(sid = 1, speaker = "narrator", text = "Oak."))
                )
            )
        )
        val fingerprint = RenderFingerprint(
            engine = "system",
            voices = mapOf("narrator" to "system:n", "dialogue" to "system:d"),
            speeds = mapOf("narrator" to 1.0f, "dialogue" to 1.0f),
            engineVersions = mapOf("system" to "v")
        )
        val index = SpoolChapterIndex(
            chapter = 1,
            fingerprint = fingerprint,
            sentences = emptyList(),
            peaks = mapOf("narrator" to 0f, "dialogue" to 0f)
        )
        try {
            ChapterAssembler.assembleFromSpool(
                chapterNumber = 1,
                chapter = chapter,
                index = index,
                loadPcm = { FloatArray(it.samples) { 0.1f } },
                sink = CollectingEncoderSink()
            )
            fail("expected missing spool failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sentence 1"))
        }
    }
}
