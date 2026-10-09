package app.auloud.player.render

import android.net.Uri
import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.Manifest
import app.auloud.player.data.ProgressEntity
import app.auloud.player.data.ProgressRepository
import app.auloud.player.playback.PlaybackQueue
import app.auloud.player.storage.BundleStorage
import app.auloud.player.storage.FakeSharedPreferences
import app.auloud.player.tts.BookVoiceViewModel
import app.auloud.player.tts.BookVoices
import app.auloud.player.tts.EngineRegistry
import app.auloud.player.tts.FakeAudioPlayer
import app.auloud.player.tts.FakeTtsEngine
import app.auloud.player.tts.PrefsTtsStore
import app.auloud.player.tts.SynthesizedAudio
import app.auloud.player.tts.TtsCapabilities
import app.auloud.player.tts.TtsEngine
import app.auloud.player.tts.TtsRole
import app.auloud.player.tts.TtsVoice
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VS6: re-render edge cases plus guards (plan section 5 VS6).
 *
 * Pure JVM with map-backed fakes (no Android, no service, no encoder).
 * Each failure mode pins two things: the RIGHT message (read-only
 * explanation, missing piece plus the voice-screen fix path, needed vs
 * free numbers, unset role) and intact audio plus manifest (byte-identical
 * snapshots on every refusal, no job started, no write).
 *
 * Entry points covered: the pure [RerenderGuards] (the seam the service
 * intent calls), the panel bulk button plus per-chapter action
 * ([RenderPanelViewModel.rerenderStale] plus
 * [RenderPanelViewModel.rerenderChapterStale]), and the voice-screen
 * apply flow ([BookVoiceViewModel]). Playback never consults the guards
 * (it keys on audio presence only), pinned by the unaffected test.
 */
class RerenderGuardsTest {

    private class FakeStorage(val files: MutableMap<String, String>) : BundleStorage {
        override fun listBundleDirs(root: String): List<String> = emptyList()
        override fun readText(path: String): String =
            files[path] ?: throw IOException("missing: $path")
        override fun exists(path: String): Boolean = files.containsKey(path)
        override fun audioUri(bundleDir: String, relPath: String): Uri =
            throw UnsupportedOperationException("not used here")
        override fun coverUri(bundleDirPath: String, coverRel: String): String? = null
        override fun writeBytes(path: String, bytes: ByteArray) {
            files[path] = String(bytes, Charsets.UTF_8)
        }
    }

    private class FakeIo(val files: MutableMap<String, String>) : RenderFileIo {
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

    private fun registryFull(): EngineRegistry = EngineRegistry(
        listOf(
            FakeTtsEngine("system", listOf("system:narr", "system:dial", "system:new-narr")),
            FakeTtsEngine("piper", listOf("piper:voice-p"))
        )
    )

    private fun registryNoPiper(): EngineRegistry = EngineRegistry(
        listOf(FakeTtsEngine("system", listOf("system:narr", "system:dial")))
    )

    private fun voicesOf(narrator: String, dialogue: String?): BookVoices = BookVoices(
        narratorVoiceId = narrator,
        dialogueVoiceId = dialogue,
        narratorSpeed = 1.0f,
        dialogueSpeed = 1.0f,
        readOnly = false
    )

    private fun fpJson(
        narratorVoice: String = "system:narr",
        dialogueVoice: String = "system:dial"
    ): String {
        val fp = RenderFingerprint(
            engine = "system",
            voices = mapOf("narrator" to narratorVoice, "dialogue" to dialogueVoice),
            speeds = mapOf("narrator" to 1.0f, "dialogue" to 1.0f),
            engineVersions = mapOf("system" to "v1")
        )
        return Json.encodeToString(JsonObject.serializer(), fp.toJsonObject())
    }

