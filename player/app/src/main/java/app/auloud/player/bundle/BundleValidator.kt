package app.auloud.player.bundle

import java.io.File

/**
 * WP2: light import-time checks over an already-parsed [Manifest].
 *
 * Checks required fields are present (non-blank), each chapter's MP3 file
 * exists under the bundle directory, and `duration_ms` is positive.
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
 * run the SAME rules: pass e.g. `storage::exists`. The default keeps the
 * direct `File` check used by `validateBundle` and the WP2 tests.
 */
object BundleValidator {

    fun validate(bundleDir: File, manifest: Manifest): List<String> =
        validate(bundleDir.path, manifest)

    fun validate(
        bundleDirPath: String,
        manifest: Manifest,
        exists: (String) -> Boolean = { File(it).isFile }
    ): List<String> {
        val errors = mutableListOf<String>()
        if (manifest.specVersion.isBlank()) {
            errors.add("manifest.json: missing required field spec_version")
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
