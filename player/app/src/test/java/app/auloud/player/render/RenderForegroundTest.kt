package app.auloud.player.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP2: [RenderForeground.tryStart] on plain JVM. A refused foreground start
 * (API 31+ ForegroundServiceStartNotAllowedException, an IllegalStateException)
 * must be reported to the caller instead of crashing the service.
 */
class RenderForegroundTest {

    @Test
    fun foregroundStart_ok_returnsTrue() {
        var starts = 0
        var refused = false
        val ok = RenderForeground.tryStart({ starts++ }) { refused = true }
        assertTrue(ok)
        assertEquals(1, starts)
        assertFalse(refused)
    }

    @Test
    fun foregroundStart_refused_returnsFalse() {
        val error = IllegalStateException("not allowed")
        var seen: Exception? = null
        val ok = RenderForeground.tryStart({ throw error }) { seen = it }
        assertFalse(ok)
        assertSame(error, seen)
    }

    @Test
    fun foregroundStart_securityException_returnsFalse() {
        val error = SecurityException("no permission")
        var seen: Exception? = null
        val ok = RenderForeground.tryStart({ throw error }) { seen = it }
        assertFalse(ok)
        assertSame(error, seen)
    }

    @Test
    fun foregroundStart_otherError_propagates() {
        var refused = false
        assertThrows(IllegalArgumentException::class.java) {
            RenderForeground.tryStart({ throw IllegalArgumentException("bad") }) { refused = true }
        }
        assertFalse(refused)
    }
}
