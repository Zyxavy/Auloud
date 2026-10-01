package app.auloud.player.reader

import app.auloud.player.bundle.ChapterTextLoader
import app.auloud.player.bundle.ChapterTextUnavailable
import app.auloud.player.playback.PlaybackState
import app.auloud.player.storage.BundleStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * RA3: turns playback state + chapter text into [ReaderState].
 *
 * Inputs are constructor-injected so tests drive a fake playback flow and
 * fake storage (no Media3, no Android): [playback] is the controller's
 * state, [textPathForChapter] resolves a 0-based chapter index to its
 * `text/chNNN.json` path (null when the manifest lists none), and
 * [onSeekTo] performs the audio seek (wired to the controller in RA7;
 * read-only position setting also lands there).
 *
 * Behavior:
 *
 * - Chapter change (auto-advance, next/previous) loads the new chapter's
 *   text, drops the old one immediately, and resets follow to [Following]
 *   at the chapter start.
 * - A ~200 ms ticker ([tickerMs]) recomputes the highlight while playing.
 *   The tick path allocates nothing and copies state only when [currentSid]
 *   actually changes; [positionMs] comes from playback emissions, never the
 *   tick, so idle ticks emit nothing downstream.
 * - Text load failure keeps the controls usable: chapter null, [currentSid]
 *   null, [textError] set (RA10 renders it).
 *
 * The ViewModel owns its coroutines (a [SupervisorJob] over [dispatcher]);
 * call [clear] when the screen is disposed, like `ViewModel.onCleared`.
 * Tests pass an unconfined dispatcher and [clear] at the end so the ticker
 * cannot outlive the test.
 *
 * API 24 safe: coroutines + kotlinx.serialization only.
 */
