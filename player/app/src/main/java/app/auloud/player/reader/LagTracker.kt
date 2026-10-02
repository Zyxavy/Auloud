package app.auloud.player.reader

/**
 * RA9: highlight-lag samples for the debug overlay.
 *
 * Each highlight change records `positionAtChange - sentence.start_ms`
 * (normally a small positive number: the 200 ms ticker notices the new
 * sentence shortly after the audio crosses its start). The overlay shows
 * the average and maximum of the last [window] changes; both are null
 * until the first change (fresh chapter load resets).
 *
 * Pure Kotlin, no Android dependencies.
 */
class LagTracker(private val window: Int = LAG_WINDOW) {

    private val samples = ArrayDeque<Long>()

    fun record(lagMs: Long) {
        samples.addLast(lagMs)
        while (samples.size > window) samples.removeFirst()
    }

    fun reset() {
        samples.clear()
    }

    val count: Int
        get() = samples.size

    val averageMs: Long?
        get() = if (samples.isEmpty()) null else samples.sum() / samples.size

    val maxMs: Long?
        get() = samples.maxOrNull()

    companion object {
        const val LAG_WINDOW = 20
    }
}
