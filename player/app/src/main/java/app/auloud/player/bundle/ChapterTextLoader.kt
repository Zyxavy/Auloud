package app.auloud.player.bundle

import app.auloud.player.storage.BundleStorage
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject

/**
 * RA1: loads one chapter's text JSON through [BundleStorage].
 *
 * Returns `Result.success(ChapterText)` on valid input, `Result.failure`
 * otherwise — never throws. Error types let callers (RA3/RA10) pick the UI:
 *
 * - [ChapterTextUnavailable]: missing/unreadable file.
 * - [ChapterTextPdfForm]: pure PDF page-sync chapter (`pages` instead of
 *   `blocks`, legacy v1.0 option (b)). Reader shows the page-only message,
 *   listening still works. CP6: `blocks`+`pages` (v1.1 text path) is NOT
 *   PdfForm — it parses as text with [ChapterText.pages] available for the
 *   Page view; only pure pages-without-blocks stays PdfForm.
 * - [ChapterTextInvalid]: malformed JSON or a spec rule broken. Names the
 *   file and every rule violated. Truncated JSON, wrong-type `pages`
 *   (e.g. a string, null entries, non-integer fields) and huge values
 *   all land here, never crash.
 *
 * Parsing runs on `Dispatchers.IO`, never the caller's thread.
 *
 * API 24 safe: `java.io` + kotlinx.serialization only.
 */
