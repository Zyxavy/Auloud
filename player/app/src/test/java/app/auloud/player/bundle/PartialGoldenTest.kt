package app.auloud.player.bundle

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice 6 UI1 (spec v1.2, D7): range-bundle contract
 * (`spec/fixtures/partial-golden/`). A 2-chapter range (source 2-3)
 * renumbered 1-2 with `source_index`, range-aware id, spec "1.2".
 * The Player ignores `source_index` (`ignoreUnknownKeys`) and reads the
 * renumbered chapters as text — guards format drift from the Player side
 * (Scribe pins the same fixture with the real `validate_bundle`).
 *
 * API 24 safe: `java.io.File` only. No Robolectric, no new dependencies.
 */
class PartialGoldenTest {

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

    @Test
    fun partialGolden_parsesAsTextWithRangeId() {
        val dir = fixtureDir("partial-golden")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val result = BundleParser.parse(dir)
        assertTrue("expected success but got: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val manifest = result.getOrThrow()
        assertEquals("1.2", manifest.specVersion)
        assertEquals("53a97272-f146-524b-94e2-3aee0d397b2f", manifest.id)
        assertEquals(2, manifest.chapters.size)
        assertEquals(1, manifest.chapters[0].index)
        assertEquals(2, manifest.chapters[1].index)
    }

    @Test
    fun partialGolden_passesValidation() {
        val dir = fixtureDir("partial-golden")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val manifest = BundleParser.parse(dir).getOrThrow()
        val errors = BundleValidator.validate(dir, manifest)
        assertTrue("expected no errors, got: $errors", errors.isEmpty())
        val combined = BundleValidator.validateBundle(dir)
        assertTrue("expected no errors, got: $combined", combined.isEmpty())
    }

    @Test
    fun partialGolden_chaptersLoadAsText() {
        val dir = fixtureDir("partial-golden")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val manifest = BundleParser.parse(dir).getOrThrow()
        for (chapter in manifest.chapters) {
            val raw = File(dir, chapter.text).readText(Charsets.UTF_8)
            val parsed = ChapterTextLoader.parse(chapter.text, raw)
            assertTrue(
                "chapter ${chapter.index} must load as text: " +
                    "${parsed.exceptionOrNull()?.message}",
                parsed.isSuccess
            )
        }
    }

    @Test
    fun spec12_acceptedAndUnknownFieldsIgnored() {
        // source_index is unknown to the Player models: parsing must ignore
        // it (ignoreUnknownKeys) rather than fail.
        val manifestPayload = """{"spec_version":"1.2","id":"x","title":"T",
            "type":"epub","audio":{"format":"mp3","channels":1,"sample_rate":24000,
            "bitrate_kbps":64,"cbr":true},"chapters":[{"index":1,"title":"C",
            "audio":"audio/ch001.mp3","text":"text/ch001.json","duration_ms":1000,
            "source_index":3}]}"""
        val parsed = BundleParser.parseText(manifestPayload)
        assertTrue(parsed.isSuccess)
        assertEquals("1.2", parsed.getOrThrow().specVersion)
        val chapterPayload = """{"spec_version":"1.2","chapter":1,"title":"C",
            "duration_ms":1000,"source_index":3,"blocks":[{"id":1,"type":"para",
            "sentences":[{"sid":1,"speaker":"narrator","start_ms":0,
            "end_ms":1000,"text":"Hi. "}]}]}"""
        val chapter = ChapterTextLoader.parse("text/ch001.json", chapterPayload)
        assertTrue(chapter.isSuccess)
    }
}
