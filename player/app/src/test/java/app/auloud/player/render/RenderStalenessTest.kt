package app.auloud.player.render

import app.auloud.player.bundle.AudioInfo
import app.auloud.player.bundle.BundleValidator
import app.auloud.player.bundle.ChapterInfo
import app.auloud.player.bundle.Manifest
import app.auloud.player.tts.BookVoices
import app.auloud.player.tts.EngineRegistry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VS2: staleness matrix (D-114 used-roles narrowing, D-115 states).
 *
 * Pure JVM: BookVoices plus fake versions plus small chapters, no
 * Android, no storage, no service. Roles-used comes from the chapter
 * text sentences (see `RenderStaleness` file KDoc for the cheapest-source
 * rationale); the summary reuses the Slice 10 provisional constants.
 */
class RenderStalenessTest {

    private fun bookVoices(
        narratorId: String = "system:narr",
        dialogueId: String? = "system:dial",
        narratorSpeed: Float = 1.0f,
        dialogueSpeed: Float = 1.0f
    ) = BookVoices(
        narratorVoiceId = narratorId,
        dialogueVoiceId = dialogueId,
        narratorSpeed = narratorSpeed,
        dialogueSpeed = dialogueSpeed,
        readOnly = false
    )

    private fun versions(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = mapOf(*pairs)
        return { map[it] }
    }

    private fun bothFingerprint(
        narratorVoice: String = "system:narr",
        dialogueVoice: String = "system:dial",
        narratorSpeed: Float = 1.0f,
        dialogueSpeed: Float = 1.0f,
        engine: String = "system",
        version: String = "v1"
    ) = RenderFingerprint(
        engine = engine,
        voices = mapOf("narrator" to narratorVoice, "dialogue" to dialogueVoice),
        speeds = mapOf("narrator" to narratorSpeed, "dialogue" to dialogueSpeed),
        engineVersions = mapOf("system" to version)
    )

    private fun narratorOnlyFingerprint(
        voice: String = "system:narr",
        speed: Float = 1.0f,
        version: String = "v1"
    ) = RenderFingerprint(
        engine = "system",
        voices = mapOf("narrator" to voice),
        speeds = mapOf("narrator" to speed),
        engineVersions = mapOf("system" to version)
    )

    private fun narratorOnlyChapter() =
        blockChapter(1, listOf("narrator" to "Hello. ", "narrator" to "Still here. "))

    private fun dialogueChapter() =
        blockChapter(1, listOf("narrator" to "Hello. ", "dialogue" to "Hi. "))

    private val v1 = versions("system" to "v1", "piper" to "pack-3")

    @Test
    fun narratorChange_isStale() {
        val stored = bothFingerprint()
        val voices = bookVoices(narratorId = "system:other")
        val state = RenderStaleness.classifyChapter(
            durationMs = 60_000L,
            storedJson = stored.toJsonObject(),
            bookVoices = voices,
            versionOf = v1,
            chapter = narratorOnlyChapter()
        )
        assertEquals(ChapterStaleState.STALE, state)
    }

    @Test
    fun dialogueOnlyChange_dialogueFreeChapter_staysCurrent() {
        val stored = bothFingerprint(dialogueVoice = "system:old-dial")
        val voices = bookVoices(dialogueId = "system:new-dial")
        val state = RenderStaleness.classifyChapter(
            durationMs = 60_000L,
            storedJson = stored.toJsonObject(),
            bookVoices = voices,
            versionOf = v1,
            chapter = narratorOnlyChapter()
        )
        assertEquals(ChapterStaleState.CURRENT, state)
    }

    @Test
    fun dialogueOnlyChange_dialogueChapter_isStale() {
        val stored = bothFingerprint(dialogueVoice = "system:old-dial")
        val voices = bookVoices(dialogueId = "system:new-dial")
        val state = RenderStaleness.classifyChapter(
            durationMs = 60_000L,
            storedJson = stored.toJsonObject(),
            bookVoices = voices,
            versionOf = v1,
            chapter = dialogueChapter()
        )
        assertEquals(ChapterStaleState.STALE, state)
    }

    @Test
    fun narratorSpeedChange_stalesEveryRenderedChapter() {
        val stored = bothFingerprint(narratorSpeed = 1.0f)
        val voices = bookVoices(narratorSpeed = 1.25f)
        for (chapter in listOf(narratorOnlyChapter(), dialogueChapter())) {
            val state = RenderStaleness.classifyChapter(
                durationMs = 60_000L,
                storedJson = stored.toJsonObject(),
                bookVoices = voices,
                versionOf = v1,
                chapter = chapter
            )
            assertEquals(ChapterStaleState.STALE, state)
        }
    }

