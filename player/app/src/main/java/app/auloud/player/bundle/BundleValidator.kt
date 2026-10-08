package app.auloud.player.bundle

import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * WP2: light import-time checks over an already-parsed [Manifest].
 *
 * Checks required fields are present (non-blank), each rendered chapter's
 * MP3 file exists under the bundle directory, and `duration_ms` is
 * positive when present.
 *
 * IN1 (spec 2.0): the version gate accepts "1.0", "1.1", "1.2" and "2.0"
 * (anything else, e.g. "3.0", fails naming `manifest.json` and the rule,
 * which is also how pre-2.0 Players refuse 2.0 books cleanly).
 * `render_state` is absent in 1.x and required (`none`/`partial`/
 * `complete`) in 2.0; per-chapter `audio`/`duration_ms` are required in
 * 1.x and paired-or-absent in 2.0 (presence of `duration_ms` marks a
 * rendered chapter); 2.0 sentences use only the reserved `narrator` and
 * `dialogue` speakers. Unrendered chapters skip the audio-existence check
 * (there is no MP3 yet).
 *
 * CP4: optional text-file check. When [readText] is provided, every chapter
 * with a non-blank `text` field is also checked: a missing file (via
 * [exists]) or unparsable JSON (via the same pure parser [ChapterTextLoader]
 * uses, no duplicated JSON logic) becomes a chapter-scoped error naming the
 * file and the rule, e.g.
 * `manifest.json: chapter 2 text file invalid text/ch002.json (<reason>)`.
 * On a successful parse the chapter is also cross-checked against its
 * manifest entry (spec-version match, duration presence match, 2.0
 * reserved speakers) via [validateAgainstManifest]. PDF page-sync chapters
 * (`pages` instead of `blocks`) are NOT errors —
 * listening still works. Files over [MAX_TEXT_BYTES] are refused with a
 * named error without parsing, so the check cannot OOM on huge values
 * (`ChapterTextLoader` itself caps nothing, so this cap lives here and is
 * mirrored in the KDoc).
 *
 * Every returned error names the file and the rule broken, e.g.
 * `manifest.json: chapter 2 audio file missing audio/ch002.mp3`.
 * An empty list means valid. A bad chapter must be skipped with a message,
 * never crash import or playback (enforced by callers in WP5).
 *
 * API 24 safe: uses `java.io.File` only.
 *
 * The audio-existence check is a seam so callers that must not touch files
 * (WP5 `LibraryViewModel`, which sees storage only through `BundleStorage`)
 * run the SAME rules: pass e.g. `storage::exists`. The text check adds the
 * [readText] seam the same way (e.g. `storage::readText`); it may return
 * null for unreadable files and may throw — both become named chapter
 * errors, never crashes. Pure + JVM-testable.
 */
object BundleValidator {

    /** CP4: text files over this size are refused without parsing (8 MB). */
    const val MAX_TEXT_BYTES = 8 * 1024 * 1024

    /** Spec versions this Player reads (IN1: 2.0 joins the 1.x line). */
    val SUPPORTED_VERSIONS = setOf("1.0", "1.1", "1.2", "2.0")

    /** 1.x versions: audio and timings are required, never conditional. */
    val V1_VERSIONS = setOf("1.0", "1.1", "1.2")

    /** IN1: manifest `render_state` values (spec 2.0 section 3). */
    val RENDER_STATES = setOf("none", "partial", "complete")

    /** IN1: reserved 2.0 speakers (spec 2.0 section 3). */
    val RESERVED_SPEAKERS = setOf("narrator", "dialogue")

    /** RN1: manifest `audio.format` values (spec 2.0 part 2 section 2). */
    val AUDIO_FORMATS = setOf("mp3", "m4a")

    /** RN1: roles carrying per-role gain (spec 2.0 part 2 section 3). */
    val GAIN_ROLES = setOf("narrator", "dialogue")

    /**
     * RN1: `mp3`/`m4a` from a chapter `audio` path extension, null for
     * anything else. The extension decides the chapter codec (lowercase
     * `.mp3`/`.m4a` only, matching the lowercase generated-file rule).
     */
    fun audioFormatForExtension(audio: String): String? =
        when {
            audio.endsWith(".mp3") -> "mp3"
            audio.endsWith(".m4a") -> "m4a"
            else -> null
        }

