package app.auloud.player.render

import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.ChapterTextLoader

/**
 * VS3: re-render recovery additions (D-116 interrupted swap, D-109 rules).
 *
 * Interrupted-swap states (all repaired to validator-clean, each
 * reported, never silent):
 * - audio done, JSON missing: manifest still points at old, new audio
 *   unreferenced (orphan, swept); old audio plus old JSON plus old
 *   manifest stay consistent.
 * - JSON done, manifest stale: JSON carries new timings while the
 *   manifest still points at old. Recovery forward-completes the
 *   manifest to the versioned audio when exactly one versioned
 *   candidate for the chapter exists on disk and the JSON duration
 *   differs from the manifest duration (the retimed signal), with the
 *   fingerprint removed (absent means unknown, never invented, so the
 *   chapter reads STALE and the next run re-verifies). A retimed JSON
 *   with an identical duration is indistinguishable from the old JSON
 *   and follows the audio-done orphan path (swept, retried next run);
 *   anything else ambiguous (many candidates, JSON untimed or
 *   unreadable, candidate missing) preserves the versioned files for
 *   retry instead of deleting them, so no new audio is lost.
 * - versioned new referenced but JSON missing, untimed or corrupt:
 *   refuse and record like D-109 (the existing
 *   [RenderRecovery.RepairKind.ManifestDowngraded] path strips the
 *   entry back to unrendered; no fingerprint, gain or offset is
 *   invented, absent means unknown).
 * - manifest done with both files present: the unreferenced old file
 *   is deleted (deferred while the player has it loaded).
 *
 * Deferred window: a delete that fails because the player still has
 * the file open is reported in [RerenderSweep.deferred] and retried on
 * the next recovery or render run. When no further render runs, the
 * deferred file stays on disk until then; the player release itself
 * does not trigger a sweep.
 *
 * Call only when no render is running (same contract as
 * [RenderRecovery]: the RN8 single runner owns it and calls this at
 * startup before resuming anything). Audio listing arrives via
 * [listAudioFiles] so JVM tests inject fakes and production lists the
 * real `audio/` dir; when null, only the base [RenderRecovery] pass
 * runs (backward compatible with existing callers and tests).
 *
 * Pure apart from the injected seams; API 24 safe, no new dependency.
 */
object RerenderRecovery {

    /** What one re-render recovery run swept beyond the base pass. */
    data class RerenderSweep(
        /** Unreferenced versioned/old audio plus audio temps deleted. */
        val sweptAudio: List<String>,
        /** Old rels that still exist after delete (player has them open). */
        val deferred: List<String>,
        /** Chapters forward-completed from JSON-done-manifest-stale. */
        val forwardCompleted: List<Int> = emptyList(),
        /** Versioned files preserved for retry (ambiguous, never deleted). */
        val preservedForRetry: List<String> = emptyList()
    )