open class ChapterTextUnavailable(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/** RA10: PDF page-only chapter - text unavailable, listening works. */
class ChapterTextPdfForm(message: String) : ChapterTextUnavailable(message)

class ChapterTextInvalid(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

object ChapterTextLoader {

    private val blockTypes = setOf("heading", "para", "quote", "break")

    suspend fun load(storage: BundleStorage, textPath: String): Result<ChapterText> =
        withContext(Dispatchers.IO) {
            val raw: String
            try {
                if (!storage.exists(textPath)) {
                    return@withContext Result.failure(
                        ChapterTextUnavailable("$textPath: text file not found")
                    )
                }
                raw = storage.readText(textPath)
            } catch (e: Exception) {
                return@withContext Result.failure(
                    ChapterTextUnavailable("$textPath: cannot read text: ${e.message}", e)
                )
            }
            parse(textPath, raw)
        }

    /** Parses an in-memory `text/chNNN.json` payload (pure; used by tests). */
    internal fun parse(textPath: String, raw: String): Result<ChapterText> {
        val root = try {
            BundleParser.json.parseToJsonElement(raw).jsonObject
        } catch (e: SerializationException) {
            return Result.failure(
                ChapterTextInvalid("$textPath: invalid JSON: ${e.message}", e)
            )
        } catch (e: IllegalArgumentException) {
            return Result.failure(
                ChapterTextInvalid("$textPath: invalid JSON: ${e.message}", e)
            )
        }
        if (root.containsKey("pages") && !root.containsKey("blocks")) {
            // IN1 (spec 2.0): pages-without-blocks is a 1.x-only legacy
            // shape; 2.0 chapters always carry blocks. Peek the version so
            // a 2.0 page-only file is invalid (file + rule), not PdfForm.
            // (`contentOrNull` needs a newer serialization than the pinned
            // 1.7.3, so read `content` with an explicit null guard.)
            val versionElement = root["spec_version"]
            val version = if (
                versionElement == null ||
                versionElement is kotlinx.serialization.json.JsonNull ||
                versionElement !is kotlinx.serialization.json.JsonPrimitive
            ) {
                null
            } else {
                versionElement.content
            }
            if (version == "2.0") {
                return Result.failure(
                    ChapterTextInvalid(
                        "$textPath: pages without blocks is a 1.x-only shape " +
                            "(2.0 chapters always carry blocks)"
                    )
                )
            }
            return Result.failure(
                ChapterTextPdfForm(
                    "$textPath: page-only PDF chapter " +
                        "(text unavailable - listening still works)"
                )
            )
        }
        val chapter = try {
            BundleParser.json.decodeFromJsonElement(ChapterText.serializer(), root)
        } catch (e: SerializationException) {
            return Result.failure(
                ChapterTextInvalid("$textPath: invalid chapter text: ${e.message}", e)
            )
        } catch (e: IllegalArgumentException) {
            return Result.failure(
                ChapterTextInvalid("$textPath: invalid chapter text: ${e.message}", e)
            )
        }
        return validate(textPath, chapter).map { chapter }
    }

    /**
     * Spec section 4/6 rules: consecutive sids, plus ordered
     * non-overlapping timings for rendered chapters (IN1: unrendered
     * chapters carry no `duration_ms` and no sentence timings; sids and
     * block/page shape are still checked, timing rules are vacuous).
     */
    internal fun validate(textPath: String, chapter: ChapterText): Result<Unit> {
        val errors = ArrayList<String>()
        if (chapter.specVersion != "1.0" && chapter.specVersion != "1.1" &&
            chapter.specVersion != "1.2" && chapter.specVersion != "2.0"
        ) {
            errors.add("spec_version \"${chapter.specVersion}\" must be \"1.0\", \"1.1\", \"1.2\" or \"2.0\"")
        }
        chapter.blocks.forEach { block ->
            if (block.type !in blockTypes) {
                errors.add("block ${block.id}: unknown type '${block.type}'")
            }
        }
        val sentences = chapter.sentencesInOrder()
        val expected = (1..sentences.size).toList()
        if (sentences.map { it.sid } != expected) {
            errors.add(
                "sids [${sentences.take(8).joinToString { it.sid.toString() }}" +
                    "${if (sentences.size > 8) ", ..." else ""}]: " +
                    "must run 1..${sentences.size} in order"
            )
        }
        val duration = chapter.durationMs
        if (duration == null) {
            validateUntimed(textPath, sentences, errors)
            if (chapter.pages != null) {
                errors.add(
                    "pages marks need timings " +
                        "(unrendered chapters carry blocks without pages)"
                )
            }
        } else {
            if (duration <= 0) {
                errors.add("duration_ms $duration must be positive")
            }
            validateTimed(textPath, sentences, duration, errors)
            validatePages(chapter, sentences, duration, errors)
        }
        return if (errors.isEmpty()) {
            Result.success(Unit)
        } else {
            Result.failure(ChapterTextInvalid("$textPath: ${errors.joinToString("; ")}"))
        }
    }

    /** IN1: sentence checks for chapters without timings (spec 2.0). */
    private fun validateUntimed(
        textPath: String,
        sentences: List<Sentence>,
        errors: MutableList<String>
    ) {
        for (sentence in sentences) {
            if (sentence.page != null && sentence.page < 1) {
                errors.add("sid ${sentence.sid}: page ${sentence.page} must be 1-based")
            }
            if (sentence.startMs != null || sentence.endMs != null) {
                errors.add(
                    "sid ${sentence.sid} carries timings " +
                        "but the chapter has no duration_ms " +
                        "(unrendered chapters omit start_ms and end_ms everywhere)"
                )
            }
        }
    }

    /** Timing rules for chapters carrying `duration_ms` (spec section 6). */
    private fun validateTimed(
        textPath: String,
        sentences: List<Sentence>,
        duration: Long,
        errors: MutableList<String>
    ) {
        if (sentences.isNotEmpty()) {
            val first = sentences.first()
            if (first.startMs == null || first.endMs == null) {
                errors.add(
                    "sid ${first.sid} omits timings " +
                        "but the chapter has duration_ms $duration " +
                        "(rendered chapters time every sentence)"
                )
            } else if (first.startMs != 0L) {
                errors.add(
                    "first sentence starts at ${first.startMs}: must be 0"
                )
            }
        }
        var prevEnd = -1L
        var prevSid = 0
        var prevTimed = false
        sentences.forEach { sentence ->
            val start = sentence.startMs
            val end = sentence.endMs
            if (sentence.page != null && sentence.page < 1) {
                errors.add("sid ${sentence.sid}: page ${sentence.page} must be 1-based")
            }
            if (start == null || end == null) {
                if (sentence.sid != sentences.firstOrNull()?.sid) {
                    errors.add(
                        "sid ${sentence.sid} omits timings " +
                            "but the chapter has duration_ms $duration " +
                            "(rendered chapters time every sentence)"
                    )
                }
                prevTimed = false
                prevSid = sentence.sid
                return@forEach
            }
            if (start < 0 || start >= end) {
                errors.add(
                    "sid ${sentence.sid}: [$start, $end] " +
                        "is not a valid range"
                )
            } else {
                if (prevTimed && start < prevEnd) {
                    errors.add("sid ${sentence.sid} overlaps sid $prevSid")
                }
                if (end > duration) {
                    errors.add(
                        "sid ${sentence.sid} ends at $end, " +
                            "past duration $duration"
                    )
                }
            }
            prevEnd = if (!prevTimed) end else maxOf(prevEnd, end)
            prevTimed = true
            prevSid = sentence.sid
        }
    }

    /** CP6 v1.1 `pages` rules (light, never throws): sorted, first 0, match. */
    private fun validatePages(
        chapter: ChapterText,
        sentences: List<Sentence>,
        duration: Long,
        errors: MutableList<String>
    ) {
        val pages = chapter.pages ?: return
        if (pages.isEmpty()) {
            errors.add("pages present but empty (need one entry per page)")
            return
        }
        val firstStartByPage = LinkedHashMap<Int, Long>()
        for (sentence in sentences) {
            val page = sentence.page ?: continue
            val start = sentence.startMs ?: continue
            if (!firstStartByPage.containsKey(page)) {
                firstStartByPage[page] = start
            }
        }
        var prevPage: Int? = null
        var prevStart: Long? = null
        val seen = HashSet<Int>()
        for ((pos, mark) in pages.withIndex()) {
            if (mark.page < 1) {
                errors.add("page entry $pos has non-positive page ${mark.page}")
            }
            if (mark.startMs < 0 || mark.startMs > duration) {
                errors.add(
                    "page entry $pos start_ms ${mark.startMs} outside duration $duration"
                )
            }
            if (prevPage != null && mark.page <= prevPage) {
                errors.add("page entry $pos page ${mark.page} out of order (previous $prevPage)")
            }
            if (prevStart == null && mark.startMs != 0L) {
                errors.add("first page start_ms is ${mark.startMs}, expected 0")
            }
            if (prevStart != null && mark.startMs <= prevStart) {
                errors.add(
                    "page entry $pos start_ms ${mark.startMs} out of order (previous $prevStart)"
                )
            }
            if (!seen.add(mark.page)) {
                errors.add("page entry $pos duplicates page ${mark.page}")
            }
            val expected = firstStartByPage[mark.page]
            if (expected == null) {
                errors.add("page entry $pos page ${mark.page} has no sentences")
            } else if (expected != mark.startMs) {
                errors.add(
                    "page entry $pos start_ms ${mark.startMs} does not match " +
                        "first sentence on page ${mark.page} ($expected)"
                )
            }
            prevPage = mark.page
            prevStart = mark.startMs
        }
        for (sentence in sentences) {
            val page = sentence.page ?: continue
            if (!seen.contains(page)) {
                errors.add("sid ${sentence.sid} page $page missing from pages marks")
            }
        }
    }
}