class ReaderViewModel(
    private val playback: StateFlow<PlaybackState>,
    private val storage: BundleStorage,
    private val textPathForChapter: (Int) -> String?,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val tickerMs: Long = READER_TICK_MS,
    private val onSeekTo: (Long) -> Unit = {},
    private val debounceMs: Long = READ_SETTLE_MS
) {

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var index: SentenceIndex? = null
    private var loadStartedFor: Int? = null
    private var loadJob: Job? = null
    private var settleJob: Job? = null

    init {
        scope.launch { playback.collect { onPlayback(it) } }
        scope.launch {
            while (isActive) {
                delay(tickerMs)
                onTick()
            }
        }
    }

    /** Cancels the collectors and the ticker (screen disposed). */
    fun clear() {
        loadJob?.cancel()
        scope.cancel()
    }

    /** Manual scroll detached the view (RA5 scrolls; this only flips state). */
    fun onUserScrolled() {
        _state.value = _state.value.let { it.copy(follow = reduceFollow(it.follow, FollowEvent.UserScrolled)) }
    }

    /** "Back to now" re-attaches (RA5 performs the scroll to [currentSid]). */
    fun onBackToNow() {
        _state.value = _state.value.let { it.copy(follow = reduceFollow(it.follow, FollowEvent.BackToNow)) }
    }

    /**
     * Tap-to-jump (RA6): seek the audio to the tapped sentence's start and
     * re-attach. Unknown sids are ignored. Read-only position setting (no
     * audio) is RA7's mode wiring on this same path.
     */
    fun onSentenceTap(sid: Int) {
        val startMs = index?.startMsOf(sid) ?: return
        onSeekTo(startMs)
        _state.value = _state.value.let { it.copy(follow = reduceFollow(it.follow, FollowEvent.TextJump)) }
    }

    /**
     * Read-mode position (RA7): the screen reports the top visible sentence
     * on every scroll; when scrolling goes idle (debounced) that sentence
     * becomes the saved position via a paused seek (the service's usual
     * save follows the seek). Ignored outside Read mode, where the audio
     * owns the position. Rapid scrolls keep only the last report.
     */
    fun onTopVisibleSid(sid: Int) {
        if (_state.value.mode != ReaderMode.Read) return
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(debounceMs)
            settleReadingPosition(sid)
        }
    }

    private fun settleReadingPosition(sid: Int) {
        val current = _state.value
        if (current.mode != ReaderMode.Read || current.chapter == null) return
        val startMs = index?.startMsOf(sid) ?: return
        onSeekTo(startMs)
        val updated = _state.value
        if (updated.currentSid != sid) _state.value = updated.copy(currentSid = sid)
    }

    /** Mode switch never loses the place: chapter/sid/position are untouched. */
    fun setMode(mode: ReaderMode) {
        val current = _state.value
        if (current.mode != mode) _state.value = current.copy(mode = mode)
    }

    private fun onPlayback(snapshot: PlaybackState) {
        val current = _state.value
        if (snapshot.chapterIndex != loadStartedFor) {
            startLoad(snapshot.chapterIndex, snapshot.positionMs)
        }
        val chapterIndex = index
        val sid = if (chapterIndex != null && snapshot.chapterIndex == loadStartedFor) {
            chapterIndex.currentSid(snapshot.positionMs)
        } else {
            current.currentSid
        }
        val next = current.copy(
            positionMs = snapshot.positionMs,
            isPlaying = snapshot.isPlaying,
            currentSid = sid
        )
        if (next != current) _state.value = next
    }

    /**
     * Hot path (~200 ms while playing): one binary search, no allocation
     * beyond the boxed result, and a state copy only when the highlight
     * actually moves.
     */
    private fun onTick() {
        val current = _state.value
        val chapterIndex = index ?: return
        if (!current.isPlaying || current.chapter == null) return
        val position = playback.value.positionMs
        val sid = chapterIndex.currentSid(position)
        if (sid != current.currentSid) {
            _state.value = current.copy(currentSid = sid, positionMs = position)
        }
    }

    /**
     * RA7: the manifest (hence text paths) arrives after the first load
     * attempt, which fails as "no text listed". Retry the current chapter
     * once paths may exist; a loaded chapter makes this a no-op.
     */
    fun retryCurrentChapter() {
        if (_state.value.chapter != null) return
        val chapterIndex = _state.value.chapterIndex
        loadStartedFor = null
        startLoad(chapterIndex, playback.value.positionMs)
    }

    private fun startLoad(chapterIndex: Int, positionMs: Long) {        loadStartedFor = chapterIndex
        loadJob?.cancel()
        index = null
        _state.value = _state.value.copy(
            chapterIndex = chapterIndex,
            chapter = null,
            follow = reduceFollow(_state.value.follow, FollowEvent.ChapterChanged),
            currentSid = null,
            positionMs = positionMs,
            isTextLoading = true,
            textError = null
        )
        loadJob = scope.launch {
            val path = textPathForChapter(chapterIndex)
            val result = if (path == null) {
                Result.failure(
                    ChapterTextUnavailable("chapter ${chapterIndex + 1}: no text listed in manifest")
                )
            } else {
                ChapterTextLoader.load(storage, path)
            }
            // A newer chapter load started while this one ran: stale, drop it.
            if (loadStartedFor != chapterIndex) return@launch
            result.fold(
                onSuccess = { chapter ->
                    val built = SentenceIndex(chapter.blocks)
                    // Only adopt when still current (a chapter change may have
                    // landed between the guard above and this line).
                    if (loadStartedFor != chapterIndex) return@launch
                    index = built
                    val position = playback.value.positionMs
                    _state.value = _state.value.copy(
                        chapter = chapter,
                        currentSid = built.currentSid(position),
                        positionMs = position,
                        isTextLoading = false,
                        textError = null
                    )
                },
                onFailure = { error ->
                    if (loadStartedFor != chapterIndex) return@launch
                    index = null
                    _state.value = _state.value.copy(
                        chapter = null,
                        currentSid = null,
                        isTextLoading = false,
                        textError = error.message ?: "text unavailable"
                    )
                }
            )
        }
    }

    companion object {
        const val READER_TICK_MS = 200L

        /** Scroll-idle delay before the top sentence becomes the position. */
        const val READ_SETTLE_MS = 750L
    }
}
