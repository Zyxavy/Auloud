package app.auloud.player.battery

import app.auloud.player.storage.FakeSharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WP8: shown-once gating through the real [PrefsBatteryPromptStore] over the
 * WP3 [FakeSharedPreferences], on plain JVM (no Robolectric).
 */
class BatteryPromptStoreTest {

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: BatteryPromptStore

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        store = PrefsBatteryPromptStore(prefs)
    }

    @Test
    fun initially_notShown() {
        assertFalse(store.wasShown())
    }

    @Test
    fun markShown_persistsAcrossStoreInstances() {
        store.markShown()

        assertTrue(store.wasShown())
        // A fresh store over the same prefs sees it: the auto-dialog fires once.
        assertTrue(PrefsBatteryPromptStore(prefs).wasShown())
    }

    @Test
    fun gating_firstPlayShows_secondPlaySkips() {
        // First playback: gate open.
        assertTrue(BatteryPromptLogic.shouldShowPrompt(store.wasShown(), isExempt = false))
        store.markShown()
        // Second playback: gate closed.
        assertFalse(BatteryPromptLogic.shouldShowPrompt(store.wasShown(), isExempt = false))
    }
}
