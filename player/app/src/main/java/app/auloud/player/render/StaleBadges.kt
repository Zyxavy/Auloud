package app.auloud.player.render

/**
 * VS5: stale-voice badges, chips, banner and re-render targets (D-115, D-119).
 *
 * UI wiring over the VS2/VS3/VS4 logic; no new pipeline. Everything here
 * is pure Kotlin (no Android, no storage), so the mapping, counts, banner
 * rules and intent wiring are plain-JVM-testable.
 *
 * Two axes stay visually distinct (Slice 11 plan section 8: badges always
 * visible, never conflated):
 * - RN9 [ChapterRenderState] (job progress: does audio exist, what is the
 *   queue doing) renders through [chapterStatusText].
 * - VS2 [ChapterStaleState] (voice match: does existing audio still match
 *   the book voices) renders through [staleBadgeText] below, always with
 *   the `Voices:` prefix so the two lines can never read as one axis.
 *
 * Delete-stale-audio semantic (decided in VS5, see D-124): a stale
 * chapter's audio IS the current playable audio (the old file stays
 * playable until the VS3 swap, and after the swap the old file is already
 * deleted or deferred by [RerenderSwap]). There are no separate "stale
 * files". Deleting a stale chapter's audio therefore removes playable
 * audio and returns the chapter to NOT_RENDERED (the existing
 * [RenderAudioDelete] path: manifest plus JSON stripped first, audio
 * last); the normal first-render path recreates it with the current
 * voices. It never "reverts to old playable audio": the old audio is
 * what is deleted.
 *
 * Per-chapter re-render wiring: there is no single-chapter re-render mode
 * in the pipeline (VS3 modes are STALE_ONLY, FROM_HERE, ALL), so a
 * per-chapter action starts STALE_ONLY at that chapter
 * ([perChapterRerender]): the tapped chapter renders first (selection is
 * reading-position-forward with wrap), then the rest of the stale set.
 * Cancelling after the first chapter gives the single-chapter effect.
 * The bulk button ([staleOnlyRerender]) starts the same mode from the
 * reading position.
 *
 * API 24 safe: pure Kotlin, no `java.time`, no Android types, no new
 * dependency, no permission.
 */
object StaleBadges

/**
 * VS5: one-line stale badge for a chapter row (the `Voices:` prefix keeps
 * this axis distinct from the RN9 job-progress line).
 */
fun staleBadgeText(state: ChapterStaleState): String = when (state) {
    ChapterStaleState.CURRENT -> "Voices: current"
    ChapterStaleState.STALE -> "Voices: changed - needs re-render"
    ChapterStaleState.OUTDATED -> "Voices: outdated (engine updated)"
    ChapterStaleState.NOT_RENDERED -> "Voices: not rendered"
}

/**
 * VS5: library chip for a book with stale chapters (the RN9 chip pattern:
 * short text, tap opens the book; the chip lives next to the
 * [renderChipText] chip, never merged into it). Null when there is
 * nothing stale to show.
 */
fun staleChipText(stale: Int): String? {
    if (stale <= 0) return null
    return if (stale == 1) {
        "1 chapter needs re-render"
    } else {
        "$stale chapters need re-render"
    }
}

/** VS5: mixed-voice banner content (D-119) for a chapter list header. */
data class StaleBanner(
    val stale: Int,
    val total: Int,
    val text: String
)

/**
 * VS5: true when the book mixes voices (D-119): at least one STALE
 * chapter plus at least one chapter already on the current voices
 * (CURRENT, or OUTDATED which shares the book voice ids and differs only
 * by engine version string). NOT_RENDERED chapters never count either
 * way: unrendered audio is not "old voice" audio.
 */
fun isMixedVoice(summary: BookStalenessSummary): Boolean =
    summary.stale > 0 && (summary.current > 0 || summary.outdated > 0)

/**
 * VS5: mixed-voice banner for [summary], or null when the book is not
 * mixed (all current, all stale with nothing rendered on the new voices,
 * nothing rendered at all, or outdated only). Covers the VS4 LATER case:
 * persisting voices without re-rendering leaves exactly this shape, and
 * the banner is what surfaces it.
 */
fun bannerFor(summary: BookStalenessSummary): StaleBanner? {
    if (!isMixedVoice(summary)) return null
    val total = summary.current + summary.stale + summary.outdated + summary.notRendered
    val text = if (summary.stale == 1) {
        "Mixed voices: 1 of $total chapters uses older voices."
    } else {
        "Mixed voices: ${summary.stale} of $total chapters use older voices."
    }
    return StaleBanner(stale = summary.stale, total = total, text = text)
}

/**
 * VS5: re-render start target (reading chapter plus [RerenderMode]) for
 * the [RenderService.startRerenderIntent] call. Pure so the mode/scope
 * wiring is JVM-testable without a Context.
 */
data class RerenderStart(
    val readingChapter: Int,
    val mode: RerenderMode
)

/**
 * VS5: per-chapter re-render target: STALE_ONLY starting at [chapterPos]
 * (0-based position), so the tapped chapter goes first. See the file KDoc
 * for why there is no single-chapter mode.
 */
fun perChapterRerender(chapterPos: Int): RerenderStart =
    RerenderStart(readingChapter = chapterPos, mode = RerenderMode.STALE_ONLY)

/** VS5: bulk stale re-render target from the reading position. */
fun staleOnlyRerender(readingChapter: Int): RerenderStart =
    RerenderStart(readingChapter = readingChapter, mode = RerenderMode.STALE_ONLY)

/**
 * VS5: [RerenderMode] to the [RenderService] intent mode name (STALE_ONLY,
 * FROM_HERE, ALL). The service plans via [RerenderPlanner]; the UI only
 * names the mode.
 */
fun rerenderModeName(mode: RerenderMode): String = when (mode) {
    RerenderMode.FROM_HERE -> RenderService.RERENDER_FROM_HERE
    RerenderMode.ALL -> RenderService.RERENDER_ALL
    RerenderMode.STALE_ONLY -> RenderService.RERENDER_STALE_ONLY
}
