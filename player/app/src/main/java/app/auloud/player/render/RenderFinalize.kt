package app.auloud.player.render

import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.ChapterText
import app.auloud.player.bundle.ChapterTextLoader
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * RN7: crash-safe chapter finalize (Slice 10, D-096 write order).
 *
 * Per rendered chapter, in this exact order:
 * 1. chapter audio file (written by RN6 `ChapterEncode.encode` as
 *    `.m4a.tmp`-then-rename; this object only gates on its presence),
 * 2. timed chapter JSON (temp-then-rename, encoder offset applied here via
 *    [EncoderOffset], so readers need no correction),
 * 3. manifest update (temp-then-rename: `render_state`, chapter
 *    audio/text/duration, `gain_db`, `encoder_offset_ms`,
 *    `render_fingerprint`).
 *
 * Ordered writes plus atomic renames bound crash damage to the newest
 * chapter: readers never see a manifest entry without its audio and JSON
 * on disk. Each step is a separate function so tests simulate a crash at
 * each point (audio done/JSON missing, JSON done/manifest stale) and
 * `RenderRecovery` repairs to a consistent state. All file IO runs
 * through [RenderFileIo] (the RN3 seam, text plus rename plus delete),
 * so the retry math is JVM-testable with a fake and no RN3 file changes.
 *
 * No service, notification, MediaCodec or UI code. API 24 safe: pure
 * Kotlin plus kotlinx.serialization, epoch millis nowhere needed, no
 * `java.time`, no Android types.
 */
object RenderFinalize {

    /** Temp suffix for in-progress JSON writes (same directory, same volume). */
    const val TMP_SUFFIX = ".tmp"

    /** Manifest file name inside a book folder. */
    const val MANIFEST_FILE = "manifest.json"

    /** Write retry budget (RN3 parity: 20 x 50 ms). */
    const val WRITE_RETRIES = 20

    /** Write retry delay in millis. */
    const val WRITE_RETRY_DELAY_MS = 50L

    private val prettyJson = Json { prettyPrint = true; explicitNulls = false }

    /**
     * Device-rendered chapter audio rel for [chapterNumber]
     * (`audio/chNNN.m4a`, same convention the encoder writes).
     */
    fun deviceAudioRel(chapterNumber: Int): String =
        "audio/" + EncoderFiles.chapterFileName(chapterNumber)

    /** Chapter text rel for [chapterNumber] (`text/chNNN.json`). */
    fun chapterTextRel(chapterNumber: Int): String {
        require(chapterNumber >= 1) { "chapterNumber must be 1-based, got $chapterNumber." }
        return "text/ch%03d.json".format(chapterNumber)
    }

    /** Temp path for a final path (`text/ch001.json` gives `text/ch001.json.tmp`). */
    fun tmpPathFor(finalPath: String): String {
        require(finalPath.isNotBlank()) { "finalize output path is blank." }
        return finalPath + TMP_SUFFIX
    }

    /** One finished chapter: the rels plus the shifted (as-written) duration. */
    data class FinalizedChapter(
        val audioRel: String,
        val textRel: String,
        /** Duration written into the JSON and manifest (offset applied). */
        val durationMs: Int
    )

