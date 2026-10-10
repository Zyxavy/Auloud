package app.auloud.player.render

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN5: shared timing vectors (Slice 10 RN2, D-094 plus D-103).
 *
 * Replays all 10 cases from
 * `spec/fixtures/timing-vectors/timing-vectors.json` through the Kotlin
 * assembly: native dummy PCM of the vector length at the vector rate is
 * resampled with [AssemblyMath.resampleMono] (committed length must equal
 * `resampled_samples`, proving mixed-rate arithmetic), then assembled
 * with pauses from the identical table. Expected `start_ms`, `end_ms`,
 * `pause_after_ms` gaps, chapter `duration_ms` and `sample_count` must
 * match exactly. Audio values are arbitrary per the vector README (only
 * lengths affect timings), so the dummy is constant 0.5.
 *
 * Round-half-even is the load-bearing check: `Math.round` (half-up)
 * fails the `rounding-half-even` case, `Math.rint` (half-even) passes.
 * No tablet claimed: file access is plain `java.io.File` with the same
 * walk-up pattern as the ingest parity tests.
 */
class ChapterAssemblyVectorsTest {

    private data class VectorSentence(
        val sid: Int,
        val speaker: String,
        val sampleRate: Int,
        val samples: Int,
        val resampledSamples: Int,
        val splitPair: Int?
    )

    private data class VectorBlock(
        val id: Int,
        val type: String,
        val sentences: List<VectorSentence>
    )

    private data class VectorTiming(
        val sid: Int,
        val startMs: Int,
        val endMs: Int,
        val pauseAfterMs: Int
    )

    private data class VectorCase(
        val id: String,
        val blocks: List<VectorBlock>,
        val timings: List<VectorTiming>,
        val durationMs: Int,
        val sampleCount: Long
    )

    private fun repoFixturesDir(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val direct = File(userDir, "../../spec/fixtures")
        if (direct.isDirectory) return direct
        var cur: File? = userDir
        while (cur != null) {
            val candidate = File(cur, "spec/fixtures")
            if (candidate.isDirectory) return candidate
            cur = cur.parentFile
        }
        return direct
    }

    private fun loadCases(): List<VectorCase> {
        val path = File(repoFixturesDir(), "timing-vectors/timing-vectors.json")
        assertTrue("vectors missing: ${path.path}", path.isFile)
        val root = Json.parseToJsonElement(path.readText(Charsets.UTF_8)).jsonObject
        assertEquals(24000, root["bundle_rate_hz"]!!.jsonPrimitive.int)
        return root["cases"]!!.jsonArray.map { entry ->
            val obj = entry.jsonObject
            val blocks = obj["blocks"]!!.jsonArray.map { blockEntry ->
                val blockObj = blockEntry.jsonObject
                VectorBlock(
                    id = blockObj["id"]!!.jsonPrimitive.int,
                    type = blockObj["type"]!!.jsonPrimitive.content,
                    sentences = blockObj["sentences"]!!.jsonArray.map { sentenceEntry ->
                        val s = sentenceEntry.jsonObject
                        val rawSplit = s["split_pair"]!!
                        VectorSentence(
                            sid = s["sid"]!!.jsonPrimitive.int,
                            speaker = s["speaker"]!!.jsonPrimitive.content,
                            sampleRate = s["sample_rate"]!!.jsonPrimitive.int,
                            samples = s["samples"]!!.jsonPrimitive.int,
                            resampledSamples = s["resampled_samples"]!!.jsonPrimitive.int,
                            splitPair = try {
                                rawSplit.jsonPrimitive.int
                            } catch (_: Exception) {
                                null
                            }
                        )
                    }
                )
            }
            val expected = obj["expected"]!!.jsonObject
            VectorCase(
                id = obj["id"]!!.jsonPrimitive.content,
                blocks = blocks,
                timings = expected["timings"]!!.jsonArray.map { timingEntry ->
                    val t = timingEntry.jsonObject
                    VectorTiming(
                        sid = t["sid"]!!.jsonPrimitive.int,
                        startMs = t["start_ms"]!!.jsonPrimitive.int,
                        endMs = t["end_ms"]!!.jsonPrimitive.int,
                        pauseAfterMs = t["pause_after_ms"]!!.jsonPrimitive.int
                    )
                },
                durationMs = expected["duration_ms"]!!.jsonPrimitive.int,
                sampleCount = expected["sample_count"]!!.jsonPrimitive.int.toLong()
            )
        }
    }

