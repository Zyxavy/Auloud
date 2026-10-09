package app.auloud.player.render

import android.net.Uri
import app.auloud.player.data.ProgressEntity
import app.auloud.player.data.ProgressRepository
import app.auloud.player.storage.BundleStorage
import app.auloud.player.storage.FakeSharedPreferences
import app.auloud.player.tts.PrefsTtsStore
import app.auloud.player.tts.TtsRole
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN9: [RenderPanelViewModel] on plain JVM (map-backed fakes).
 *
 * Covers the brief Verify: view-model states, estimate ranges, option
 * mapping, error mapping and the delete flow. The dispatcher is
 * [Dispatchers.Unconfined], so loads complete eagerly with no
 * test-coroutines artifact.
 */
class RenderPanelViewModelTest {

    private class FakeStorage(val files: MutableMap<String, String>) : BundleStorage {
        override fun listBundleDirs(root: String): List<String> = emptyList()
        override fun readText(path: String): String =
            files[path] ?: throw IOException("missing: $path")
        override fun exists(path: String): Boolean = files.containsKey(path)
        override fun audioUri(bundleDir: String, relPath: String): Uri =
            throw UnsupportedOperationException("not used by the panel")
        override fun coverUri(bundleDirPath: String, coverRel: String): String? = null
    }

    private class FakeIo(val files: MutableMap<String, String>) : RenderFileIo {
        var sleeps = 0
        val noSleep: (Long) -> Unit = { sleeps++ }

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

    private class FakeProgress(val rows: MutableMap<String, ProgressEntity> = mutableMapOf()) :
        ProgressRepository {
        override suspend fun save(
            bookId: String,
            chapterIndex: Int,
            positionMs: Long,
            sentenceSid: Int?
        ): Result<Unit> {
            rows[bookId] = ProgressEntity(bookId, chapterIndex, positionMs, 0L, sentenceSid)
            return Result.success(Unit)
        }
        override suspend fun load(bookId: String): Result<ProgressEntity?> =
            Result.success(rows[bookId])
        override suspend fun delete(bookId: String): Result<Unit> {
            rows.remove(bookId)
            return Result.success(Unit)
        }
    }

    private val bundleDir = "/books/b1"

    private fun manifestPartialJson(): String = """
        {
          "spec_version": "2.0",
          "id": "b1",
          "title": "Partial",
          "type": "epub",
          "render_state": "partial",
          "audio": {"format": "m4a", "channels": 1, "sample_rate": 24000, "bitrate_kbps": 64, "cbr": true},
          "voices": {
            "narrator": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0},
            "dialogue": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0}
          },
          "chapters": [
            {"index": 1, "title": "Ch 1", "audio": "audio/ch001.m4a",
             "text": "text/ch001.json", "duration_ms": 60000},
            {"index": 2, "title": "Ch 2", "text": "text/ch002.json"},
            {"index": 3, "title": "Ch 3", "text": "text/ch003.json"}
          ]
        }
        """.trimIndent()

    private fun timedChapterJson(): String =
        """{"spec_version":"2.0","chapter":1,"title":"Ch 1","duration_ms":60000,"blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","start_ms":0,"end_ms":1000,"text":"Done. "}]}]}"""

    private fun untimedChapterJson(chapter: Int, text: String): String =
        """{"spec_version":"2.0","chapter":$chapter,"title":"Ch $chapter","blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","text":"$text"}]}]}"""

    private fun partialFiles(): HashMap<String, String> = hashMapOf(
        "$bundleDir/manifest.json" to manifestPartialJson(),
        "$bundleDir/text/ch001.json" to timedChapterJson(),
        "$bundleDir/text/ch002.json" to untimedChapterJson(2, "One two three four. "),
        "$bundleDir/text/ch003.json" to untimedChapterJson(3, "Five six. "),
        "$bundleDir/audio/ch001.m4a" to "fake-audio"
    )