    @Test
    fun dialogueSpeedChange_dialogueFreeStaysCurrent_dialogueStales() {
        val stored = bothFingerprint(dialogueSpeed = 1.0f)
        val voices = bookVoices(dialogueSpeed = 1.5f)
        val free = RenderStaleness.classifyChapter(
            durationMs = 60_000L,
            storedJson = stored.toJsonObject(),
            bookVoices = voices,
            versionOf = v1,
            chapter = narratorOnlyChapter()
        )
        assertEquals(ChapterStaleState.CURRENT, free)
        val withDialogue = RenderStaleness.classifyChapter(
            durationMs = 60_000L,
            storedJson = stored.toJsonObject(),
            bookVoices = voices,
            versionOf = v1,
            chapter = dialogueChapter()
        )
        assertEquals(ChapterStaleState.STALE, withDialogue)
    }

    @Test
    fun engineChange_isStale() {
        val stored = bothFingerprint(
            narratorVoice = "system:narr",
            dialogueVoice = "system:dial",
            engine = "system"
        )
        val voices = bookVoices(narratorId = "piper:narr", dialogueId = "piper:dial")
        val versionOf = versions("system" to "v1", "piper" to "pack-3")
        val state = RenderStaleness.classifyChapter(
            durationMs = 60_000L,
            storedJson = stored.toJsonObject(),
            bookVoices = voices,
            versionOf = versionOf,
            chapter = dialogueChapter()
        )
        assertEquals(ChapterStaleState.STALE, state)
    }

    @Test
    fun dialogueEngineChange_dialogueFreeStaysCurrent() {
        val stored = bothFingerprint(dialogueVoice = "system:dial", engine = "system")
        val voices = bookVoices(dialogueId = "piper:dial")
        val versionOf = versions("system" to "v1", "piper" to "pack-3")
        val free = RenderStaleness.classifyChapter(
            durationMs = 60_000L,
            storedJson = stored.toJsonObject(),
            bookVoices = voices,
            versionOf = versionOf,
            chapter = narratorOnlyChapter()
        )
        assertEquals(ChapterStaleState.CURRENT, free)
        val withDialogue = RenderStaleness.classifyChapter(
            durationMs = 60_000L,
            storedJson = stored.toJsonObject(),
            bookVoices = voices,
            versionOf = versionOf,
            chapter = dialogueChapter()
        )
        assertEquals(ChapterStaleState.STALE, withDialogue)
    }

    @Test
    fun versionOnlyChange_isOutdatedQuiet() {
        val stored = bothFingerprint(version = "v1")
        val voices = bookVoices()
        val state = RenderStaleness.classifyChapter(
            durationMs = 60_000L,
            storedJson = stored.toJsonObject(),
            bookVoices = voices,
            versionOf = versions("system" to "v2"),
            chapter = dialogueChapter()
        )
        assertEquals(ChapterStaleState.OUTDATED, state)
    }

    @Test
    fun versionOnlyChange_unusedEngineIgnored() {
        val stored = RenderFingerprint(
            engine = "piper+system",
            voices = mapOf("narrator" to "system:narr", "dialogue" to "piper:dial"),
            speeds = mapOf("narrator" to 1.0f, "dialogue" to 1.0f),
            engineVersions = mapOf("system" to "v1", "piper" to "old-pack")
        )
        val voices = bookVoices(dialogueId = "piper:dial")
        val state = RenderStaleness.classifyChapter(
            durationMs = 60_000L,
            storedJson = stored.toJsonObject(),
            bookVoices = voices,
            versionOf = versions("system" to "v1", "piper" to "new-pack"),
            chapter = narratorOnlyChapter()
        )
        assertEquals(ChapterStaleState.CURRENT, state)
    }

    @Test
    fun unrenderedChapter_isNotRenderedRegardless() {
        val stored = bothFingerprint()
        val voices = bookVoices(narratorId = "system:changed")
        for (json in listOf(stored.toJsonObject(), null)) {
            val free = RenderStaleness.classifyChapter(
                durationMs = null,
                storedJson = json,
                bookVoices = voices,
                versionOf = v1,
                chapter = narratorOnlyChapter()
            )
            assertEquals(ChapterStaleState.NOT_RENDERED, free)
            val withDialogue = RenderStaleness.classifyChapter(
                durationMs = null,
                storedJson = json,
                bookVoices = voices,
                versionOf = v1,
                chapter = dialogueChapter()
            )
            assertEquals(ChapterStaleState.NOT_RENDERED, withDialogue)
        }
    }

