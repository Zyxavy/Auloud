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
    val spans: List<Span> = emptyList()
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
    val blocks: List<Block>
) {
    /** All sentences in document order (block order, then sid order). */
    fun sentencesInOrder(): List<Sentence> = blocks.flatMap { it.sentences }
}
