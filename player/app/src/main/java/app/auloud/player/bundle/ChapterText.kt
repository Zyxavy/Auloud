package app.auloud.player.bundle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * RA1: chapter text models (`text/chNNN.json`, EPUB form).
 *
 * Field names and required/optional status come from `docs/03-BundleSpec.md`
 * section 4. Unknown JSON keys are ignored at parse time via the shared
 * `Json { ignoreUnknownKeys = true }` config (see [BundleParser]); missing
 * optional fields fall back to the defaults below.
 *
 * CP6 (spec v1.1): PDF text-path chapters keep `blocks` and MAY add `pages`
 * plus an optional `page` per sentence. `page` is the 1-based source page
 * (absent for EPUB, never null on write; missing/null reads as null).
 * `pages` marks are `{page, start_ms}` with `start_ms` of the page's first
 * sentence. Blocks+pages still reads as text (Text view); only pure
 * pages-without-blocks stays [ChapterTextPdfForm] (see [ChapterTextLoader]).
 *
 * Timings are media milliseconds ([Long], matching [ChapterInfo.durationMs]
 * and the playback position type). Semantic checks (consecutive sids,
 * ordered non-overlapping timings, first start 0) live in
 * [ChapterTextLoader], not here.
 *
 * API 24 safe: pure Kotlin + kotlinx.serialization, no Android dependencies.
 */
@Serializable
data class Span(
    val start: Int,
    val end: Int,
    val style: String
)

@Serializable
data class Sentence(
    val sid: Int,
    val speaker: String,
    @SerialName("start_ms")
    val startMs: Long,
    @SerialName("end_ms")
    val endMs: Long,
    val text: String,
    val spans: List<Span> = emptyList(),
    val page: Int? = null
)

@Serializable
data class PageMark(
    val page: Int,
    @SerialName("start_ms")
    val startMs: Long
)

@Serializable
data class Block(
    val id: Int,
    val type: String,
    val level: Int? = null,
    val text: String? = null,
    val sentences: List<Sentence> = emptyList()
)

@Serializable
data class ChapterText(
    @SerialName("spec_version")
    val specVersion: String,
    val chapter: Int,
    val title: String,
    @SerialName("duration_ms")
    val durationMs: Long,
    val blocks: List<Block>,
    val pages: List<PageMark>? = null
) {
    /** All sentences in document order (block order, then sid order). */
    fun sentencesInOrder(): List<Sentence> = blocks.flatMap { it.sentences }

    /** Page mark lookup: page containing [positionMs] (last start <= pos). */
    fun pageAt(positionMs: Long): PageMark? {
        val marks = pages ?: return null
        var current: PageMark? = null
        for (mark in marks) {
            if (positionMs >= mark.startMs) current = mark else break
        }
        return current
    }
}
