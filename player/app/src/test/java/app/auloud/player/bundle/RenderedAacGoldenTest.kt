package app.auloud.player.bundle

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN1: spec 2.0 part 2 contract (`spec/fixtures/rendered-aac-golden/`
 * and `spec/fixtures/partial-aac-golden/`).
 *
 * Mirrors Scribe's `test_render_aac_golden.py` from the Player side with
 * the real [BundleParser], [BundleValidator] and [ChapterTextLoader]:
 * the AAC goldens parse, validate and load; a Scribe-rendered MP3 book
 * still loads untouched; and invalid part-2 combinations are rejected
 * naming the file and the rule. Together with the Scribe test this
 * catches drift between the halves.
 *
 * API 24 safe: `java.io.File` only. No Robolectric, no new dependencies.
 */
class RenderedAacGoldenTest {

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

    private fun goldenManifest(name: String): Manifest {
        val dir = fixtureDir(name)
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val parsed = BundleParser.parse(dir)
        assertTrue(
            "expected parse success but got: ${parsed.exceptionOrNull()?.message}",
            parsed.isSuccess
        )
        return parsed.getOrThrow()
    }

    private fun manifestErrors(manifest: Manifest, name: String): List<String> {
        val dir = fixtureDir(name)
        return BundleValidator.validate(dir.path, manifest, exists = { true })
    }

    @Test
    fun renderedGolden_parsesWithExpectedIdentity() {
        val manifest = goldenManifest("rendered-aac-golden")
        assertEquals("2.0", manifest.specVersion)
        assertEquals("complete", manifest.renderState)
        assertEquals("Rendered AAC Golden", manifest.title)
        assertEquals("m4a", manifest.audio?.format)
        assertEquals(2, manifest.chapters.size)
        manifest.chapters.forEach { chapter ->
            assertTrue(
                "chapter ${chapter.index} audio must be .m4a, got: ${chapter.audio}",
                chapter.audio.endsWith(".m4a")
            )
            assertTrue(
                "chapter ${chapter.index} must carry duration",
                (chapter.durationMs ?: 0L) > 0L
            )
            assertTrue(
                "chapter ${chapter.index} must carry a fingerprint",
                chapter.renderFingerprint != null
            )
        }
        assertTrue("gain_db must be present", manifest.gainDb != null)
        assertTrue("encoder_offset_ms must be present", manifest.encoderOffsetMs != null)
    }

    @Test
    fun renderedGolden_passesValidation() {
        val dir = fixtureDir("rendered-aac-golden")
        val manifest = BundleParser.parse(dir).getOrThrow()
        val errors = BundleValidator.validate(dir, manifest)
        assertTrue("expected no errors, got: $errors", errors.isEmpty())
        val combined = BundleValidator.validateBundle(dir)
        assertTrue("expected no errors, got: $combined", combined.isEmpty())
    }

    @Test
    fun renderedGolden_chaptersLoadWithTimings() {
        val dir = fixtureDir("rendered-aac-golden")
        val manifest = BundleParser.parse(dir).getOrThrow()
        val expectedDurations = listOf(4000L, 3000L)
        var total = 0
        manifest.chapters.forEachIndexed { pos, chapter ->
            val raw = File(dir, chapter.text).readText(Charsets.UTF_8)
            val loaded = ChapterTextLoader.parse(chapter.text, raw).getOrThrow()
            assertEquals("2.0", loaded.specVersion)
            assertEquals(expectedDurations[pos], loaded.durationMs)
            assertEquals(expectedDurations[pos], chapter.durationMs)
            val sentences = loaded.sentencesInOrder()
            assertTrue("${chapter.text}: expected sentences", sentences.isNotEmpty())
            assertTrue(
                "${chapter.text}: every sentence timed",
                sentences.all { it.startMs != null && it.endMs != null }
            )
            assertEquals("${chapter.text}: first start must be 0", 0L, sentences.first().startMs)
            total += sentences.size
        }
        assertEquals("rendered golden holds 7 sentences across 2 chapters", 7, total)
    }

    @Test
    fun partialGolden_parsesAndValidates() {
        val dir = fixtureDir("partial-aac-golden")
        val manifest = BundleParser.parse(dir).getOrThrow()
        assertEquals("2.0", manifest.specVersion)
        assertEquals("partial", manifest.renderState)
        assertEquals("m4a", manifest.audio?.format)
        val errors = BundleValidator.validate(dir, manifest)
        assertTrue("expected no errors, got: $errors", errors.isEmpty())
    }