    /**
     * Recovers one book plus the re-render swap residue.
     *
     * Runs the base [RenderRecovery.recoverBook] first (temps from the
     * manifest, spool sweep, four chapter repairs, job interrupted
     * path), then forward-completes any JSON-done-manifest-stale
     * chapter (see file KDoc), then sweeps versioned orphans via
     * [RerenderSwap] excluding preserved files. Returns the base report
     * plus the extra sweep.
     */
    fun recoverBookWithRerender(
        bundleDir: String,
        io: RenderFileIo,
        spoolDir: String? = null,
        spoolIo: SpoolIo? = null,
        listAudioFiles: (() -> List<String>)? = null,
        clock: () -> Long = System::currentTimeMillis,
        sleeper: (Long) -> Unit = Thread::sleep
    ): Result<Pair<RenderRecovery.RecoveryReport, RerenderSweep>> {
        val base = RenderRecovery.recoverBook(bundleDir, io, spoolDir, spoolIo, clock, sleeper)
            .getOrElse { return Result.failure(it) }
        if (listAudioFiles == null) {
            return Result.success(base to RerenderSweep(emptyList(), emptyList()))
        }
        val manifestRaw = try {
            io.readText(join(bundleDir, RenderFinalize.MANIFEST_FILE))
        } catch (_: Exception) {
            return Result.success(base to RerenderSweep(emptyList(), emptyList()))
        }
        val manifest = BundleParser.parseText(manifestRaw).getOrNull()
            ?: return Result.success(base to RerenderSweep(emptyList(), emptyList()))
        if (manifest.specVersion != "2.0") {
            return Result.success(base to RerenderSweep(emptyList(), emptyList()))
        }
        val existing = try {
            listAudioFiles()
        } catch (_: Exception) {
            emptyList()
        }
        // Re-read the manifest after the base pass: repairs may have
        // rewritten it (completed forward or downgraded), and the
        // forward-complete plus sweep must compare against the repaired
        // references, not the stale pre-pass text.
        val freshRaw = try {
            io.readText(join(bundleDir, RenderFinalize.MANIFEST_FILE))
        } catch (_: Exception) {
            manifestRaw
        }
        val fresh = BundleParser.parseText(freshRaw).getOrNull() ?: manifest
        // JSON-done-manifest-stale forward-complete: manifest points at
        // old while the JSON already carries new timings. The retimed
        // signal is a JSON duration that differs from the manifest
        // duration; anything ambiguous preserves the versioned files.
        var manifestTextForSweep = freshRaw
        val forwardCompleted = ArrayList<Int>()
        val preserved = ArrayList<String>()
        val forwardCandidates = ArrayList<String>()
        for (entry in fresh.chapters.sortedBy { it.index }) {
            val versioned = existing.filter { path ->
                val rel = toRel(bundleDir, path) ?: return@filter false
                RerenderSwap.isVersionedAudioRel(rel) &&
                    RerenderSwap.chapterNumberFromAudioRel(rel) == entry.index
            }
            if (versioned.isEmpty()) continue
            val durationMs = entry.durationMs
            if (durationMs == null) {
                preserved.addAll(versioned)
                continue
            }
            val audioRel = entry.audio.takeIf { it.isNotBlank() }
            if (audioRel == null) {
                preserved.addAll(versioned)
                continue
            }
            if (RerenderSwap.isVersionedAudioRel(audioRel)) continue
            val textRel = entry.text.takeIf { it.isNotBlank() }
            if (textRel == null) {
                preserved.addAll(versioned)
                continue
            }
            val timed = try {
                val raw = io.readText(join(bundleDir, textRel))
                ChapterTextLoader.parse(textRel, raw).getOrNull()
            } catch (_: Exception) {
                null
            }
            val jsonDuration = timed?.durationMs
            if (jsonDuration == null) {
                preserved.addAll(versioned)
                continue
            }
            if (versioned.size != 1) {
                preserved.addAll(versioned)
                continue
            }
            if (jsonDuration == durationMs) {
                continue
            }
            val candidateAbs = versioned[0]
            val candidateRel = toRel(bundleDir, candidateAbs)
            if (candidateRel == null) {
                preserved.addAll(versioned)
                continue
            }
            val present = try {
                io.exists(candidateAbs)
            } catch (_: Exception) {
                false
            }
            if (!present) {
                preserved.addAll(versioned)
                continue
            }
            val updated = RenderFinalize.buildUpdatedManifestJson(
                rawManifestJson = manifestTextForSweep,
                chapterNumber = entry.index,
                audio = candidateRel,
                durationMs = jsonDuration,
                fingerprint = null,
                gainDb = null,
                encoderOffsetMs = null
            ).getOrNull()
            if (updated == null) {
                preserved.addAll(versioned)
                continue
            }
            manifestTextForSweep = updated
            forwardCompleted.add(entry.index)
            forwardCandidates.add(candidateAbs)
        }
        if (forwardCompleted.isNotEmpty()) {
            val wrote = RenderFinalize.atomicWriteText(
                join(bundleDir, RenderFinalize.MANIFEST_FILE),
                manifestTextForSweep,
                io,
                sleeper
            )
            if (wrote.isFailure) {
                preserved.addAll(forwardCandidates)
                forwardCompleted.clear()
                manifestTextForSweep = freshRaw
            }
        }
        val repaired = BundleParser.parseText(manifestTextForSweep).getOrNull() ?: fresh
        val freshReferenced = repaired.chapters.mapNotNullTo(LinkedHashSet()) { entry ->
            entry.audio.takeIf { it.isNotBlank() }
        }
        val preservedSet = preserved.toSet()
        val candidates = RerenderSwap.pickSwapOrphans(bundleDir, freshReferenced, existing)
            .filter { it !in preservedSet }
        val swept = ArrayList<String>()
        val deferred = ArrayList<String>()
        for (path in candidates) {
            // Never delete a file the repaired manifest references
            // (the base pass may have just completed it forward).
            if (path in freshReferenced.map { join(bundleDir, it) }) continue
            try {
                io.deleteIfExists(path)
            } catch (_: Exception) {
            }
            var gone = true
            try {
                gone = !io.exists(path)
            } catch (_: Exception) {
            }
            if (gone) {
                swept.add(path)
            } else {
                deferred.add(path)
            }
            Unit
        }
        return Result.success(
            base to RerenderSweep(
                swept.sorted(),
                deferred.sorted(),
                forwardCompleted.sorted(),
                preserved.sorted()
            )
        )
    }

    private fun join(dir: String, rel: String): String =
        dir.trimEnd('/') + '/' + rel.trimStart('/')

    private fun toRel(bundleDir: String, absPath: String): String? {
        val prefix = bundleDir.trimEnd('/') + '/'
        if (!absPath.startsWith(prefix)) return null
        return absPath.removePrefix(prefix).takeIf { it.isNotBlank() }
    }
}
