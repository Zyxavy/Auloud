package app.auloud.player.bundle

import java.io.File

/**
 * WP2: light import-time checks over an already-parsed [Manifest].
 *
 * Checks required fields are present (non-blank), each chapter's MP3 file
 * exists under the bundle directory, and `duration_ms` is positive.
 *
 * CP4: optional text-file check. When [readText] is provided, every chapter
 * with a non-blank `text` field is also checked: a missing file (via
 * [exists]) or unparsable JSON (via the same pure parser [ChapterTextLoader]
 * uses, no duplicated JSON logic) becomes a chapter-scoped error naming the
 * file and the rule, e.g.
 * `manifest.json: chapter 2 text file invalid text/ch002.json (<reason>)`.
 * PDF page-sync chapters (`pages` instead of `blocks`) are NOT errors —
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
        } else if (manifest.specVersion != "1.0" && manifest.specVersion != "1.1") {
            errors.add("manifest.json: spec_version \"${manifest.specVersion}\" must be \"1.0\" or \"1.1\"")
        }
        if (manifest.id.isBlank()) {
            errors.add("manifest.json: missing required field id")
        }
        if (manifest.title.isBlank()) {
            errors.add("manifest.json: missing required field title")
        }
        if (manifest.type.isBlank()) {
            errors.add("manifest.json: missing required field type")
        }
        if (manifest.chapters.isEmpty()) {
            errors.add("manifest.json: no chapters listed")
        }
        for (chapter in manifest.chapters) {
            val label = "chapter ${chapter.index}"
            if (chapter.title.isBlank()) {
                errors.add("manifest.json: $label missing required field title")
            }
            if (chapter.audio.isBlank()) {
                errors.add("manifest.json: $label missing required field audio")
            }
            if (chapter.text.isBlank()) {
                errors.add("manifest.json: $label missing required field text")
            }
            if (chapter.durationMs <= 0) {
                errors.add(
                    "manifest.json: $label has non-positive duration_ms ${chapter.durationMs}"
                )
            }
            if (chapter.audio.isNotBlank()) {
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
                        }
                    }
                }
            }
        }
        return errors
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
