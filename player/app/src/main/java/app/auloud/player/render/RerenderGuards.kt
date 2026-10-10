package app.auloud.player.render

import app.auloud.player.tts.BookVoices
import app.auloud.player.tts.EngineRegistry

/**
 * VS6: re-render edge-case guards (D-118, D-121, D-125).
 *
 * Pure planning-time checks every re-render entry point runs BEFORE
 * writing a byte of new audio or touching the manifest swap:
 * - read-only Scribe books refuse with [BookVoices.READ_ONLY_MESSAGE],
 * - missing engine or voice refuses naming the missing piece plus the
 *   fix path ([VOICE_FIX_SUFFIX]),
 * - low storage refuses with needed vs free numbers
 *   ([checkSwapStorage], old plus new during swap per VS2 math).
 *
 * Unset roles (blank narrator) refuse like a missing voice (the message
 * names the role). Unrendered books need no guard here: they carry no
 * stale audio, so callers simply plan nothing (no job, no crash).
 *
 * Playback never consults these guards: [PlaybackQueue] keys on audio
 * presence only, so existing audio keeps playing while planning refuses.
 *
 * Pure Kotlin, API 24 safe: no Android types, no java.time, no new
 * dependency, no permission.
 */
object RerenderGuards {

    /**
     * Fix path appended to every voice-availability failure (the book
     * voice screen edits per-book voices; global Settings never touches
     * existing books per D-113).
     */
    const val VOICE_FIX_SUFFIX = " - open the book voice screen and pick an installed voice"

    /**
     * Refuses read-only books with the plain model explanation.
     */
    fun checkNotReadOnly(isReadOnly: Boolean): Result<Unit> {
        if (!isReadOnly) return Result.success(Unit)
        return Result.failure(IllegalStateException(BookVoices.READ_ONLY_MESSAGE))
    }

    /**
     * Validates [bookVoices] against live [registry].
     *
     * Read-only books refuse with the plain explanation (their PC voice
     * ids are not in the device registry, so a voice message would only
     * confuse). Otherwise [BookVoices.validate] decides; its message
     * already names the missing piece (role, voice id, engine, model
     * pack) and this appends [VOICE_FIX_SUFFIX] so the way to fix it is
     * always present. First failure wins, matching the model.
     */
    fun checkVoicesAvailable(
        bookVoices: BookVoices,
        registry: EngineRegistry
    ): Result<Unit> {
        if (bookVoices.readOnly) {
            return Result.failure(IllegalStateException(BookVoices.READ_ONLY_MESSAGE))
        }
        val outcome = bookVoices.validate(registry)
        if (outcome.isSuccess) return Result.success(Unit)
        val base = outcome.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
            ?: "voice setup failed"
        val withFix = if ("voice screen" in base) base else base + VOICE_FIX_SUFFIX
        return Result.failure(IllegalStateException(withFix))
    }

    /**
     * Swap bytes for [staleAudioMs] milliseconds of stale audio.
     *
     * VS2 summary math: old audio stays playable until the VS3 swap, so
     * old plus new sit on disk at once. `audioBytes` is the old audio
     * size proxy, `totalBytes` is fresh audio plus spool, and the swap
     * needs both (`audioBytes + totalBytes`). Reuses [RenderEstimates]
     * rates so the panel impact dialog and this pre-check never disagree.
     */
    fun swapBytesFor(staleAudioMs: Long): Long {
        require(staleAudioMs >= 0) { "staleAudioMs must be >= 0, got $staleAudioMs." }
        val estimate = RenderEstimates.estimateForAudioMs(staleAudioMs)
        return estimate.audioBytes + estimate.totalBytes
    }

    /**
     * Pre-check before writing new audio: [freeBytes] must cover the
     * swap ([swapBytesFor]). Failure names needed vs free byte counts so
     * the message is actionable without opening anything else.
     */
    fun checkSwapStorage(freeBytes: Long, staleAudioMs: Long): Result<Unit> {
        val needed = swapBytesFor(staleAudioMs)
        if (freeBytes >= needed) return Result.success(Unit)
        return Result.failure(
            IllegalStateException(
                "Not enough storage for re-render swap " +
                    "(need $needed bytes for old plus new audio, " +
                    "only $freeBytes bytes free) - free space or delete audio first"
            )
        )
    }
}