    @Test
    fun partialGolden_renderedChapterTimedAndUnrenderedUntimed() {
        val dir = fixtureDir("partial-aac-golden")
        val manifest = BundleParser.parse(dir).getOrThrow()
        val (rendered, unrendered) = manifest.chapters
        assertTrue(rendered.audio.endsWith(".m4a"))
        assertEquals(3000L, rendered.durationMs)
        assertTrue(unrendered.audio.isBlank())
        assertNull(unrendered.durationMs)
        val timedRaw = File(dir, rendered.text).readText(Charsets.UTF_8)
        val timed = ChapterTextLoader.parse(rendered.text, timedRaw).getOrThrow()
        assertEquals(3000L, timed.durationMs)
        assertTrue(timed.sentencesInOrder().all { it.startMs != null && it.endMs != null })
        val untimedRaw = File(dir, unrendered.text).readText(Charsets.UTF_8)
        val untimed = ChapterTextLoader.parse(unrendered.text, untimedRaw).getOrThrow()
        assertNull(untimed.durationMs)
        assertTrue(untimed.sentencesInOrder().all { it.startMs == null && it.endMs == null })
    }

    @Test
    fun scribeMp3Books_stillLoadUntouched() {
        for (name in listOf("valid-bundle", "scribe-golden")) {
            val dir = fixtureDir(name)
            assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
            val manifest = BundleParser.parse(dir).getOrThrow()
            assertTrue("1.x manifests carry no render_state", manifest.renderState == null)
            assertTrue("1.x manifests carry no gain_db", manifest.gainDb == null)
            assertTrue("1.x manifests carry no offset", manifest.encoderOffsetMs == null)
            assertTrue(
                "1.x chapters carry no fingerprint",
                manifest.chapters.all { it.renderFingerprint == null }
            )
            val errors = BundleValidator.validate(dir, manifest)
            assertTrue("expected no errors for $name, got: $errors", errors.isEmpty())
            val raw = File(dir, manifest.chapters[0].text).readText(Charsets.UTF_8)
            val chapter = ChapterTextLoader.parse(manifest.chapters[0].text, raw).getOrThrow()
            assertTrue(
                "$name: rendered chapter must carry timings",
                chapter.sentencesInOrder().all { it.startMs != null && it.endMs != null }
            )
        }
    }

    @Test
    fun badAudioExtension_rejectedWithFileAndRule() {
        val manifest = goldenManifest("rendered-aac-golden")
        val bad = manifest.copy(
            chapters = listOf(
                manifest.chapters[0].copy(audio = "audio/ch001.ogg"),
                manifest.chapters[1]
            )
        )
        val errors = manifestErrors(bad, "rendered-aac-golden")
        assertTrue("expected extension error, got: $errors", errors.isNotEmpty())
        val joined = errors.joinToString("\n")
        assertTrue(joined.contains("manifest.json"))
        assertTrue(joined.contains(".mp3 or .m4a"))
    }

    @Test
    fun m4aIn1xBundle_rejectedWithFileAndRule() {
        val manifest = goldenManifest("rendered-aac-golden")
        val bad = manifest.copy(specVersion = "1.1", renderState = null)
        val errors = manifestErrors(bad, "rendered-aac-golden")
        assertTrue("expected m4a-needs-2.0 error, got: $errors", errors.isNotEmpty())
        val joined = errors.joinToString("\n")
        assertTrue(joined.contains("manifest.json"))
        assertTrue(joined.contains("m4a audio needs spec 2.0"))
    }

    @Test
    fun unknownManifestFormat_rejectedWithFileAndRule() {
        val manifest = goldenManifest("rendered-aac-golden")
        val bad = manifest.copy(audio = manifest.audio?.copy(format = "opus"))
        val errors = manifestErrors(bad, "rendered-aac-golden")
        assertTrue("expected format error, got: $errors", errors.isNotEmpty())
        val joined = errors.joinToString("\n")
        assertTrue(joined.contains("manifest.json"))
        assertTrue(joined.contains("opus"))
    }

    @Test
    fun manifestFormatMatchingNoChapter_rejectedWithFileAndRule() {
        val manifest = goldenManifest("rendered-aac-golden")
        val bad = manifest.copy(audio = manifest.audio?.copy(format = "mp3"))
        val errors = manifestErrors(bad, "rendered-aac-golden")
        assertTrue("expected agreement error, got: $errors", errors.isNotEmpty())
        val joined = errors.joinToString("\n")
        assertTrue(joined.contains("manifest.json"))
        assertTrue(joined.contains("matches no rendered chapter"))
    }

    @Test
    fun mixedMp3AndM4aManifest_accepted() {
        val manifest = goldenManifest("rendered-aac-golden")
        val mixed = manifest.copy(
            chapters = listOf(
                manifest.chapters[0],
                manifest.chapters[1].copy(audio = "audio/ch002.mp3")
            )
        )
        val errors = manifestErrors(mixed, "rendered-aac-golden")
        assertTrue("mixed MP3/M4A books are legal, got: $errors", errors.isEmpty())
    }

    @Test
    fun gainDbUnknownRole_rejectedWithFileAndRule() {
        val manifest = goldenManifest("rendered-aac-golden")
        val bad = manifest.copy(gainDb = Json.parseToJsonElement("""{"narrator": -1.5, "Ana": 0.5}"""))
        val errors = manifestErrors(bad, "rendered-aac-golden")
        assertTrue("expected gain role error, got: $errors", errors.isNotEmpty())
        val joined = errors.joinToString("\n")
        assertTrue(joined.contains("manifest.json"))
        assertTrue(joined.contains("unknown role"))
        assertTrue(joined.contains("Ana"))
    }

