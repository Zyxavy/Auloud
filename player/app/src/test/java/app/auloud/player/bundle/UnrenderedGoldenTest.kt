package app.auloud.player.bundle

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IN1: unrendered-golden contract (`spec/fixtures/unrendered-golden/`).
 *
 * Mirrors Scribe's `test_unrendered_golden.py` from the Player side with the
 * real [BundleParser], [BundleValidator] and [ChapterTextLoader]: the golden
 * validates and loads, a rendered 1.x bundle still loads, and invalid 2.0
 * combinations are rejected naming the file and the rule. Together with the
 * Scribe test this catches drift between the halves.
 *
 * API 24 safe: `java.io.File` only. No Robolectric, no new dependencies.
 */
class UnrenderedGoldenTest {

    private fun fixtureDir(name: String): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val direct = File(userDir, "../../spec/fixtures/$name")
        if (direct.isDirectory) return direct
        var cur: File? = userDir
        while (cur != null) {
            val candidate = File(cur, "spec/fixtures/$name")
            if (candidate.isDirectory) return candidate
            cur = cur.parentFile
        }
        return direct
    }

    private fun goldenManifest(): Manifest {
        val dir = fixtureDir("unrendered-golden")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val parsed = BundleParser.parse(dir)
        assertTrue(
            "expected parse success but got: ${parsed.exceptionOrNull()?.message}",
            parsed.isSuccess
        )
        return parsed.getOrThrow()
    }

    @Test
    fun golden_parsesWithExpectedIdentity() {
        val manifest = goldenManifest()
        assertEquals("2.0", manifest.specVersion)
        assertEquals("none", manifest.renderState)
        assertEquals("f8be80a6-3e94-56fa-9fb7-c88444bf239d", manifest.id)
        assertEquals("Unrendered Golden", manifest.title)
        assertEquals("epub", manifest.type)
        assertNull("none books carry no audio object", manifest.audio)
        assertTrue(
            "voices must hold both reserved keys, got: ${manifest.voices.keys}",
            manifest.voices.keys.containsAll(listOf("narrator", "dialogue"))
        )
        assertEquals(2, manifest.chapters.size)
        manifest.chapters.forEachIndexed { pos, chapter ->
            assertEquals(pos + 1, chapter.index)
            assertTrue("chapter ${chapter.index} audio must be blank", chapter.audio.isBlank())
            assertNull("chapter ${chapter.index} duration must be absent", chapter.durationMs)
        }
        assertEquals("text/ch001.json", manifest.chapters[0].text)
        assertEquals("text/ch002.json", manifest.chapters[1].text)
    }

    @Test
    fun golden_passesValidation() {
        val dir = fixtureDir("unrendered-golden")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val manifest = BundleParser.parse(dir).getOrThrow()
        val errors = BundleValidator.validate(dir, manifest)
        assertTrue("expected no errors, got: $errors", errors.isEmpty())
        val combined = BundleValidator.validateBundle(dir)
        assertTrue("expected no errors, got: $combined", combined.isEmpty())
    }

    @Test
    fun golden_chaptersLoadWithoutTimings() {
        val dir = fixtureDir("unrendered-golden")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val manifest = BundleParser.parse(dir).getOrThrow()
        var total = 0
        for (chapter in manifest.chapters) {
            val raw = File(dir, chapter.text).readText(Charsets.UTF_8)
            val parsed = ChapterTextLoader.parse(chapter.text, raw)
            assertTrue(
                "expected ${chapter.text} to load but got: ${parsed.exceptionOrNull()?.message}",
                parsed.isSuccess
            )
            val loaded = parsed.getOrThrow()
            assertEquals("2.0", loaded.specVersion)
            assertNull("${chapter.text}: duration must be absent", loaded.durationMs)
            val sentences = loaded.sentencesInOrder()
            assertTrue("${chapter.text}: expected sentences", sentences.isNotEmpty())
            assertEquals(
                "${chapter.text}: sids must run 1..N",
                (1..sentences.size).toList(),
                sentences.map { it.sid }
            )
            for (sentence in sentences) {
                assertTrue(
                    "${chapter.text} sid ${sentence.sid}: speaker ${sentence.speaker} not reserved",
                    sentence.speaker == "narrator" || sentence.speaker == "dialogue"
                )
                assertNull("${chapter.text} sid ${sentence.sid}: start must be absent", sentence.startMs)
                assertNull("${chapter.text} sid ${sentence.sid}: end must be absent", sentence.endMs)
            }
            total += sentences.size
        }
        assertEquals("golden holds 7 sentences across 2 chapters", 7, total)
    }

    @Test
    fun rendered1xBundle_stillLoads() {
        val dir = fixtureDir("valid-bundle")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val manifest = BundleParser.parse(dir).getOrThrow()
        assertEquals("1.0", manifest.specVersion)
        assertNull("1.x manifests carry no render_state", manifest.renderState)
        val errors = BundleValidator.validate(dir, manifest)
        assertTrue("expected no errors, got: $errors", errors.isEmpty())
        val raw = File(dir, manifest.chapters[0].text).readText(Charsets.UTF_8)
        val chapter = ChapterTextLoader.parse(manifest.chapters[0].text, raw).getOrThrow()
        assertTrue(
            "rendered chapter must carry timings",
            chapter.sentencesInOrder().all { it.startMs != null && it.endMs != null }
        )
    }

    private fun manifestErrors(manifest: Manifest): List<String> {
        val dir = fixtureDir("unrendered-golden")
        return BundleValidator.validate(dir.path, manifest, exists = { true })
    }

    @Test
    fun missingRenderState_rejectedWithFileAndRule() {
        val errors = manifestErrors(goldenManifest().copy(renderState = null))
        assertTrue("expected render_state error, got: $errors", errors.isNotEmpty())
        val joined = errors.joinToString("\n")
        assertTrue(joined.contains("manifest.json"))
        assertTrue(joined.contains("render_state"))
    }

    @Test
    fun badRenderStateValue_rejectedWithFileAndRule() {
        val errors = manifestErrors(goldenManifest().copy(renderState = "half"))
        assertTrue("expected render_state error, got: $errors", errors.isNotEmpty())
        val joined = errors.joinToString("\n")
        assertTrue(joined.contains("manifest.json"))
        assertTrue(joined.contains("half"))
    }

    @Test
    fun renderStateMismatch_rejectedWithFileAndRule() {
        val errors = manifestErrors(goldenManifest().copy(renderState = "complete"))
        assertTrue("expected complete-mismatch error, got: $errors", errors.isNotEmpty())
        val joined = errors.joinToString("\n")
        assertTrue(joined.contains("manifest.json"))
        assertTrue(joined.contains("complete"))
    }

    @Test
    fun audioWithoutDuration_rejectedWithFileAndRule() {
        val manifest = goldenManifest()
        val bad = manifest.copy(
            chapters = listOf(
                manifest.chapters[0].copy(audio = "audio/ch001.mp3"),
                manifest.chapters[1]
            )
        )
        val errors = manifestErrors(bad)
        assertTrue("expected audio-without-duration error, got: $errors", errors.isNotEmpty())
        val joined = errors.joinToString("\n")
        assertTrue(joined.contains("manifest.json"))
        assertTrue(joined.contains("audio without duration_ms"))
    }

    @Test
    fun audioObjectWithNone_rejectedWithFileAndRule() {
        val bad = goldenManifest().copy(audio = AudioInfo())
        val errors = manifestErrors(bad)
        assertTrue("expected audio-with-none error, got: $errors", errors.isNotEmpty())
        val joined = errors.joinToString("\n")
        assertTrue(joined.contains("manifest.json"))
        assertTrue(joined.contains("audio"))
    }

    @Test
    fun timingsInUnrenderedChapter_rejectedWithFileAndRule() {
        val payload = """{"spec_version": "2.0", "chapter": 1, "title": "Ch",
            "blocks": [{"id": 1, "type": "para",
            "sentences": [{"sid": 1, "speaker": "narrator",
            "start_ms": 0, "end_ms": 100, "text": "Hi. "}]}]}"""
        val result = ChapterTextLoader.parse("text/ch001.json", payload)
        assertTrue("expected timings error", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(message.contains("text/ch001.json"))
        assertTrue(message.contains("timings"))
    }

    @Test
    fun foreignSpeaker_rejectedWithFileAndRule() {
        val manifest = goldenManifest()
        val entry = manifest.chapters[0]
        val payload = """{"spec_version": "2.0", "chapter": 1, "title": "Ch",
            "blocks": [{"id": 1, "type": "para",
            "sentences": [{"sid": 1, "speaker": "Ana", "text": "Hi. "}]}]}"""
        val chapter = ChapterTextLoader.parse(entry.text, payload).getOrThrow()
        val problems = BundleValidator.validateAgainstManifest(manifest, entry, chapter)
        assertTrue("expected speaker error, got: $problems", problems.isNotEmpty())
        val joined = problems.joinToString("\n")
        assertTrue(joined.contains(entry.text))
        assertTrue(joined.contains("Ana"))
    }

    @Test
    fun unknownSpecVersion_rejectedWithFileAndRule() {
        val errors = manifestErrors(goldenManifest().copy(specVersion = "3.0"))
        assertTrue("expected spec_version error, got: $errors", errors.isNotEmpty())
        val joined = errors.joinToString("\n")
        assertTrue(joined.contains("manifest.json"))
        assertTrue(joined.contains("3.0"))
    }
}
