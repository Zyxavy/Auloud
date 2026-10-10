package app.auloud.player.render

import app.auloud.player.bundle.BundleParser
import app.auloud.player.storage.FakeSharedPreferences
import app.auloud.player.tts.PrefsTtsStore
import app.auloud.player.tts.TtsRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * VS5: [StaleBookScan] on plain JVM (no Android, no service).
 *
 * Covers the brief Verify: counts (per-chapter states plus the book
 * summary over one mixed book) and the honesty rules (read-only books
 * scan as null, books with nothing rendered read no chapter text, an
 * unreadable chapter goes stale-ward, never current).
 */
class StaleBookScanTest {

    private val narratorVoice = "system:narr"
    private val dialogueVoice = "system:dial"
    private val oldDialogueVoice = "system:old-dial"
    private val liveVersion = "v1"

    private fun globals(): PrefsTtsStore =
        PrefsTtsStore(FakeSharedPreferences()).apply {
            setVoiceId(TtsRole.Narrator, narratorVoice)
            setVoiceId(TtsRole.Dialogue, dialogueVoice)
        }

    private val versionOf: (String) -> String? = { namespace ->
        if (namespace == "system") liveVersion else null
    }

    private fun fpJson(fp: RenderFingerprint): String =
        Json.encodeToString(JsonObject.serializer(), fp.toJsonObject())

    private fun narratorOnlyFp(
        voice: String = narratorVoice,
        speed: Float = 1.0f,
        version: String = liveVersion
    ) = RenderFingerprint(
        engine = "system",
        voices = mapOf("narrator" to voice),
        speeds = mapOf("narrator" to speed),
        engineVersions = mapOf("system" to version)
    )

    private fun bothFp(
        narrator: String = narratorVoice,
        dialogue: String = dialogueVoice,
        version: String = liveVersion
    ) = RenderFingerprint(
        engine = "system",
        voices = mapOf("narrator" to narrator, "dialogue" to dialogue),
        speeds = mapOf("narrator" to 1.0f, "dialogue" to 1.0f),
        engineVersions = mapOf("system" to version)
    )

    private fun narratorChapterJson(chapter: Int, durationMs: Long? = 60_000L): String {
        val timed = if (durationMs != null) {
            ""","duration_ms":$durationMs"""
        } else {
            ""
        }
        val timings = if (durationMs != null) {
            ""","start_ms":0,"end_ms":1000"""
        } else {
            ""
        }
        return """{"spec_version":"2.0","chapter":$chapter,"title":"Ch $chapter"$timed,"blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator"$timings,"text":"Done. "}]}]}"""
    }

    private fun dialogueChapterJson(chapter: Int, durationMs: Long = 60_000L): String =
        """{"spec_version":"2.0","chapter":$chapter,"title":"Ch $chapter","duration_ms":$durationMs,"blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","start_ms":0,"end_ms":1000,"text":"He said. "},{"sid":2,"speaker":"dialogue","start_ms":1000,"end_ms":2000,"text":"Hi. "}]}]}"""

    private fun mixedManifest(): String {
        val ch1Fp = fpJson(narratorOnlyFp())
        val ch2Fp = fpJson(bothFp(dialogue = oldDialogueVoice))
        val ch3Fp = fpJson(narratorOnlyFp(version = "v0"))
        return """
        {
          "spec_version": "2.0",
          "id": "b1",
          "title": "Mixed",
          "type": "epub",
          "render_state": "partial",
          "audio": {"format": "m4a"},
          "voices": {
            "narrator": {"engine": "system", "voice": "narr", "speed": 1.0, "pitch": 1.0},
            "dialogue": {"engine": "system", "voice": "dial", "speed": 1.0, "pitch": 1.0}
          },
          "chapters": [
            {"index": 1, "title": "Ch 1", "audio": "audio/ch001.m4a",
             "text": "text/ch001.json", "duration_ms": 60000,
             "render_fingerprint": $ch1Fp},
            {"index": 2, "title": "Ch 2", "audio": "audio/ch002.m4a",
             "text": "text/ch002.json", "duration_ms": 60000,
             "render_fingerprint": $ch2Fp},
            {"index": 3, "title": "Ch 3", "audio": "audio/ch003.m4a",
             "text": "text/ch003.json", "duration_ms": 60000,
             "render_fingerprint": $ch3Fp},
            {"index": 4, "title": "Ch 4", "text": "text/ch004.json"}
          ]
        }
        """.trimIndent()
    }

    private fun mixedTexts(): Map<String, String> = mapOf(
        "text/ch001.json" to narratorChapterJson(1),
        "text/ch002.json" to dialogueChapterJson(2),
        "text/ch003.json" to narratorChapterJson(3),
        "text/ch004.json" to narratorChapterJson(4, durationMs = null)
    )