    /**
     * Finalizes one chapter end to end (D-096 order).
     *
     * Fails shaped without writing anything when the audio file is absent
     * (encode the chapter first); otherwise writes the timed JSON then the
     * manifest, each temp-then-rename. [gainDb] is the `BookGains.derive`
     * decibel map: stored only when the manifest has no `gain_db` yet
     * (first-chapter reference wins, D-095); pass empty to leave gains
     * untouched. [sleeper] is injectable so tests never really sleep.
     */
    fun finalizeChapter(
        bundleDir: String,
        chapterNumber: Int,
        audioRel: String,
        timings: List<AssemblySentenceTiming>,
        durationMs: Int,
        fingerprint: RenderFingerprint,
        gainDb: Map<String, Double> = emptyMap(),
        encoderOffsetMs: Int = 0,
        io: RenderFileIo,
        sleeper: (Long) -> Unit = Thread::sleep
    ): Result<FinalizedChapter> {
        require(chapterNumber >= 1) { "chapterNumber must be 1-based, got $chapterNumber." }
        val audioPath = join(bundleDir, audioRel)
        if (!io.exists(audioPath)) {
            return Result.failure(
                IOException(
                    "$audioPath: chapter audio missing " +
                        "(encode chapter $chapterNumber first)"
                )
            )
        }
        val textRel = chapterTextRel(chapterNumber)
        val textPath = join(bundleDir, textRel)
        val rawChapter = try {
            io.readText(textPath)
        } catch (e: Exception) {
            return Result.failure(
                IOException("$textPath: cannot read chapter text (${e.message})", e)
            )
        }
        val timedJson = buildTimedChapterJson(
            chapterNumber = chapterNumber,
            rawUntimedJson = rawChapter,
            timings = timings,
            durationMs = durationMs,
            offsetMs = encoderOffsetMs
        ).getOrElse { return Result.failure(it) }
        atomicWriteText(textPath, timedJson, io, sleeper).getOrElse {
            return Result.failure(it)
        }
        val manifestPath = join(bundleDir, MANIFEST_FILE)
        val rawManifest = try {
            io.readText(manifestPath)
        } catch (e: Exception) {
            return Result.failure(
                IOException("$manifestPath: cannot read manifest (${e.message})", e)
            )
        }
        val shiftedDuration = EncoderOffset.applyToTimings(timings, durationMs, encoderOffsetMs).second
        val updatedManifest = buildUpdatedManifestJson(
            rawManifestJson = rawManifest,
            chapterNumber = chapterNumber,
            audio = audioRel,
            durationMs = shiftedDuration.toLong(),
            fingerprint = fingerprint.toJsonObject(),
            gainDb = gainDb.takeIf { it.isNotEmpty() },
            encoderOffsetMs = encoderOffsetMs
        ).getOrElse { return Result.failure(it) }
        atomicWriteText(manifestPath, updatedManifest, io, sleeper).getOrElse {
            return Result.failure(it)
        }
        return Result.success(
            FinalizedChapter(audioRel = audioRel, textRel = textRel, durationMs = shiftedDuration)
        )
    }

    /**
     * Builds the timed chapter JSON for one chapter: parses the existing
     * untimed 2.0 text, attaches [timings] (shifted by [offsetMs] via
     * [EncoderOffset]) plus the shifted duration, and self-checks the
     * result through [ChapterTextLoader] so only validator-clean JSON is
     * ever written. Sentence count plus sids must match the timings
     * exactly; anything else fails naming chapter plus sid.
     *
     * Spec boundary (D-109): the offset shifts every timing including the
     * first start, but spec section 6 rule 1 still requires first
     * `start_ms` 0 (carried exactly into 2.0), so a nonzero offset fails
     * this self-check shaped instead of writing spec-violating JSON. The
     * provisional offset is 0 (D-102), so no live path hits this; the
     * RN11 beep measurement decides whether the spec gains an offset
     * exception (spec-first, then this check follows).
     */
    fun buildTimedChapterJson(
        chapterNumber: Int,
        rawUntimedJson: String,
        timings: List<AssemblySentenceTiming>,
        durationMs: Int,
        offsetMs: Int
    ): Result<String> {
        require(chapterNumber >= 1) { "chapterNumber must be 1-based, got $chapterNumber." }
        require(offsetMs >= 0) { "encoder offset must be non-negative, got $offsetMs." }
        val parsed = ChapterTextLoader.parse("text/ch%03d.json".format(chapterNumber), rawUntimedJson)
        val chapter = parsed.getOrElse {
            return Result.failure(
                IOException(
                    "text/ch%03d.json: untimed chapter unreadable (${it.message})".format(chapterNumber),
                    it
                )
            )
        }
        if (chapter.specVersion != "2.0") {
            return Result.failure(
                IOException(
                    "text/ch%03d.json: spec_version '${chapter.specVersion}' is not '2.0' ".format(chapterNumber) +
                        "(device renders finalize 2.0 chapters only)"
                )
            )
        }
        if (chapter.durationMs != null) {
            return Result.failure(
                IOException(
                    "text/ch%03d.json: chapter already carries duration_ms ".format(chapterNumber) +
                        "${chapter.durationMs} (finalize runs once per chapter)"
                )
            )
        }
        val sentences = chapter.sentencesInOrder()
        if (sentences.size != timings.size) {
            return Result.failure(
                IOException(
                    "chapter $chapterNumber: chapter has ${sentences.size} sentences " +
                        "but timings carry ${timings.size} (re-render the chapter)"
                )
            )
        }
        val bySid = timings.associateBy { it.sid }
        for (sentence in sentences) {
            if (bySid[sentence.sid] == null) {
                return Result.failure(
                    IOException(
                        "chapter $chapterNumber sentence ${sentence.sid}: " +
                            "no timing (re-render the chapter)"
                    )
                )
            }
        }
        val (shifted, shiftedDuration) = try {
            EncoderOffset.applyToTimings(timings, durationMs, offsetMs)
        } catch (e: Exception) {
            return Result.failure(
                IOException("chapter $chapterNumber: bad timings (${e.message})", e)
            )
        }
        val shiftedBySid = shifted.associateBy { it.sid }
        val timedBlocks = chapter.blocks.map { block ->
            block.copy(
                sentences = block.sentences.map { sentence ->
                    val timing = shiftedBySid[sentence.sid]
                        ?: return Result.failure(
                            IOException(
                                "chapter $chapterNumber sentence ${sentence.sid}: " +
                                    "no timing (re-render the chapter)"
                            )
                        )
                    sentence.copy(
                        startMs = timing.startMs.toLong(),
                        endMs = timing.endMs.toLong()
                    )
                }
            )
        }
        val timed = chapter.copy(
            durationMs = shiftedDuration.toLong(),
            blocks = timedBlocks
        )
        val text = prettyJson.encodeToString(ChapterText.serializer(), timed)
        val check = ChapterTextLoader.parse("text/ch%03d.json".format(chapterNumber), text)
        if (check.isFailure) {
            return Result.failure(
                IOException(
                    "text/ch%03d.json: timed chapter failed self-check ".format(chapterNumber) +
                        "(${check.exceptionOrNull()?.message})"
                )
            )
        }
        return Result.success(text)
    }

