package app.auloud.player.reader

import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.Manifest
import app.auloud.player.storage.BundleStorage
import app.auloud.player.storage.ReadGuardOutcome
import app.auloud.player.storage.STALL_TIMEOUT_MS
import app.auloud.player.storage.guardedRead
import app.auloud.player.storage.secondsText
import kotlinx.coroutines.CancellationException

/**
 * OPEN1/OPEN3: chapter-list load for [BookScreen] and the read-only
 * reader, extracted so the JVM suite can prove every failure mode lands
 * visibly (a silent loader death used to leave owners on
 * `Loading book…` forever).
 *
 * [Ready] carries the entries plus the manifest render state (and the
 * manifest itself for the complete-book stale scan and the reader's text
 * paths). [Failed] carries plain-words text for the hub error screen;
 * [timedOut] names the stalled-storage case. The read runs under
 * [guardedRead], so a blocked storage read reports a stall instead of
 * holding the caller forever (OPEN3); any rogue [Throwable] (a linking or
 * native error on device bypasses an `Exception` catch) lands in [Failed]
 * with its class name. Only genuine cancellation propagates.
 */
internal sealed interface ChapterLoadResult {
    data class Ready(
        val chapters: List<ChapterEntry>,
        val renderState: String?,
        val manifest: Manifest?
    ) : ChapterLoadResult
    data class Failed(val message: String, val timedOut: Boolean) : ChapterLoadResult
}

internal suspend fun loadChapterList(
    storage: BundleStorage,
    bundleDir: String,
    timeoutMs: Long = STALL_TIMEOUT_MS
): ChapterLoadResult {
    val root = bundleDir.trimEnd('/')
    val path = "$root/manifest.json"
    val outcome = guardedRead(timeoutMs) {
        try {
            BundleParser.parseText(storage.readText(path)).getOrThrow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    val parsed = when (outcome) {
        is ReadGuardOutcome.Ok -> outcome.value
        is ReadGuardOutcome.Failed -> return ChapterLoadResult.Failed(
            chapterListFailure(outcome.stalled, outcome.cause, path, timeoutMs),
            outcome.stalled
        )
    }
    val list = try {
        parsed?.chapters?.let(::toChapterEntries) ?: emptyList()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return ChapterLoadResult.Failed(
            chapterListFailure(stalled = false, cause = e, path = path, timeoutMs = timeoutMs),
            false
        )
    }
    return ChapterLoadResult.Ready(list, parsed?.renderState, parsed)
}

/** Plain-words failure naming the layer, the path and the stall state. */
internal fun chapterListFailure(
    stalled: Boolean,
    cause: Throwable?,
    path: String,
    timeoutMs: Long
): String = if (stalled) {
    "Could not read the chapter list: no answer within ${secondsText(timeoutMs)} ($path). " +
        "The storage read looks stuck; tap Retry, and if it sticks again restart the tablet."
} else {
    val name = cause?.javaClass?.simpleName ?: "unknown error"
    "Could not read the chapter list ($name: ${cause?.message}). " +
        "Try again or re-import the book."
}