    private fun timedJson(chapter: Int, dialogue: Boolean): String {
        val first =
            """{"sid":1,"speaker":"narrator","start_ms":0,"end_ms":1000,"text":"He said. "}"""
        val second =
            """{"sid":2,"speaker":"dialogue","start_ms":1000,"end_ms":2000,"text":"Hi. "}"""
        val sentences = if (dialogue) "$first,$second" else first
        return """{"spec_version":"2.0","chapter":$chapter,"title":"Ch $chapter","duration_ms":60000,"blocks":[{"id":1,"type":"para","sentences":[$sentences]}]}"""
    }

    private fun untimedJson(chapter: Int): String =
        """{"spec_version":"2.0","chapter":$chapter,"title":"Ch $chapter","blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","text":"Once. "}]}]}"""

    private fun manifestRenderedJson(
        narratorEngine: String = "system",
        narratorVoice: String = "narr",
        dialogueEngine: String = "system",
        dialogueVoice: String = "dial",
        ch2DialogueVoice: String = "system:dial",
        readOnlyExtra: Boolean = false
    ): String {
        val voicesBlock = if (readOnlyExtra) {
            """"narrator": {"engine": "$narratorEngine", "voice": "$narratorVoice", "speed": 1.0, "pitch": 1.0}, "dialogue": {"engine": "$dialogueEngine", "voice": "$dialogueVoice", "speed": 1.0, "pitch": 1.0}, "alice": {"engine": "pc", "voice": "alice", "speed": 1.0, "pitch": 1.0}"""
        } else {
            """"narrator": {"engine": "$narratorEngine", "voice": "$narratorVoice", "speed": 1.0, "pitch": 1.0}, "dialogue": {"engine": "$dialogueEngine", "voice": "$dialogueVoice", "speed": 1.0, "pitch": 1.0}"""
        }
        return """
        {
          "spec_version": "2.0",
          "id": "b1",
          "title": "Guarded",
          "type": "epub",
          "render_state": "partial",
          "audio": {"format": "m4a", "channels": 1, "sample_rate": 24000, "bitrate_kbps": 64, "cbr": true},
          "voices": {
            $voicesBlock
          },
          "chapters": [
            {"index": 1, "title": "Ch 1", "audio": "audio/ch001.m4a",
             "text": "text/ch001.json", "duration_ms": 60000,
             "render_fingerprint": ${fpJson("system:narr", "system:dial")}},
            {"index": 2, "title": "Ch 2", "audio": "audio/ch002.m4a",
             "text": "text/ch002.json", "duration_ms": 60000,
             "render_fingerprint": ${fpJson("system:narr", ch2DialogueVoice)}},
            {"index": 3, "title": "Ch 3", "text": "text/ch003.json"}
          ]
        }
        """.trimIndent()
    }

    private fun renderedFiles(
        narratorEngine: String = "system",
        narratorVoice: String = "narr",
        dialogueEngine: String = "system",
        dialogueVoice: String = "dial",
        ch2DialogueVoice: String = "system:old-dial",
        readOnlyExtra: Boolean = false
    ): HashMap<String, String> = hashMapOf(
        "$bundleDir/manifest.json" to manifestRenderedJson(
            narratorEngine, narratorVoice, dialogueEngine, dialogueVoice,
            ch2DialogueVoice, readOnlyExtra
        ),
        "$bundleDir/text/ch001.json" to timedJson(1, dialogue = false),
        "$bundleDir/text/ch002.json" to timedJson(2, dialogue = true),
        "$bundleDir/text/ch003.json" to untimedJson(3),
        "$bundleDir/audio/ch001.m4a" to "old-audio-1",
        "$bundleDir/audio/ch002.m4a" to "old-audio-2"
    )

    private fun unrenderedFiles(): HashMap<String, String> = hashMapOf(
        "$bundleDir/manifest.json" to """
        {
          "spec_version": "2.0",
          "id": "b1",
          "title": "Fresh",
          "type": "epub",
          "render_state": "none",
          "audio": {"format": "m4a", "channels": 1, "sample_rate": 24000, "bitrate_kbps": 64, "cbr": true},
          "voices": {
            "narrator": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0},
            "dialogue": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0}
          },
          "chapters": [
            {"index": 1, "title": "Ch 1", "text": "text/ch001.json"},
            {"index": 2, "title": "Ch 2", "text": "text/ch002.json"}
          ]
        }
        """.trimIndent(),
        "$bundleDir/text/ch001.json" to untimedJson(1),
        "$bundleDir/text/ch002.json" to untimedJson(2)
    )

