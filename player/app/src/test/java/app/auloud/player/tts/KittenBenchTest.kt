package app.auloud.player.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KT3: benchmark math on plain JVM (no device, no models).
 */
class KittenBenchTest {

    private fun run(
        threads: Int = 2,
        walls: List<Long> = listOf(900L, 400L, 400L),
        audio: List<Double> = listOf(2.0, 2.0, 2.0)
    ) = KittenBenchRun(
        threadCount = threads,
        loadMs = 1500L,
        wallMs = walls,
        audioSec = audio,
        pssBeforeMb = 100L,
        pssAfterMb = 140L
    )

    @Test
    fun warmedRtf_skipsFirstSentence() {
        // (2 + 2) audio over (0.4 + 0.4) wall = 5.0.
        assertEquals(5.0, warmedRtf(run()) ?: -1.0, 1e-9)
    }

    @Test
    fun warmedRtf_tooShortOrEmpty_unmeasured() {
        assertNull(warmedRtf(listOf(400L), listOf(2.0)))
        assertNull(warmedRtf(emptyList(), emptyList()))
        assertNull(warmedRtf(run(audio = listOf(0.0, 0.0, 0.0))))
    }

    @Test
    fun bestThreads_picksFastestTieFewest() {
        val slow = run(threads = 1, walls = listOf(900L, 800L, 800L))
        val fast2 = run(threads = 2)
        val fast4 = run(threads = 4)
        assertEquals(2, bestThreads(listOf(slow, fast4, fast2)))
        assertNull(bestThreads(emptyList()))
        assertNull(bestThreads(listOf(run(walls = listOf(100L)))))
    }

    @Test
    fun formatKittenReport_spikeLineStyle() {
        val lines = formatKittenReport("nano-int8", run(), batteryPct = 80, tempC = 31.5)
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("kitten=nano-int8 threads=2 loadMs=1500"))
        assertTrue("pssBeforeMb=100" in lines[0] && "pssAfterMb=140" in lines[0])
        assertTrue(lines[1].startsWith("kittenWarmedRtf=5.00 audioSec=4.0 wallSec=0.8"))
        assertTrue(lines[1].endsWith("battery=80% temp=31.5C"))
    }

    @Test
    fun formatKittenReport_unmeasured() {
        val lines = formatKittenReport("micro", run(walls = listOf(100L)), 80, 30.0)
        assertTrue("kittenWarmedRtf=unmeasured" in lines[1])
    }

    @Test
    fun kitten_staysBackgroundUntilMeasured() {
        assertEquals(
            EngineSpeedCategory.BACKGROUND,
            EngineBenchmark.categoryFor(KITTEN_NAMESPACE)
        )
    }
}
