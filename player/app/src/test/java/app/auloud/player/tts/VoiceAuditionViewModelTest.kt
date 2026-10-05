package app.auloud.player.tts

import app.auloud.player.storage.FakeSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PW8: audition ViewModel on plain JVM (fake engines, store, player).
 *
 * The ViewModel takes an injected dispatcher; tests pass
 * [Dispatchers.Unconfined] so launched previews complete eagerly
 * (the fake engine never suspends). No test-coroutines artifact.
 */
class FakeAudioPlayer : AudioPlayer {
    val played = mutableListOf<SynthesizedAudio>()
    var stops = 0
    var releases = 0

    override fun play(audio: SynthesizedAudio) {
        played.add(audio)
    }

    override fun stop() {
        stops += 1
    }

    override fun release() {
        releases += 1
    }
}

class VoiceAuditionViewModelTest {

    private val kokoro = FakeTtsEngine(
        namespace = "kokoro",
        voiceIds = listOf("kokoro:af_heart", "kokoro:am_onyx")
    )
    private val piper = FakeTtsEngine(
        namespace = "piper",
        voiceIds = listOf("piper:en_US-lessac-low")
    )

    private fun viewModel(
        store: TtsVoiceStore = PrefsTtsStore(FakeSharedPreferences()),
        audio: FakeAudioPlayer = FakeAudioPlayer(),
        registry: EngineRegistry = EngineRegistry(listOf(kokoro, piper))
    ): Triple<VoiceAuditionViewModel, TtsVoiceStore, FakeAudioPlayer> {
        val vm = VoiceAuditionViewModel(
            registry,
            store,
            audio,
            dispatcher = Dispatchers.Unconfined
        )
        return Triple(vm, store, audio)
    }

    @Test
    fun initial_listsEnginesAndSelectsStored() {
        val store = PrefsTtsStore(FakeSharedPreferences())
        store.setVoiceId(TtsRole.Narrator, "piper:en_US-lessac-low")
        val (vm, _, _) = viewModel(store = store)
        try {
            val state = vm.state.value
            assertEquals(listOf("kokoro", "piper"), state.engines)
            assertEquals("piper", state.selectedEngine)
            assertEquals("piper:en_US-lessac-low", state.narratorVoiceId)
            assertEquals(
                listOf(TtsVoice("piper:en_US-lessac-low", "piper")),
                state.engineVoices
            )
        } finally {
            vm.clear()
        }
    }

    @Test
    fun selectEngine_remapsAndPersists() {
        val (vm, store, _) = viewModel()
        try {
            vm.selectVoice(TtsRole.Narrator, "kokoro:am_onyx")
            vm.selectVoice(TtsRole.Dialogue, "kokoro:af_heart")
            vm.selectEngine("piper")
            assertEquals("piper:en_US-lessac-low", store.voiceId(TtsRole.Narrator))
            assertEquals("piper:en_US-lessac-low", store.voiceId(TtsRole.Dialogue))
            assertEquals("piper", vm.state.value.selectedEngine)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun selectEngine_unknownNamespace_ignored() {
        val (vm, store, _) = viewModel()
        try {
            vm.selectVoice(TtsRole.Narrator, "kokoro:am_onyx")
            vm.selectEngine("espeak")
            assertEquals("kokoro:am_onyx", store.voiceId(TtsRole.Narrator))
            assertEquals("kokoro", vm.state.value.selectedEngine)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun selectVoice_rejectsUnknownEngine() {
        val (vm, store, _) = viewModel()
        try {
            vm.selectVoice(TtsRole.Narrator, "espeak:default")
            assertEquals("", store.voiceId(TtsRole.Narrator))
        } finally {
            vm.clear()
        }
    }

    @Test
    fun setSpeed_persistsClamped() {
        val (vm, store, _) = viewModel()
        try {
            vm.setSpeed(TtsRole.Dialogue, 9.0f)
            assertEquals(MAX_TTS_SPEED, store.speed(TtsRole.Dialogue), 0f)
            assertEquals(MAX_TTS_SPEED, vm.state.value.dialogueSpeed, 0f)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun preview_synthesizesAndPlays(): Unit = runBlocking {
        val (vm, _, audio) = viewModel()
        try {
            vm.selectVoice(TtsRole.Narrator, "kokoro:am_onyx")
            vm.preview(TtsRole.Narrator)
            assertEquals(1, audio.played.size)
            assertEquals(1, kokoro.calls.size)
            assertEquals("kokoro:am_onyx", kokoro.calls.single().voice.id)
            assertNull(vm.state.value.previewingRole)
            assertNull(vm.state.value.error)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun preview_withoutVoice_reportsError(): Unit = runBlocking {
        val (vm, _, audio) = viewModel()
        try {
            vm.preview(TtsRole.Dialogue)
            assertTrue(audio.played.isEmpty())
            assertTrue(vm.state.value.error?.isNotBlank() == true)
            assertNull(vm.state.value.previewingRole)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun stop_clearsPreviewAndStopsAudio(): Unit = runBlocking {
        val (vm, _, audio) = viewModel()
        try {
            vm.selectVoice(TtsRole.Narrator, "kokoro:am_onyx")
            vm.preview(TtsRole.Narrator)
            vm.stop()
            assertTrue(audio.stops >= 1)
            assertNull(vm.state.value.previewingRole)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun clear_releasesAudio() {
        val audio = FakeAudioPlayer()
        val vm = VoiceAuditionViewModel(
            EngineRegistry(listOf(kokoro)),
            PrefsTtsStore(FakeSharedPreferences()),
            audio,
            dispatcher = Dispatchers.Unconfined
        )
        vm.clear()
        assertEquals(1, audio.releases)
    }
}
