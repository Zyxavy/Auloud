package app.auloud.player.settings

import app.auloud.player.render.BeepTtsEngine
import app.auloud.player.render.DebugRenderEngines
import app.auloud.player.tts.EngineRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN10: beep debug-gate tests (plain JVM, no Robolectric).
 *
 * Proves the release-build behavior is unchanged: the beep engine is
 * offered only when [isDebugBuild] is true (production call sites pass
 * `BuildConfig.DEBUG`, which R8 folds to false in release), and a
 * release registry never resolves a `beep:` voice.
 */
class BeepDebugGateTest {

    @Test
    fun debugBuild_offersBeepCheck() {
        assertTrue(isBeepSelfCheckAvailable(isDebugBuild = true))
    }

    @Test
    fun releaseBuild_hidesBeepCheck() {
        assertFalse(isBeepSelfCheckAvailable(isDebugBuild = false))
    }

    @Test
    fun debugBuild_providesBeepEngine() {
        val engine = DebugRenderEngines.beepEngineIfDebug(isDebugBuild = true)
        assertEquals(BeepTtsEngine.NAMESPACE, engine?.namespace)
    }

    @Test
    fun releaseBuild_providesNoBeepEngine() {
        assertNull(DebugRenderEngines.beepEngineIfDebug(isDebugBuild = false))
    }

    @Test
    fun releaseRegistry_neverResolvesBeepVoices() {
        val releaseRegistry = EngineRegistry(
            listOfNotNull(DebugRenderEngines.beepEngineIfDebug(isDebugBuild = false))
        )
        assertTrue(releaseRegistry.namespaces().isEmpty())
        assertNull(releaseRegistry.engineFor(BeepTtsEngine.NARRATOR_VOICE_ID))
    }

    @Test
    fun debugRegistry_resolvesBeepVoices() {
        val debugRegistry = EngineRegistry(
            listOfNotNull(DebugRenderEngines.beepEngineIfDebug(isDebugBuild = true))
        )
        assertEquals(listOf("beep"), debugRegistry.namespaces())
        assertTrue(debugRegistry.engineFor(BeepTtsEngine.DIALOGUE_VOICE_ID) != null)
    }
}