    fun validate(
        bundleDir: File,
        manifest: Manifest,
        readText: ((String) -> String?)? = null
    ): List<String> =
        validate(bundleDir.path, manifest, { File(it).isFile }, readText)

    fun validate(
        bundleDirPath: String,
        manifest: Manifest,
        exists: (String) -> Boolean = { File(it).isFile },
        readText: ((String) -> String?)? = null
    ): List<String> {
        val errors = mutableListOf<String>()
        if (manifest.specVersion.isBlank()) {
            errors.add("manifest.json: missing required field spec_version")
        } else if (manifest.specVersion !in SUPPORTED_VERSIONS) {
            errors.add("manifest.json: spec_version \"${manifest.specVersion}\" must be \"1.0\", \"1.1\", \"1.2\" or \"2.0\"")
        }
        errors.addAll(validateRenderState(manifest))
        if (manifest.id.isBlank()) {
            errors.add("manifest.json: missing required field id")
        }
        if (manifest.title.isBlank()) {
            errors.add("manifest.json: missing required field title")
        }
        if (manifest.type.isBlank()) {
            errors.add("manifest.json: missing required field type")
        }
        if (manifest.specVersion in V1_VERSIONS && manifest.audio == null) {
            errors.add("manifest.json: missing required field audio (absent)")
        }
        val audioFormat = manifest.audio?.format
        if (manifest.audio != null && audioFormat !in AUDIO_FORMATS) {
            errors.add("manifest.json: audio.format \"$audioFormat\" must be \"mp3\" or \"m4a\"")
        }
        if (manifest.specVersion == "2.0") {
            if (manifest.renderState == "none" && manifest.audio != null) {
                errors.add(
                    "manifest.json: audio must be absent when render_state is 'none' " +
                        "(unrendered books carry no audio)"
                )
            }
            if ((manifest.renderState == "partial" || manifest.renderState == "complete") &&
                manifest.audio == null
            ) {
                errors.add(
                    "manifest.json: missing required field audio " +
                        "(render_state '${manifest.renderState}' needs it for rendered chapters)"
                )
            }
        }
        if (manifest.chapters.isEmpty()) {
            errors.add("manifest.json: no chapters listed")
        }
        for (chapter in manifest.chapters) {
            val label = "chapter ${chapter.index}"
            if (chapter.title.isBlank()) {
                errors.add("manifest.json: $label missing required field title")
            }
            if (chapter.text.isBlank()) {
                errors.add("manifest.json: $label missing required field text")
            }
            val hasAudio = chapter.audio.isNotBlank()
            val hasDuration = chapter.durationMs != null
            if (manifest.specVersion in V1_VERSIONS) {
                if (!hasAudio) {
                    errors.add("manifest.json: $label missing required field audio")
                }
                if (!hasDuration) {
                    errors.add("manifest.json: $label missing required field duration_ms (absent)")
                } else if (chapter.durationMs ?: 0L <= 0L) {
                    errors.add(
                        "manifest.json: $label has non-positive duration_ms ${chapter.durationMs}"
                    )
                }
            } else if (manifest.specVersion == "2.0") {
                if (hasAudio != hasDuration) {
                    if (hasAudio) {
                        errors.add(
                            "manifest.json: $label has audio without duration_ms " +
                                "(rendered chapters need both; unrendered chapters omit both)"
                        )
                    } else {
                        errors.add(
                            "manifest.json: $label has duration_ms without audio " +
                                "(rendered chapters need both; unrendered chapters omit both)"
                        )
                    }
                } else if (hasDuration && (chapter.durationMs ?: 0L) <= 0L) {
                    errors.add(
                        "manifest.json: $label has non-positive duration_ms ${chapter.durationMs}"
                    )
                }
            } else if (chapter.durationMs != null && chapter.durationMs <= 0L) {
                errors.add(
                    "manifest.json: $label has non-positive duration_ms ${chapter.durationMs}"
                )
            }
            // RN1 (spec 2.0 part 2): the audio extension decides the
            // chapter codec (1.x books always use .mp3).
            if (hasAudio) {
                val chapterFormat = audioFormatForExtension(chapter.audio)
                if (manifest.specVersion in V1_VERSIONS && chapterFormat != "mp3") {
                    errors.add(
                        "manifest.json: $label audio file \"${chapter.audio}\" " +
                            "must end in .mp3 (m4a audio needs spec 2.0)"
                    )
                } else if (manifest.specVersion == "2.0" && chapterFormat == null) {
                    errors.add(
                        "manifest.json: $label audio file \"${chapter.audio}\" " +
                            "must end in .mp3 or .m4a " +
                            "(per-chapter format comes from the extension)"
                    )
                }
            }
            errors.addAll(validateChapterFingerprint(manifest, chapter))
            if (hasAudio && (manifest.specVersion in V1_VERSIONS || hasDuration)) {
                val audioPath = joinPath(bundleDirPath, chapter.audio)
                if (!exists(audioPath)) {
                    errors.add("manifest.json: $label audio file missing ${chapter.audio}")
                }
            }
            if (readText != null && chapter.text.isNotBlank()) {
                val textPath = joinPath(bundleDirPath, chapter.text)
                if (!exists(textPath)) {
                    errors.add("manifest.json: $label text file missing ${chapter.text}")
                } else {
                    val raw: String? = try {
                        readText(textPath)
                    } catch (e: Exception) {
                        errors.add(
                            "manifest.json: $label text file invalid ${chapter.text} " +
                                "(${e.message ?: "cannot read"})"
                        )
                        null
                    }
                    if (raw == null) {
                        // Null means unreadable without an exception. The throw
                        // branch above already reported, so only report here
                        // when the last error does not cover this file.
                        val last = errors.lastOrNull()
                        if (last == null || !last.contains(chapter.text)) {
                            errors.add(
                                "manifest.json: $label text file invalid ${chapter.text} " +
                                    "(cannot read)"
                            )
                        }
                    } else if (raw.length > MAX_TEXT_BYTES) {
                        errors.add(
                            "manifest.json: $label text file too large ${chapter.text} " +
                                "(size ${raw.length} exceeds 8 MB limit)"
                        )
                    } else {
                        val parsed = ChapterTextLoader.parse(chapter.text, raw)
                        val failure = parsed.exceptionOrNull()
                        if (parsed.isFailure && failure !is ChapterTextPdfForm) {
                            errors.add(
                                "manifest.json: $label text file invalid ${chapter.text} " +
                                    "(${failure?.message ?: "invalid"})"
                            )
                        } else if (parsed.isSuccess) {
                            for (problem in validateAgainstManifest(manifest, chapter, parsed.getOrThrow())) {
                                errors.add(
                                    "manifest.json: $label text file invalid ${chapter.text} ($problem)"
                                )
                            }
                        }
                    }
                }
            }
        }
        errors.addAll(validateAudioFormatAgreement(manifest))
        errors.addAll(validateDeviceFields(manifest))
        errors.addAll(validateRenderStateConsistency(manifest))
        return errors
    }

