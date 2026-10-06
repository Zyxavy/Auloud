package app.auloud.player.render

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * RN6: [AudioEncoder] seam unit tests (pure JVM, fake encoder).
 *
 * Covers the plan RN6 verify list on the JVM side: chunk buffering with
 * EOS, error mapping (no encoder, config, muxer, duration mismatch),
 * the D-093 offset hook (including a nonzero offset), the duration check
 * failure, temp naming plus atomic rename, and the assembly-to-encoder
 * path ([ChapterEncode]) streaming through the sink without holding the
 * chapter. The real MediaCodec/MediaMuxer path needs the tablet (RN10).
 */

/** In-memory [AudioEncoder] driven by the shared [EncoderCore] rules. */
internal class FakeAudioEncoder(
    val chapterNumber: Int,
    val config: EncoderConfig = EncoderConfig(),
    var failOnWrite: AudioEncoderException? = null,
    var failOnFinish: AudioEncoderException? = null
) : AudioEncoder {
    override val sampleRateHz: Int = config.sampleRateHz

    private val core = EncoderCore(chapterNumber, config)

    /** Accepted chunk sizes in order (audio itself is not held). */
    val chunkSizes = ArrayList<Int>()
    var peak: Float = 0f
        private set
    var eosSignaled: Boolean = false
        private set
    var finishedFile: EncodedChapter? = null
        private set
    var aborted: Boolean = false
        private set
    var renamedTmpTo: Pair<String, String>? = null
        private set

    val tmpPath: String = EncoderFiles.tmpPathFor(fakeFinalPath(chapterNumber))

    override fun writePcm(chunk: FloatArray) {
        failOnWrite?.let { throw it }
        core.onWrite(chunk.size)
        chunkSizes.add(chunk.size)
        for (value in chunk) {
            val magnitude = if (value < 0f) -value else value
            if (magnitude > peak) peak = magnitude
        }
    }

    override fun finish(totalSamples: Long, durationMs: Int): EncodedChapter {
        failOnFinish?.let { throw it }
        core.onFinish(totalSamples, durationMs)
        eosSignaled = true
        val finalPath = fakeFinalPath(chapterNumber)
        renamedTmpTo = tmpPath to finalPath
        return EncodedChapter(
            filePath = finalPath,
            sampleCount = totalSamples,
            durationMs = durationMs,
            encoderOffsetMs = config.encoderOffsetMs
        ).also { finishedFile = it }
    }

    override fun abort() {
        aborted = true
    }

    companion object {
        fun fakeFinalPath(chapterNumber: Int): String =
            "audio/${EncoderFiles.chapterFileName(chapterNumber)}"
    }
}

/** In-memory [EncoderFileIo] (no sleeps, no disk). */
internal class MemEncoderFileIo(
    var renameScript: ArrayDeque<Boolean> = ArrayDeque(),
    var renameCalls: Int = 0
) : EncoderFileIo {
    val files = LinkedHashSet<String>()
    val deleted = ArrayList<String>()
    var parentsEnsured = ArrayList<String>()

    override fun exists(path: String): Boolean = path in files

    override fun renameTempToTarget(tmpPath: String, targetPath: String): Boolean {
        renameCalls++
        val step = if (renameScript.isNotEmpty()) renameScript.removeFirst() else true
        if (step) {
            files.remove(tmpPath)
            files.add(targetPath)
        }
        return step
    }

    override fun deleteIfExists(path: String) {
        files.remove(path)
        deleted.add(path)
    }

    override fun ensureParentDirs(path: String) {
        parentsEnsured.add(path)
    }
}

class AudioEncoderTest {

    // Timestamp math (sample counts, never wall clock).

    @Test
    fun timestamps_zeroIsZero() {
        assertEquals(0L, EncoderTimestamps.presentationTimeUs(0L, 24000))
    }

    @Test
    fun timestamps_oneSecondAt24k() {
        assertEquals(1_000_000L, EncoderTimestamps.presentationTimeUs(24000L, 24000))
    }

    @Test
    fun timestamps_halfMillisecondTruncates() {
        assertEquals(500L, EncoderTimestamps.presentationTimeUs(12L, 24000))
        assertEquals(41L, EncoderTimestamps.presentationTimeUs(1L, 24000))
    }

    @Test
    fun timestamps_growMonotonically() {
        var prev = -1L
        for (offset in 0L..24000L step 7) {
            val pts = EncoderTimestamps.presentationTimeUs(offset, 24000)
            assertTrue("pts must grow at offset $offset", pts >= prev)
            prev = pts
        }
    }

