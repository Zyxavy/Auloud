package app.auloud.player.playback

/**
 * RA8: pure sleep-timer countdown (the service owns one of these).
 *
 * All time comes in as parameters, so tests drive a fake clock: [nowMs] is
 * wall time (`SystemClock.uptimeMillis()` in production), [positionMs] and
 * [chapterDurationMs] are media times (positions are media time, so speed
 * changes never disturb the timer).
 *
 * [SleepOption.EndOfChapter] expires when the chapter ends; the minute
 * options expire at a wall-clock deadline. The last [FADE_MS] fade the
 * volume linearly to 0, then the service pauses and saves.
 *
 * API 24 safe: pure Kotlin.
 */
enum class SleepOption {
    Off,
    Min15,
    Min30,
    Min45,
    Min60,
    EndOfChapter
}

/** Last seconds over which the volume fades to zero before pausing. */
const val SLEEP_FADE_MS = 10_000L

/**
 * FP1: End-of-chapter is refused on the stream path (a stream reports a
 * sentence index/count, not milliseconds; a few hundred "ms" would pin
 * the fade near zero for the whole chapter). Minute options are
 * wall-clock and work on both paths.
 */
fun isStreamEndOfChapterRefused(option: SleepOption, isStream: Boolean): Boolean =
    isStream && option == SleepOption.EndOfChapter

fun sleepMinutes(option: SleepOption): Long? = when (option) {
    SleepOption.Min15 -> 15L
    SleepOption.Min30 -> 30L
    SleepOption.Min45 -> 45L
    SleepOption.Min60 -> 60L
    SleepOption.Off, SleepOption.EndOfChapter -> null
}

/** UI cycle order: off -> 15 -> 30 -> 45 -> 60 -> end of chapter -> off. */
fun cycleSleepOption(current: SleepOption): SleepOption {
    val values = SleepOption.entries
    return values[(values.indexOf(current) + 1) % values.size]
}

/**
 * FP3: rotation-safe base for the sleep cycle cursor.
 *
 * The button's [SleepOption] cursor is UI-local `remember` state, so a
 * rotation resets it to Off while the timer keeps running in the service.
 * Cycling from a blind Off would send Min15 (re-arming instead of
 * advancing). Deriving the base from the live [remainingMs] fixes both
 * directions: a dead timer resets a stale cursor to Off, and an active
 * timer with a lost cursor is treated as end-of-chapter, so the next tap
 * lands on Off and cancels.
 * shortcut: the exact minute option is unrecoverable from remaining alone, so the first tap after rotation cancels instead of advancing; upgrade by persisting the cursor when a settings store owns it.
 */
fun sleepCycleBase(local: SleepOption, remainingMs: Long?): SleepOption = when {
    remainingMs == null -> SleepOption.Off
    local != SleepOption.Off -> local
    else -> SleepOption.EndOfChapter
}

/** mm:ss, API 24 safe (no java.time). Shared by the player, the sleep button and the debug overlay. */
fun formatMmSs(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1_000L).coerceAtMost(599_999L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${if (seconds < 10L) "0$seconds" else "$seconds"}"
}

fun sleepOptionLabel(option: SleepOption): String = when (option) {
    SleepOption.Off -> "off"
    SleepOption.Min15 -> "15 min"
    SleepOption.Min30 -> "30 min"
    SleepOption.Min45 -> "45 min"
    SleepOption.Min60 -> "60 min"
    SleepOption.EndOfChapter -> "end of chapter"
}

class SleepTimer {

    var option: SleepOption = SleepOption.Off
        private set

    private var deadlineMs: Long = 0L

    val isActive: Boolean
        get() = option != SleepOption.Off

    fun start(option: SleepOption, nowMs: Long) {
        this.option = option
        deadlineMs = if (option == SleepOption.Off) 0L else nowMs
    }

    fun cancel() {
        option = SleepOption.Off
        deadlineMs = 0L
    }

    /**
     * Remaining time, or null when off (or end-of-chapter with unknown
     * duration). Never negative.
     */
    fun remainingMs(nowMs: Long, positionMs: Long, chapterDurationMs: Long): Long? {
        return when (option) {
            SleepOption.Off -> null
            SleepOption.EndOfChapter ->
                if (chapterDurationMs <= 0) null
                else (chapterDurationMs - positionMs).coerceAtLeast(0L)
            else -> (deadlineMs + (sleepMinutes(option) ?: 0L) * 60_000L - nowMs)
                .coerceAtLeast(0L)
        }
    }

    fun isExpired(nowMs: Long, positionMs: Long, chapterDurationMs: Long): Boolean {
        return when (option) {
            SleepOption.Off -> false
            SleepOption.EndOfChapter ->
                chapterDurationMs > 0 && positionMs >= chapterDurationMs
            else -> remainingMs(nowMs, positionMs, chapterDurationMs) == 0L
        }
    }

    /** 1 outside the fade window, linear to 0 across the last [SLEEP_FADE_MS]. */
    fun fadeVolume(nowMs: Long, positionMs: Long, chapterDurationMs: Long): Float {
        val remaining = remainingMs(nowMs, positionMs, chapterDurationMs) ?: return 1f
        if (remaining >= SLEEP_FADE_MS) return 1f
        return (remaining.toFloat() / SLEEP_FADE_MS).coerceIn(0f, 1f)
    }
}

/**
 * RA8: service-to-UI snapshot for the timer (same-process, WP9 precedent).
 *
 * The countdown lives in [PlaybackService] (survives UI closes); the
 * controller copies this into [PlaybackState.sleepRemainingMs] on its
 * regular refresh, so no polling or IPC exists. Volatile reads only.
 */
object SleepTimerMonitor {
    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    var remainingMs: Long? = null
        private set

    fun publish(active: Boolean, remainingMs: Long?) {
        this.active = active
        this.remainingMs = remainingMs
    }
}
