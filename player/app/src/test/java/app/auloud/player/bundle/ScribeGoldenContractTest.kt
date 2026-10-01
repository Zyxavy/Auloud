package app.auloud.player.bundle

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SW10: golden contract bundle (`spec/fixtures/scribe-golden/`) built by the
 * real Scribe pipeline with a fake sine synth. Parses and validates it with
 * the real [BundleParser] + [BundleValidator] — guards format drift from the
 * Player side (Scribe pins the same fixture with the real `validate_bundle`).
 *
 * API 24 safe: `java.io.File` only. No Robolectric, no new dependencies.
 */
class ScribeGoldenContractTest {

    private fun fixtureDir(name: String): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        // Same relative-path pattern as the WP2/WP4 tests: the module dir is
        // player/app, so ../../spec/fixtures from there.
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
    fun scribeGolden_parsesWithExpectedIdentity() {
        val dir = fixtureDir("scribe-golden")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val result = BundleParser.parse(dir)
        assertTrue("expected success but got: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val manifest = result.getOrThrow()
        assertEquals("1.0", manifest.specVersion)
        assertEquals("6d23921c-b53a-5429-964c-05c42413ce93", manifest.id)
        assertEquals("Scribe Golden Bundle", manifest.title)
        assertEquals("epub", manifest.type)
        // Contract pins the writer's audio declaration (SW7/SW8 output shape).
        assertEquals("mp3", manifest.audio.format)
        assertEquals(1, manifest.audio.channels)
        assertEquals(24000, manifest.audio.sampleRate)
        assertEquals(64, manifest.audio.bitrateKbps)
        assertEquals(true, manifest.audio.cbr)
        // Two chapters, exact stems and durations from the golden build.
        assertEquals(2, manifest.chapters.size)
        manifest.chapters.forEachIndexed { pos, chapter ->
            val index = pos + 1
            val stem = "ch%03d".format(index)
            assertEquals(index, chapter.index)
            assertEquals("audio/$stem.mp3", chapter.audio)
            assertEquals("text/$stem.json", chapter.text)
            assertEquals(7450L, chapter.durationMs)
        }
    }

    @Test
    fun scribeGolden_referencedFilesExist() {
        val dir = fixtureDir("scribe-golden")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val manifest = BundleParser.parse(dir).getOrThrow()
        for (chapter in manifest.chapters) {
            val audio = File(dir, chapter.audio)
            val text = File(dir, chapter.text)
            assertTrue("audio missing: ${audio.path}", audio.isFile && audio.length() > 0)
            assertTrue("text missing: ${text.path}", text.isFile && text.length() > 0)
        }
    }

    @Test
    fun scribeGolden_passesValidation() {
        val dir = fixtureDir("scribe-golden")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val manifest = BundleParser.parse(dir).getOrThrow()
        val errors = BundleValidator.validate(dir, manifest)
        assertTrue("expected no errors, got: $errors", errors.isEmpty())
        // Combined parse+validate path used at import must agree.
        val combined = BundleValidator.validateBundle(dir)
        assertTrue("expected no errors, got: $combined", combined.isEmpty())
    }
}
