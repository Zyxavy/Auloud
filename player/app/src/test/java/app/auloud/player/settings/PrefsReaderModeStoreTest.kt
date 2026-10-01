package app.auloud.player.settings

import app.auloud.player.reader.ReaderFontSize
import app.auloud.player.reader.ReaderMode
import app.auloud.player.storage.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RA7: reader settings store on plain JVM (fake prefs, no Robolectric).
 */
class PrefsReaderModeStoreTest {

    @Test
    fun defaults_areReadListenAndKeepOn() {
        val store = PrefsReaderModeStore(FakeSharedPreferences())
        assertEquals(ReaderMode.ReadListen, store.mode())
        assertTrue(store.keepScreenOn())
    }

    @Test
    fun mode_roundTripsEveryValue() {
        val store = PrefsReaderModeStore(FakeSharedPreferences())
        ReaderMode.entries.forEach { mode ->
            store.setMode(mode)
            assertEquals(mode, store.mode())
        }
    }

    @Test
    fun unknownStoredMode_fallsBackToReadListen() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString(PrefsReaderModeStore.KEY_MODE, "Sepia").apply()
        assertEquals(ReaderMode.ReadListen, PrefsReaderModeStore(prefs).mode())
    }

    @Test
    fun keepScreenOn_roundTrips() {
        val store = PrefsReaderModeStore(FakeSharedPreferences())
        store.setKeepScreenOn(false)
        assertFalse(store.keepScreenOn())
        store.setKeepScreenOn(true)
        assertTrue(store.keepScreenOn())
    }

    @Test
    fun speed_defaultsClampsAndRoundTrips() {
        val store = PrefsReaderModeStore(FakeSharedPreferences())
        assertEquals(1.0f, store.playbackSpeed(), 0f)
        store.setPlaybackSpeed(1.5f)
        assertEquals(1.5f, store.playbackSpeed(), 0f)
        store.setPlaybackSpeed(9.0f)
        assertEquals(2.0f, store.playbackSpeed(), 0f)
    }

    @Test
    fun speed_insaneStoredValue_sanitized() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putFloat(PrefsReaderModeStore.KEY_SPEED, Float.NaN).apply()
        assertEquals(1.0f, PrefsReaderModeStore(prefs).playbackSpeed(), 0f)
    }

    @Test
    fun fontSize_defaultsRoundTripsAndFallsBack() {
        val store = PrefsReaderModeStore(FakeSharedPreferences())
        assertEquals(ReaderFontSize.Medium, store.fontSize())
        ReaderFontSize.entries.forEach { size ->
            store.setFontSize(size)
            assertEquals(size, store.fontSize())
        }
        val prefs = FakeSharedPreferences()
        prefs.edit().putString(PrefsReaderModeStore.KEY_FONT_SIZE, "Huge").apply()
        assertEquals(ReaderFontSize.Medium, PrefsReaderModeStore(prefs).fontSize())
    }
}