    private inner class Harness(files: HashMap<String, String> = HashMap()) {
        val sharedFiles: HashMap<String, String> = files
        val storage = FakeStorage(sharedFiles)
        val io = FakeIo(sharedFiles)
        val progress = FakeProgress(
            mutableMapOf("b1" to ProgressEntity("b1", 1, 0L, 0L))
        )
        val voiceStore = PrefsTtsStore(FakeSharedPreferences()).apply {
            setVoiceId(TtsRole.Narrator, "system:narrator-voice")
            setVoiceId(TtsRole.Dialogue, "system:dialogue-voice")
        }
        var starts = mutableListOf<Triple<Int, String, Int>>()
        var pauses = 0
        var resumes = 0
        var cancels = 0
        var changed = 0
        // VS5: live engine versions plus re-render starts (defaults keep
        // every RN9 test on the old path: unknown versions, no starts).
        var versionOf: (String) -> String? = { null }
        var rerenderStarts = mutableListOf<Pair<Int, RerenderMode>>()

        fun viewModel(): RenderPanelViewModel {
            return RenderPanelViewModel(
                bookId = "b1",
                bundleDir = bundleDir,
                storage = storage,
                progress = progress,
                voices = voiceStore,
                fileIo = io,
                onStartRender = { chapter, scope, nextN ->
                    starts.add(Triple(chapter, scope, nextN))
                },
                onPauseRender = { pauses++ },
                onResumeRender = { resumes++ },
                onCancelRender = { cancels++ },
                onBookChanged = { changed++ },
                versionOf = versionOf,
                onStartRerender = { chapter, mode ->
                    rerenderStarts.add(chapter to mode)
                },
                dispatcher = Dispatchers.Unconfined
            )
        }
    }

    @Test
    fun loads_titleCountsEstimateAndVoices() {
        val harness = Harness(partialFiles())
        val vm = harness.viewModel()
        try {
            val state = vm.state.value
            assertEquals(false, state.isLoading)
            assertNull(state.manifestError)
            assertEquals("Partial", state.title)
            assertEquals(3, state.chapterCount)
            assertEquals(1, state.renderedCount)
            assertEquals(listOf(1, 2), state.unrenderedPositions)
            assertEquals(1, state.readingChapter)
            assertEquals(RenderOption.WHOLE_BOOK, state.option)
            assertEquals(2, state.planSize)
            assertEquals(1_600L + 800L, state.estimate.audioMs)
            assertEquals(0, state.estimate.unknownChapters)
            assertTrue(state.estimate.wallFastMs < state.estimate.wallSlowMs)
            assertTrue("was: ${state.voicesLine}", "system:narrator-voice" in state.voicesLine)
            assertTrue("was: ${state.voicesLine}", "system:dialogue-voice" in state.voicesLine)
            assertEquals(ChapterRenderState.RENDERED, state.chapterStates[0])
            assertEquals(ChapterRenderState.UNRENDERED, state.chapterStates[1])
        } finally {
            vm.clear()
        }
    }

