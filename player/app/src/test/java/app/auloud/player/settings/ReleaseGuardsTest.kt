package app.auloud.player.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP8: the reader-preview gate. Release builds call
 * `isReaderPreviewAvailable(false)`, so the Settings spike entry and the
 * spike screen behind it are unreachable there. Plain JVM, no Robolectric.
 */
class ReleaseGuardsTest {

    @Test
    fun debugBuild_offersPreview() {
        assertTrue(isReaderPreviewAvailable(isDebugBuild = true))
    }

    @Test
    fun releaseBuild_hidesPreview() {
        assertFalse(isReaderPreviewAvailable(isDebugBuild = false))
    }
}
