package app.auloud.player.bundle

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP2: parser verifies for Slice 1 WP2 (valid, missing field, bad JSON, unknown keys). */
class BundleParserTest {

    private fun fixtureDir(name: String): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        // Task spec: fixtures load via ../../spec/fixtures from the player/app module dir.
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
    fun validManifest_parses() {
        val dir = fixtureDir("valid-bundle")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val result = BundleParser.parse(dir)
        assertTrue("expected success but got: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val manifest = result.getOrThrow()
        assertEquals("1.0", manifest.specVersion)
        assertEquals("8f0c6c1e-3a8f-4c6e-9d54-0b6a3f1a2b77", manifest.id)
        assertEquals("Example Novel", manifest.title)
        assertEquals("epub", manifest.type)
        assertEquals(2, manifest.chapters.size)
        assertEquals(1, manifest.chapters[0].index)
        assertEquals("audio/ch001.mp3", manifest.chapters[0].audio)
        assertEquals("text/ch001.json", manifest.chapters[0].text)
        assertEquals(1832400L, manifest.chapters[0].durationMs)
    }

    @Test
    fun missingRequiredField_fails() {
        val dir = fixtureDir("missing-field")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val result = BundleParser.parse(dir)
        assertTrue("expected parse failure for missing required field", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue("error must name manifest.json, got: $message", message.contains("manifest.json"))
    }

    @Test
    fun malformedJson_fails() {
        val dir = fixtureDir("bad-json")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val result = BundleParser.parse(dir)
        assertTrue("expected parse failure for malformed JSON", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue("error must name manifest.json, got: $message", message.contains("manifest.json"))
    }

    @Test
    fun extraUnknownKeys_areIgnored() {
        val dir = fixtureDir("valid-bundle")
        val original = File(dir, "manifest.json").readText(Charsets.UTF_8)
        // Inject unknown top-level, audio-level and chapter-level keys.
        val withUnknown = original.replace(
            "\"generator\": \"scribe 0.1.0\"",
            "\"generator\": \"scribe 0.1.0\", \"future_field\": 123, \"another_unknown\": {\"nested\": true}"
        ).replace(
            "\"bitrate_kbps\": 64",
            "\"bitrate_kbps\": 64, \"v2_field\": \"x\""
        ).replace(
            "\"duration_ms\": 1832400",
            "\"duration_ms\": 1832400, \"unknown_chapter_flag\": true"
        )
        val result = BundleParser.parseText(withUnknown)
        assertTrue(
            "unknown keys must be ignored, got: ${result.exceptionOrNull()?.message}",
            result.isSuccess
        )
        assertEquals("Example Novel", result.getOrThrow().title)
    }
}
