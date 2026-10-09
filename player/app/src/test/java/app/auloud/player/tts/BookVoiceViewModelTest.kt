package app.auloud.player.tts

import android.net.Uri
import app.auloud.player.bundle.Block
import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.ChapterInfo
import app.auloud.player.bundle.ChapterText
import app.auloud.player.bundle.Manifest
import app.auloud.player.bundle.Sentence
import app.auloud.player.data.ProgressEntity
import app.auloud.player.data.ProgressRepository
import app.auloud.player.render.RenderFileIo
import app.auloud.player.render.RenderFingerprint
import app.auloud.player.render.RenderJob
import app.auloud.player.render.RenderJobState
import app.auloud.player.render.RenderPlan
import app.auloud.player.render.RenderScope
import app.auloud.player.render.RenderStateStore
import app.auloud.player.render.RerenderMode
import app.auloud.player.storage.BundleStorage
import app.auloud.player.storage.FakeSharedPreferences
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VS4: book voice view-model states plus the apply flow
 * (now/later/keep), sample selection, and the mapping preview.
 *
 * Pure JVM: fake storage, progress, engines, prefs, player and job IO.
 * The dispatcher is [Dispatchers.Unconfined], so loads complete eagerly
 * with no test-coroutines artifact.
 */
class BookVoiceViewModelTest {

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

    private fun voiceEntry(engine: String, voice: String, speed: Double = 1.0) =
        buildJsonObject {
            put("engine", engine)
            put("voice", voice)
            put("speed", speed)
            put("pitch", 1.0)
        }

    private fun fingerprint(
        narratorVoice: String = "system:voice-a",
        dialogueVoice: String = "system:voice-a",
        version: String = "v1"
    ) = RenderFingerprint(
        engine = "system",
        voices = mapOf("narrator" to narratorVoice, "dialogue" to dialogueVoice),
        speeds = mapOf("narrator" to 1.0f, "dialogue" to 1.0f),
        engineVersions = mapOf("system" to version)
    )

    private fun chapterJson(vararg sentences: Pair<String, String>): String {
        val list = sentences.mapIndexed { pos, (speaker, text) ->
            Sentence(sid = pos + 1, speaker = speaker, text = text)
        }
        val chapter = ChapterText(
            specVersion = "2.0",
            chapter = 1,
            title = "Ch",
            blocks = listOf(Block(id = 1, type = "para", sentences = list))
        )
        return BundleParser.json.encodeToString(ChapterText.serializer(), chapter)
    }

    private fun manifestText(): String {
        val manifest = Manifest(
            specVersion = "2.0",
            id = "b1",
            title = "Voice Book",
            type = "epub",
            chapters = listOf(
                ChapterInfo(
                    index = 1,
                    title = "Ch 1",
                    text = "text/ch001.json",
                    audio = "audio/ch001.m4a",
                    durationMs = 120_000L,
                    renderFingerprint = fingerprint().toJsonObject()
                ),
                ChapterInfo(
                    index = 2,
                    title = "Ch 2",
                    text = "text/ch002.json",
                    audio = "audio/ch002.m4a",
                    durationMs = 60_000L,
                    renderFingerprint = fingerprint().toJsonObject()
                )
            ),
            voices = mapOf(
                "narrator" to voiceEntry("system", "voice-a"),
                "dialogue" to voiceEntry("system", "voice-a")
            )
        )
        return BundleParser.json.encodeToString(Manifest.serializer(), manifest)
    }

    private fun files(): HashMap<String, String> = hashMapOf(
        "$bundleDir/manifest.json" to manifestText(),
        "$bundleDir/text/ch001.json" to chapterJson(
            "narrator" to "The keeper lit the lamp. ",
            "dialogue" to "Who is there? "
        ),
        "$bundleDir/text/ch002.json" to chapterJson(
            "narrator" to "Morning came slowly. "
        )
    )

    private fun registry(): EngineRegistry {
        val system = FakeTtsEngine(
            namespace = "system",
            voiceIds = listOf("system:voice-a", "system:voice-b")
        )
        val piper = FakeTtsEngine(
            namespace = "piper",
            voiceIds = listOf("piper:voice-p")
        )
        return EngineRegistry(listOf(system, piper))
    }

