package app.auloud.player.playback

import kotlin.math.roundToInt

/**
 * RA8: playback speed steps (pure; the controller applies them via
 * `PlaybackParameters`, pitch preserved by ExoPlayer's default pipeline).
 *
 * Timings are unaffected: positions are media time at any speed, so the
 * reader highlight cannot drift.
 *
 * API 24 safe: pure Kotlin.
 */
val SPEED_STEPS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

const val MIN_SPEED = 0.75f
const val MAX_SPEED = 2.0f
const val DEFAULT_SPEED = 1.0f

fun clampSpeed(speed: Float): Float {
    if (!speed.isFinite()) return DEFAULT_SPEED
    return speed.coerceIn(MIN_SPEED, MAX_SPEED)
}

/** First step above [current] (with epsilon), wrapping to the first step. */
fun nextSpeed(current: Float): Float {
    return SPEED_STEPS.firstOrNull { it > current + 0.001f } ?: SPEED_STEPS.first()
}

/** Compact label: `1x`, `1.5x`, `1.25x`. */
fun formatSpeed(speed: Float): String {
    val hundredths = (speed * 100f).roundToInt()
    val whole = hundredths / 100
    val rest = hundredths % 100
    val text = when {
        rest == 0 -> "$whole"
        rest % 10 == 0 -> "$whole.${rest / 10}"
        rest < 10 -> "$whole.0$rest"
        else -> "$whole.$rest"
    }
    return "${text}x"
}