    // Pure guard unit tests (the seam the service intent calls).

    @Test
    fun readOnly_refusesWithPlainMessage() {
        val refused = RerenderGuards.checkNotReadOnly(true)
        assertTrue(refused.isFailure)
        assertEquals(BookVoices.READ_ONLY_MESSAGE, refused.exceptionOrNull()?.message)
        assertTrue(RerenderGuards.checkNotReadOnly(false).isSuccess)
    }

    @Test
    fun voices_engineRemoved_namesEngineAndFix() {
        val book = voicesOf("piper:voice-p", "piper:voice-p")
        val outcome = RerenderGuards.checkVoicesAvailable(book, registryNoPiper())
        assertTrue(outcome.isFailure)
        val message = outcome.exceptionOrNull()?.message ?: ""
        assertTrue("was: $message", "piper:voice-p" in message)
        assertTrue("was: $message", "piper" in message)
        assertTrue("was: $message", "engine not installed" in message)
        assertTrue("was: $message", "voice screen" in message)
    }

    @Test
    fun voices_voiceUninstalled_namesVoiceAndFix() {
        val book = voicesOf("system:gone", "system:dial")
        val outcome = RerenderGuards.checkVoicesAvailable(book, registryFull())
        assertTrue(outcome.isFailure)
        val message = outcome.exceptionOrNull()?.message ?: ""
        assertTrue("was: $message", "system:gone" in message)
        assertTrue("was: $message", "missing model pack or voice" in message)
        assertTrue("was: $message", "voice screen" in message)
    }

    @Test
    fun voices_piperPackDeleted_readsAsMissingVoice() {
        val emptyPiper = EngineRegistry(
            listOf(
                FakeTtsEngine("system", listOf("system:narr", "system:dial")),
                FakeTtsEngine("piper", emptyList())
            )
        )
        val book = voicesOf("piper:voice-p", null)
        val outcome = RerenderGuards.checkVoicesAvailable(book, emptyPiper)
        assertTrue(outcome.isFailure)
        val message = outcome.exceptionOrNull()?.message ?: ""
        assertTrue("was: $message", "piper:voice-p" in message)
        assertTrue("was: $message", "missing model pack or voice" in message)
        assertTrue("was: $message", "voice screen" in message)
    }

    @Test
    fun voices_readOnlyWinsOverVoiceMessage() {
        val book = BookVoices(
            narratorVoiceId = "pc:alice",
            dialogueVoiceId = null,
            narratorSpeed = 1.0f,
            dialogueSpeed = 1.0f,
            readOnly = true
        )
        val outcome = RerenderGuards.checkVoicesAvailable(book, registryFull())
        assertTrue(outcome.isFailure)
        assertEquals(BookVoices.READ_ONLY_MESSAGE, outcome.exceptionOrNull()?.message)
    }

    @Test
    fun voices_blankNarrator_namesRoleAndFix() {
        val book = voicesOf("", "system:dial")
        val outcome = RerenderGuards.checkVoicesAvailable(book, registryFull())
        assertTrue(outcome.isFailure)
        val message = outcome.exceptionOrNull()?.message ?: ""
        assertTrue("was: $message", "narrator" in message)
        assertTrue("was: $message", "not set" in message)
        assertTrue("was: $message", "voice screen" in message)
    }

    @Test
    fun voices_dialogueUnset_validatesSingleRole() {
        val book = voicesOf("system:narr", null)
        assertTrue(RerenderGuards.checkVoicesAvailable(book, registryFull()).isSuccess)
    }

    @Test
    fun swapBytes_mathMatchesVs2Summary() {
        val audioMs = 60_000L
        val estimate = RenderEstimates.estimateForAudioMs(audioMs)
        assertEquals(
            estimate.audioBytes + estimate.totalBytes,
            RerenderGuards.swapBytesFor(audioMs)
        )
        assertEquals(480_000L, estimate.audioBytes)
        assertTrue(estimate.spoolBytes > 0L)
        assertEquals(estimate.audioBytes + estimate.spoolBytes, estimate.totalBytes)
    }