    /**
     * IN1: `render_state` presence and value (spec 2.0 section 3/7).
     * 1.x bundles must not carry it; 2.0 bundles must carry exactly one
     * of `none`/`partial`/`complete`. Every error names the file + rule.
     */
    internal fun validateRenderState(manifest: Manifest): List<String> {
        if (manifest.specVersion in V1_VERSIONS && manifest.renderState != null) {
            return listOf(
                "manifest.json: render_state \"${manifest.renderState}\" " +
                    "must be absent in 1.x bundles (2.0 only)"
            )
        }
        if (manifest.specVersion == "2.0" && manifest.renderState !in RENDER_STATES) {
            return listOf(
                "manifest.json: render_state \"${manifest.renderState}\" " +
                    "must be one of none, partial, complete"
            )
        }
        return emptyList()
    }

    /**
     * IN1: manifest `render_state` must agree with the chapters (spec 2.0
     * section 3/7). Per-chapter state is inferred from `duration_ms`
     * presence. Pure + JVM-testable.
     */
    internal fun validateRenderStateConsistency(manifest: Manifest): List<String> {
        if (manifest.specVersion != "2.0" || manifest.renderState !in RENDER_STATES) {
            return emptyList()
        }
        val rendered = manifest.chapters.count { it.durationMs != null }
        val unrendered = manifest.chapters.size - rendered
        return when (manifest.renderState) {
            "none" ->
                if (rendered > 0) {
                    listOf(
                        "manifest.json: render_state 'none' but $rendered chapter(s) carry " +
                            "duration_ms (unrendered books omit audio and duration_ms everywhere)"
                    )
                } else {
                    emptyList()
                }
            "complete" ->
                if (unrendered > 0) {
                    listOf(
                        "manifest.json: render_state 'complete' but $unrendered chapter(s) omit " +
                            "duration_ms (rendered books carry audio and duration_ms everywhere)"
                    )
                } else {
                    emptyList()
                }
            else ->
                if (rendered > 0 && unrendered > 0) {
                    emptyList()
                } else {
                    listOf(
                        "manifest.json: render_state 'partial' needs at least one rendered and " +
                            "one unrendered chapter " +
                            "(got $rendered rendered, $unrendered unrendered)"
                    )
                }
        }
    }