    @Test
    fun unreadableChapterJson_countsUnknownButLoads() {
        val files = partialFiles()
        files.remove("$bundleDir/text/ch003.json")
        val harness = Harness(files)
        val vm = harness.viewModel()
        try {
            val state = vm.state.value
            assertEquals(1, state.estimate.unknownChapters)
            assertEquals(1_600L + RenderEstimates.DEFAULT_CHAPTER_AUDIO_MS, state.estimate.audioMs)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun selectOption_nextN_recomputesEstimate() {
        val harness = Harness(partialFiles())
        val vm = harness.viewModel()
        try {
            vm.selectOption(RenderOption.NEXT_N)
            vm.setNextN(1)
            val state = vm.state.value
            assertEquals(1, state.planSize)
            assertEquals(1_600L, state.estimate.audioMs)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun setNextN_clampsToChapterRange() {
        val harness = Harness(partialFiles())
        val vm = harness.viewModel()
        try {
            vm.setNextN(99)
            assertEquals(3, vm.state.value.nextN)
            vm.setNextN(0)
            assertEquals(1, vm.state.value.nextN)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun start_emptyPlan_reportsPlainError() {
        val files = partialFiles()
        files["$bundleDir/manifest.json"] = manifestPartialJson()
            .replace("\"render_state\": \"partial\"", "\"render_state\": \"complete\"")
            .replace(
                """"index": 2, "title": "Ch 2", "text": "text/ch002.json"""",
                """"index": 2, "title": "Ch 2", "audio": "audio/ch002.m4a", """ +
                    """"text": "text/ch002.json", "duration_ms": 1000"""
            )
            .replace(
                """"index": 3, "title": "Ch 3", "text": "text/ch003.json"""",
                """"index": 3, "title": "Ch 3", "audio": "audio/ch003.m4a", """ +
                    """"text": "text/ch003.json", "duration_ms": 1000"""
            )
        val harness = Harness(files)
        val vm = harness.viewModel()
        try {
            vm.start()
            assertTrue(harness.starts.isEmpty())
            assertEquals(
                "There is nothing to render. Every planned chapter already has audio.",
                vm.state.value.error
            )
        } finally {
            vm.clear()
        }
    }

    @Test
    fun start_validPlan_callsCallbackWithScope() {
        val harness = Harness(partialFiles())
        val vm = harness.viewModel()
        try {
            vm.start()
            assertEquals(listOf(Triple(1, RenderService.SCOPE_WHOLE_BOOK, 5)), harness.starts)
            assertNull(vm.state.value.error)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun renderChapter_startsSingleChapter() {
        val harness = Harness(partialFiles())
        val vm = harness.viewModel()
        try {
            vm.renderChapter(2)
            assertEquals(listOf(Triple(2, RenderService.SCOPE_NEXT_N, 1)), harness.starts)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun pauseResumeCancel_forwardToService() {
        val harness = Harness(partialFiles())
        val vm = harness.viewModel()
        try {
            vm.pause()
            vm.resume()
            vm.cancel()
            assertEquals(1, harness.pauses)
            assertEquals(1, harness.resumes)
            assertEquals(1, harness.cancels)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun deleteChapterByPos_stripsAudioAndReloads() {
        val harness = Harness(partialFiles())
        val vm = harness.viewModel()
        try {
            vm.deleteChapterByPos(0)
            assertEquals(1, harness.changed)
            assertNull(vm.state.value.error)
            val state = vm.state.value
            assertEquals(0, state.renderedCount)
            assertEquals(listOf(0, 1, 2), state.unrenderedPositions)
            assertEquals(ChapterRenderState.UNRENDERED, state.chapterStates[0])
        } finally {
            vm.clear()
        }
    }

    @Test
    fun deleteChapterByPos_failure_reportsPlainError() {
        val harness = Harness(partialFiles())
        harness.sharedFiles.clear()
        harness.sharedFiles["$bundleDir/manifest.json"] = manifestPartialJson()
        val vm = harness.viewModel()
        try {
            vm.deleteChapterByPos(0)
            assertEquals(0, harness.changed)
            val error = vm.state.value.error
            assertTrue("was: $error", error?.startsWith("Rendering stopped") == true)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun manifestUnreadable_reportsPlainError() {
        val harness = Harness(HashMap())
        val vm = harness.viewModel()
        try {
            val state = vm.state.value
            assertEquals(false, state.isLoading)
            assertTrue("was: ${state.manifestError}", "unreadable" in (state.manifestError ?: ""))
        } finally {
            vm.clear()
        }
    }

    @Test
    fun jobLoaded_stateCarriesProgressAndRowStates() {
        val files = partialFiles()
        val plan = RenderPlan(
            bookId = "b1",
            chapterCount = 3,
            startChapter = 1,
            scope = RenderScope.WholeBook,
            orderedChapters = listOf(1, 2),
            createdAt = 0L
        )
        val job = RenderJob(
            bookId = "b1",
            state = RenderJobState.RUNNING,
            plan = plan,
            completedChapters = emptyList(),
            currentChapter = 2,
            createdAt = 0L,
            updatedAt = 0L
        )
        files["$bundleDir/render-job.json"] = RenderStateStore.toJson(job)
        val harness = Harness(files)
        val vm = harness.viewModel()
        try {
            val state = vm.state.value
            assertEquals(RenderJobState.RUNNING, state.jobState)
            assertEquals(0, state.jobDone)
            assertEquals(2, state.jobTotal)
            assertEquals(3, state.jobCurrentNumber)
            assertEquals(ChapterRenderState.RENDERING, state.chapterStates[2])
            assertEquals(ChapterRenderState.UNRENDERED, state.chapterStates[1])
        } finally {
            vm.clear()
        }
    }

    @Test
    fun voicesBlank_summarySaysNotChosen() {
        val harness = Harness(partialFiles())
        harness.voiceStore.setVoiceId(TtsRole.Narrator, "")
        harness.voiceStore.setVoiceId(TtsRole.Dialogue, "")
        val vm = harness.viewModel()
        try {
            assertTrue("was: ${vm.state.value.voicesLine}", "not chosen" in vm.state.value.voicesLine)
        } finally {
            vm.clear()
        }
    }

    // VS5: staleness over VS2/VS3 logic (badges, re-render wiring,
    // delete-stale-audio semantics). The voice store globals
    // (system:narrator-voice plus system:dialogue-voice) are the book
    // voices via the placeholder fallback; version "v1" is live.

    private fun fpJson(fp: RenderFingerprint): String =
        Json.encodeToString(JsonObject.serializer(), fp.toJsonObject())

    private fun staleTimedJson(chapter: Int, dialogue: Boolean): String {
        val first =
            """{"sid":1,"speaker":"narrator","start_ms":0,"end_ms":1000,"text":"He said. "}"""
        val second =
            """{"sid":2,"speaker":"dialogue","start_ms":1000,"end_ms":2000,"text":"Hi. "}"""
        val sentences = if (dialogue) "$first,$second" else first
        return """{"spec_version":"2.0","chapter":$chapter,"title":"Ch $chapter","duration_ms":60000,"blocks":[{"id":1,"type":"para","sentences":[$sentences]}]}"""
    }

    private fun staleManifestJson(ch2DialogueVoice: String): String {
        val ch1Fp = fpJson(
            RenderFingerprint(
                engine = "system",
                voices = mapOf("narrator" to "system:narrator-voice"),
                speeds = mapOf("narrator" to 1.0f),
                engineVersions = mapOf("system" to "v1")
            )
        )
        val ch2Fp = fpJson(
            RenderFingerprint(
                engine = "system",
                voices = mapOf(
                    "narrator" to "system:narrator-voice",
                    "dialogue" to ch2DialogueVoice
                ),
                speeds = mapOf("narrator" to 1.0f, "dialogue" to 1.0f),
                engineVersions = mapOf("system" to "v1")
            )
        )
        return """
        {
          "spec_version": "2.0",
          "id": "b1",
          "title": "Stale",
          "type": "epub",
          "render_state": "partial",
          "audio": {"format": "m4a", "channels": 1, "sample_rate": 24000, "bitrate_kbps": 64, "cbr": true},
          "voices": {
            "narrator": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0},
            "dialogue": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0}
          },
          "chapters": [
            {"index": 1, "title": "Ch 1", "audio": "audio/ch001.m4a",
             "text": "text/ch001.json", "duration_ms": 60000,
             "render_fingerprint": $ch1Fp},
            {"index": 2, "title": "Ch 2", "audio": "audio/ch002.m4a",
             "text": "text/ch002.json", "duration_ms": 60000,
             "render_fingerprint": $ch2Fp},
            {"index": 3, "title": "Ch 3", "text": "text/ch003.json"}
          ]
        }
        """.trimIndent()
    }

    private fun staleFiles(dialogueVoice: String = "system:old-dialogue"): HashMap<String, String> =
        hashMapOf(
            "$bundleDir/manifest.json" to staleManifestJson(dialogueVoice),
            "$bundleDir/text/ch001.json" to staleTimedJson(1, dialogue = false),
            "$bundleDir/text/ch002.json" to staleTimedJson(2, dialogue = true),
            "$bundleDir/text/ch003.json" to untimedChapterJson(3, "Five six. "),
            "$bundleDir/audio/ch001.m4a" to "fake-audio",
            "$bundleDir/audio/ch002.m4a" to "fake-audio"
        )

    private fun staleHarness(dialogueVoice: String = "system:old-dialogue"): Harness {
        val harness = Harness(staleFiles(dialogueVoice))
        harness.versionOf = { namespace -> if (namespace == "system") "v1" else null }
        return harness
    }

    @Test
    fun stale_loadsStatesAndSummary() {
        val harness = staleHarness()
        val vm = harness.viewModel()
        try {
            val state = vm.state.value
            assertEquals(ChapterStaleState.CURRENT, state.staleStates[0])
            assertEquals(ChapterStaleState.STALE, state.staleStates[1])
            assertEquals(ChapterStaleState.NOT_RENDERED, state.staleStates[2])
            assertEquals(1, state.staleSummary?.current)
            assertEquals(1, state.staleSummary?.stale)
            assertEquals(0, state.staleSummary?.outdated)
            assertEquals(1, state.staleSummary?.notRendered)
            assertEquals(false, state.staleReadOnly)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun rerenderStale_startsStaleOnlyFromReading() {
        val harness = staleHarness()
        val vm = harness.viewModel()
        try {
            // Saved progress sits at 0-based chapter 1 (see Harness).
            vm.rerenderStale()
            assertEquals(listOf(1 to RerenderMode.STALE_ONLY), harness.rerenderStarts)
            assertNull(vm.state.value.error)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun rerenderChapterStale_startsFromChapter() {
        val harness = staleHarness()
        val vm = harness.viewModel()
        try {
            vm.rerenderChapterStale(1)
            assertEquals(listOf(1 to RerenderMode.STALE_ONLY), harness.rerenderStarts)
            assertNull(vm.state.value.error)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun rerenderStale_nothingStale_reportsPlainError() {
        val harness = staleHarness(dialogueVoice = "system:dialogue-voice")
        val vm = harness.viewModel()
        try {
            assertEquals(0, vm.state.value.staleSummary?.stale)
            vm.rerenderStale()
            assertTrue(harness.rerenderStarts.isEmpty())
            assertEquals(
                "Every rendered chapter already matches the voices.",
                vm.state.value.error
            )
        } finally {
            vm.clear()
        }
    }

    @Test
    fun deleteStaleAudio_removesOnlyStaleChapters() {
        val harness = staleHarness()
        val vm = harness.viewModel()
        try {
            vm.deleteStaleAudio()
            assertEquals(1, harness.changed)
            assertNull(vm.state.value.error)
            // Stale ch2 lost its audio and reads unrendered; current ch1
            // is untouched and still playable.
            assertTrue("$bundleDir/audio/ch001.m4a" in harness.sharedFiles)
            assertTrue("$bundleDir/audio/ch002.m4a" !in harness.sharedFiles)
            val state = vm.state.value
            assertEquals(ChapterStaleState.CURRENT, state.staleStates[0])
            assertEquals(ChapterStaleState.NOT_RENDERED, state.staleStates[1])
            assertEquals(0, state.staleSummary?.stale)
            assertEquals(1, state.staleSummary?.current)
            assertEquals(2, state.staleSummary?.notRendered)
            assertEquals(ChapterRenderState.UNRENDERED, state.chapterStates[1])
            assertEquals(ChapterRenderState.RENDERED, state.chapterStates[0])
        } finally {
            vm.clear()
        }
    }

    @Test
    fun readOnly_noStaleUiAndNoRerender() {
        val files = staleFiles()
        files["$bundleDir/manifest.json"] = staleManifestJson("system:old-dialogue").replace(
            """"dialogue": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0}""",
            """"dialogue": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0}, "alice": {"engine": "pc", "voice": "alice", "speed": 1.0, "pitch": 1.0}"""
        )
        val harness = Harness(files)
        harness.versionOf = { namespace -> if (namespace == "system") "v1" else null }
        val vm = harness.viewModel()
        try {
            val state = vm.state.value
            assertNull(state.staleSummary)
            assertTrue(state.staleStates.isEmpty())
            assertEquals(true, state.staleReadOnly)
            vm.rerenderStale()
            assertTrue(harness.rerenderStarts.isEmpty())
        } finally {
            vm.clear()
        }
    }
}