    @Test
    fun timestamps_rejectBadInputs() {
        try {
            EncoderTimestamps.presentationTimeUs(-1L, 24000)
            fail("negative offset must fail")
        } catch (_: IllegalArgumentException) {
        }
        try {
            EncoderTimestamps.presentationTimeUs(10L, 0)
            fail("zero rate must fail")
        } catch (_: IllegalArgumentException) {
        }
    }

    // Config defaults and guards.

    @Test
    fun config_defaultsMatchSpec() {
        val config = EncoderConfig()
        assertEquals(24000, config.sampleRateHz)
        assertEquals(1, config.channelCount)
        assertEquals(64000, config.bitRateBps)
        assertEquals(0, config.encoderOffsetMs)
        assertEquals("audio/mp4a-latm", EncoderConfig.MIME_TYPE)
    }

    @Test
    fun config_rejectsBadValues() {
        try {
            EncoderConfig(channelCount = 2)
            fail("stereo must fail")
        } catch (_: IllegalArgumentException) {
        }
        try {
            EncoderConfig(encoderOffsetMs = -5)
            fail("negative offset must fail")
        } catch (_: IllegalArgumentException) {
        }
        try {
            EncoderConfig(bitRateBps = 0)
            fail("zero bitrate must fail")
        } catch (_: IllegalArgumentException) {
        }
    }

    // Fake encoder: buffering, EOS, finish handshake.

    @Test
    fun fake_buffersChunksAndFinishesWithEos() {
        val encoder = FakeAudioEncoder(chapterNumber = 1)
        encoder.writePcm(FloatArray(2400) { 0.1f })
        encoder.writePcm(FloatArray(1200) { -0.2f })
        encoder.writePcm(FloatArray(2400))
        val total = 2400L + 1200L + 2400L
        val duration = AssemblyMath.msForSamples(total)
        val done = encoder.finish(total, duration)
        assertEquals(listOf(2400, 1200, 2400), encoder.chunkSizes)
        assertTrue("finish must signal EOS", encoder.eosSignaled)
        assertEquals("audio/ch001.m4a", done.filePath)
        assertEquals(total, done.sampleCount)
        assertEquals(duration, done.durationMs)
        assertEquals(0, done.encoderOffsetMs)
    }

    @Test
    fun fake_rejectsWriteAfterFinish() {
        val encoder = FakeAudioEncoder(chapterNumber = 2)
        encoder.writePcm(FloatArray(2400) { 0.1f })
        encoder.finish(2400L, AssemblyMath.msForSamples(2400L))
        try {
            encoder.writePcm(FloatArray(100) { 0.1f })
            fail("write after finish must fail")
        } catch (e: DurationMismatchException) {
            assertTrue("names the chapter, got: ${e.message}", e.message!!.contains("chapter 2"))
        }
    }

    @Test
    fun fake_rejectsDoubleFinish() {
        val encoder = FakeAudioEncoder(chapterNumber = 2)
        encoder.writePcm(FloatArray(2400) { 0.1f })
        encoder.finish(2400L, AssemblyMath.msForSamples(2400L))
        try {
            encoder.finish(2400L, AssemblyMath.msForSamples(2400L))
            fail("double finish must fail")
        } catch (e: DurationMismatchException) {
            assertTrue(e.message!!.contains("chapter 2"))
        }
    }

    @Test
    fun fake_durationMismatchOnWrongDuration() {
        val encoder = FakeAudioEncoder(chapterNumber = 3)
        encoder.writePcm(FloatArray(48000) { 0.1f })
        try {
            encoder.finish(48000L, 9999)
            fail("wrong duration must fail")
        } catch (e: DurationMismatchException) {
            assertTrue("names chapter and rule, got: ${e.message}", e.message!!.contains("chapter 3"))
        }
    }

    @Test
    fun fake_sampleCountMismatchOnShortFeed() {
        val encoder = FakeAudioEncoder(chapterNumber = 3)
        encoder.writePcm(FloatArray(100) { 0.1f })
        try {
            encoder.finish(200L, AssemblyMath.msForSamples(200L))
            fail("short feed must fail")
        } catch (e: DurationMismatchException) {
            assertTrue(e.message!!.contains("chapter 3"))
        }
    }

