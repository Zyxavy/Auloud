package app.auloud.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP12: a stream that fails again before speaking anything must not reload
 * forever. Pure JVM; the service owns one gate across stream reloads.
 */
class StreamFailureGateTest {

    @Test
    fun firstFailure_allowsReload() {
        val gate = StreamFailureGate()
        assertTrue(gate.onFailure())
    }

    @Test
    fun secondFailureInARow_refusesReload() {
        val gate = StreamFailureGate()
        assertTrue(gate.onFailure())
        assertFalse(gate.onFailure())
        assertFalse(gate.onFailure())
    }

    @Test
    fun onSpoken_resetsSoNextFailureAllowsReload() {
        val gate = StreamFailureGate()
        assertTrue(gate.onFailure())
        gate.onSpoken()
        assertTrue(gate.onFailure())
        assertFalse(gate.onFailure())
    }
}
