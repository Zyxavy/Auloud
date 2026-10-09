package app.auloud.player.render

import app.auloud.player.bundle.BundleParser

/**
 * VS3: re-render recovery additions (D-116 interrupted swap, D-109 rules).
 *
 * Interrupted-swap states (all repaired to validator-clean, each
 * reported, never silent):
 * - old audio plus manifest pointing at old: fine. The versioned new
 *   audio is unreferenced and is deleted; old JSON plus old manifest
 *   stay consistent.
 * - versioned new audio unreferenced (plus any `*.tmp` in audio):
 *   deleted best effort (deferred while open is reported as still
 *   present; the next recovery finishes it).
 * - versioned new referenced but JSON missing, untimed or corrupt:
 *   refuse and record like D-109 (the existing
 *   [RenderRecovery.RepairKind.ManifestDowngraded] path strips the
 *   entry back to unrendered; no fingerprint, gain or offset is
 *   invented, absent means unknown).
 * - manifest done with both files present: the unreferenced old file
 *   is deleted (deferred while the player has it loaded).
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
        val deferred: List<String>
    )

    /**
     * Recovers one book plus the re-render swap residue.
     *
     * Runs the base [RenderRecovery.recoverBook] first (temps from the
     * manifest, spool sweep, four chapter repairs, job interrupted
     * path), then sweeps versioned orphans via [RerenderSwap].
     * Returns the base report plus the extra sweep (the manifest is
     * never rewritten by the extra sweep itself; downgrades from the
     * base pass already cover referenced-but-broken chapters).
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
        // rewritten it (completed forward or downgraded), and the sweep
        // must compare against the repaired references, not the stale
        // pre-pass text.
        val freshRaw = try {
            io.readText(join(bundleDir, RenderFinalize.MANIFEST_FILE))
        } catch (_: Exception) {
            manifestRaw
        }
        val fresh = BundleParser.parseText(freshRaw).getOrNull() ?: manifest
        val freshReferenced = fresh.chapters.mapNotNullTo(LinkedHashSet()) { entry ->
            entry.audio.takeIf { it.isNotBlank() }
        }
        val candidates = RerenderSwap.pickSwapOrphans(bundleDir, freshReferenced, existing)
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
        return Result.success(base to RerenderSweep(swept.sorted(), deferred.sorted()))
    }

    private fun join(dir: String, rel: String): String =
        dir.trimEnd('/') + '/' + rel.trimStart('/')
}