    /**
     * Builds the updated `manifest.json` for one finalized chapter.
     *
     * Edits the raw JSON object (unknown keys preserved): sets the chapter
     * entry audio/duration/`render_fingerprint`, records manifest
     * `encoder_offset_ms`, stores `gain_db` only when the manifest has none
     * yet (first-chapter reference wins, D-095; pass null to leave gains
     * untouched), ensures the manifest `audio` object exists for rendered
     * content (created as m4a when absent, left alone when present so mixed
     * MP3/M4A books keep their value), removes it again when the recompute
     * lands on `none`, and recomputes `render_state` from chapter
     * duration presence (all rendered is `complete`, some is `partial`,
     * none is `none`). Null [audio]/[durationMs] strips the entry back to
     * unrendered (recovery downgrade); null [fingerprint] removes the
     * fingerprint field (recovery forward-complete invents none: absent
     * means unknown, never up to date).
     */
    fun buildUpdatedManifestJson(
        rawManifestJson: String,
        chapterNumber: Int,
        audio: String?,
        durationMs: Long?,
        fingerprint: JsonObject?,
        gainDb: Map<String, Double>?,
        encoderOffsetMs: Int?
    ): Result<String> {
        require(chapterNumber >= 1) { "chapterNumber must be 1-based, got $chapterNumber." }
        if (encoderOffsetMs != null) {
            require(encoderOffsetMs >= 0) {
                "encoder offset must be non-negative, got $encoderOffsetMs."
            }
        }
        if (gainDb != null) {
            for ((role, db) in gainDb) {
                if (role != "narrator" && role != "dialogue") {
                    return Result.failure(
                        IOException(
                            "manifest.json: gain_db has unknown role \"$role\" " +
                                "(need narrator and/or dialogue)"
                        )
                    )
                }
                if (!db.isFinite()) {
                    return Result.failure(
                        IOException("manifest.json: gain_db.$role must be a finite number (got $db)")
                    )
                }
            }
        }
        val root = try {
            BundleParser.json.parseToJsonElement(rawManifestJson) as? JsonObject
                ?: return Result.failure(
                    IOException("manifest.json: manifest must be an object")
                )
        } catch (e: Exception) {
            return Result.failure(
                IOException("manifest.json: manifest unreadable (${e.message})", e)
            )
        }
        val spec = (root["spec_version"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (spec != "2.0") {
            return Result.failure(
                IOException(
                    "manifest.json: spec_version '$spec' is not '2.0' " +
                        "(device renders finalize 2.0 books only)"
                )
            )
        }
        val chapters = root["chapters"] as? JsonArray
            ?: return Result.failure(IOException("manifest.json: manifest needs a chapters array"))
        var found = false
        val updatedChapters = JsonArray(chapters.map { element ->
            val entry = element as? JsonObject ?: return Result.failure(
                IOException("manifest.json: chapter entry must be an object")
            )
            val index = (entry["index"] as? JsonPrimitive)?.intOrNull
            if (index != chapterNumber) return@map element
            found = true
            val edited = entry.toMutableMap()
            if (audio == null) {
                edited.remove("audio")
            } else {
                edited["audio"] = JsonPrimitive(audio)
            }
            if (durationMs == null) {
                edited.remove("duration_ms")
            } else {
                if (durationMs <= 0L) {
                    return Result.failure(
                        IOException(
                            "manifest.json: chapter $chapterNumber has non-positive " +
                                "duration_ms $durationMs"
                        )
                    )
                }
                edited["duration_ms"] = JsonPrimitive(durationMs)
            }
            if (fingerprint == null) {
                edited.remove("render_fingerprint")
            } else {
                edited["render_fingerprint"] = fingerprint
            }
            JsonObject(edited)
        })
        if (!found) {
            return Result.failure(
                IOException("manifest.json: no chapter entry with index $chapterNumber")
            )
        }
        val rendered = updatedChapters.count { chapter ->
            val obj = chapter as? JsonObject
            (obj?.get("duration_ms") as? JsonPrimitive)
                ?.takeIf { !it.isString }?.longOrNull != null
        }
        val unrendered = updatedChapters.size - rendered
        val renderState = when {
            rendered > 0 && unrendered > 0 -> "partial"
            rendered > 0 -> "complete"
            else -> "none"
        }
        val edited = root.toMutableMap()
        edited["chapters"] = updatedChapters
        edited["render_state"] = JsonPrimitive(renderState)
        if (renderState == "none") {
            edited.remove("audio")
        } else if (!edited.containsKey("audio")) {
            edited["audio"] = buildJsonObject {
                put("format", "m4a")
                put("channels", 1)
                put("sample_rate", 24000)
                put("bitrate_kbps", 64)
                put("cbr", true)
            }
        }
        if (gainDb != null && gainDb.isNotEmpty() && !edited.containsKey("gain_db")) {
            edited["gain_db"] = buildJsonObject {
                for ((role, db) in gainDb.toSortedMap()) put(role, db)
            }
        }
        if (encoderOffsetMs != null) {
            edited["encoder_offset_ms"] = JsonPrimitive(encoderOffsetMs)
        }
        val updated = JsonObject(edited)
        val text = prettyJson.encodeToString(JsonObject.serializer(), updated)
        val check = BundleParser.parseText(text)
        if (check.isFailure) {
            return Result.failure(
                IOException(
                    "manifest.json: updated manifest failed self-check " +
                        "(${check.exceptionOrNull()?.message})"
                )
            )
        }
        return Result.success(text)
    }

    /**
     * Temp-then-rename text write with the RN3 retry loop (20 x 50 ms,
     * delete-target fallback for the Windows rename case). Readers only
     * ever see the old file or the new file, never a torn write.
     */
    internal fun atomicWriteText(
        targetPath: String,
        text: String,
        io: RenderFileIo,
        sleeper: (Long) -> Unit = Thread::sleep
    ): Result<Unit> {
        val tmpPath = tmpPathFor(targetPath)
        try {
            io.writeText(tmpPath, text)
        } catch (e: Exception) {
            return Result.failure(
                IOException("$targetPath: cannot write file (${e.message})", e)
            )
        }
        var attempt = 0
        while (true) {
            val renamed = try {
                io.renameTempToTarget(tmpPath, targetPath)
            } catch (_: Exception) {
                false
            }
            if (renamed) {
                io.deleteIfExists(tmpPath)
                return Result.success(Unit)
            }
            if (io.exists(targetPath)) io.deleteIfExists(targetPath)
            attempt++
            if (attempt >= WRITE_RETRIES) {
                io.deleteIfExists(tmpPath)
                return Result.failure(
                    IOException("$targetPath: cannot move temp file into place")
                )
            }
            try {
                sleeper(WRITE_RETRY_DELAY_MS)
            } catch (_: Exception) {
            }
        }
    }

    private fun join(dir: String, rel: String): String =
        dir.trimEnd('/') + '/' + rel.trimStart('/')
}