    private fun assembleCase(vector: VectorCase): AssembledChapterResult {
        val blocks = vector.blocks.map { block ->
            AssemblyBlockMeta(
                id = block.id,
                type = block.type,
                sentences = block.sentences.map { sentence ->
                    AssemblySentenceMeta(
                        sid = sentence.sid,
                        role = sentence.speaker,
                        nativeRateHz = sentence.sampleRate,
                        nativeSamplesExpected = sentence.samples,
                        splitPair = sentence.splitPair
                    )
                }
            )
        }
        val pcmBySid = vector.blocks.flatMap { it.sentences }.associate { sentence ->
            sentence.sid to FloatArray(sentence.samples) { 0.5f }
        }
        // Committed resample lengths must match Scribe exactly first.
        for (sentence in vector.blocks.flatMap { it.sentences }) {
            val got = AssemblyMath.resampledLength(sentence.samples, sentence.sampleRate)
            assertEquals(
                "case ${vector.id} sid ${sentence.sid}: resampled length",
                sentence.resampledSamples, got
            )
        }
        val sink = CollectingEncoderSink()
        return ChapterAssembler.assemble(
            chapterNumber = 1,
            blocks = blocks,
            pcmFor = { meta -> pcmBySid[meta.sid] ?: error("no pcm for sid ${meta.sid}") },
            bookGainsLinear = emptyMap(),
            sink = sink
        )
    }

    private fun checkCase(vector: VectorCase) {
        val result = assembleCase(vector)
        assertEquals("case ${vector.id}: duration", vector.durationMs, result.durationMs)
        assertEquals("case ${vector.id}: sample_count", vector.sampleCount, result.sampleCount)
        assertEquals(
            "case ${vector.id}: sentence count",
            vector.timings.size, result.timings.size
        )
        for (index in vector.timings.indices) {
            val want = vector.timings[index]
            val got = result.timings[index]
            assertEquals("case ${vector.id} sid ${want.sid}: sid", want.sid, got.sid)
            assertEquals("case ${vector.id} sid ${want.sid}: start", want.startMs, got.startMs)
            assertEquals("case ${vector.id} sid ${want.sid}: end", want.endMs, got.endMs)
            val wantGap = want.pauseAfterMs
            val gotGap = if (index + 1 < result.timings.size) {
                result.timings[index + 1].startMs - got.endMs
            } else {
                result.durationMs - got.endMs
            }
            assertEquals("case ${vector.id} sid ${want.sid}: pause_after", wantGap, gotGap)
        }
    }

    @Test
    fun vectors_allTenCasesPass() {
        val cases = loadCases()
        assertEquals("vector case count", 10, cases.size)
        for (vector in cases) checkCase(vector)
    }

    @Test
    fun vectors_sentencePause() = checkCase(loadCases().first { it.id == "sentence-pause" })

    @Test
    fun vectors_paraFinal() = checkCase(loadCases().first { it.id == "para-final" })

    @Test
    fun vectors_headingPause() = checkCase(loadCases().first { it.id == "heading-pause" })

    @Test
    fun vectors_quoteLikePara() = checkCase(loadCases().first { it.id == "quote-like-para" })

    @Test
    fun vectors_breakMid() = checkCase(loadCases().first { it.id == "break-mid" })

    @Test
    fun vectors_breakTrailing() = checkCase(loadCases().first { it.id == "break-trailing" })

    @Test
    fun vectors_leadingBreakDrop() = checkCase(loadCases().first { it.id == "leading-break-drop" })

    @Test
    fun vectors_tagSplit() = checkCase(loadCases().first { it.id == "tag-split" })

    @Test
    fun vectors_mixedRates() = checkCase(loadCases().first { it.id == "mixed-rates" })

    @Test
    fun vectors_roundingHalfEven() = checkCase(loadCases().first { it.id == "rounding-half-even" })
}
