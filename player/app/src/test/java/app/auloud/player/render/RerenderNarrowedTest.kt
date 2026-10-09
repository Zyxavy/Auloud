package app.auloud.player.render

import app.auloud.player.tts.EngineRegistry
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
}
