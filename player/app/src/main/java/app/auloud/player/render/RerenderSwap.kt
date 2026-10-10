package app.auloud.player.render

import app.auloud.player.bundle.BundleParser
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * VS3: versioned re-render swap (D-116, D-096 order, D-109 recovery).
 *
 * Spec-filename check (D-116 caution, spec/bundle.md sections 2 and 7):
 * per-chapter `audio` paths must end in `.mp3` or `.m4a` (2.0 device
 * chapters use `.m4a`); the extension decides the codec and the manifest
 * `audio.format` must match at least one rendered chapter. No rule pins
 * the exact file stem: the layout example shows `audio/ch001.m4a` and
 * section 1 asks for ASCII lowercase plus zero-padded chapter numbers,
 * but the validator enforces only the extension plus file presence.
 * A versioned name `audio/ch001-<8hex>.m4a` therefore stays inside the
 * spec: lowercase ASCII, zero-padded `ch001` prefix intact, `.m4a`
 * extension, manifest format still `m4a`, validator-clean. No spec
 * change was needed; if the spec ever pins the stem exactly, this file
 * must stop and ask first (slice-workflow rule).
 *
 * Swap order per chapter (Slice 10 D-096 order, manifest switches last):
 * 1. new audio file (written by the RN6 encoder path under the versioned
 *    rel; this object only gates on its presence, never encodes),
 * 2. timed chapter JSON (temp-then-rename, offset applied at write),
 * 3. manifest update (temp-then-rename: audio switches to the versioned
 *    rel, duration, narrowed `render_fingerprint`, `gain_db` merged for
 *    changed roles, `encoder_offset_ms`),
 * 4. old audio delete (best effort after the switch; when the player
 *    still has the old file open the delete is recorded as deferred and
 *    a later recovery or render run finishes it; when no further render
 *    runs the deferred file stays on disk until then).
 *
 * Cancel or failure leaves the old audio plus the old fingerprint
 * untouched (the manifest is only written on success, and the old file
 * is only deleted after that write). The chapter therefore stays Stale
 * and the next run retries it.
 *
 * Crash states (each covered by a JVM test):
 * - audio done, JSON missing: manifest still points at old, new audio
 *   unreferenced (orphan, swept); old audio plus old JSON plus old
 *   manifest stay consistent.
 * - JSON done, manifest stale: manifest still points at old while the
 *   JSON carries new timings; [RerenderRecovery] forward-completes the
 *   manifest to the versioned audio when exactly one versioned
 *   candidate for the chapter exists and the JSON duration differs
 *   from the manifest duration, with the fingerprint removed (STALE
 *   until re-verified). A same-duration retimed JSON is
 *   indistinguishable from old and follows the orphan path; other
 *   ambiguity (many candidates, untimed JSON) preserves the versioned
 *   file for retry, so new audio is never silently lost.
 * - manifest done, old delete pending: manifest points at new, both
 *   files present; recovery deletes the unreferenced old (deferred while
 *   open).
 * - new referenced but JSON missing/untimed/corrupt: refuse and record
 *   like D-109 (downgrade to unrendered, never invent timings).
 *
 * Pure apart from the injected [RenderFileIo]; API 24 safe, no new
 * dependency, no permission.
 */
object RerenderSwap {

    /** Temp suffix for staged versioned audio (matches the encoder convention). */
    const val VERSIONED_TMP_SUFFIX = ".tmp"

    /**
     * Versioned audio rel for [chapterNumber] under [fingerprint]
     * (`audio/ch001-<tag>.m4a`, tag is the fingerprint fileTag).
     */
    fun versionedAudioRel(chapterNumber: Int, fingerprint: RenderFingerprint): String {
        require(chapterNumber >= 1) { "chapterNumber must be 1-based, got $chapterNumber." }
        val stem = "ch%03d".format(chapterNumber)
        val tag = fingerprint.fileTag()
        require(tag.matches(Regex("[0-9a-f]{8}"))) {
            "chapter $chapterNumber: fingerprint tag must be 8 lowercase hex, got \"$tag\""
        }
        return "audio/$stem-$tag.m4a"
    }

    /**
     * True when [audioRel] is a versioned device rel
     * (`audio/chNNN-<8hex>.m4a`).
     */
    fun isVersionedAudioRel(audioRel: String): Boolean {
        if (!audioRel.startsWith("audio/ch")) return false
        if (!audioRel.endsWith(".m4a")) return false
        val name = audioRel.substringAfterLast('/')
        return name.matches(Regex("ch\\d{3,}-([0-9a-f]{8})\\.m4a"))
    }

