package app.auloud.player.reader

import app.auloud.player.bundle.ChapterTextLoader
import app.auloud.player.bundle.ChapterTextPdfForm
import app.auloud.player.bundle.ChapterTextUnavailable
import app.auloud.player.data.ProgressRepository
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
import kotlinx.coroutines.launch

/**
 * IN9: sid-based reader for unrendered (2.0 `none`/`partial`) books.
 *
 * There is no audio, so there is no playback flow, no ticker and no
 * millisecond seek: the position is chapter index + sentence sid (spec
 * section 8), saved through the existing [ProgressRepository] path with
 * `positionMs` 0 and the sid in `sentenceSid` (the IN1 column). Timed
 * position code never runs here; [SentenceIndex] is not built (it skips
 * untimed sentences by design).
 *
 * Behavior:
 *
 * - Open restores the saved chapter + sid; a saved sid missing from the
 *   chapter (re-imported or changed book) falls back to the chapter start
 *   via [restoreReadingSid], never null when sentences exist.
 * - [jumpToChapter] validates with [coerceChapterJump] (out-of-range is a
 *   no-op) and loads the chapter start, saving it on success. Failures
 *   keep the error state and do not overwrite the last good position.
 * - [onSentenceTap] moves the reading position immediately (no confirm
 *   prompt: there is no audio to lose) and saves it; unknown sids are
 *   ignored. [onTopVisibleSid] debounces rapid scroll reports and settles
 *   the last one the same way.
 * - Follow reuses the shared machine: manual scroll detaches, back-to-now
 *   and chapter changes re-attach.
 *
 * The ViewModel owns its coroutines (a [SupervisorJob] over [dispatcher]);
 * call [clear] when the screen is disposed, like `ViewModel.onCleared`.
 *
 * API 24 safe: coroutines only.
 */
class UnrenderedReaderViewModel(
    private val bookId: String,
    private val storage: BundleStorage,
    private val textPathForChapter: (Int) -> String?,
    private val chapterCount: Int,
    private val progress: ProgressRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val debounceMs: Long = READ_SETTLE_MS
) {

    private val _state = MutableStateFlow(
        UnrenderedReaderState(chapterCount = chapterCount, isTextLoading = chapterCount > 0)
    )
    val state: StateFlow<UnrenderedReaderState> = _state

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var loadJob: Job? = null
    private var settleJob: Job? = null

    init {
        scope.launch {
            val saved = try {
                progress.load(bookId).getOrNull()
            } catch (_: Exception) {
                null
            }
            val startChapter = if (chapterCount > 0) {
                (saved?.chapterIndex ?: 0).coerceIn(0, chapterCount - 1)
            } else {
                0
            }
            loadChapter(startChapter, saved?.sentenceSid)
        }
    }

    /** Cancels loads and the settle debounce (screen disposed). */
    fun clear() {
        loadJob?.cancel()
        settleJob?.cancel()
        scope.cancel()
    }

    /**
     * Chapter list jump (chapter list screen). Validates via
     * [coerceChapterJump]; out-of-range is a no-op. Loads the chapter start
     * and saves it on success.
     */
    fun jumpToChapter(index: Int) {
        val target = coerceChapterJump(index, chapterCount) ?: return
        if (target == _state.value.chapterIndex && _state.value.chapter != null) return
        settleJob?.cancel()
        loadChapter(target, null)
    }

    /** Manual scroll detached the view (same rule as the timed reader). */
    fun onUserScrolled() {
        _state.value = _state.value.let {
            it.copy(follow = reduceFollow(it.follow, FollowEvent.UserScrolled))
        }
    }

    /** "Back to now" re-attaches at [UnrenderedReaderState.currentSid]. */
    fun onBackToNow() {
        _state.value = _state.value.let {
            it.copy(follow = reduceFollow(it.follow, FollowEvent.BackToNow))
        }
    }

    /**
     * Tap moves the reading position (no confirm: there is no audio to
     * lose). Unknown sids are ignored.
     */
    fun onSentenceTap(sid: Int) {
        val chapter = _state.value.chapter ?: return
        if (chapter.sentencesInOrder().none { it.sid == sid }) return
        _state.value = _state.value.copy(currentSid = sid)
        saveReadingPosition(_state.value.chapterIndex, sid)
    }

    /**
     * The screen reports the top visible sentence on every scroll; when
     * scrolling goes idle (debounced) that sentence becomes the saved
     * reading position. Rapid scrolls keep only the last report; unknown
     * sids settle to nothing.
     */
    fun onTopVisibleSid(sid: Int) {
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(debounceMs)
            settleReadingPosition(sid)
        }
    }

    private fun settleReadingPosition(sid: Int) {
        val current = _state.value
        val chapter = current.chapter ?: return
        if (chapter.sentencesInOrder().none { it.sid == sid }) return
        if (current.currentSid != sid) {
            _state.value = current.copy(currentSid = sid)
        }
        saveReadingPosition(current.chapterIndex, sid)
    }

    private fun loadChapter(chapterIndex: Int, restoreSid: Int?) {
        loadJob?.cancel()
        _state.value = _state.value.copy(
            chapterIndex = chapterIndex,
            chapter = null,
            follow = reduceFollow(_state.value.follow, FollowEvent.ChapterChanged),
            currentSid = null,
            isTextLoading = true,
            textError = null,
            textKind = null
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
            result.fold(
                onSuccess = { chapter ->
                    val sentences = chapter.sentencesInOrder()
                    val sid = restoreReadingSid(restoreSid, sentences)
                    _state.value = _state.value.copy(
                        chapterIndex = chapterIndex,
                        chapter = chapter,
                        currentSid = sid,
                        isTextLoading = false,
                        textError = null,
                        textKind = null,
                        follow = FollowState.Following
                    )
                    if (sid != null) saveReadingPosition(chapterIndex, sid)
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(
                        chapterIndex = chapterIndex,
                        chapter = null,
                        currentSid = null,
                        isTextLoading = false,
                        textError = error.message ?: "text unavailable",
                        textKind = when (error) {
                            is ChapterTextPdfForm -> TextKind.PdfForm
                            is ChapterTextUnavailable -> TextKind.Missing
                            else -> TextKind.Corrupt
                        }
                    )
                }
            )
        }
    }

    private fun saveReadingPosition(chapterIndex: Int, sid: Int) {
        scope.launch {
            try {
                progress.save(bookId, chapterIndex, 0L, sentenceSid = sid)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        /** Scroll-idle delay before the top sentence becomes the position. */
        const val READ_SETTLE_MS = 750L
    }
}
