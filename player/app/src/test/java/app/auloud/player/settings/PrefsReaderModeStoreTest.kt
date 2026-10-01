package app.auloud.player.settings

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
}