    private inner class Harness(
        shared: HashMap<String, String> = files(),
        globalsNarrator: String = "system:voice-a",
        globalsDialogue: String = "system:voice-a"
    ) {
        val storage = FakeStorage(shared)
        val ioFiles: HashMap<String, String> = hashMapOf()
        val io = FakeIo(ioFiles)
        val progress = FakeProgress()
        val globals = PrefsTtsStore(FakeSharedPreferences()).also {
            if (globalsNarrator.isNotBlank()) it.setVoiceId(TtsRole.Narrator, globalsNarrator)
            if (globalsDialogue.isNotBlank()) it.setVoiceId(TtsRole.Dialogue, globalsDialogue)
        }
        val engines = registry()
        val audio = FakeAudioPlayer()
        var pauses = 0
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
            onPauseRender = { pauses++ },
            onBookChanged = { bookChanged++ },
            dispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun loads_voicesAndRealLineSamples() {
        val harness = Harness()
        try {
            val state = harness.vm.state.value
            assertFalse(state.isLoading)
            assertNull(state.manifestError)
            assertFalse(state.readOnly)
            assertEquals("system:voice-a", state.narratorVoiceId)
            assertEquals("The keeper lit the lamp. ", state.narrationSample)
            assertEquals("Who is there? ", state.dialogueSample)
            assertFalse(state.hasChanges)
        } finally {
            harness.vm.clear()
        }
    }

    @Test
    fun selectVoice_andSpeed_markChanges() {
        val harness = Harness()
        try {
            harness.vm.selectVoice(TtsRole.Narrator, "system:voice-b")
            harness.vm.setSpeed(TtsRole.Dialogue, 1.5f)
            val state = harness.vm.state.value
            assertEquals("system:voice-b", state.narratorVoiceId)
            assertEquals(1.5f, state.dialogueSpeed, 0f)
            assertTrue(state.hasChanges)
        } finally {
            harness.vm.clear()
        }
    }

    @Test
    fun selectVoice_unknownEngine_ignored() {
        val harness = Harness()
        try {
            harness.vm.selectVoice(TtsRole.Narrator, "kokoro:af_heart")
            assertEquals("system:voice-a", harness.vm.state.value.narratorVoiceId)
            assertFalse(harness.vm.state.value.hasChanges)
        } finally {
            harness.vm.clear()
        }
    }

    @Test
    fun engineSwitch_stagesPreviewAndSlowWarning() {
        val harness = Harness()
        try {
            harness.vm.selectEngine("piper")
            val staged = harness.vm.state.value
            assertEquals("piper", staged.pendingEngine)
            assertNotNull(staged.mappingPreview)
            assertEquals("piper", staged.mappingPreview!!.targetEngine)
            assertNotNull(staged.pendingWarning)
            assertEquals(EngineSpeedCategory.TOO_SLOW, staged.pendingWarning!!.category)
            assertFalse(staged.hasChanges)
            harness.vm.confirmEngineSwitch()
            val applied = harness.vm.state.value
            assertNull(applied.pendingEngine)
            assertEquals("piper:voice-p", applied.narratorVoiceId)
            assertTrue(applied.hasChanges)
        } finally {
            harness.vm.clear()
        }
    }

    @Test
    fun engineSwitch_cancel_keepsVoices() {
        val harness = Harness()
        try {
            harness.vm.selectEngine("piper")
            harness.vm.cancelEngineSwitch()
            val state = harness.vm.state.value
            assertNull(state.pendingEngine)
            assertNull(state.mappingPreview)
            assertEquals("system:voice-a", state.narratorVoiceId)
            assertFalse(state.hasChanges)
        } finally {
            harness.vm.clear()
        }
    }

    @Test
    fun preview_synthesizesRealLineSample() {
        val harness = Harness()
        try {
            harness.vm.selectVoice(TtsRole.Narrator, "system:voice-b")
            val system = harness.engines.engineFor("system:voice-b") as FakeTtsEngine
            harness.vm.preview(TtsRole.Narrator)
            assertEquals(1, system.calls.size)
            assertEquals("The keeper lit the lamp. ", system.calls.single().text)
            assertEquals("system:voice-b", system.calls.single().voice.id)
            assertEquals(1, harness.audio.played.size)
        } finally {
            harness.vm.clear()
        }
    }

    @Test
    fun preview_dialogueWithoutSample_fallsBackToNarration() {
        val shared = files()
        shared["$bundleDir/text/ch001.json"] = chapterJson(
            "narrator" to "Only narration anywhere. "
        )
        shared["$bundleDir/text/ch002.json"] = chapterJson(
            "narrator" to "More narration. "
        )
        val harness = Harness(shared = shared)
        try {
            assertNull(harness.vm.state.value.dialogueSample)
            val system = harness.engines.engineFor("system:voice-a") as FakeTtsEngine
            harness.vm.preview(TtsRole.Dialogue)
            assertEquals("Only narration anywhere. ", system.calls.single().text)
        } finally {
            harness.vm.clear()
        }
    }

    @Test
    fun preview_compare_usesAlternateVoice() {
        val harness = Harness()
        try {
            harness.vm.setAlternateVoice(TtsRole.Narrator, "system:voice-b")
            val system = harness.engines.engineFor("system:voice-a") as FakeTtsEngine
            harness.vm.preview(TtsRole.Narrator, alternate = true)
            assertEquals("system:voice-b", system.calls.single().voice.id)
            assertEquals("The keeper lit the lamp. ", system.calls.single().text)
        } finally {
            harness.vm.clear()
        }
    }

    @Test
    fun applyNow_persistsAndStartsStaleOnlyForward() {
        val harness = Harness()
        try {
            harness.vm.selectVoice(TtsRole.Narrator, "system:voice-b")
            harness.vm.requestApply()
            val impact = harness.vm.state.value.impact
            assertNotNull(impact)
            assertEquals(2, impact!!.staleChapters)
            assertEquals(180_000L, impact.staleAudioMs)
            assertTrue(impact.swapBytes > 0L)
            harness.vm.confirmApply(BookVoiceViewModel.ApplyChoice.NOW)
            assertEquals(1, harness.bookChanged)
            assertEquals(1, harness.rerenderStarts.size)
            assertEquals(0, harness.rerenderStarts.single().first)
            assertEquals(RerenderMode.STALE_ONLY, harness.rerenderStarts.single().second)
            val raw = harness.storage.files["$bundleDir/manifest.json"]!!
            val persisted = BundleParser.parseText(raw).getOrThrow()
            val reread = BookVoices.read(persisted, harness.globals)
            assertEquals("system:voice-b", reread.narratorVoiceId)
            assertFalse(harness.vm.state.value.hasChanges)
        } finally {
            harness.vm.clear()
        }
    }

    @Test
    fun applyLater_persistsWithoutStarting() {
        val harness = Harness()
        try {
            harness.vm.selectVoice(TtsRole.Narrator, "system:voice-b")
            harness.vm.requestApply()
            harness.vm.confirmApply(BookVoiceViewModel.ApplyChoice.LATER)
            assertEquals(1, harness.bookChanged)
            assertTrue(harness.rerenderStarts.isEmpty())
            assertNotNull(harness.vm.state.value.notice)
            val raw = harness.storage.files["$bundleDir/manifest.json"]!!
            val persisted = BundleParser.parseText(raw).getOrThrow()
            val reread = BookVoices.read(persisted, harness.globals)
            assertEquals("system:voice-b", reread.narratorVoiceId)
            assertFalse(harness.vm.state.value.hasChanges)
        } finally {
            harness.vm.clear()
        }
    }

    @Test
    fun applyKeep_persistsWithoutStarting() {
        val harness = Harness()
        try {
            val before = harness.storage.files["$bundleDir/manifest.json"]!!
            harness.vm.selectVoice(TtsRole.Narrator, "system:voice-b")
            harness.vm.requestApply()
            harness.vm.confirmApply(BookVoiceViewModel.ApplyChoice.KEEP)
            assertEquals(1, harness.bookChanged)
            assertTrue(harness.rerenderStarts.isEmpty())
            assertNotNull(harness.vm.state.value.notice)
            val raw = harness.storage.files["$bundleDir/manifest.json"]!!
            assertTrue(raw != before)
            val persisted = BundleParser.parseText(raw).getOrThrow()
            val reread = BookVoices.read(persisted, harness.globals)
            assertEquals("system:voice-b", reread.narratorVoiceId)
            assertEquals("system:voice-b", harness.vm.state.value.narratorVoiceId)
            assertFalse(harness.vm.state.value.hasChanges)
        } finally {
            harness.vm.clear()
        }
    }

    @Test
    fun midRenderChange_pausesAndShowsAskState() {
        val harness = Harness()
        val job = RenderJob(
            bookId = "b1",
            state = RenderJobState.RUNNING,
            plan = RenderPlan(
                bookId = "b1",
                chapterCount = 2,
                startChapter = 0,
                scope = RenderScope.WholeBook,
                orderedChapters = listOf(1),
                createdAt = 0L
            ),
            currentChapter = 1
        )
        RenderStateStore.save(bundleDir, job, harness.io) { }
        harness.vm.refresh()
        try {
            harness.vm.selectVoice(TtsRole.Narrator, "system:voice-b")
            harness.vm.requestApply()
            assertEquals(1, harness.pauses)
            val state = harness.vm.state.value
            assertTrue(state.showImpact)
            assertNotNull(state.impact)
            assertTrue(state.impact!!.jobWasPaused)
            assertEquals(listOf(0), state.impact!!.added)
            assertEquals(RenderJobState.PAUSED, state.jobState)
        } finally {
            harness.vm.clear()
        }
    }

    @Test
    fun promoteToDefaults_writesGlobals() {
        val harness = Harness()
        try {
            harness.vm.selectVoice(TtsRole.Narrator, "system:voice-b")
            harness.vm.promoteToDefaults()
            assertEquals("system:voice-b", harness.globals.voiceId(TtsRole.Narrator))
            assertNotNull(harness.vm.state.value.notice)
        } finally {
            harness.vm.clear()
        }
    }

    @Test
    fun readOnly_editsRefusedWithPlainMessage() {
        val scribeManifest = Manifest(
            specVersion = "1.1",
            id = "b1",
            title = "Scribe Book",
            type = "epub",
            chapters = emptyList(),
            voices = mapOf("narrator" to voiceEntry("kokoro", "af_heart"))
        )
        val shared = hashMapOf(
            "$bundleDir/manifest.json" to
                BundleParser.json.encodeToString(Manifest.serializer(), scribeManifest)
        )
        val harness = Harness(shared = shared)
        try {
            assertTrue(harness.vm.state.value.readOnly)
            harness.vm.selectVoice(TtsRole.Narrator, "system:voice-b")
            assertTrue((harness.vm.state.value.error ?: "").contains("read-only"))
            harness.vm.preview(TtsRole.Narrator)
            assertTrue((harness.vm.state.value.error ?: "").contains("read-only"))
            harness.vm.requestApply()
            assertTrue((harness.vm.state.value.error ?: "").contains("read-only"))
            assertFalse(harness.vm.state.value.showImpact)
        } finally {
            harness.vm.clear()
        }
    }

    @Test
    fun persist_keepsUnknownManifestKeys() {
        val harness = Harness()
        try {
            harness.vm.selectVoice(TtsRole.Narrator, "system:voice-b")
            harness.vm.requestApply()
            harness.vm.confirmApply(BookVoiceViewModel.ApplyChoice.LATER)
            val raw = harness.storage.files["$bundleDir/manifest.json"]!!
            assertTrue("Voice Book" in raw)
            val manifest = BundleParser.parseText(raw).getOrThrow()
            assertEquals("b1", manifest.id)
            assertEquals(2, manifest.chapters.size)
        } finally {
            harness.vm.clear()
        }
    }
}
