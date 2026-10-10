package app.auloud.player.render

import app.auloud.player.bundle.ChapterText
import app.auloud.player.bundle.ChapterTextLoader
import app.auloud.player.data.ProgressEntity

/**
 * VS3 review fixup (D-117 wiring): after-swap position conversion.
 *
 * The live re-render path converts the saved position only after
 * [RerenderSwap.finalizeRerender] succeeds, by reading the freshly
 * written chapter JSON from disk and delegating to
 * [RenderProgress.convertOnRerender]. Converting before the swap (or
 * duplicating the sid bridge inline) leaves a converted position on
 * old audio when the swap fails, so the service must call
 * [convertAfterSwap] after success and skip it entirely on failure or
 * cancel (the old position stays valid on old audio).
 *
 * JVM-testable with a fake [RenderFileIo]; no Android types, no new
 * dependency. API 24 safe.
 */
object RerenderProgressAfterSwap {

    /**
     * True only after a successful swap when the job was not cancelled.
     *
     * The service checks this between [RerenderSwap.finalizeRerender]
     * and [convertAfterSwap]: a swap failure returns before conversion
     * and a cancelled job stops before conversion, so old audio keeps
     * the old position. Pure, API 24 safe.
     */
    fun shouldConvertAfterSwap(swapSucceeded: Boolean, cancelled: Boolean): Boolean =
        swapSucceeded && !cancelled

    /**
     * Reads the swapped chapter JSON at `text/chNNN.json` and converts
     * [saved] from [oldChapter] timings into the new timings.
     *
     * Returns [saved] unchanged when there is nothing to convert (null
     * saved, other chapter, unreadable or untimed new JSON); returns
     * null only when [saved] is null. Never throws: unreadable files
     * keep the old position.
     */
    fun convertAfterSwap(
        bundleDir: String,
        chapterNumber: Int,
        saved: ProgressEntity?,
        pos: Int,
        oldChapter: ChapterText,
        io: RenderFileIo
    ): ProgressEntity? {
        if (saved == null) return null
        if (saved.chapterIndex != pos) return saved
        val textRel = RenderFinalize.chapterTextRel(chapterNumber)
        val newTimed = try {
            val raw = io.readText(join(bundleDir, textRel))
            ChapterTextLoader.parse(textRel, raw).getOrNull() ?: return saved
        } catch (_: Exception) {
            return saved
        }
        return RenderProgress.convertOnRerender(saved, pos, oldChapter, newTimed)
    }

    private fun join(dir: String, rel: String): String =
        dir.trimEnd('/') + '/' + rel.trimStart('/')
}
