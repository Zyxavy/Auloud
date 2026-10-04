package app.auloud.player.bundle

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP2: validator verifies for Slice 1 WP2 (MP3 existence, positive duration, required fields). */
class BundleValidatorTest {

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
    fun validBundle_passesValidation() {
        val dir = fixtureDir("valid-bundle")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val manifest = BundleParser.parse(dir).getOrThrow()
        val errors = BundleValidator.validate(dir, manifest)
        assertTrue("expected no errors, got: $errors", errors.isEmpty())
    }

    @Test
    fun missingChapterMp3_failsWithNamedError() {
        val dir = fixtureDir("missing-mp3")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val manifest = BundleParser.parse(dir).getOrThrow()
        val errors = BundleValidator.validate(dir, manifest)
        assertTrue("expected missing-MP3 error, got: $errors", errors.isNotEmpty())
        val joined = errors.joinToString("\n")
        assertTrue("error must name manifest.json, got: $joined", joined.contains("manifest.json"))
        assertTrue(
            "error must name the missing file audio/ch002.mp3, got: $joined",
            joined.contains("audio/ch002.mp3")
        )
    }

    @Test
    fun nonPositiveDuration_fails() {
        val dir = fixtureDir("valid-bundle")
        val manifest = BundleParser.parse(dir).getOrThrow()
        val bad = manifest.copy(
            chapters = listOf(manifest.chapters[0].copy(durationMs = 0))
        )
        val errors = BundleValidator.validate(dir, bad)
        assertTrue("expected duration error, got: $errors", errors.isNotEmpty())
        val joined = errors.joinToString("\n")
        assertTrue("error must name manifest.json, got: $joined", joined.contains("manifest.json"))
        assertTrue("error must mention duration_ms, got: $joined", joined.contains("duration_ms"))
    }

    @Test
    fun blankRequiredField_isFlagged() {
        val dir = fixtureDir("valid-bundle")
        val manifest = BundleParser.parse(dir).getOrThrow()
        val bad = manifest.copy(title = "  ")
        val errors = BundleValidator.validate(dir, bad)
        assertTrue("expected missing-title error, got: $errors", errors.isNotEmpty())
        assertTrue(errors.joinToString().contains("manifest.json"))
    }

    @Test
    fun existsSeam_matchesFileBasedVerdict() {
        // The WP5 rescan path calls validate() with a storage-backed `exists`;
        // both overloads must reach identical verdicts or the test fails.
        for (name in listOf("valid-bundle", "missing-mp3")) {
            val dir = fixtureDir(name)
            assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
            val manifest = BundleParser.parse(dir).getOrThrow()
            val viaFile = BundleValidator.validate(dir, manifest)
            val present = manifest.chapters.map { it.audio }
                .filter { File(dir, it).isFile }
                .map { dir.path.trimEnd('/') + "/" + it.trimStart('/') }
                .toSet()
            val viaSeam = BundleValidator.validate(dir.path, manifest, exists = { it in present })
            assertEquals("validator paths diverged for $name", viaFile, viaSeam)
        }
    }