    /**
     * RN1: manifest `audio.format` must match at least one rendered
     * chapter (spec 2.0 part 2 section 2/7). Mixed MP3/M4A books are
     * legal, so the manifest value cannot be normative per chapter;
     * per-chapter extension is authoritative. `none` books carry no
     * `audio` object, so the rule is vacuous for them. Pure.
     */
    internal fun validateAudioFormatAgreement(manifest: Manifest): List<String> {
        val format = manifest.audio?.format ?: return emptyList()
        if (format !in AUDIO_FORMATS) return emptyList()
        val rendered = manifest.chapters.filter { it.durationMs != null }
        if (rendered.isEmpty()) return emptyList()
        val chapterFormats = rendered.mapNotNull { audioFormatForExtension(it.audio) }.toSet()
        if (chapterFormats.isNotEmpty() && format !in chapterFormats) {
            val have = chapterFormats.sorted().joinToString(", ")
            return listOf(
                "manifest.json: audio.format \"$format\" matches no rendered chapter " +
                    "(chapters are $have; per-chapter format comes from the audio file extension)"
            )
        }
        return emptyList()
    }

    /**
     * RN1: manifest `gain_db` and `encoder_offset_ms` presence and shape
     * (spec 2.0 part 2 section 3/7). Both are 2.0-only; gain is one finite
     * number per role, the offset is an integer literal. Raw JSON
     * elements (see [Manifest]) so malformed values become named rule
     * errors, never whole-manifest parse failures. Pure.
     */
    internal fun validateDeviceFields(manifest: Manifest): List<String> {
        val errors = mutableListOf<String>()
        val gain = manifest.gainDb
        if (gain != null) {
            if (manifest.specVersion in V1_VERSIONS) {
                errors.add("manifest.json: gain_db is 2.0-only (absent in 1.x bundles)")
            } else if (manifest.specVersion == "2.0") {
                val obj = gain as? JsonObject
                if (obj == null) {
                    errors.add("manifest.json: gain_db must be an object (role to decibels)")
                } else if (obj.isEmpty()) {
                    errors.add("manifest.json: gain_db present but empty (need one number per role)")
                } else {
                    for ((role, value) in obj) {
                        if (role !in GAIN_ROLES) {
                            errors.add(
                                "manifest.json: gain_db has unknown role \"$role\" " +
                                    "(need narrator and/or dialogue)"
                            )
                        } else {
                            val number = (value as? JsonPrimitive)
                                ?.takeIf { !it.isString }
                                ?.doubleOrNull
                            if (number == null || !number.isFinite()) {
                                errors.add(
                                    "manifest.json: gain_db.$role must be a finite number " +
                                        "(got ${value.toString().take(24)})"
                                )
                            }
                        }
                    }
                }
            }
        }
        val offset = manifest.encoderOffsetMs
        if (offset != null) {
            if (manifest.specVersion in V1_VERSIONS) {
                errors.add("manifest.json: encoder_offset_ms is 2.0-only (absent in 1.x bundles)")
            } else if (manifest.specVersion == "2.0" && !isIntegerLiteral(offset)) {
                errors.add(
                    "manifest.json: encoder_offset_ms must be an integer " +
                        "(got ${offset.toString().take(24)})"
                )
            }
        }
        return errors
    }

