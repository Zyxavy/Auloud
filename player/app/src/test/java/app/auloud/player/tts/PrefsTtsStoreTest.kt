package app.auloud.player.tts

import app.auloud.player.storage.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PW5: TTS voice settings store on plain JVM (fake prefs, no Robolectric).
 */
class PrefsTtsStoreTest {

    @Test
    fun defaults_areEmptyAnd1x() {
        val store = PrefsTtsStore(FakeSharedPreferences())
        assertEquals("", store.voiceId(TtsRole.Narrator))
        assertEquals("", store.voiceId(TtsRole.Dialogue))
        assertEquals(1.0f, store.speed(TtsRole.Narrator), 0f)
        assertEquals(1.0f, store.speed(TtsRole.Dialogue), 0f)
    }

    @Test
    fun voiceIds_roundTripPerRole() {
        val store = PrefsTtsStore(FakeSharedPreferences())
        store.setVoiceId(TtsRole.Narrator, "kokoro:am_onyx")
        store.setVoiceId(TtsRole.Dialogue, "piper:en_US-lessac-low")
        assertEquals("kokoro:am_onyx", store.voiceId(TtsRole.Narrator))
        assertEquals("piper:en_US-lessac-low", store.voiceId(TtsRole.Dialogue))
    }

    @Test
    fun speeds_roundTripPerRole() {
        val store = PrefsTtsStore(FakeSharedPreferences())
        store.setSpeed(TtsRole.Narrator, 1.5f)
        store.setSpeed(TtsRole.Dialogue, 0.75f)
        assertEquals(1.5f, store.speed(TtsRole.Narrator), 0f)
        assertEquals(0.75f, store.speed(TtsRole.Dialogue), 0f)
    }

    @Test
    fun speeds_clampedAndGarbageSanitized() {
        val prefs = FakeSharedPreferences()
        val store = PrefsTtsStore(prefs)
        store.setSpeed(TtsRole.Narrator, 9.0f)
        assertEquals(MAX_TTS_SPEED, store.speed(TtsRole.Narrator), 0f)
        store.setSpeed(TtsRole.Dialogue, 0.1f)
        assertEquals(MIN_TTS_SPEED, store.speed(TtsRole.Dialogue), 0f)
        prefs.edit().putFloat(PrefsTtsStore.KEY_NARRATOR_SPEED, Float.NaN).apply()
        assertEquals(DEFAULT_TTS_SPEED, store.speed(TtsRole.Narrator), 0f)
    }

    @Test
    fun clampTtsSpeed_bounds() {
        assertEquals(0.5f, clampTtsSpeed(0.1f), 0f)
        assertEquals(2.0f, clampTtsSpeed(9.0f), 0f)
        assertEquals(1.0f, clampTtsSpeed(Float.NaN), 0f)
        assertEquals(1.25f, clampTtsSpeed(1.25f), 0f)
    }
}