    @Test
    fun storage_refusesWithNeededVsFreeNumbers() {
        val staleAudioMs = 60_000L
        val needed = RerenderGuards.swapBytesFor(staleAudioMs)
        val refused = RerenderGuards.checkSwapStorage(needed - 1, staleAudioMs)
        assertTrue(refused.isFailure)
        val message = refused.exceptionOrNull()?.message ?: ""
        assertTrue("was: $message", needed.toString() in message)
        assertTrue("was: $message", (needed - 1).toString() in message)
        assertTrue("was: $message", "old plus new" in message)
        assertTrue(RerenderGuards.checkSwapStorage(needed, staleAudioMs).isSuccess)
        assertTrue(RerenderGuards.checkSwapStorage(Long.MAX_VALUE, staleAudioMs).isSuccess)
    }

    @Test
    fun storage_zeroStaleAudio_needsNothing() {
        assertEquals(0L, RerenderGuards.swapBytesFor(0L))
        assertTrue(RerenderGuards.checkSwapStorage(0L, 0L).isSuccess)
    }

    // Playback pin: existing audio keeps playing while planning refuses.

    @Test
    fun playbackUnaffected_whilePlanningRefuses() {
        val manifestJson = """
        {
          "spec_version": "2.0",
          "id": "b1",
          "title": "Played",
          "type": "epub",
          "render_state": "complete",
          "audio": {"format": "m4a", "channels": 1, "sample_rate": 24000, "bitrate_kbps": 64, "cbr": true},
          "voices": {
            "narrator": {"engine": "piper", "voice": "voice-p", "speed": 1.0, "pitch": 1.0},
            "dialogue": {"engine": "piper", "voice": "voice-p", "speed": 1.0, "pitch": 1.0}
          },
          "chapters": [
            {"index": 1, "title": "Ch 1", "audio": "audio/ch001.m4a",
             "text": "text/ch001.json", "duration_ms": 60000,
             "render_fingerprint": ${fpJson()}},
            {"index": 2, "title": "Ch 2", "audio": "audio/ch002.m4a",
             "text": "text/ch002.json", "duration_ms": 60000,
             "render_fingerprint": ${fpJson()}}
          ]
        }
        """.trimIndent()
        val manifest = BundleParser.parseText(manifestJson).getOrThrow()
        assertEquals(PlaybackQueue.PlaybackGate.Playable, PlaybackQueue.gateFor(manifest))
        assertTrue(manifest.chapters.all(PlaybackQueue::isRenderedChapter))
        val book = BookVoices.read(manifest, PrefsTtsStore(FakeSharedPreferences()))
        val refused = RerenderGuards.checkVoicesAvailable(book, registryNoPiper())
        assertTrue(refused.isFailure)
        val message = refused.exceptionOrNull()?.message ?: ""
        assertTrue("was: $message", "piper" in message)
        assertTrue("was: $message", "voice screen" in message)
        assertEquals(PlaybackQueue.PlaybackGate.Playable, PlaybackQueue.gateFor(manifest))
        assertTrue(manifest.chapters.all(PlaybackQueue::isRenderedChapter))
    }

    // Panel entry points: bulk button plus per-chapter action.