    /** 1-based chapter number from an audio rel stem, or null. */
    fun chapterNumberFromAudioRel(audioRel: String): Int? {
        val name = audioRel.substringAfterLast('/')
        if (!name.startsWith("ch")) return null
        val digits = name.removePrefix("ch").takeWhile { it.isDigit() }
        if (digits.isEmpty()) return null
        return digits.toIntOrNull()?.takeIf { it >= 1 }
    }

    /** One swapped chapter: rels plus the duration written. */
    data class SwappedChapter(
        val audioRel: String,
        val textRel: String,
        val durationMs: Int,
        /** Old audio rel when it still needs deleting (deferred), else null. */
        val deferredOldAudio: String?
    )

    /**
     * Finalizes one re-rendered chapter end to end (D-116 order).
     *
     * @param oldAudioRel manifest audio rel before the swap (stays
     * readable until the switch; deleted after, deferred when open).
     * @param newAudioRel versioned rel from [versionedAudioRel] (must
     * already exist: encode first).
     * @param gainDb merged gains for the manifest (null leaves gains
     * untouched; when non-null, changed roles overwrite even though the
     * manifest already carries `gain_db` - unlike first-render
     * first-wins, per D-120).
     */
    fun finalizeRerender(
        bundleDir: String,
        chapterNumber: Int,
        oldAudioRel: String,
        newAudioRel: String,
        timings: List<AssemblySentenceTiming>,
        durationMs: Int,
        fingerprint: RenderFingerprint,
        gainDb: Map<String, Double>? = null,
        encoderOffsetMs: Int = 0,
        io: RenderFileIo,
        sleeper: (Long) -> Unit = Thread::sleep
    ): Result<SwappedChapter> {
        require(chapterNumber >= 1) { "chapterNumber must be 1-based, got $chapterNumber." }
        require(oldAudioRel.isNotBlank()) { "chapter $chapterNumber: old audio rel is blank." }
        require(newAudioRel.isNotBlank()) { "chapter $chapterNumber: new audio rel is blank." }
        require(newAudioRel != oldAudioRel) {
            "chapter $chapterNumber: new audio must differ from old (versioned swap, got \"$newAudioRel\")"
        }
        val newAudioPath = join(bundleDir, newAudioRel)
        if (!io.exists(newAudioPath)) {
            return Result.failure(
                IOException(
                    "$newAudioPath: chapter audio missing " +
                        "(encode chapter $chapterNumber first)"
                )
            )
        }
        val textRel = RenderFinalize.chapterTextRel(chapterNumber)
        val textPath = join(bundleDir, textRel)
        val rawChapter = try {
            io.readText(textPath)
        } catch (e: Exception) {
            return Result.failure(
                IOException("$textPath: cannot read chapter text (${e.message})", e)
            )
        }
        val timedJson = buildRetimedChapterJson(
            chapterNumber = chapterNumber,
            rawTimedJson = rawChapter,
            timings = timings,
            durationMs = durationMs,
            offsetMs = encoderOffsetMs
        ).getOrElse { return Result.failure(it) }
        val jsonToWrite: String = timedJson
        RenderFinalize.atomicWriteText(textPath, jsonToWrite, io, sleeper).getOrElse {
            return Result.failure(it)
        }
        val manifestPath = join(bundleDir, RenderFinalize.MANIFEST_FILE)
        val rawManifest = try {
            io.readText(manifestPath)
        } catch (e: Exception) {
            return Result.failure(
                IOException("$manifestPath: cannot read manifest (${e.message})", e)
            )
        }
        val shiftedDuration = EncoderOffset.applyToTimings(timings, durationMs, encoderOffsetMs).second
        val withoutGain = RenderFinalize.buildUpdatedManifestJson(
            rawManifestJson = rawManifest,
            chapterNumber = chapterNumber,
            audio = newAudioRel,
            durationMs = shiftedDuration.toLong(),
            fingerprint = fingerprint.toJsonObject(),
            gainDb = null,
            encoderOffsetMs = encoderOffsetMs
        ).getOrElse { return Result.failure(it) }
        val withGain = if (gainDb == null) {
            withoutGain
        } else {
            applyGainDbOverwrite(withoutGain, gainDb).getOrElse { return Result.failure(it) }
        }
        RenderFinalize.atomicWriteText(manifestPath, withGain, io, sleeper).getOrElse {
            return Result.failure(it)
        }
        var deferred: String? = null
        if (oldAudioRel != newAudioRel) {
            val oldPath = join(bundleDir, oldAudioRel)
            var stillThere = false
            try {
                io.deleteIfExists(oldPath)
            } catch (_: Exception) {
                stillThere = true
            }
            try {
                stillThere = io.exists(oldPath)
            } catch (_: Exception) {
            }
            if (stillThere) {
                deferred = oldAudioRel
            }
        }
        return Result.success(
            SwappedChapter(
                audioRel = newAudioRel,
                textRel = textRel,
                durationMs = shiftedDuration,
                deferredOldAudio = deferred
            )
        )
    }

