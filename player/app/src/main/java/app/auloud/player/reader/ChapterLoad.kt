package app.auloud.player.reader

import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.Manifest
import app.auloud.player.storage.BundleStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * OPEN1: chapter-list load for [BookScreen], extracted so the JVM suite
 * can prove every failure mode lands visibly (a silent loader death
 * used to leave owners on `Loading book…` forever).
 *
 * [Ready] carries the entries plus the manifest render state (and the
 * manifest itself for the complete-book stale scan). [Failed] carries
 * plain-words text for the hub error screen; [timedOut] names the
 * slow-storage case. Any rogue [Throwable] (a linking or native error
 * on device bypasses an `Exception` catch) lands in [Failed] with its
 * class name, so the next report names the layer. Only genuine
 * cancellation propagates.
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
    timeoutMs: Long = 20_000L
): ChapterLoadResult {
    try {
        val parsed = withTimeout(timeoutMs) {
            withContext(Dispatchers.IO) {
                try {
                    val root = bundleDir.trimEnd('/')
                    val raw = storage.readText("$root/manifest.json")
                    BundleParser.parseText(raw).getOrThrow()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }
        val list = try {
            parsed?.chapters?.let(::toChapterEntries) ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return ChapterLoadResult.Failed(loadFailedMessage(e), false)
        }
        return ChapterLoadResult.Ready(list, parsed?.renderState, parsed)
    } catch (e: TimeoutCancellationException) {
        return ChapterLoadResult.Failed(
            "Could not read the chapter list in time. Check storage, then retry.",
            true
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return ChapterLoadResult.Failed(loadFailedMessage(e), false)
    }
}

private fun loadFailedMessage(e: Throwable): String =
    "Could not read the chapter list (${e.javaClass.simpleName}). " +
        "Try again or re-import the book."