    @Test
    fun missingOrCorruptFingerprint_isStaleNeverCurrent() {
        val voices = bookVoices()
        val corrupt = Json.parseToJsonElement("""{"engine":"system"}""")
        for (json in listOf(corrupt, null)) {
            val state = RenderStaleness.classifyChapter(
                durationMs = 60_000L,
                storedJson = json,
                bookVoices = voices,
                versionOf = v1,
                chapter = narratorOnlyChapter()
            )
            assertEquals(ChapterStaleState.STALE, state)
        }
    }

    @Test
    fun oldBothRole_missingUsedRoleNeverCurrent() {
        val singleStored = narratorOnlyFingerprint()
        val voices = bookVoices()
        val state = RenderStaleness.classifyChapter(
            durationMs = 60_000L,
            storedJson = singleStored.toJsonObject(),
            bookVoices = voices,
            versionOf = v1,
            chapter = dialogueChapter()
        )
        assertEquals(ChapterStaleState.STALE, state)
    }

    @Test
    fun oldBothRole_matchingUsedRoles_staysCurrent() {
        val stored = bothFingerprint()
        val voices = bookVoices()
        val state = RenderStaleness.classifyChapter(
            durationMs = 60_000L,
            storedJson = stored.toJsonObject(),
            bookVoices = voices,
            versionOf = v1,
            chapter = narratorOnlyChapter()
        )
        assertEquals(ChapterStaleState.CURRENT, state)
    }

    @Test
    fun singleRoleStored_matchesNarrowedExpectation() {
        val stored = narratorOnlyFingerprint()
        val voices = bookVoices()
        val state = RenderStaleness.classifyChapter(
            durationMs = 60_000L,
            storedJson = stored.toJsonObject(),
            bookVoices = voices,
            versionOf = v1,
            chapter = narratorOnlyChapter()
        )
        assertEquals(ChapterStaleState.CURRENT, state)
    }

    @Test
    fun partialBook_summaryCountsAndEstimates() {
        val currentFp = bothFingerprint(version = "v2")
        val staleFp = bothFingerprint(narratorVoice = "system:old-narr", version = "v2")
        val outdatedFp = bothFingerprint(version = "v1")
        val voices = bookVoices(narratorId = "system:narr")
        val versionOf = versions("system" to "v2")
        val inputs = listOf(
            StaleChapterInput(1, 3_600_000L, currentFp.toJsonObject(), hasDialogue = false),
            StaleChapterInput(2, 1_800_000L, staleFp.toJsonObject(), hasDialogue = false),
            StaleChapterInput(3, 900_000L, outdatedFp.toJsonObject(), hasDialogue = false),
            StaleChapterInput(4, null, null, hasDialogue = false)
        )
        val summary = RenderStaleness.summarizeChapters(inputs, voices, versionOf)
        assertEquals(1, summary.current)
        assertEquals(1, summary.stale)
        assertEquals(1, summary.outdated)
        assertEquals(1, summary.notRendered)
        assertEquals(1_800_000L, summary.staleAudioMs)
        assertEquals(0.5, summary.staleHours, 1e-9)
        assertEquals((1_800_000L / RENDER_RTF_HIGH).toLong(), summary.wallFastMs)
        assertEquals((1_800_000L / RENDER_RTF_LOW).toLong(), summary.wallSlowMs)
        val expected = RenderEstimates.estimateForAudioMs(1_800_000L)
        assertEquals(expected.totalBytes, summary.newBytes)
        assertEquals(expected.audioBytes + expected.totalBytes, summary.swapBytes)
    }

    @Test
    fun summary_outdatedExcludedFromStaleSet() {
        val fp = bothFingerprint(version = "v1")
        val voices = bookVoices()
        val inputs = listOf(
            StaleChapterInput(1, 600_000L, fp.toJsonObject(), hasDialogue = true)
        )
        val summary = RenderStaleness.summarizeChapters(
            inputs, voices, versions("system" to "v2")
        )
        assertEquals(0, summary.stale)
        assertEquals(1, summary.outdated)
        assertEquals(0L, summary.staleAudioMs)
        assertEquals(0L, summary.newBytes)
    }

    @Test
    fun rolesUsed_emptyChapterFallsBackToNarrator() {
        val empty = blockChapter(1, emptyList())
        assertEquals(setOf("narrator"), RenderStaleness.rolesUsedInChapter(empty))
        assertEquals(setOf("narrator"), RenderStaleness.usedRolesFor(false, false))
    }

