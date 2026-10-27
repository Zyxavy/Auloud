package app.auloud.player.reader

/**
 * OPEN5: the loader coroutines cannot be trusted to report (a dead or
 * wedged load writes nothing), so the gate screens watch their own
 * ticking seconds and, past [STUCK_AFTER_SECS], show what is still
 * waiting. The seconds counter runs on the recomposition clock, which
 * is only alive if Main is free, so this card also cannot be faked by a
 * load that never returns.
 */
internal const val STUCK_AFTER_SECS = 30

/** [BookScreen]'s chapter-load counters, shown while a gate has no error. */
data class LoadSignals(val started: Int = 0, val finished: Int = 0)

/** Plain words for the counters (`started` counts effect runs, not retries). */
internal fun loadSignalText(signals: LoadSignals): String = when {
    signals.started == 0 -> "the loader never started"
    signals.finished == 0 -> "the loader started ${signals.started} time(s), never finished"
    else -> "the loader started ${signals.started} time(s), finished ${signals.finished}"
}

/** Null below the threshold; else the card body naming every waiting part. */
internal fun stuckWaitText(secs: Int, lines: List<String>): String? {
    if (secs < STUCK_AFTER_SECS) return null
    return "The book did not open after ${secs}s. Still waiting:\n" +
        lines.joinToString("\n") { "- $it" }
}