    @Test
    fun fake_emptyChapterFails() {
        val encoder = FakeAudioEncoder(chapterNumber = 4)
        try {
            encoder.finish(0L, 0)
            fail("empty chapter must fail")
        } catch (e: DurationMismatchException) {
            assertTrue(e.message!!.contains("chapter 4"))
        }
    }

    // Error mapping: every shape names the chapter.

    @Test
    fun errors_eachShapeNamesTheChapter() {
        val chapter = 7
        val noEncoder = EncoderErrors.noEncoder(chapter, IOException("none"))
        assertTrue(noEncoder is NoAacEncoderException)
        assertTrue(noEncoder.message!!.contains("chapter 7"))
        val config = EncoderErrors.config(chapter, "bad profile", null)
        assertTrue(config is EncoderConfigException)
        assertTrue(config.message!!.contains("chapter 7"))
        val muxer = EncoderErrors.muxer(chapter, "write failed", null)
        assertTrue(muxer is MuxerException)
        assertTrue(muxer.message!!.contains("chapter 7"))
        val io = EncoderErrors.io(chapter, "audio/ch007.m4a", "rename lost", null)
        assertTrue(io is EncoderIoException)
        assertTrue(io.message!!.contains("chapter 7"))
        val mismatch = EncoderErrors.mismatch(chapter, "fed 1, finish says 2")
        assertTrue(mismatch is DurationMismatchException)
        assertTrue(mismatch.message!!.contains("chapter 7"))
    }

    @Test
    fun fake_scriptedCodecFailurePropagatesShaped() {
        val encoder = FakeAudioEncoder(chapterNumber = 5)
        encoder.failOnWrite = EncoderErrors.muxer(5, "scripted drain failure", null)
        try {
            encoder.writePcm(FloatArray(100) { 0.1f })
            fail("scripted failure must propagate")
        } catch (e: MuxerException) {
            assertTrue(e.message!!.contains("chapter 5"))
        }
    }

    // D-093 offset hook, including a nonzero offset.

    @Test
    fun offset_zeroIsIdentity() {
        assertEquals(1000, EncoderOffset.apply(1000, 0))
        val timings = listOf(
            AssemblySentenceTiming(sid = 1, startMs = 0, endMs = 4200),
            AssemblySentenceTiming(sid = 2, startMs = 4450, endMs = 6100)
        )
        val (shifted, duration) = EncoderOffset.applyToTimings(timings, 6600, 0)
        assertEquals(timings, shifted)
        assertEquals(6600, duration)
    }

    @Test
    fun offset_nonzeroShiftsEveryTimingAndDuration() {
        val timings = listOf(
            AssemblySentenceTiming(sid = 1, startMs = 0, endMs = 4200),
            AssemblySentenceTiming(sid = 2, startMs = 4450, endMs = 6100)
        )
        val (shifted, duration) = EncoderOffset.applyToTimings(timings, 6600, 25)
        assertEquals(25, shifted[0].startMs)
        assertEquals(4225, shifted[0].endMs)
        assertEquals(4475, shifted[1].startMs)
        assertEquals(6125, shifted[1].endMs)
        assertEquals(6625, duration)
        assertEquals(
            shifted[1].startMs - shifted[0].endMs,
            timings[1].startMs - timings[0].endMs
        )
    }

    @Test
    fun offset_configCarriesIntoEncodedFile() {
        val encoder = FakeAudioEncoder(chapterNumber = 1, config = EncoderConfig(encoderOffsetMs = 25))
        encoder.writePcm(FloatArray(2400) { 0.1f })
        val done = encoder.finish(2400L, AssemblyMath.msForSamples(2400L))
        assertEquals(25, done.encoderOffsetMs)
    }

    // Temp naming plus atomic rename.

    @Test
    fun files_tmpNamingAndChapterName() {
        assertEquals("audio/ch001.m4a.tmp", EncoderFiles.tmpPathFor("audio/ch001.m4a"))
        assertEquals("ch001.m4a", EncoderFiles.chapterFileName(1))
        assertEquals("ch012.m4a", EncoderFiles.chapterFileName(12))
    }

    @Test
    fun files_atomicRenameRetriesThenSucceeds() {
        val io = MemEncoderFileIo(renameScript = ArrayDeque(listOf(false, false, true)))
        io.files.add("audio/ch001.m4a.tmp")
        val result = EncoderFiles.atomicRename(
            "audio/ch001.m4a.tmp",
            "audio/ch001.m4a",
            io,
            sleeper = {}
        )
        assertTrue("rename must succeed, got $result", result.isSuccess)
        assertEquals(3, io.renameCalls)
        assertTrue(io.files.contains("audio/ch001.m4a"))
    }

