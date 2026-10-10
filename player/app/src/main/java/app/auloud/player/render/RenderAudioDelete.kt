package app.auloud.player.render

import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.ChapterTextLoader
import java.io.IOException
import kotlinx.serialization.json.JsonObject

/**
 * RN9: delete rendered audio for a chapter or a whole book (Slice 10).
 *
 * Deleting returns chapters to unrendered cleanly through RN7-compatible
 * state: the timed chapter JSON is rewritten untimed (same strip as the
 * recovery rollback) and the manifest entry is stripped back
 * (audio/duration/fingerprint removed, `render_state` recomputed by
 * [RenderFinalize.buildUpdatedManifestJson]), so validators and the
 * reader see exactly an unrendered chapter. The job state file is
 * deleted best-effort alongside: a stored plan may list the deleted
 * chapter as completed, and keeping it would skip the chapter on the
 * next render instead of re-rendering it.
 *
 * Crash order mirrors the reasoning in recovery: manifest plus JSON
 * first, audio files last. A kill between the two leaves orphan audio
 * (swept by [RenderRecovery]) or a downgraded entry (repaired by
 * [RenderRecovery]), never a manifest entry without its files.
 *
 * All file IO runs through [RenderFileIo] (the RN3 seam), so this is
 * JVM-testable with a fake. No service, notification, MediaCodec or UI
 * code. API 24 safe: pure Kotlin plus kotlinx.serialization.
 */
object RenderAudioDelete {

    /**
     * Deletes the rendered audio of one 1-based [chapterNumber].
     *
     * An already-unrendered chapter is a success no-op (stray audio at
     * the entry rel is still removed). Missing manifest or chapter entry
     * fails shaped naming the file and the rule.
     */
    fun deleteChapterAudio(
        bundleDir: String,
        chapterNumber: Int,
        io: RenderFileIo,
        sleeper: (Long) -> Unit = Thread::sleep
    ): Result<Unit> {
        require(chapterNumber >= 1) { "chapterNumber must be 1-based, got $chapterNumber." }
        val manifestPath = join(bundleDir, RenderFinalize.MANIFEST_FILE)
        val rawManifest = try {
            io.readText(manifestPath)
        } catch (e: Exception) {
            return Result.failure(
                IOException("$manifestPath: cannot read manifest (${e.message})", e)
            )
        }
        val manifest = BundleParser.parseText(rawManifest).getOrElse {
            return Result.failure(
                IOException("$manifestPath: manifest unreadable (${it.message})", it)
            )
        }
        val entry = manifest.chapters.firstOrNull { it.index == chapterNumber }
            ?: return Result.failure(
                IOException("$manifestPath: no chapter entry with index $chapterNumber")
            )
        val textRel = entry.text.takeIf { it.isNotBlank() }
            ?: return Result.failure(
                IOException("$manifestPath: chapter $chapterNumber has no text path")
            )
        val audioRel = entry.audio.takeIf { it.isNotBlank() }
            ?: RenderFinalize.deviceAudioRel(chapterNumber)
        if (entry.durationMs == null) {
            io.deleteIfExists(join(bundleDir, audioRel))
            return Result.success(Unit)
        }
        val textPath = join(bundleDir, textRel)
        val rawChapter = try {
            io.readText(textPath)
        } catch (e: Exception) {
            return Result.failure(
                IOException("$textPath: cannot read chapter text (${e.message})", e)
            )
        }
        val timed = ChapterTextLoader.parse(textRel, rawChapter).getOrNull()
            ?: return Result.failure(
                IOException("$textPath: chapter text unreadable (re-import the book)")
            )
        if (timed.specVersion != "2.0") {
            return Result.failure(
                IOException(
                    "$textPath: spec_version '${timed.specVersion}' is not '2.0' " +
                        "(device audio deletes 2.0 chapters only)"
                )
            )
        }
        if (timed.durationMs != null) {
            val stripped = RenderRecovery.stripTimings(textRel, timed)
                ?: return Result.failure(
                    IOException("$textPath: cannot strip timings (re-import the book)")
                )
            RenderFinalize.atomicWriteText(textPath, stripped, io, sleeper).getOrElse {
                return Result.failure(it)
            }
        }
        val updatedManifest = RenderFinalize.buildUpdatedManifestJson(
            rawManifestJson = rawManifest,
            chapterNumber = chapterNumber,
            audio = null,
            durationMs = null,
            fingerprint = null,
            gainDb = null,
            encoderOffsetMs = null
        ).getOrElse { return Result.failure(it) }
        RenderFinalize.atomicWriteText(manifestPath, updatedManifest, io, sleeper).getOrElse {
            return Result.failure(it)
        }
        io.deleteIfExists(join(bundleDir, audioRel))
        io.deleteIfExists(RenderStateStore.statePath(bundleDir))
        return Result.success(Unit)
    }

