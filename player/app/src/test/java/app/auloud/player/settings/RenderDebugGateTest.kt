package app.auloud.player.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN9: the render debug-overlay gate (same seam as the beep card).
 * Release builds hide the partial-book hub overlay lines (benchmark
 * RTF, current chapter and sentence, battery temperature, spool size).
 * Plain JVM, no Robolectric.
 */
class RenderDebugGateTest {

    @Test
    fun debugBuild_showsRenderOverlay() {
        assertTrue(isRenderDebugAvailable(isDebugBuild = true))
    }

    @Test
    fun releaseBuild_hidesRenderOverlay() {
        assertFalse(isRenderDebugAvailable(isDebugBuild = false))
    }
}