    private inner class PanelHarness(
        files: HashMap<String, String>,
        val registry: EngineRegistry? = null,
        val free: Long = Long.MAX_VALUE,
        narratorGlobal: String = "system:narr",
        dialogueGlobal: String = "system:dial"
    ) {
        val shared: HashMap<String, String> = files
        val storage = FakeStorage(shared)
        val io = FakeIo(shared)
        val progress = FakeProgress(
            mutableMapOf("b1" to ProgressEntity("b1", 1, 0L, 0L))
        )
        val voiceStore = PrefsTtsStore(FakeSharedPreferences()).apply {
            setVoiceId(TtsRole.Narrator, narratorGlobal)
            setVoiceId(TtsRole.Dialogue, dialogueGlobal)
        }
        var policy = RenderPolicy(chargingOnly = true)
        var rerenderStarts = mutableListOf<Pair<Int, RerenderMode>>()
        var versionOf: (String) -> String? = { ns -> if (ns == "system") "v1" else null }

        fun viewModel(): RenderPanelViewModel = RenderPanelViewModel(
            bookId = "b1",
            bundleDir = bundleDir,
            storage = storage,
            progress = progress,
            voices = voiceStore,
            fileIo = io,
            policy = policy,
            onPolicyChange = { policy = it },
            onStartRender = { _, _, _ -> },
            onPauseRender = {},
            onResumeRender = {},
            onCancelRender = {},
            onBookChanged = {},
            versionOf = versionOf,
            onStartRerender = { chapter, mode -> rerenderStarts.add(chapter to mode) },
            registry = registry,
            freeBytes = { free },
            dispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun panel_bulkReadOnly_refusesWithPlainMessageAndNoStart() {
        val files = renderedFiles(readOnlyExtra = true)
        val beforeManifest = files["$bundleDir/manifest.json"]
        val beforeAudio = files["$bundleDir/audio/ch002.m4a"]
        val harness = PanelHarness(files, registry = registryFull())
        val vm = harness.viewModel()
        try {
            assertTrue(vm.state.value.staleReadOnly)
            vm.rerenderStale()
            assertTrue(harness.rerenderStarts.isEmpty())
            assertEquals(BookVoices.READ_ONLY_MESSAGE, vm.state.value.error)
            assertEquals(beforeManifest, files["$bundleDir/manifest.json"])
            assertEquals(beforeAudio, files["$bundleDir/audio/ch002.m4a"])
        } finally {
            vm.clear()
        }
    }

    @Test
    fun panel_perChapterReadOnly_refusesWithPlainMessageAndNoStart() {
        val files = renderedFiles(readOnlyExtra = true)
        val beforeManifest = files["$bundleDir/manifest.json"]
        val harness = PanelHarness(files, registry = registryFull())
        val vm = harness.viewModel()
        try {
            vm.rerenderChapterStale(1)
            assertTrue(harness.rerenderStarts.isEmpty())
            assertEquals(BookVoices.READ_ONLY_MESSAGE, vm.state.value.error)
            assertEquals(beforeManifest, files["$bundleDir/manifest.json"])
        } finally {
            vm.clear()
        }
    }

    @Test
    fun panel_bulkMissingVoice_refusesNamingPieceAndFix() {
        val files = renderedFiles(
            narratorEngine = "piper", narratorVoice = "voice-p",
            dialogueEngine = "piper", dialogueVoice = "voice-p",
            ch2DialogueVoice = "system:dial"
        )
        val beforeManifest = files["$bundleDir/manifest.json"]
        val beforeAudio = files["$bundleDir/audio/ch001.m4a"]
        val harness = PanelHarness(files, registry = registryNoPiper())
        harness.versionOf = { ns -> if (ns == "piper" || ns == "system") "v1" else null }
        val vm = harness.viewModel()
        try {
            vm.rerenderStale()
            assertTrue(harness.rerenderStarts.isEmpty())
            val error = vm.state.value.error ?: ""
            assertTrue("was: $error", "piper" in error)
            assertTrue("was: $error", "voice screen" in error)
            assertEquals(beforeManifest, files["$bundleDir/manifest.json"])
            assertEquals(beforeAudio, files["$bundleDir/audio/ch001.m4a"])
        } finally {
            vm.clear()
        }
    }

    @Test
    fun panel_perChapterMissingVoice_refusesWithFix() {
        val files = renderedFiles(
            narratorEngine = "piper", narratorVoice = "voice-p",
            dialogueEngine = "piper", dialogueVoice = "voice-p",
            ch2DialogueVoice = "system:dial"
        )
        val harness = PanelHarness(files, registry = registryNoPiper())
        harness.versionOf = { ns -> if (ns == "piper" || ns == "system") "v1" else null }
        val vm = harness.viewModel()
        try {
            vm.rerenderChapterStale(0)
            assertTrue(harness.rerenderStarts.isEmpty())
            val error = vm.state.value.error ?: ""
            assertTrue("was: $error", "voice screen" in error)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun panel_bulkLowStorage_refusesWithNumbersAndNoStart() {
        val files = renderedFiles()
        val beforeManifest = files["$bundleDir/manifest.json"]
        val beforeAudio = files["$bundleDir/audio/ch002.m4a"]
        val harness = PanelHarness(files, registry = registryFull(), free = 1L)
        val vm = harness.viewModel()
        try {
            val staleAudioMs = vm.state.value.staleSummary?.staleAudioMs ?: 0L
            assertTrue(staleAudioMs > 0L)
            val needed = RerenderGuards.swapBytesFor(staleAudioMs)
            vm.rerenderStale()
            assertTrue(harness.rerenderStarts.isEmpty())
            val error = vm.state.value.error ?: ""
            assertTrue("was: $error", needed.toString() in error)
            assertTrue("was: $error", "1" in error)
            assertEquals(beforeManifest, files["$bundleDir/manifest.json"])
            assertEquals(beforeAudio, files["$bundleDir/audio/ch002.m4a"])
        } finally {
            vm.clear()
        }
    }

    @Test
    fun panel_perChapterLowStorage_refusesWithNumbers() {
        val files = renderedFiles()
        val harness = PanelHarness(files, registry = registryFull(), free = 1L)
        val vm = harness.viewModel()
        try {
            vm.rerenderChapterStale(1)
            assertTrue(harness.rerenderStarts.isEmpty())
            val error = vm.state.value.error ?: ""
            assertTrue("was: $error", "storage" in error.lowercase())
        } finally {
            vm.clear()
        }
    }

    @Test
    fun panel_bulkNoStale_reportsPlainMessageAndNoStart() {
        val files = renderedFiles(ch2DialogueVoice = "system:dial")
        val harness = PanelHarness(files, registry = registryFull())
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

    // Voice-screen apply flow: unrendered settings-only plus refusals.

    private inner class VoiceHarness(
        shared: HashMap<String, String>,
        engines: EngineRegistry = registryFull(),
        free: Long = Long.MAX_VALUE,
        globalsNarrator: String = "system:narr",
        globalsDialogue: String = "system:dial"
    ) {
        val storage = FakeStorage(shared)
        val ioFiles: HashMap<String, String> = hashMapOf()
        val io = FakeIo(ioFiles)
        val progress = FakeProgress()
        val globals = PrefsTtsStore(FakeSharedPreferences()).also {
            if (globalsNarrator.isNotBlank()) it.setVoiceId(TtsRole.Narrator, globalsNarrator)
            if (globalsDialogue.isNotBlank()) it.setVoiceId(TtsRole.Dialogue, globalsDialogue)
        }
        val audio = FakeAudioPlayer()
        var rerenderStarts = mutableListOf<Pair<Int, RerenderMode>>()
        var bookChanged = 0
        val vm = BookVoiceViewModel(
            bookId = "b1",
            bundleDir = bundleDir,
            storage = storage,
            progress = progress,
            globals = globals,
            registry = engines,
            audio = audio,
            versionOf = { "v1" },
            fileIo = io,
            onStartRerender = { chapter, mode -> rerenderStarts.add(chapter to mode) },
            onPauseRender = {},
            onBookChanged = { bookChanged++ },
            freeBytes = { free },
            dispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun voice_unrenderedChange_persistsWithNoJobAndRendersLater() {
        val shared = unrenderedFiles()
        val beforeAudioKeys = shared.keys.filter { "/audio/" in it }.sorted()
        val harness = VoiceHarness(shared)
        try {
            harness.vm.selectVoice(TtsRole.Narrator, "system:new-narr")
            harness.vm.requestApply()
            assertNull(harness.vm.state.value.error)
            harness.vm.confirmApply(BookVoiceViewModel.ApplyChoice.NOW)
            assertTrue(harness.rerenderStarts.isEmpty())
            assertEquals(1, harness.bookChanged)
            val raw = harness.storage.files["$bundleDir/manifest.json"]!!
            val persisted = BundleParser.parseText(raw).getOrThrow()
            val reread = BookVoices.read(persisted, harness.globals)
            assertEquals("system:new-narr", reread.narratorVoiceId)
            assertEquals(beforeAudioKeys, shared.keys.filter { "/audio/" in it }.sorted())
            val plan = RenderPlanner.plan(
                bookId = "b1",
                chapterCount = persisted.chapters.size,
                readingChapter = 0,
                scope = RenderScope.WholeBook,
                isRendered = { pos ->
                    persisted.chapters.sortedBy { it.index }.getOrNull(pos)
                        ?.let(PlaybackQueue::isRenderedChapter) == true
                }
            )
            assertEquals(listOf(0, 1), plan.orderedChapters)
        } finally {
            harness.vm.clear()
        }
    }

    @Test
    fun voice_unrenderedLater_persistsWithNoJob() {
        val shared = unrenderedFiles()
        val harness = VoiceHarness(shared)
        try {
            harness.vm.selectVoice(TtsRole.Narrator, "system:new-narr")
            harness.vm.requestApply()
            harness.vm.confirmApply(BookVoiceViewModel.ApplyChoice.LATER)
            assertTrue(harness.rerenderStarts.isEmpty())
            assertEquals(1, harness.bookChanged)
            val raw = harness.storage.files["$bundleDir/manifest.json"]!!
            val persisted = BundleParser.parseText(raw).getOrThrow()
            assertEquals(
                "system:new-narr",
                BookVoices.read(persisted, harness.globals).narratorVoiceId
            )
        } finally {
            harness.vm.clear()
        }
    }

    @Test
    fun voice_nowMissingVoice_refusesBeforePersistWithFix() {
        val mutable = MutableEngine("system", listOf("system:narr", "system:new-narr", "system:dial"))
        val engines = EngineRegistry(listOf(mutable))
        val shared = renderedFiles()
        val beforeManifest = shared["$bundleDir/manifest.json"]
        val beforeAudio = shared["$bundleDir/audio/ch002.m4a"]
        val harness = VoiceHarness(shared, engines = engines)
        try {
            harness.vm.selectVoice(TtsRole.Narrator, "system:new-narr")
            harness.vm.requestApply()
            assertNull(harness.vm.state.value.error)
            mutable.ids = listOf("system:narr", "system:dial")
            harness.vm.confirmApply(BookVoiceViewModel.ApplyChoice.NOW)
            assertTrue(harness.rerenderStarts.isEmpty())
            assertEquals(0, harness.bookChanged)
            val error = harness.vm.state.value.error ?: ""
            assertTrue("was: $error", "system:new-narr" in error)
            assertTrue("was: $error", "voice screen" in error)
            assertEquals(beforeManifest, shared["$bundleDir/manifest.json"])
            assertEquals(beforeAudio, shared["$bundleDir/audio/ch002.m4a"])
        } finally {
            harness.vm.clear()
        }
    }

    @Test
    fun voice_requestApplyMissingVoice_refusesWithFixAndNoPause() {
        val shared = renderedFiles().let { base ->
            base["$bundleDir/manifest.json"] = manifestRenderedJson(
                narratorEngine = "piper", narratorVoice = "voice-p",
                dialogueEngine = "piper", dialogueVoice = "voice-p"
            )
            base
        }
        val beforeManifest = shared["$bundleDir/manifest.json"]
        val harness = VoiceHarness(shared, engines = registryNoPiper())
        try {
            harness.vm.setSpeed(TtsRole.Narrator, 1.25f)
            harness.vm.requestApply()
            val error = harness.vm.state.value.error ?: ""
            assertTrue("was: $error", "piper" in error)
            assertTrue("was: $error", "voice screen" in error)
            assertTrue(!harness.vm.state.value.showImpact)
            assertEquals(beforeManifest, shared["$bundleDir/manifest.json"])
            assertTrue(harness.rerenderStarts.isEmpty())
        } finally {
            harness.vm.clear()
        }
    }

    @Test
    fun voice_nowLowStorage_refusesBeforePersistWithNumbers() {
        val shared = renderedFiles()
        val beforeManifest = shared["$bundleDir/manifest.json"]
        val beforeAudio = shared["$bundleDir/audio/ch002.m4a"]
        val harness = VoiceHarness(shared, free = 1L)
        try {
            harness.vm.selectVoice(TtsRole.Narrator, "system:new-narr")
            harness.vm.requestApply()
            assertNull(harness.vm.state.value.error)
            harness.vm.confirmApply(BookVoiceViewModel.ApplyChoice.NOW)
            assertTrue(harness.rerenderStarts.isEmpty())
            val error = harness.vm.state.value.error ?: ""
            assertTrue("was: $error", "storage" in error.lowercase())
            assertEquals(beforeManifest, shared["$bundleDir/manifest.json"])
            assertEquals(beforeAudio, shared["$bundleDir/audio/ch002.m4a"])
        } finally {
            harness.vm.clear()
        }
    }

    @Test
    fun voice_laterLowStorage_persistsWithoutStarting() {
        val shared = renderedFiles()
        val harness = VoiceHarness(shared, free = 1L)
        try {
            harness.vm.selectVoice(TtsRole.Narrator, "system:new-narr")
            harness.vm.requestApply()
            harness.vm.confirmApply(BookVoiceViewModel.ApplyChoice.LATER)
            assertTrue(harness.rerenderStarts.isEmpty())
            assertEquals(1, harness.bookChanged)
            val raw = harness.storage.files["$bundleDir/manifest.json"]!!
            val persisted = BundleParser.parseText(raw).getOrThrow()
            assertEquals("system:new-narr", BookVoices.read(persisted, harness.globals).narratorVoiceId)
        } finally {
            harness.vm.clear()
        }
    }

    @Test
    fun voice_readOnlyConfirm_refusesWithPlainMessageAndNoPersist() {
        val scribeManifest = Manifest(
            specVersion = "1.1",
            id = "b1",
            title = "Scribe Book",
            type = "epub",
            chapters = emptyList(),
            voices = mapOf("narrator" to buildVoiceEntry("kokoro", "af_heart"))
        )
        val shared = hashMapOf(
            "$bundleDir/manifest.json" to
                BundleParser.json.encodeToString(Manifest.serializer(), scribeManifest)
        )
        val before = shared["$bundleDir/manifest.json"]
        val harness = VoiceHarness(shared)
        try {
            assertTrue(harness.vm.state.value.readOnly)
            harness.vm.confirmApply(BookVoiceViewModel.ApplyChoice.LATER)
            assertTrue((harness.vm.state.value.error ?: "").contains("read-only"))
            assertEquals(before, shared["$bundleDir/manifest.json"])
            assertTrue(harness.rerenderStarts.isEmpty())
            assertEquals(0, harness.bookChanged)
        } finally {
            harness.vm.clear()
        }
    }

    private fun buildVoiceEntry(engine: String, voice: String) =
        kotlinx.serialization.json.buildJsonObject {
            put("engine", engine)
            put("voice", voice)
            put("speed", 1.0)
            put("pitch", 1.0)
        }

    /** Mutable fake engine: the voice list can shrink mid-test (uninstall). */
    private class MutableEngine(
        override val namespace: String,
        var ids: List<String>
    ) : TtsEngine {
        override fun voices(): List<TtsVoice> =
            ids.map { TtsVoice(id = it, engine = namespace) }

        override fun capabilities(): TtsCapabilities = TtsCapabilities(
            multiSpeaker = true,
            loadCostMb = 0,
            sampleRateHz = 24_000
        )

        override suspend fun synthesize(
            text: String,
            voice: TtsVoice,
            speed: Float
        ): SynthesizedAudio = throw UnsupportedOperationException("not used here")
    }
}