    /**
     * RN1: one chapter entry's `render_fingerprint` (spec 2.0 part 2
     * section 3/7).
     *
     * VS2 (D-114): 2.0-only, rendered chapters only, one or both reserved
     * roles pinned with non-blank voice ids and positive speeds, versions
     * non-empty. Voices plus speeds key sets must match exactly (both
     * both-role or both the same single role); unknown roles fail. Old
     * both-role fingerprints keep passing; clean single-role fingerprints
     * now pass too. Each error names the file and the rule. Pure.
     */
    internal fun validateChapterFingerprint(manifest: Manifest, chapter: ChapterInfo): List<String> {
        val fingerprint = chapter.renderFingerprint ?: return emptyList()
        val label = "manifest.json: chapter ${chapter.index} render_fingerprint"
        if (manifest.specVersion in V1_VERSIONS) {
            return listOf("$label is 2.0-only (absent in 1.x bundles)")
        }
        if (manifest.specVersion != "2.0") return emptyList()
        if (chapter.durationMs == null) {
            return listOf(
                "$label carries render_fingerprint without duration_ms " +
                    "(fingerprints describe rendered chapters only)"
            )
        }
        val obj = fingerprint as? JsonObject
            ?: return listOf("$label must be an object (engine, voices, speeds, engine_versions)")
        val errors = mutableListOf<String>()
        val engine = (obj["engine"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (engine.isNullOrBlank()) {
            errors.add("$label.engine must be a non-empty string (namespaced engine id)")
        }
        val voices = obj["voices"] as? JsonObject
        if (voices == null) {
            errors.add("$label.voices must be an object (one voice id per role)")
        } else {
            val voiceRoles = voices.keys.toSet()
            if (voiceRoles.isEmpty() || voiceRoles.any { it !in RESERVED_SPEAKERS }) {
                for (role in voices.keys) {
                    if (role !in RESERVED_SPEAKERS) {
                        errors.add("$label.voices has unknown role \"$role\" (need narrator and dialogue)")
                    }
                }
                if (voiceRoles.isEmpty()) {
                    errors.add(
                        "$label.voices present but empty (need narrator and/or dialogue)"
                    )
                }
            } else {
                for (role in voiceRoles) {
                    val voice = (voices[role] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (voice.isNullOrBlank()) {
                        errors.add(
                            "$label.voices missing or blank voice for role '$role' " +
                                "(need narrator and/or dialogue)"
                        )
                    }
                }
            }
        }
        val speeds = obj["speeds"] as? JsonObject
        if (speeds == null) {
            errors.add("$label.speeds must be an object (one speed per role)")
        } else {
            val speedRoles = speeds.keys.toSet()
            if (speedRoles.isEmpty() || speedRoles.any { it !in RESERVED_SPEAKERS }) {
                for (role in speeds.keys) {
                    if (role !in RESERVED_SPEAKERS) {
                        errors.add("$label.speeds has unknown role \"$role\" (need narrator and dialogue)")
                    }
                }
                if (speedRoles.isEmpty()) {
                    errors.add(
                        "$label.speeds present but empty (need narrator and/or dialogue)"
                    )
                }
            } else {
                for (role in speedRoles) {
                    val speed = (speeds[role] as? JsonPrimitive)
                        ?.takeIf { !it.isString }
                        ?.doubleOrNull
                    if (speed == null || !speed.isFinite() || speed <= 0.0) {
                        errors.add(
                            "$label.speeds missing or invalid speed for role '$role' " +
                                "(need a number above 0)"
                        )
                    }
                }
            }
            val voiceRoles = (voices as? JsonObject)?.keys?.toSet()
            if (voiceRoles != null && voiceRoles.all { it in RESERVED_SPEAKERS } &&
                speedRoles.all { it in RESERVED_SPEAKERS } &&
                voiceRoles.isNotEmpty() && speedRoles.isNotEmpty() &&
                voiceRoles != speedRoles
            ) {
                val missing = (RESERVED_SPEAKERS - voiceRoles) + (RESERVED_SPEAKERS - speedRoles)
                for (role in missing.sorted()) {
                    if (role in RESERVED_SPEAKERS - voiceRoles) {
                        errors.add(
                            "$label.voices missing or blank voice for role '$role' " +
                                "(voices plus speeds must cover the same roles)"
                        )
                    } else {
                        errors.add(
                            "$label.speeds missing or invalid speed for role '$role' " +
                                "(voices plus speeds must cover the same roles)"
                        )
                    }
                }
            }
        }
        val versions = obj["engine_versions"] as? JsonObject
        if (versions == null || versions.isEmpty()) {
            errors.add("$label.engine_versions present but empty (need one version string per engine used)")
        } else {
            for ((key, value) in versions) {
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (key.isBlank() || text.isNullOrBlank()) {
                    errors.add(
                        "$label.engine_versions has a blank engine or version " +
                            "(need non-empty strings)"
                    )
                    break
                }
            }
        }
        return errors
    }

    /**
     * True for a JSON integer literal (`12`, `-3`); false for strings,
     * booleans, floats and null. The regex (not `longOrNull` alone) pins
     * the literal shape independent of parser-version quirks.
     */
    internal fun isIntegerLiteral(element: JsonElement): Boolean {
        if (element !is JsonPrimitive || element.isString) return false
        if (element.booleanOrNull != null) return false
        if (!element.content.matches(Regex("-?\\d+"))) return false
        return element.longOrNull != null
    }

    /**
     * IN1: cross-checks between a parsed chapter and its manifest entry
     * (spec 2.0 section 7). 1.x chapters mix freely among 1.0/1.1/1.2;
     * 2.0 chapters belong to 2.0 manifests and vice versa. Duration
     * presence must match the entry, and 2.0 sentences use only the
     * reserved speakers (which `voices` must contain). Each returned
     * error already names the chapter file and the rule. Pure.
     */
    internal fun validateAgainstManifest(
        manifest: Manifest,
        entry: ChapterInfo,
        chapter: ChapterText
    ): List<String> {
        val problems = mutableListOf<String>()
        val path = entry.text
        if (manifest.specVersion == "2.0" && chapter.specVersion != "2.0") {
            problems.add(
                "$path: spec_version \"${chapter.specVersion}\" does not match " +
                    "manifest spec_version '2.0' (2.0 chapters belong to 2.0 books)"
            )
        } else if (manifest.specVersion in V1_VERSIONS && chapter.specVersion == "2.0") {
            problems.add(
                "$path: spec_version '2.0' does not match manifest " +
                    "spec_version '${manifest.specVersion}' (1.x books hold 1.x chapters)"
            )
        }
        if ((chapter.durationMs == null) != (entry.durationMs == null)) {
            if (chapter.durationMs == null) {
                problems.add(
                    "$path: chapter file omits duration_ms but the manifest entry has " +
                        "${entry.durationMs} (both present or both absent)"
                )
            } else {
                problems.add(
                    "$path: chapter file has duration_ms ${chapter.durationMs} but the " +
                        "manifest entry omits it (both present or both absent)"
                )
            }
        }
        if (manifest.specVersion == "2.0") {
            for (speaker in chapter.sentencesInOrder().map { it.speaker }) {
                if (speaker !in RESERVED_SPEAKERS) {
                    problems.add(
                        "$path: sentence speaker \"$speaker\" " +
                            "(2.0 books allow only narrator and dialogue)"
                    )
                    break
                }
            }
            for (reserved in RESERVED_SPEAKERS) {
                if (!manifest.voices.containsKey(reserved)) {
                    problems.add(
                        "$path: voices missing reserved speaker '$reserved' " +
                            "(2.0 books need both narrator and dialogue)"
                    )
                }
            }
        }
        return problems
    }

    private fun joinPath(dir: String, rel: String): String =
        dir.trimEnd('/') + '/' + rel.trimStart('/')

    /**
     * Parses `manifest.json` in [bundleDir] then validates it.
     * Returns the list of errors (parse failure counts as a single error).
     */
    fun validateBundle(bundleDir: File): List<String> {
        val parsed = BundleParser.parse(bundleDir)
        if (parsed.isFailure) {
            return listOf(parsed.exceptionOrNull()?.message ?: "manifest.json: parse failed")
        }
        return validate(bundleDir, parsed.getOrThrow())
    }
}
