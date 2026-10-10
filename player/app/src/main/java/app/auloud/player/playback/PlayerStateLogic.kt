package app.auloud.player.playback

import androidx.media3.common.PlaybackException
import app.auloud.player.data.ProgressEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WP7: raw player values in one snapshot.
 *
 * Plain data so the mapping stays plain-JVM-testable (no Media3, no Android).
 * [PlaybackController] builds these from `MediaController` callbacks; tests
 * feed fakes directly. Titles are nullable because Media3 metadata may omit
 * them; the mapper normalizes blanks to `""`.
 */
data class ControllerSnapshot(
    val isPlaying: Boolean,
    val chapterIndex: Int,
    val chapterTitle: String?,
    val bookTitle: String?,
    val positionMs: Long,
    val durationMs: Long,
    val chapterCount: Int,
    val isConnected: Boolean,
    /** WP9: service-recorded save time; 0 in release. Defaults keep old call sites compiling. */
    val lastSaveWallMs: Long = 0L,
    /** RA8: sleep timer remaining ms (null = off). Copied, never computed, here. */
    val sleepRemainingMs: Long? = null,
    /**
     * ST5: live voice sentence sid (null when no stream is speaking).
     * Copied from [StreamSidMonitor], never computed, here. Positions
     * stay millisecond-based; the holder never clamps this to duration.
     */
    val streamSid: Int? = null,
    /**
     * CP4: fresh skip/storage notice from [SkipNoticeMonitor.consume].
     * Null means "no new notice" (the holder retains the displayed one
     * until the UI dismisses it); non-null replaces it.
     */
    val skipNotice: String? = null
)

/**
 * Maps a [ControllerSnapshot] to UI [PlaybackState].
 *
 * Clamps defensively: positions to `[0, duration]`, durations to `>= 0`,
 * chapter index to `[0, chapterCount)` (0 when the playlist is empty), blank
 * titles to `""`. Pure, no allocations beyond the result.
 */
fun ControllerSnapshot.toPlaybackState(): PlaybackState {
    val count = chapterCount.coerceAtLeast(0)
    val index = if (count == 0) 0 else chapterIndex.coerceIn(0, count - 1)
    val duration = durationMs.coerceAtLeast(0L)
    val position = positionMs.coerceIn(0L, duration)
    return PlaybackState(
        isPlaying = isPlaying,
        chapterIndex = index,
        chapterTitle = chapterTitle?.takeIf { it.isNotBlank() } ?: "",
        bookTitle = bookTitle?.takeIf { it.isNotBlank() } ?: "",
        positionMs = position,
        durationMs = duration,
        chapterCount = count,
        isConnected = isConnected,
        lastSaveWallMs = lastSaveWallMs.coerceAtLeast(0L),
        sleepRemainingMs = sleepRemainingMs?.coerceAtLeast(0L),
        streamSid = streamSid?.takeIf { it >= 1 },
        skipNotice = skipNotice
    )
}

/**
 * Clamps a UI seek request into the current chapter: `[0, durationMs]`.
 * Negative/unknown durations clamp to 0. Pure.
 */
fun clampSeekRequest(requestedMs: Long, durationMs: Long): Long {
    val duration = durationMs.coerceAtLeast(0L)
    return requestedMs.coerceIn(0L, duration)
}

/**
 * WP7 restore entry point: maps a saved [ProgressEntity] to a playlist start.
 *
 * Thin reuse of WP6 [PlaybackQueue.startFrom] so the player screen and the
 * service can never disagree (same 0-based chapter convention, same clamping,
 * finished = last chapter at full duration). Null progress (never saved)
 * starts at `(0, 0)`; an empty book yields `(0, 0)` and the service refuses to
 * load it. The screen itself never computes this: [PlaybackService] reads the
 * entity when it receives its book-id extra; this wrapper exists so WP7
 * unit tests pin the contract without touching the service.
 *
 * Finished-book behavior (D-028, decided in RA7): a book saved at the last
 * chapter's full duration reopens staying at the end, paused — and Play
 * restarts it from chapter 1 ([isFinishedBook],
 * [PlaybackController.playOrRestart]).
 */
fun restoreStart(
    progress: ProgressEntity?,
    chapterCount: Int,
    durations: List<Long> = emptyList()
): StartPosition = PlaybackQueue.startFrom(progress, chapterCount, durations)

/**
 * RA7 (D-028): a finished book is the last chapter at (or past) its full
 * duration. Play on such a book restarts from chapter 1
 * ([PlaybackController.restartBook]) instead of resuming the end. Pure.
 */
fun isFinishedBook(state: PlaybackState): Boolean {
    if (state.chapterCount <= 0 || state.durationMs <= 0) return false
    return state.chapterIndex >= state.chapterCount - 1 &&
        state.positionMs >= state.durationMs
}

/**
 * WP7: monotonic generation guard for the async `MediaController` connect.
 *
 * `buildAsync()` resolves later; if [PlaybackController.release] runs first
 * (screen disposed), the late listener must not resurrect the controller or
 * start the ticker. The controller captures `beginConnect()`'s token and the
 * listener only proceeds while `shouldResolve(token)` holds; `release()`
 * invalidates every pending token. Pure Kotlin so plain-JVM tests pin the
 * contract without Media3.
 */
class ConnectGuard {
    private var generation = 0

    /** A new connect attempt; the caller holds the returned token for its listener. */
    fun beginConnect(): Int {
        generation += 1
        return generation
    }

    /** Invalidates all pending connect tokens; late resolutions become no-ops. */
    fun release() {
        generation += 1
    }

    /** True only if no `release()` (or newer connect) superseded [token]. */
    fun shouldResolve(token: Int): Boolean = token == generation
}

