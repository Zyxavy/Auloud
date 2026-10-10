package app.auloud.player.bundle

import app.auloud.player.playback.PlaybackQueue
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VC3: v1-to-v2 compatibility matrix on the JVM (no device claimed).
 *
 * Each row loads a real golden fixture through the real [BundleParser],
 * validates it with the real [BundleValidator] (file-existence checks on),
 * and checks playback gating truthfully through [PlaybackQueue.gateFor] +
 * [PlaybackQueue.buildPlayable]: Playable plus one queue item per rendered
 * chapter with non-blank paths and matching durations.
 *
 * Rows:
 * - v1 MP3 bundle: `spec/fixtures/valid-bundle/` (spec 1.0)
 * - Scribe (PC) bundle: `spec/fixtures/scribe-golden/` (spec 1.1, real
 *   Scribe pipeline output)
 * - PC multi-voice bundle: `spec/fixtures/multivoice-golden/` (spec 1.0,
 *   narrator plus Alice voices)
 * - v1-refuses-2.0: the v1.0.0 tree is not checked out in this worktree,
 *   so its gate cannot run here. The gate condition was read at tag
 *   `v1.0.0` (`BundleValidator.kt`: specVersion must be "1.0", "1.1" or
 *   "1.2", else a named `manifest.json: spec_version ...` error) and is
 *   replayed literally below against a real parsed 2.0 manifest; refusal
 *   cleanliness (named error, no throw, no file writes) is proven through
 *   the same-shaped refusal path in this tree.
 *
 * The Room v1-to-v2 migration has no JVM test: the v1 schema was never
 * exported (`exportSchema = false` in v1, only `2.json` exists under
 * `player/app/schemas/`) and the unit harness is plain JVM JUnit with no
 * Robolectric or room-testing (adding either is a new dependency needing
 * owner approval). Data survival is proven by the owner-run over-install
 * procedure in `docs/ReleaseSigning.md`. The ms-to-NULL-sid rule the
 * procedure checks comes from [app.auloud.player.data.ProgressEntity]:
 * `sentenceSid` is null for ms-based positions (every row written before
 * v2), which is exactly what the nullable `ADD COLUMN` migration yields
 * for existing rows.
 *
 * API 24 safe: `java.io.File` only. No Robolectric, no new dependencies.
 */
class VC3CompatibilityMatrixTest {

    private val audioUriOf: (String, String) -> String = { dir, rel -> "file://$dir/$rel" }

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

    private fun parseFixture(name: String): Pair<File, Manifest> {
        val dir = fixtureDir(name)
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val parsed = BundleParser.parse(dir)
        assertTrue(
            "expected parse success but got: ${parsed.exceptionOrNull()?.message}",
            parsed.isSuccess
        )
        return dir to parsed.getOrThrow()
    }

    /**
     * One matrix row: parse plus validate clean, gate Playable, queue
     * items one per chapter with exact durations and non-blank paths.
     */
    private fun checkRenderedRow(
        fixture: String,
        expectedSpec: String,
        expectedDurations: List<Long>
    ) {
        val (dir, manifest) = parseFixture(fixture)
        assertEquals(expectedSpec, manifest.specVersion)
        val errors = BundleValidator.validate(dir, manifest)
        assertTrue("expected no validation errors, got: $errors", errors.isEmpty())
        assertEquals(PlaybackQueue.PlaybackGate.Playable, PlaybackQueue.gateFor(manifest))
        val items = PlaybackQueue.buildPlayable(manifest, dir.path, audioUriOf, null)
        assertEquals(expectedDurations.size, items.size)
        items.forEachIndexed { pos, item ->
            assertEquals(expectedDurations[pos], item.durationMs)
            assertTrue("item $pos carries a blank audio path", item.audioRelPath.isNotBlank())
            assertTrue("item $pos carries a blank audio uri", item.audioUri.isNotBlank())
            assertTrue("item $pos has no duration", item.durationMs > 0L)
        }
        assertEquals(
            expectedDurations,
            manifest.chapters.sortedBy { it.index }.map { it.durationMs }
        )
    }

    @Test
    fun v1Mp3Bundle_validBundle_loadsValidatesAndGatesPlayable() {
        checkRenderedRow("valid-bundle", "1.0", listOf(1_832_400L, 1_640_100L))
    }

    @Test
    fun scribePcBundle_scribeGolden_loadsValidatesAndGatesPlayable() {
        checkRenderedRow("scribe-golden", "1.1", listOf(7_450L, 7_450L))
    }

    @Test
    fun pcMultivoiceBundle_multivoiceGolden_loadsValidatesAndGatesPlayable() {
        checkRenderedRow("multivoice-golden", "1.0", listOf(1_550L))
    }

    @Test
    fun v1GateRefuses20Books_cleanlyAtTheVersionBoundary() {
        // The 2.0 side: a real parsed 2.0 manifest.
        val (dir, manifest) = parseFixture("unrendered-golden")
        assertEquals("2.0", manifest.specVersion)
        // The v1.0.0 gate condition, replayed literally (tag v1.0.0,
        // BundleValidator.kt): only "1.0", "1.1", "1.2" pass, so a 2.0
        // book takes the refusal branch.
        val v1GatePasses = manifest.specVersion == "1.0" ||
            manifest.specVersion == "1.1" ||
            manifest.specVersion == "1.2"
        assertTrue("v1 gate must refuse spec 2.0", !v1GatePasses)
        assertTrue(
            "2.0 must stay outside the pre-2.0 set",
            !BundleValidator.V1_VERSIONS.contains("2.0")
        )
        // Refusal cleanliness through the same-shaped refusal path in
        // this tree: an out-of-set version is a named error (names the
        // file, the rule and the version), never a throw, and the
        // validator writes nothing (fixture dir identical before/after).
        val before = dir.walkTopDown().map { it.relativeTo(dir).path }.toSortedSet()
        val refused = manifest.copy(specVersion = "2.1-future")
        val errors = BundleValidator.validate(dir, refused)
        assertTrue("out-of-set version must fail, got none", errors.isNotEmpty())
        val joined = errors.joinToString("\n")
        assertTrue("refusal must name the file, got: $joined", joined.contains("manifest.json"))
        assertTrue("refusal must name the rule, got: $joined", joined.contains("spec_version"))
        assertTrue("refusal must name the version, got: $joined", joined.contains("2.1-future"))
        val after = dir.walkTopDown().map { it.relativeTo(dir).path }.toSortedSet()
        assertEquals("validator must leave no partial state", before, after)
    }
}