    /**
     * Builds timed JSON for a re-render: the existing file already
     * carries old timings, so the old timings are replaced with [timings]
     * (shifted by [offsetMs]) instead of refusing like the first-render
     * path. Block and sentence structure (counts plus sids) must still
     * match exactly; anything else fails naming chapter plus sid.
     */
    fun buildRetimedChapterJson(
        chapterNumber: Int,
        rawTimedJson: String,
        timings: List<AssemblySentenceTiming>,
        durationMs: Int,
        offsetMs: Int
    ): Result<String> {
        require(chapterNumber >= 1) { "chapterNumber must be 1-based, got $chapterNumber." }
        require(offsetMs >= 0) { "encoder offset must be non-negative, got $offsetMs." }
        val textRel = RenderFinalize.chapterTextRel(chapterNumber)
        val parsed = app.auloud.player.bundle.ChapterTextLoader.parse(textRel, rawTimedJson)
        val chapter = parsed.getOrElse {
            return Result.failure(
                IOException("$textRel: rendered chapter unreadable (${it.message})", it)
            )
        }
        if (chapter.specVersion != "2.0") {
            return Result.failure(
                IOException(
                    "$textRel: spec_version '${chapter.specVersion}' is not '2.0' " +
                        "(device re-renders finalize 2.0 chapters only)"
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
        val retimedBlocks = chapter.blocks.map { block ->
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
        val retimed = chapter.copy(
            durationMs = shiftedDuration.toLong(),
            blocks = retimedBlocks
        )
        val pretty = kotlinx.serialization.json.Json { prettyPrint = true; explicitNulls = false }
        val text = pretty.encodeToString(
            app.auloud.player.bundle.ChapterText.serializer(), retimed
        )
        val check = app.auloud.player.bundle.ChapterTextLoader.parse(textRel, text)
        if (check.isFailure) {
            return Result.failure(
                IOException(
                    "$textRel: retimed chapter failed self-check " +
                        "(${check.exceptionOrNull()?.message})"
                )
            )
        }
        return Result.success(text)
    }

    /**
     * Overwrites manifest `gain_db` with [gainDb] (re-render D-120;
     * first-render keeps first-wins inside [RenderFinalize]). Unknown
     * keys preserved; the result self-checks through [BundleParser].
     */
    fun applyGainDbOverwrite(
        rawManifestJson: String,
        gainDb: Map<String, Double>
    ): Result<String> {
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
        val edited = root.toMutableMap()
        edited["gain_db"] = buildJsonObject {
            for ((role, db) in gainDb.toSortedMap()) put(role, db)
        }
        val updated = JsonObject(edited)
        val pretty = kotlinx.serialization.json.Json { prettyPrint = true; explicitNulls = false }
        val text = pretty.encodeToString(JsonObject.serializer(), updated)
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
     * Pure orphan pick: unreferenced device audio plus all audio temps.
     *
     * @param referenced manifest audio rels (as listed).
     * @param existing absolute audio paths present on disk.
     * @param bundleDir book folder (to map rels to absolute paths).
     * Returns absolute paths to delete (sorted): every `*.tmp` under
     * audio plus every unreferenced `*.m4a` (versioned orphans before
     * the switch, old files after it). Referenced files are never
     * returned. Caller deletes best effort (deferred when open).
     */
    fun pickSwapOrphans(
        bundleDir: String,
        referenced: Set<String>,
        existing: List<String>
    ): List<String> {
        val root = bundleDir.trimEnd('/')
        val referencedAbs = referenced.map { join(root, it) }.toSet()
        val out = ArrayList<String>()
        for (path in existing) {
            if (!path.startsWith(root + "/audio/")) continue
            if (path.endsWith(VERSIONED_TMP_SUFFIX)) {
                out.add(path)
                continue
            }
            if (path.endsWith(".m4a") && path !in referencedAbs) {
                out.add(path)
            }
        }
        return out.sorted()
    }

    private fun join(dir: String, rel: String): String =
        dir.trimEnd('/') + '/' + rel.trimStart('/')
}