    @Test
    fun mixedBook_statesAndCounts() {
        val manifest = BundleParser.parseText(mixedManifest()).getOrThrow()
        val texts = mixedTexts()
        val data = StaleBookScan.scan(
            manifest = manifest,
            readChapterText = { texts[it] },
            globals = globals(),
            versionOf = versionOf
        )
        assertNotNull(data)
        assertEquals(ChapterStaleState.CURRENT, data!!.states[0])
        assertEquals(ChapterStaleState.STALE, data.states[1])
        assertEquals(ChapterStaleState.OUTDATED, data.states[2])
        assertEquals(ChapterStaleState.NOT_RENDERED, data.states[3])
        assertEquals(1, data.summary.current)
        assertEquals(1, data.summary.stale)
        assertEquals(1, data.summary.outdated)
        assertEquals(1, data.summary.notRendered)
        assertEquals(1, data.summary.stale)
    }

    @Test
    fun readOnlyBook_scansNull() {
        // Same book with a per-character cast entry: Scribe audio the
        // device must never re-render, so no stale UI at all.
        val raw = mixedManifest().replace(
            """"dialogue": {"engine": "system", "voice": "dial", "speed": 1.0, "pitch": 1.0}""",
            """"dialogue": {"engine": "system", "voice": "dial", "speed": 1.0, "pitch": 1.0},""" +
                """"alice": {"engine": "pc", "voice": "alice", "speed": 1.0, "pitch": 1.0}"""
        )
        val manifest = BundleParser.parseText(raw).getOrThrow()
        var reads = 0
        val data = StaleBookScan.scan(
            manifest = manifest,
            readChapterText = { reads++; mixedTexts()[it] },
            globals = globals(),
            versionOf = versionOf
        )
        assertNull(data)
        assertEquals(0, reads)
    }

    @Test
    fun legacyBook_scansNull() {
        val raw = mixedManifest().replace(
            """"spec_version": "2.0"""",
            """"spec_version": "1.0""""
        )
        val manifest = BundleParser.parseText(raw).getOrThrow()
        val data = StaleBookScan.scan(
            manifest = manifest,
            readChapterText = { mixedTexts()[it] },
            globals = globals(),
            versionOf = versionOf
        )
        assertNull(data)
    }

    @Test
    fun nothingRendered_scansNullWithoutChapterReads() {
        val raw = """
        {
          "spec_version": "2.0",
          "id": "b1",
          "title": "Fresh",
          "type": "epub",
          "render_state": "none",
          "voices": {
            "narrator": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0},
            "dialogue": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0}
          },
          "chapters": [
            {"index": 1, "title": "Ch 1", "text": "text/ch001.json"},
            {"index": 2, "title": "Ch 2", "text": "text/ch002.json"}
          ]
        }
        """.trimIndent()
        val manifest = BundleParser.parseText(raw).getOrThrow()
        var reads = 0
        val data = StaleBookScan.scan(
            manifest = manifest,
            readChapterText = { reads++; null },
            globals = globals(),
            versionOf = versionOf
        )
        assertNull(data)
        assertEquals(0, reads)
    }

    @Test
    fun unreadableChapter_goesStaleWardNeverCurrent() {
        val manifest = BundleParser.parseText(mixedManifest()).getOrThrow()
        val texts = mixedTexts()
        val data = StaleBookScan.scan(
            manifest = manifest,
            // ch1 (narrator-only, stored narrator-only) loses its text:
            // both-role fallback finds no stored dialogue entry: STALE.
            readChapterText = { if (it == "text/ch001.json") null else texts[it] },
            globals = globals(),
            versionOf = versionOf
        )
        assertNotNull(data)
        assertEquals(ChapterStaleState.STALE, data!!.states[0])
        // The rest of the book is unaffected.
        assertEquals(ChapterStaleState.STALE, data.states[1])
        assertEquals(ChapterStaleState.OUTDATED, data.states[2])
        assertEquals(ChapterStaleState.NOT_RENDERED, data.states[3])
    }

    @Test
    fun missingFingerprint_readsStale() {
        // A rendered chapter with no fingerprint is unknown, never
        // current (VS2 rule).
        val plain = """
        {
          "spec_version": "2.0",
          "id": "b1",
          "title": "NoFp",
          "type": "epub",
          "render_state": "partial",
          "audio": {"format": "m4a"},
          "voices": {
            "narrator": {"engine": "system", "voice": "narr", "speed": 1.0, "pitch": 1.0},
            "dialogue": {"engine": "system", "voice": "dial", "speed": 1.0, "pitch": 1.0}
          },
          "chapters": [
            {"index": 1, "title": "Ch 1", "audio": "audio/ch001.m4a",
             "text": "text/ch001.json", "duration_ms": 60000},
            {"index": 2, "title": "Ch 2", "text": "text/ch002.json"}
          ]
        }
        """.trimIndent()
        val manifest = BundleParser.parseText(plain).getOrThrow()
        val data = StaleBookScan.scan(
            manifest = manifest,
            readChapterText = { narratorChapterJson(1) },
            globals = globals(),
            versionOf = versionOf
        )
        assertNotNull(data)
        assertEquals(ChapterStaleState.STALE, data!!.states[0])
        assertEquals(ChapterStaleState.NOT_RENDERED, data.states[1])
    }
}