    @Test
    fun gainDbEmpty_rejectedWithFileAndRule() {
        val manifest = goldenManifest("rendered-aac-golden")
        val bad = manifest.copy(gainDb = Json.parseToJsonElement("""{}"""))
        val errors = manifestErrors(bad, "rendered-aac-golden")
        assertTrue("expected empty-gain error, got: $errors", errors.isNotEmpty())
        assertTrue(errors.joinToString().contains("gain_db present but empty"))
    }

    @Test
    fun gainDbStringValue_rejectedWithFileAndRule() {
        val manifest = goldenManifest("rendered-aac-golden")
        val bad = manifest.copy(gainDb = Json.parseToJsonElement("""{"narrator": "loud"}"""))
        val errors = manifestErrors(bad, "rendered-aac-golden")
        assertTrue("expected gain type error, got: $errors", errors.isNotEmpty())
        val joined = errors.joinToString("\n")
        assertTrue(joined.contains("manifest.json"))
        assertTrue(joined.contains("gain_db.narrator"))
    }

    @Test
    fun deviceFieldsIn1x_rejectedWithFileAndRule() {
        val manifest = goldenManifest("rendered-aac-golden")
        val bad = manifest.copy(specVersion = "1.1", renderState = null)
        val errors = manifestErrors(bad, "rendered-aac-golden")
        assertTrue("expected 2.0-only errors, got: $errors", errors.isNotEmpty())
        val joined = errors.joinToString("\n")
        assertTrue(joined.contains("gain_db is 2.0-only"))
        assertTrue(joined.contains("encoder_offset_ms is 2.0-only"))
        assertTrue(joined.contains("render_fingerprint") && joined.contains("2.0-only"))
    }

    @Test
    fun offsetNonInteger_rejectedWithFileAndRule() {
        val manifest = goldenManifest("rendered-aac-golden")
        for (payload in listOf(""""12"""", "1.5", "true")) {
            val bad = manifest.copy(encoderOffsetMs = Json.parseToJsonElement(payload))
            val errors = manifestErrors(bad, "rendered-aac-golden")
            assertTrue("expected offset error for $payload, got: $errors", errors.isNotEmpty())
            val joined = errors.joinToString("\n")
            assertTrue(joined.contains("manifest.json"))
            assertTrue(joined.contains("encoder_offset_ms"))
        }
    }

    @Test
    fun fingerprintOnUnrenderedChapter_rejectedWithFileAndRule() {
        val manifest = goldenManifest("partial-aac-golden")
        val rendered = manifest.chapters[0]
        val bad = manifest.copy(
            chapters = listOf(
                rendered,
                manifest.chapters[1].copy(renderFingerprint = rendered.renderFingerprint)
            )
        )
        val errors = manifestErrors(bad, "partial-aac-golden")
        assertTrue("expected fingerprint error, got: $errors", errors.isNotEmpty())
        val joined = errors.joinToString("\n")
        assertTrue(joined.contains("manifest.json"))
        assertTrue(joined.contains("without duration_ms"))
    }

    @Test
    fun fingerprintMissingRole_rejectedWithFileAndRule() {
        val manifest = goldenManifest("rendered-aac-golden")
        val bare = """{"engine": "system", "voices": {"narrator": "default"},
            "speeds": {"narrator": 1.0, "dialogue": 1.0},
            "engine_versions": {"system": "v"}}"""
        val bad = manifest.copy(
            chapters = listOf(
                manifest.chapters[0].copy(renderFingerprint = Json.parseToJsonElement(bare)),
                manifest.chapters[1]
            )
        )
        val errors = manifestErrors(bad, "rendered-aac-golden")
        assertTrue("expected fingerprint role error, got: $errors", errors.isNotEmpty())
        val joined = errors.joinToString("\n")
        assertTrue(joined.contains("manifest.json"))
        assertTrue(joined.contains("dialogue"))
    }

    @Test
    fun fingerprintBadSpeed_rejectedWithFileAndRule() {
        val manifest = goldenManifest("rendered-aac-golden")
        val bare = """{"engine": "system",
            "voices": {"narrator": "default", "dialogue": "default"},
            "speeds": {"narrator": 0, "dialogue": 1.0},
            "engine_versions": {"system": "v"}}"""
        val bad = manifest.copy(
            chapters = listOf(
                manifest.chapters[0].copy(renderFingerprint = Json.parseToJsonElement(bare)),
                manifest.chapters[1]
            )
        )
        val errors = manifestErrors(bad, "rendered-aac-golden")
        assertTrue("expected fingerprint speed error, got: $errors", errors.isNotEmpty())
        val joined = errors.joinToString("\n")
        assertTrue(joined.contains("manifest.json"))
        assertTrue(joined.contains("speed"))
    }
}
