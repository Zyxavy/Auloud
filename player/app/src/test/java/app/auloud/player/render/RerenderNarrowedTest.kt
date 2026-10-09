package app.auloud.player.render

import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.BundleValidator
import app.auloud.player.tts.BookVoices
import app.auloud.player.tts.EngineRegistry
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VS3: narrowed synthesis via [RenderVoices.resolveForRoles] plus
 * [SpoolRenderer.renderChapterNarrowed] (D-114, VS2 seam wired).
 *
 * Stored chapter fingerprints become used-roles-only going forward;
 * old both-role fingerprints still read stale-at-worst (see
 * `RenderStalenessTest`).
 */
class RerenderNarrowedTest {

    @Test
    fun narratorOnlyChapter_blankDialogueResolvesAndSpools(): Unit = runBlocking {
        val registry = EngineRegistry(
            listOf(ScriptedSpoolEngine("system", listOf("system:narr")))
        )
        val store = MemRenderVoiceStore(
            narratorId = "system:narr",
            dialogueId = "",
            narratorSpeed = 1.0f,
            dialogueSpeed = 1.0f
        )
        val narrowed = RenderVoices.resolveForRoles(
            registry, store, versionOf = { "v1" }, roles = setOf("narrator")
        ).getOrThrow()
        assertEquals(setOf("narrator"), narrowed.fingerprint.voices.keys)

        val chapter = blockChapter(1, listOf("narrator" to "Hello. ", "narrator" to "Again. "))
        val io = MemSpoolIo()
        val outcome = SpoolRenderer.renderChapterNarrowed(
            chapterPos = 0,
            chapterNumber = 1,
            chapter = chapter,
            voices = narrowed,
            spoolDir = "/spool/b1",
            io = io
        )
        assertTrue(outcome is ChapterSpoolOutcome.Completed)
        val summary = (outcome as ChapterSpoolOutcome.Completed).summary
        assertEquals(setOf("narrator"), summary.fingerprint.voices.keys)
        assertEquals(2, summary.sentences.size)
    }

    @Test
    fun dialogueChapter_missingDialogueBinding_failsNamingRole(): Unit = runBlocking {
        val registry = EngineRegistry(
            listOf(ScriptedSpoolEngine("system", listOf("system:narr")))
        )
        val store = MemRenderVoiceStore(narratorId = "system:narr", dialogueId = "")
        val narrowed = RenderVoices.resolveForRoles(
            registry, store, versionOf = { "v1" }, roles = setOf("narrator")
        ).getOrThrow()
        val chapter = blockChapter(1, listOf("narrator" to "Hello. ", "dialogue" to "Hi. "))
        val io = MemSpoolIo()
        val outcome = SpoolRenderer.renderChapterNarrowed(
            chapterPos = 0,
            chapterNumber = 1,
            chapter = chapter,
            voices = narrowed,
            spoolDir = "/spool/b1",
            io = io
        )
        assertTrue(outcome is ChapterSpoolOutcome.Failed)
        val reason = (outcome as ChapterSpoolOutcome.Failed).reason
        assertTrue(reason.contains("dialogue"))
    }

    @Test
    fun bothRoleChapter_narrowedBoth_matchesBothResolve() {
        val registry = EngineRegistry(
            listOf(ScriptedSpoolEngine("system", listOf("system:narr", "system:dial")))
        )
        val store = MemRenderVoiceStore("system:narr", "system:dial")
        val both = RenderVoices.resolve(registry, store) { "v1" }.getOrThrow()
        val narrowed = RenderVoices.resolveForRoles(
            registry, store, versionOf = { "v1" }, roles = setOf("narrator", "dialogue")
        ).getOrThrow()
        assertEquals(both.fingerprint, narrowed.fingerprint)
    }

    @Test
    fun firstRender_narrowedFingerprint_validatesCleanAndClassifiesCurrent() {
        // First-render pin: a narrator-only chapter finalizes with its
        // narrowed (single-role) fingerprint, validates clean, and
        // classifies CURRENT under the same book voices.
        val registry = EngineRegistry(
            listOf(ScriptedSpoolEngine("system", listOf("system:narr")))
        )
        val store = MemRenderVoiceStore(narratorId = "system:narr", dialogueId = "")
        val narrowed = RenderVoices.resolveForRoles(
            registry, store, versionOf = { "v1" }, roles = setOf("narrator")
        ).getOrThrow()
        assertEquals(setOf("narrator"), narrowed.fingerprint.voices.keys)

        val io = FakeFinalizeIo()
        val bundleDir = "/books/b1"
        io.files["$bundleDir/manifest.json"] = manifestNoneJson()
        io.files["$bundleDir/text/ch001.json"] = untimedNarratorChapterJson()
        io.files["$bundleDir/text/ch002.json"] = untimedNarratorChapterJson(2)
        io.files["$bundleDir/audio/ch001.m4a"] = "fake-audio"

        val result = RenderFinalize.finalizeChapter(
            bundleDir = bundleDir,
            chapterNumber = 1,
            audioRel = "audio/ch001.m4a",
            timings = listOf(
                AssemblySentenceTiming(sid = 1, startMs = 0, endMs = 500),
                AssemblySentenceTiming(sid = 2, startMs = 750, endMs = 1250)
            ),
            durationMs = 2000,
            fingerprint = narrowed.fingerprint,
            gainDb = emptyMap(),
            encoderOffsetMs = 0,
            io = io,
            sleeper = io.sleeper
        )

        assertTrue(result.isSuccess)
        val manifest = BundleParser.parseText(io.files["$bundleDir/manifest.json"]!!).getOrThrow()
        val errors = BundleValidator.validate(
            bundleDir, manifest,
            exists = { io.files.containsKey(it) },
            readText = { io.files[it] }
        )
        assertTrue("expected clean, got: $errors", errors.isEmpty())
        val bookVoices = BookVoices(
            narratorVoiceId = "system:narr",
            dialogueVoiceId = null,
            narratorSpeed = 1.0f,
            dialogueSpeed = 1.0f,
            readOnly = false
        )
        val state = RenderStaleness.classifyChapter(
            entry = manifest.chapters[0],
            bookVoices = bookVoices,
            versionOf = { "v1" },
            hasDialogue = false
        )
        assertEquals(ChapterStaleState.CURRENT, state)
    }

    private fun manifestNoneJson(): String = """
        {
          "spec_version": "2.0",
          "id": "b1",
          "title": "Unrendered",
          "type": "epub",
          "render_state": "none",
          "source": {"file": "source/book.epub", "sha256": "abc"},
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

    private fun untimedNarratorChapterJson(chapter: Int = 1): String =
        """{"spec_version":"2.0","chapter":$chapter,"title":"Ch $chapter","blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","text":"Hello. "},{"sid":2,"speaker":"narrator","text":"Again. "}]}]}"""

    private class FakeFinalizeIo : RenderFileIo {
        val files = HashMap<String, String>()
        val sleeper: (Long) -> Unit = { }

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun readText(path: String): String =
            files[path] ?: throw IOException("$path: file not found or not readable")

        override fun writeText(path: String, text: String) {
            files[path] = text
        }

        override fun renameTempToTarget(tmpPath: String, targetPath: String): Boolean {
            val text = files[tmpPath] ?: return false
            files[targetPath] = text
            files.remove(tmpPath)
            return true
        }

        override fun deleteIfExists(path: String) {
            files.remove(path)
        }
    }
}