/**
 * WP7: pure holder for [PlaybackState].
 *
 * No Android types, no scope: the Android [PlaybackController] owns one of
 * these and forwards `MediaController` callbacks to [onSnapshot]; plain-JVM
 * tests drive it with fake snapshots and Turbine. [onSnapshot] avoids
 * emitting (and avoids recompositions on the Tab E) when the mapped state is
 * unchanged, and avoids allocating a new [PlaybackState] on idle ticker ticks
 * by comparing clamped primitives first.
 */
class PlayerStateHolder(initial: PlaybackState = PlaybackState()) {

    private val _state = MutableStateFlow(initial)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun onSnapshot(snapshot: ControllerSnapshot) {
        val cur = _state.value
        // Fast path: compare without allocating. Mirrors toPlaybackState()
        // clamping so an idle 500 ms tick changes nothing and emits nothing.
        val count = snapshot.chapterCount.coerceAtLeast(0)
        val index = if (count == 0) 0 else snapshot.chapterIndex.coerceIn(0, count - 1)
        val duration = snapshot.durationMs.coerceAtLeast(0L)
        val position = snapshot.positionMs.coerceIn(0L, duration)
        val chapterTitle = snapshot.chapterTitle?.takeIf { it.isNotBlank() } ?: ""
        val bookTitle = snapshot.bookTitle?.takeIf { it.isNotBlank() } ?: ""
        val lastSave = snapshot.lastSaveWallMs.coerceAtLeast(0L)
        val sleepRemaining = snapshot.sleepRemainingMs?.coerceAtLeast(0L)
        val streamSid = snapshot.streamSid?.takeIf { it >= 1 }
        // CP4: null in a snapshot means "no new notice", never "clear".
        // The displayed notice survives idle ticks until the UI dismisses
        // it via clearSkipNotice() (tap or ~6 s timeout).
        val notice = snapshot.skipNotice ?: cur.skipNotice
        if (snapshot.isPlaying == cur.isPlaying &&
            index == cur.chapterIndex &&
            chapterTitle == cur.chapterTitle &&
            bookTitle == cur.bookTitle &&
            position == cur.positionMs &&
            duration == cur.durationMs &&
            count == cur.chapterCount &&
            snapshot.isConnected == cur.isConnected &&
            lastSave == cur.lastSaveWallMs &&
            sleepRemaining == cur.sleepRemainingMs &&
            streamSid == cur.streamSid &&
            notice == cur.skipNotice
        ) {
            return
        }
        _state.value = PlaybackState(
            isPlaying = snapshot.isPlaying,
            chapterIndex = index,
            chapterTitle = chapterTitle,
            bookTitle = bookTitle,
            positionMs = position,
            durationMs = duration,
            chapterCount = count,
            isConnected = snapshot.isConnected,
            lastSaveWallMs = lastSave,
            sleepRemainingMs = sleepRemaining,
            streamSid = streamSid,
            skipNotice = notice
        )
    }

    /**
     * CP4: UI dismissal for the transient skip/storage notice (tap or
     * timeout). Clearing here, not on null snapshots, is what keeps
     * rotation from resurrecting it: a fresh controller starts with no
     * notice and the monitor slot is already consumed.
     */
    fun clearSkipNotice() {
        if (_state.value.skipNotice != null) {
            _state.value = _state.value.copy(skipNotice = null)
        }
    }

    fun onDisconnected() {
        _state.value = _state.value.copy(isPlaying = false, isConnected = false)
    }
}

/** CP4: what [PlaybackService.onPlayerError] does with one failure. */
enum class PlayerErrorDecision {
    /** One bad chapter: record a skip notice and move to the next item. */
    Skip,
    /** Storage looks gone: pause, keep the position, show the message. */
    StorageLoss
}

/**
 * CP4: pure skip-vs-pause policy for playback errors.
 *
 * Error-code choice: only [PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND]
 * and [PlaybackException.ERROR_CODE_IO_NO_PERMISSION] count as reliable
 * missing-file signals (local `file://`/document-URI audio has no HTTP
 * codes; the exact DataSource mapping for an ejected microSD varies by
 * device, so a single code is never trusted alone). Everything else rides
 * on the consecutive-failure heuristic below.
 *
 * Heuristic: [consecutiveErrors] counts back-to-back `onPlayerError`
 * calls with no intervening `STATE_READY`. Two in a row where the latest
 * is a missing-file signal means storage loss (a single bad chapter only
 * skips once); any three in a row means storage loss regardless of code,
 * which also bounds the worst case to 3 skips so the service never loops
 * through a whole playlist. A chapter that reaches `STATE_READY` resets
 * the count to 0; handling a storage loss also resets it, so the next
 * user Play retries the current chapter naturally (files back: plays;
 * still gone: errors again, one skip at a time).
 *
 * Pure Kotlin + Media3 error-code ints (inlined constants, no player);
 * plain-JVM-testable.
 */
object PlaybackErrorPolicy {
    /** Missing-file signals that fast-path to storage loss on repeat. */
    fun isMissingFile(errorCode: Int): Boolean =
        errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION

    /** Next decision for one error given [consecutiveErrors] prior ones. */
    fun decide(errorCode: Int, consecutiveErrors: Int): PlayerErrorDecision {
        val upcoming = (consecutiveErrors.coerceAtLeast(0)) + 1
        if (isMissingFile(errorCode) && upcoming >= 2) return PlayerErrorDecision.StorageLoss
        if (upcoming >= 3) return PlayerErrorDecision.StorageLoss
        return PlayerErrorDecision.Skip
    }
}