    /**
     * Deletes the rendered audio of every rendered chapter in the book.
     *
     * Each chapter commits through [deleteChapterAudio] (manifest plus
     * JSON first, audio last), then the book-level `gain_db` and
     * `encoder_offset_ms` references are cleared: they described the
     * deleted audio, and keeping them would bias the next render's first
     * chapter reference. Per-chapter deletes keep them (other rendered
     * chapters still use the reference).
     */
    fun deleteBookAudio(
        bundleDir: String,
        io: RenderFileIo,
        sleeper: (Long) -> Unit = Thread::sleep
    ): Result<Unit> {
        val manifestPath = join(bundleDir, RenderFinalize.MANIFEST_FILE)
        val rawManifest = try {
            io.readText(manifestPath)
        } catch (e: Exception) {
            return Result.failure(
                IOException("$manifestPath: cannot read manifest (${e.message})", e)
            )
        }
        val manifest = BundleParser.parseText(rawManifest).getOrElse {
            return Result.failure(
                IOException("$manifestPath: manifest unreadable (${it.message})", it)
            )
        }
        for (entry in manifest.chapters.sortedBy { it.index }) {
            if (entry.durationMs == null) continue
            deleteChapterAudio(bundleDir, entry.index, io, sleeper).getOrElse {
                return Result.failure(it)
            }
        }
        clearBookRenderFields(bundleDir, io, sleeper).getOrElse {
            return Result.failure(it)
        }
        io.deleteIfExists(RenderStateStore.statePath(bundleDir))
        return Result.success(Unit)
    }

    /**
     * Removes the book-level render references (`gain_db`,
     * `encoder_offset_ms`) after a whole-book delete. Unknown keys are
     * preserved; the result re-parses before commit.
     */
    private fun clearBookRenderFields(
        bundleDir: String,
        io: RenderFileIo,
        sleeper: (Long) -> Unit
    ): Result<Unit> {
        val manifestPath = join(bundleDir, RenderFinalize.MANIFEST_FILE)
        val rawManifest = try {
            io.readText(manifestPath)
        } catch (e: Exception) {
            return Result.failure(
                IOException("$manifestPath: cannot read manifest (${e.message})", e)
            )
        }
        val root = try {
            BundleParser.json.parseToJsonElement(rawManifest) as? JsonObject
                ?: return Result.failure(
                    IOException("$manifestPath: manifest must be an object")
                )
        } catch (e: Exception) {
            return Result.failure(
                IOException("$manifestPath: manifest unreadable (${e.message})", e)
            )
        }
        val edited = root.toMutableMap()
        edited.remove("gain_db")
        edited.remove("encoder_offset_ms")
        val updated = JsonObject(edited)
        val text = try {
            BundleParser.json.encodeToString(JsonObject.serializer(), updated)
        } catch (e: Exception) {
            return Result.failure(
                IOException("$manifestPath: cannot rewrite manifest (${e.message})", e)
            )
        }
        if (BundleParser.parseText(text).isFailure) {
            return Result.failure(
                IOException("$manifestPath: rewritten manifest failed self-check")
            )
        }
        return RenderFinalize.atomicWriteText(manifestPath, text, io, sleeper)
    }

    private fun join(dir: String, rel: String): String =
        dir.trimEnd('/') + '/' + rel.trimStart('/')
}