    @Test
    fun fingerprint_singleRoleParses() {
        val single = narratorOnlyFingerprint().toJsonObject()
        assertEquals(narratorOnlyFingerprint(), RenderFingerprint.fromJsonObject(single))
        val both = bothFingerprint().toJsonObject()
        assertEquals(bothFingerprint(), RenderFingerprint.fromJsonObject(both))
    }

    @Test
    fun fingerprint_rejectsMismatchAndUnknown() {
        val mismatch = Json.parseToJsonElement(
            """{"engine":"system","voices":{"narrator":"system:n"},
            "speeds":{"narrator":1.0,"dialogue":1.0},"engine_versions":{"system":"v"}}"""
        ).let { it as kotlinx.serialization.json.JsonObject }
        assertEquals(null, RenderFingerprint.fromJsonObject(mismatch))
        val unknown = Json.parseToJsonElement(
            """{"engine":"system","voices":{"narrator":"a","chorus":"b"},
            "speeds":{"narrator":1.0,"chorus":1.0},"engine_versions":{"system":"v"}}"""
        ).let { it as kotlinx.serialization.json.JsonObject }
        assertEquals(null, RenderFingerprint.fromJsonObject(unknown))
    }

    @Test
    fun validator_singleRolePasses_mismatchFails() {
        val manifest = Manifest(
            specVersion = "2.0",
            id = "b1",
            title = "T",
            type = "epub",
            chapters = listOf(
                ChapterInfo(1, "Ch 1", "text/ch001.json", "audio/ch001.m4a", 60_000L),
                ChapterInfo(2, "Ch 2", "text/ch002.json", "", null)
            ),
            audio = AudioInfo(format = "m4a"),
            renderState = "partial"
        )
        val single = narratorOnlyFingerprint().toJsonObject()
        val ok = manifest.copy(
            chapters = listOf(
                manifest.chapters[0].copy(renderFingerprint = single),
                manifest.chapters[1]
            )
        )
        assertTrue(BundleValidator.validateChapterFingerprint(ok, ok.chapters[0]).isEmpty())
        val mismatch = Json.parseToJsonElement(
            """{"engine":"system","voices":{"narrator":"system:n"},
            "speeds":{"narrator":1.0,"dialogue":1.0},"engine_versions":{"system":"v"}}"""
        )
        val bad = manifest.copy(
            chapters = listOf(
                manifest.chapters[0].copy(renderFingerprint = mismatch),
                manifest.chapters[1]
            )
        )
        assertTrue(BundleValidator.validateChapterFingerprint(bad, bad.chapters[0]).isNotEmpty())
    }

    @Test
    fun resolveForRoles_narratorOnlyIgnoresBlankDialogue() {
        val registry = EngineRegistry(
            listOf(ScriptedSpoolEngine("system", listOf("system:narr")))
        )
        val store = MemRenderVoiceStore(
            narratorId = "system:narr",
            dialogueId = "",
            narratorSpeed = 1.0f,
            dialogueSpeed = 1.0f
        )
        val outcome = RenderVoices.resolveForRoles(
            registry, store, versionOf = { "v1" }, roles = setOf("narrator")
        )
        assertTrue(outcome.isSuccess)
        val narrowed = outcome.getOrThrow()
        assertEquals(setOf("narrator"), narrowed.fingerprint.voices.keys)
        assertEquals(setOf("narrator"), narrowed.fingerprint.speeds.keys)
        assertNotNull(narrowed.narrator)
        assertEquals(null, narrowed.dialogue)
    }

    @Test
    fun resolveForRoles_rejectsEmptyAndUnknown() {
        val registry = EngineRegistry(
            listOf(ScriptedSpoolEngine("system", listOf("system:narr", "system:dial")))
        )
        val store = MemRenderVoiceStore("system:narr", "system:dial")
        assertTrue(
            RenderVoices.resolveForRoles(registry, store, { "v1" }, emptySet()).isFailure
        )
        assertTrue(
            RenderVoices.resolveForRoles(registry, store, { "v1" }, setOf("chorus")).isFailure
        )
    }

    @Test
    fun resolve_bothRolesUnchanged() {
        val registry = EngineRegistry(
            listOf(ScriptedSpoolEngine("system", listOf("system:narr", "system:dial")))
        )
        val store = MemRenderVoiceStore("system:narr", "system:dial")
        val outcome = RenderVoices.resolve(registry, store) { "v1" }
        assertTrue(outcome.isSuccess)
        val resolved = outcome.getOrThrow()
        assertEquals("system:narr", resolved.fingerprint.voices["narrator"])
        assertEquals("system:dial", resolved.fingerprint.voices["dialogue"])
    }
}