    @Test
    fun files_atomicRenameExhaustedFailsShaped() {
        val io = MemEncoderFileIo(renameScript = ArrayDeque(MutableList(40) { false }))
        io.files.add("audio/ch001.m4a.tmp")
        val result = EncoderFiles.atomicRename(
            "audio/ch001.m4a.tmp",
            "audio/ch001.m4a",
            io,
            sleeper = {}
        )
        assertTrue("rename must fail, got $result", result.isFailure)
        assertEquals(EncoderFiles.WRITE_RETRIES, io.renameCalls)
    }

    // Assembly-to-encoder path: streams without holding the chapter.

    private fun twoSentenceBlocks(): List<AssemblyBlockMeta> = listOf(
        AssemblyBlockMeta(
            id = 1,
            type = "para",
            sentences = listOf(
                AssemblySentenceMeta(
                    sid = 1,
                    role = "narrator",
                    nativeRateHz = 24000,
                    nativeSamplesExpected = 2400,
                    splitPair = null
                ),
                AssemblySentenceMeta(
                    sid = 2,
                    role = "dialogue",
                    nativeRateHz = 24000,
                    nativeSamplesExpected = 1200,
                    splitPair = null
                )
            )
        )
    )

    @Test
    fun bridge_streamsAssemblyIntoEncoder() {
        val pcm = mapOf(
            1 to FloatArray(2400) { 0.3f },
            2 to FloatArray(1200) { 0.4f }
        )
        val encoder = FakeAudioEncoder(chapterNumber = 6)
        val (assembled, encoded) = ChapterEncode.encode(
            chapterNumber = 6,
            blocks = twoSentenceBlocks(),
            pcmFor = { meta -> pcm[meta.sid] ?: error("no pcm sid ${meta.sid}") },
            encoder = encoder
        )
        assertEquals(assembled.sampleCount, encoded.sampleCount)
        assertEquals(assembled.durationMs, encoded.durationMs)
        assertEquals("audio/ch006.m4a", encoded.filePath)
        assertTrue(
            "assembly must stream sentence by sentence, got ${encoder.chunkSizes}",
            encoder.chunkSizes.size > 1
        )
        assertEquals(assembled.sampleCount, encoder.chunkSizes.sum().toLong())
        assertTrue(encoder.eosSignaled)
    }

    @Test
    fun bridge_abortsEncoderOnAssemblyFailure() {
        val bad = listOf(
            AssemblyBlockMeta(id = 1, type = "para", sentences = emptyList())
        )
        val encoder = FakeAudioEncoder(chapterNumber = 6)
        try {
            ChapterEncode.encode(
                chapterNumber = 6,
                blocks = bad,
                pcmFor = { FloatArray(10) },
                encoder = encoder
            )
            fail("empty assembly must fail")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue("encoder must abort, temp dropped", encoder.aborted)
        assertTrue("no file must finish", encoder.finishedFile == null)
    }

    @Test
    fun bridge_rejectsWrongEncoderRate() {
        val encoder = FakeAudioEncoder(chapterNumber = 6, config = EncoderConfig())
        val offRate = object : AudioEncoder by encoder {
            override val sampleRateHz: Int = 16000
        }
        try {
            ChapterEncode.encode(
                chapterNumber = 6,
                blocks = twoSentenceBlocks(),
                pcmFor = { FloatArray(2400) },
                encoder = offRate
            )
            fail("wrong encoder rate must fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("chapter 6"))
        }
    }

    // PCM-16 conversion for codec input.

    @Test
    fun pcm16_encodesClampedLittleEndian() {
        val bytes = Pcm16.encodeFloat(floatArrayOf(0f, 1f, -1f, 1.5f, -2f))
        assertEquals(10, bytes.size)
        fun codeAt(index: Int): Int {
            val low = bytes[index * 2].toInt() and 0xFF
            val high = bytes[index * 2 + 1].toInt()
            return (high shl 8) or low
        }
        assertEquals(0, codeAt(0))
        assertEquals(32767, codeAt(1))
        assertEquals(-32767, codeAt(2))
        assertEquals(32767, codeAt(3))
        assertEquals(-32767, codeAt(4))
    }
}
