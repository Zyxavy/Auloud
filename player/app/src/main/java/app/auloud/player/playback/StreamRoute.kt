package app.auloud.player.playback

import app.auloud.player.tts.SystemTtsAdapter

/**
 * ST4: chapter routing between the rendered (ExoPlayer) path and the
 * live-stream path, plus stream save validation.
 *
 * The unit of routing is the chapter (`PlaybackQueue.isRenderedChapter`
 * already exists per chapter): rendered chapters play, unrendered
 * chapters stream when available, else refuse exactly as before (IN9).
 * Streaming is off until the ST0 gate passes ([GATE_PASSED]); the
 * offline-voice half is proven at stream start by the driver (a chapter
 * that cannot stream refuses with the render-first message).
 *
 * Pure Kotlin, no Android: JVM-tested (`StreamRouteTest`). The service
 * only wires these answers into player switches and saves.
 */
object StreamRoute {

    /**
     * ST0 gate verdict, flipped true by owner order 2026-10-10 (D-138):
     * the measured medians (400-500 ms) and start latency (10+ s) are
     * accepted as good enough for "listen now". Until ST7 proves it on
     * the tablet, every device claim stays with the owner.
     */
    const val GATE_PASSED = true

    /** Where one chapter plays. */
    enum class Target {
        Rendered,
        Streamed,
        Unavailable,
    }

    fun targetFor(isRenderedChapter: Boolean, streamingAvailable: Boolean): Target =
        when {
            isRenderedChapter -> Target.Rendered
            streamingAvailable -> Target.Streamed
            else -> Target.Unavailable
        }

    /** Chapter advance outcome: position plus where it plays. */
    data class Advance(val chapterPos: Int, val target: Target)

    /**
     * Adjacent chapter in [direction] (+1 next, -1 previous), or null
     * past the book ends. With streaming, every adjacent chapter is a
     * stop (rendered or streamed); without it, unrendered chapters are
     * skipped exactly like the rendered playlist does today.
     */
    fun advance(
        pos: Int,
        rendered: List<Boolean>,
        streamingAvailable: Boolean,
        direction: Int,
    ): Advance? {
        require(direction == 1 || direction == -1) { "direction must be +1 or -1." }
        if (streamingAvailable) {
            val next = pos + direction
            if (next < 0 || next >= rendered.size) return null
            return Advance(next, targetFor(rendered[next], true))
        }
        var next = pos + direction
        while (next in rendered.indices) {
            if (rendered[next]) return Advance(next, Target.Rendered)
            next += direction
        }
        return null
    }

    /** Validated stream save (chapter position + sentence sid, spec 8). */
    data class StreamSave(val bookId: String, val chapterPos: Int, val sid: Int)

    /**
     * Null when there is nothing to save (unknown book, negative
     * chapter, missing or non-positive sid); the service writes these
     * with `positionMs` 0 plus the sid (the IN1 column).
     */
    fun streamSaveOrNull(bookId: String?, chapterPos: Int, sid: Int?): StreamSave? {
        if (bookId.isNullOrBlank() || chapterPos < 0 || sid == null || sid < 1) return null
        return StreamSave(bookId, chapterPos, sid)
    }

    /**
     * Local system voice name for a namespaced voice id, or null when
     * the voice is not a system voice (streaming is system-only, so a
     * Piper id refuses instead of mis-speaking).
     */
    fun systemVoiceNameOrNull(voiceId: String): String? {
        val prefix = SystemTtsAdapter.SYSTEM_NAMESPACE + ":"
        if (!voiceId.startsWith(prefix)) return null
        return voiceId.removePrefix(prefix).takeIf { it.isNotBlank() }
    }

    /**
     * ST7-fix: role voice for the stream feed. A blank configured id
     * (voice never chosen) falls back to the engine [defaultName], so
     * "Listen now" works out of the box; a non-system id refuses (null).
     * Pure; the installed-voice check still runs downstream.
     */
    fun streamVoiceNameOrDefault(configuredId: String, defaultName: String?): String? {
        if (configuredId.isBlank()) return defaultName?.takeIf { it.isNotBlank() }
        return systemVoiceNameOrNull(configuredId)
    }
}
