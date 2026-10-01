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
 * - [ChapterTextUnavailable]: missing/unreadable file, or a PDF page-sync
 *   chapter (`pages` instead of `blocks`). Reader shows "text unavailable",
 *   listening still works.
 * - [ChapterTextInvalid]: malformed JSON or a spec rule broken. Names the
 *   file and every rule violated.
 *
 * Parsing runs on `Dispatchers.IO`, never the caller's thread.
 *
 * API 24 safe: `java.io` + kotlinx.serialization only.
 */
class ChapterTextUnavailable(message: String, cause: Throwable? = null) :
    IOException(message, cause)

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
            return Result.failure(
                ChapterTextUnavailable(
                    "$textPath: PDF page-sync chapter " +
                        "(reader not available for this book yet)"
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

    /** Spec section 4/6 rules: consecutive sids, ordered non-overlapping timings. */
    internal fun validate(textPath: String, chapter: ChapterText): Result<Unit> {
        val errors = ArrayList<String>()
        if (chapter.durationMs <= 0) {
            errors.add("duration_ms ${chapter.durationMs} must be positive")
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
        if (sentences.isNotEmpty() && sentences.first().startMs != 0L) {
            errors.add(
                "first sentence starts at ${sentences.first().startMs}: must be 0"
            )
        }
        var prevEnd = -1L
        var prevSid = 0
        sentences.forEach { sentence ->
            if (sentence.startMs < 0 || sentence.startMs >= sentence.endMs) {
                errors.add(
                    "sid ${sentence.sid}: [${sentence.startMs}, ${sentence.endMs}] " +
                        "is not a valid range"
                )
            } else {
                if (prevEnd >= 0 && sentence.startMs < prevEnd) {
                    errors.add("sid ${sentence.sid} overlaps sid $prevSid")
                }
                if (sentence.endMs > chapter.durationMs) {
                    errors.add(
                        "sid ${sentence.sid} ends at ${sentence.endMs}, " +
                            "past duration ${chapter.durationMs}"
                    )
                }
            }
            prevEnd = sentence.endMs
            prevSid = sentence.sid
        }
        return if (errors.isEmpty()) {
            Result.success(Unit)
        } else {
            Result.failure(ChapterTextInvalid("$textPath: ${errors.joinToString("; ")}"))
        }
    }
}