    @Test
    fun validateBundle_missingDir_reportsManifestFile() {        val errors = BundleValidator.validateBundle(File(fixtureDir("valid-bundle"), "does-not-exist"))
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.joinToString().contains("manifest.json"))
        // Keep the suite green even if the placeholder example test was removed.
        assertEquals(1, errors.size)
    }

    // CP4: text-file checks (pure + JVM-testable, no File I/O).

    private fun textManifest(): Manifest = Manifest(
        specVersion = "1.0",
        id = "book-1",
        title = "Example",
        type = "epub",
        audio = AudioInfo(),
        chapters = listOf(
            ChapterInfo(1, "Ch 1", "audio/ch001.mp3", "text/ch001.json", 600_000L),
            ChapterInfo(2, "Ch 2", "audio/ch002.mp3", "text/ch002.json", 400_000L)
        )
    )

    private fun validChapterJson(): String =
        """{"spec_version":"1.0","chapter":1,"title":"Ch","duration_ms":1000,"blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","start_ms":0,"end_ms":1000,"text":"Hi. "}]}]}"""

    private fun textSetup(
        manifest: Manifest = textManifest(),
        dir: String = "/books/book",
        texts: Map<String, String> = mapOf(
            "$dir/text/ch001.json" to validChapterJson(),
            "$dir/text/ch002.json" to validChapterJson()
        ),
        audios: Set<String> = setOf("$dir/audio/ch001.mp3", "$dir/audio/ch002.mp3")
    ): Triple<String, Manifest, Pair<Set<String>, Map<String, String>>> {
        val present = audios + texts.keys
        return Triple(dir, manifest, present to texts)
    }

    @Test
    fun missingTextFile_reportsChapterAndFile() {
        val dir = "/books/book"
        val manifest = textManifest()
        val present = setOf(
            "$dir/audio/ch001.mp3", "$dir/audio/ch002.mp3",
            "$dir/text/ch001.json"
        )
        val texts = mapOf("$dir/text/ch001.json" to validChapterJson())
        val errors = BundleValidator.validate(
            dir, manifest,
            exists = { it in present },
            readText = { texts[it] }
        )
        assertTrue("expected text-missing error, got: $errors", errors.isNotEmpty())
        val joined = errors.joinToString("\n")
        assertTrue(joined.contains("manifest.json"))
        assertTrue(joined.contains("chapter 2"))
        assertTrue(joined.contains("text/ch002.json"))
    }

    @Test
    fun truncatedTextJson_reportsInvalid() {
        val dir = "/books/book"
        val manifest = textManifest()
        val present = setOf(
            "$dir/audio/ch001.mp3", "$dir/audio/ch002.mp3",
            "$dir/text/ch001.json", "$dir/text/ch002.json"
        )
        val texts = mapOf(
            "$dir/text/ch001.json" to validChapterJson(),
            "$dir/text/ch002.json" to "{ truncated"
        )
        val errors = BundleValidator.validate(
            dir, manifest,
            exists = { it in present },
            readText = { texts[it] }
        )
        val joined = errors.joinToString("\n")
        assertTrue("expected invalid-text error, got: $errors", errors.isNotEmpty())
        assertTrue(joined.contains("manifest.json"))
        assertTrue(joined.contains("chapter 2"))
        assertTrue(joined.contains("text/ch002.json"))
        assertTrue(joined.contains("invalid"))
    }

    @Test
    fun wrongTypesTextJson_reportsInvalid() {
        val dir = "/books/book"
        val manifest = textManifest()
        val present = setOf(
            "$dir/audio/ch001.mp3", "$dir/audio/ch002.mp3",
            "$dir/text/ch001.json", "$dir/text/ch002.json"
        )
        // `chapter` is a string, `duration_ms` is a string: loader must reject.
        val wrongTypes = """{"spec_version":"1.0","chapter":"one","title":"Ch","duration_ms":"long","blocks":[]}"""
        val texts = mapOf(
            "$dir/text/ch001.json" to validChapterJson(),
            "$dir/text/ch002.json" to wrongTypes
        )
        val errors = BundleValidator.validate(
            dir, manifest,
            exists = { it in present },
            readText = { texts[it] }
        )
        val joined = errors.joinToString("\n")
        assertTrue("expected wrong-types error, got: $errors", errors.isNotEmpty())
        assertTrue(joined.contains("chapter 2"))
        assertTrue(joined.contains("text/ch002.json"))
        assertTrue(joined.contains("invalid"))
    }

    @Test
    fun hugeTextFile_refusedWithoutParsing() {
        val dir = "/books/book"
        val manifest = textManifest()
        val present = setOf(
            "$dir/audio/ch001.mp3", "$dir/audio/ch002.mp3",
            "$dir/text/ch001.json", "$dir/text/ch002.json"
        )
        val huge = "x".repeat(BundleValidator.MAX_TEXT_BYTES + 1)
        val texts = mapOf(
            "$dir/text/ch001.json" to validChapterJson(),
            "$dir/text/ch002.json" to huge
        )
        val errors = BundleValidator.validate(
            dir, manifest,
            exists = { it in present },
            readText = { texts[it] }
        )
        val joined = errors.joinToString("\n")
        assertTrue("expected too-large error, got size ${errors.size}", errors.isNotEmpty())
        assertTrue(joined.contains("chapter 2"))
        assertTrue(joined.contains("text/ch002.json"))
        assertTrue(joined.contains("8 MB") || joined.contains("too large"))
    }

    @Test
    fun validTextFiles_pass() {
        val (dir, manifest, seams) = textSetup()
        val (present, texts) = seams
        val errors = BundleValidator.validate(
            dir, manifest,
            exists = { it in present },
            readText = { texts[it] }
        )
        assertTrue("expected no errors, got: $errors", errors.isEmpty())
    }

    @Test
    fun pdfPagesForm_notAnError() {
        val dir = "/books/book"
        val manifest = textManifest()
        val present = setOf(
            "$dir/audio/ch001.mp3", "$dir/audio/ch002.mp3",
            "$dir/text/ch001.json", "$dir/text/ch002.json"
        )
        val pdfForm = """{"spec_version":"1.0","chapter":2,"title":"P","duration_ms":1000,"pages":[{"page":1,"start_ms":0}]}"""
        val texts = mapOf(
            "$dir/text/ch001.json" to validChapterJson(),
            "$dir/text/ch002.json" to pdfForm
        )
        val errors = BundleValidator.validate(
            dir, manifest,
            exists = { it in present },
            readText = { texts[it] }
        )
        assertTrue("PDF page-sync chapter must not fail import, got: $errors", errors.isEmpty())
    }

    @Test
    fun noReadSeam_skipsTextChecks() {
        val dir = "/books/book"
        val manifest = textManifest()
        // No text files exist, but without the seam the validator stays silent.
        val errors = BundleValidator.validate(dir, manifest, exists = { false })
        val joined = errors.joinToString("\n")
        // Only the two audio-missing errors, no text-missing errors.
        assertTrue(joined.contains("audio file missing"))
        assertTrue(!joined.contains("text file"))
    }

    @Test
    fun unknownSpecVersion_failsWithNamedError() {
        val dir = fixtureDir("valid-bundle")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val manifest = BundleParser.parse(dir).getOrThrow().copy(specVersion = "2.0")
        val errors = BundleValidator.validate(dir, manifest)
        assertTrue("spec 2.0 must fail, got: $errors", errors.isNotEmpty())
        assertTrue(errors.joinToString().contains("spec_version"))
        assertTrue(errors.joinToString().contains("2.0"))
    }
}
