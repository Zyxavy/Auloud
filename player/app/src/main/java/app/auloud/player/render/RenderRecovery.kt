package app.auloud.player.render

import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.ChapterText
import app.auloud.player.bundle.ChapterTextLoader
import app.auloud.player.ingest.StrayTempSweep
import kotlinx.serialization.json.Json

/**
 * RN7: startup recovery for device-rendered books (Slice 10, D-096).
 *
 * A kill can land between any two finalize steps, so startup sweeps
 * orphan temp files and repairs half-finished chapters back to a
 * consistent (validator-clean) state, then resumes job state through the
 * RN3 interrupted path. All file IO runs through [RenderFileIo] (text
 * plus rename plus delete, the RN3 seam); spool IO through [SpoolIo].
 * Call only when no render is running (RN8 owns the single runner and
 * calls this at startup before resuming anything).
 *
 * Temp sweep reuses the IN7 sweeper ([StrayTempSweep.renderTempPaths]
 * plus [StrayTempSweep.sweepPaths]): no parallel sweeper, same
 * best-effort never-throw contract. Candidates are computed from the
 * manifest, so unknown strays are impossible and live files are never
 * touched. When the manifest itself is unreadable there are no entries
 * to derive candidates from, so [sweepOrphanTemps] lists `audio/` and
 * `text/` for `*.tmp` residue instead (FP4).
 *
 * Half-finished chapter repairs (each reported, never silent):
 * - audio without JSON (`OrphanAudio`): the audio file has no timed JSON
 *   and no manifest entry, so re-render (RN8 resume from the intact
 *   spool) regenerates it; recovery deletes the orphan.
 * - JSON without manifest entry (`ManifestCompleted`): the timed JSON
 *   already carries the shifted timings, so recovery completes the
 *   manifest entry forward (audio rel verified on disk, duration from
 *   the JSON). No fingerprint, gain or offset is invented: those fields
 *   stay absent, which the spec reads as unknown, never up to date.
 * - timed JSON without any audio (`TimingsStripped`): nothing playable
 *   exists, so recovery rewrites the JSON untimed (timings plus duration
 *   plus pages dropped), restoring the last consistent unrendered state.
 * - manifest entry without files (`ManifestDowngraded`): the claim is
 *   bogus (audio missing, or JSON missing/untimed/corrupt), so recovery
 *   strips the entry back to unrendered and recomputes `render_state`.
 *
 * Consistent chapters (rendered with files, unrendered without) are left
 * alone. The manifest is rewritten at most once (temp-then-rename). Job
 * state resumes via [RenderStateStore.markInterruptedIfActive] (a RUNNING
 * or PAUSED job left by the dead process becomes INTERRUPTED, keeping
 * finished chapters plus the spool); a job-file failure is reported, not
 * fatal.
 *
 * No service, notification, MediaCodec or UI code. API 24 safe: pure
 * Kotlin plus kotlinx.serialization, no `java.time`, no Android types.
 */
object RenderRecovery {

    /** How one chapter was repaired (see the file KDoc for the rules). */
    enum class RepairKind {
        OrphanAudio,
        ManifestCompleted,
        TimingsStripped,
        ManifestDowngraded
    }

    /** One chapter repair. */
    data class ChapterRepair(
        val chapterNumber: Int,
        val kind: RepairKind,
        val detail: String
    )

    /** What one `recoverBook` run did. */
    data class RecoveryReport(
        val sweptTemps: List<String>,
        val sweptSpool: List<String>,
        val repairs: List<ChapterRepair>,
        val manifestRewritten: Boolean,
        /** Job after the interrupted-path resume (null when no state file). */
        val jobAfter: RenderJob?,
        /** Job-file failure message when the resume step failed (repairs still stand). */
        val jobError: String?,
        /** False when the manifest itself was unreadable (only fixed temps swept). */
        val manifestReadable: Boolean
    )

    private val prettyJson = Json { prettyPrint = true; explicitNulls = false }

    /**
     * Recovers one book folder end to end: temp sweep, spool sweep (when
     * [spoolDir] plus [spoolIo] are given), chapter audit plus repair,
     * then the job interrupted-path resume. Best-effort: per-chapter work
     * never throws (failures become a job-error-style report failure only
     * for the manifest write itself).
     */
    fun recoverBook(
        bundleDir: String,
        io: RenderFileIo,
        spoolDir: String? = null,
        spoolIo: SpoolIo? = null,
        clock: () -> Long = System::currentTimeMillis,
        sleeper: (Long) -> Unit = Thread::sleep
    ): Result<RecoveryReport> {
        val manifestPath = join(bundleDir, RenderFinalize.MANIFEST_FILE)
        val rawManifest = try {
            io.readText(manifestPath)
        } catch (e: Exception) {
            val fixedSwept = StrayTempSweep.sweepPaths(
                listOf(
                    "$manifestPath${StrayTempSweep.RENDER_TMP_SUFFIX}",
                    join(bundleDir, RenderStateStore.STATE_FILE) + RenderStateStore.TEMP_SUFFIX
                ),
                io::exists,
                io::deleteIfExists
            )
            val orphanSwept = sweepOrphanTemps(bundleDir, io)
            val job = RenderStateStore.markInterruptedIfActive(bundleDir, io, clock, sleeper)
            return Result.success(
                RecoveryReport(
                    sweptTemps = (fixedSwept + orphanSwept).sorted(),
                    sweptSpool = emptyList(),
                    repairs = emptyList(),
                    manifestRewritten = false,
                    jobAfter = job.getOrNull(),
                    jobError = job.exceptionOrNull()?.message,
                    manifestReadable = false
                )
            )
        }
        val manifest = BundleParser.parseText(rawManifest).getOrElse {
            // FP4: a torn manifest still leaves per-chapter temps behind
            // (kill mid-finalize), so sweep them by directory listing
            // before reporting the failure; without entries there are no
            // manifest-derived candidates.
            val fixedSwept = StrayTempSweep.sweepPaths(
                listOf(
                    "$manifestPath${StrayTempSweep.RENDER_TMP_SUFFIX}",
                    join(bundleDir, RenderStateStore.STATE_FILE) + RenderStateStore.TEMP_SUFFIX
                ),
                io::exists,
                io::deleteIfExists
            )
            val orphanSwept = sweepOrphanTemps(bundleDir, io)
            val swept = (fixedSwept + orphanSwept).sorted()
            RenderStateStore.markInterruptedIfActive(bundleDir, io, clock, sleeper)
            return Result.failure(
                java.io.IOException(
                    "$manifestPath: manifest unreadable (${it.message}; " +
                        "swept ${swept.size} temp(s))",
                    it
                )
            )
        }
        val sweptTemps = StrayTempSweep.sweepPaths(
            StrayTempSweep.renderTempPaths(bundleDir, manifest),
            io::exists,
            io::deleteIfExists
        )
        val sweptSpool = if (spoolDir != null && spoolIo != null) {
            sweepOrphanSpool(spoolDir, spoolIo)
        } else {
            emptyList()
        }
        val repairs = ArrayList<ChapterRepair>()
        var manifestText = rawManifest
        var manifestDirty = false
        for (entry in manifest.chapters.sortedBy { it.index }) {
            val repair = auditChapter(bundleDir, manifestText, entry.index, io)
            if (repair == null) continue
            when (repair.kind) {
                RepairKind.OrphanAudio,
                RepairKind.TimingsStripped -> {
                    // File-only repairs: no manifest change.
                    repairs.add(repair)
                }
                RepairKind.ManifestCompleted,
                RepairKind.ManifestDowngraded -> {
                    manifestText = repair.detail
                    manifestDirty = true
                    repairs.add(repair.copy(detail = repair.describe()))
                }
            }
        }
        var manifestRewritten = false
        if (manifestDirty) {
            RenderFinalize.atomicWriteText(manifestPath, manifestText, io, sleeper).getOrElse {
                return Result.failure(it)
            }
            manifestRewritten = true
        }
        val job = RenderStateStore.markInterruptedIfActive(bundleDir, io, clock, sleeper)
        return Result.success(
            RecoveryReport(
                sweptTemps = sweptTemps,
                sweptSpool = sweptSpool,
                repairs = repairs,
                manifestRewritten = manifestRewritten,
                jobAfter = job.getOrNull(),
                jobError = job.exceptionOrNull()?.message,
                manifestReadable = true
            )
        )
    }

    /**
     * Audits one chapter and performs file-only repairs immediately.
     *
     * Returns null when the chapter is consistent (or claims nothing
     * recoverable). Manifest-affecting repairs return the repair with the
     * NEW FULL manifest text in [ChapterRepair.detail] (replaced with a
     * human description by [recoverBook] before reporting).
     */
    internal fun auditChapter(
        bundleDir: String,
        manifestText: String,
        chapterNumber: Int,
        io: RenderFileIo
    ): ChapterRepair? {
        val manifest = BundleParser.parseText(manifestText).getOrNull() ?: return null
        val entry = manifest.chapters.firstOrNull { it.index == chapterNumber } ?: return null
        val manifestRendered = entry.durationMs != null
        val audioRel = entry.audio.takeIf { it.isNotBlank() }
            ?: RenderFinalize.deviceAudioRel(chapterNumber)
        val audioPresent = io.exists(join(bundleDir, audioRel))
        val textRel = entry.text.takeIf { it.isNotBlank() } ?: return null
        val textPath = join(bundleDir, textRel)
        val rawJson = try {
            io.readText(textPath)
        } catch (_: Exception) {
            null
        }
        val timed = rawJson?.let { ChapterTextLoader.parse(textRel, it).getOrNull() }
        val jsonTimed = timed != null && timed.durationMs != null
        if (!manifestRendered) {
            if (timed != null && timed.durationMs != null) {
                if (audioPresent) {
                    val updated = RenderFinalize.buildUpdatedManifestJson(
                        rawManifestJson = manifestText,
                        chapterNumber = chapterNumber,
                        audio = audioRel,
                        durationMs = timed.durationMs,
                        fingerprint = null,
                        gainDb = null,
                        encoderOffsetMs = null
                    ).getOrNull() ?: return null
                    return ChapterRepair(
                        chapterNumber = chapterNumber,
                        kind = RepairKind.ManifestCompleted,
                        detail = updated
                    )
                }
                val stripped = stripTimings(textRel, timed) ?: return null
                RenderFinalize.atomicWriteText(textPath, stripped, io).getOrNull()
                    ?: return null
                return ChapterRepair(
                    chapterNumber = chapterNumber,
                    kind = RepairKind.TimingsStripped,
                    detail = "$textRel: timings stripped (no audio on disk)"
                )
            }
            if (audioPresent) {
                io.deleteIfExists(join(bundleDir, audioRel))
                return ChapterRepair(
                    chapterNumber = chapterNumber,
                    kind = RepairKind.OrphanAudio,
                    detail = "$audioRel: orphan audio deleted (no timed JSON, no manifest entry)"
                )
            }
            return null
        }
        val entryAudioPresent = entry.audio.isNotBlank() && io.exists(join(bundleDir, entry.audio))
        if (!entryAudioPresent || !jsonTimed) {
            // Downgrading the entry while the JSON stays timed would fail
            // validation the other way (timed file, unrendered entry), so
            // the JSON is stripped back to untimed first (best effort: a
            // missing or corrupt file stays for rescan to report).
            if (timed != null && timed.durationMs != null) {
                stripTimings(textRel, timed)?.let { stripped ->
                    RenderFinalize.atomicWriteText(textPath, stripped, io)
                }
            }
            val updated = RenderFinalize.buildUpdatedManifestJson(
                rawManifestJson = manifestText,
                chapterNumber = chapterNumber,
                audio = null,
                durationMs = null,
                fingerprint = null,
                gainDb = null,
                encoderOffsetMs = null
            ).getOrNull() ?: return null
            return ChapterRepair(
                chapterNumber = chapterNumber,
                kind = RepairKind.ManifestDowngraded,
                detail = updated
            )
        }
        return null
    }

    /**
     * FP4: orphan render temps by directory listing (no manifest needed).
     *
     * Finalize writes every temp as `<target>.tmp` inside `audio/` and
     * `text/`, so a kill mid-finalize with a torn or missing manifest
     * still leaves only `*.tmp` residue there. Lists both dirs and
     * deletes every `*.tmp` hit best-effort via [RenderFileIo] (never
     * throws; a listing failure reads as no strays). Live files never
     * end in `.tmp`, so they are never touched. Returns deleted paths
     * sorted.
     */
    fun sweepOrphanTemps(bundleDir: String, io: RenderFileIo): List<String> {
        val candidates = ArrayList<String>()
        for (sub in listOf("audio", "text")) {
            val listed = try {
                io.listFiles(join(bundleDir, sub), "", StrayTempSweep.RENDER_TMP_SUFFIX)
            } catch (_: Exception) {
                emptyList()
            }
            candidates.addAll(listed)
        }
        return StrayTempSweep.sweepPaths(candidates, io::exists, io::deleteIfExists)
    }

    /**
     * Sweeps orphan spool PCM: files no chapter index lists, plus whole
     * chapters whose index is missing or unparseable (a kill between the
     * PCM write and the index write). Chapters with a valid index keep
     * exactly their listed files. Fingerprint-stale spool is NOT swept
     * here: the renderer invalidates it per chapter at render time (RN4).
     * Best-effort and never throwing; returns deleted paths sorted.
     */
    fun sweepOrphanSpool(spoolDir: String, io: SpoolIo): List<String> {
        val deleted = ArrayList<String>()
        try {
            val indexPaths = try {
                io.listFiles(spoolDir, "ch", "-index.json")
            } catch (_: Exception) {
                emptyList()
            }
            val indexedChapters = HashSet<Int>()
            for (indexPath in indexPaths) {
                val chapter = chapterNumberFromName(
                    indexPath.substringAfterLast('/'),
                    "-index.json"
                ) ?: continue
                indexedChapters.add(chapter)
                val raw = try {
                    io.readText(indexPath)
                } catch (_: Exception) {
                    null
                }
                val parsed = raw?.let { SpoolIndex.parse(it) }
                if (parsed == null || parsed.chapter != chapter) {
                    deleteQuiet(io, indexPath, deleted)
                    for (pcm in listChapterPcm(spoolDir, io, chapter)) {
                        deleteQuiet(io, pcm, deleted)
                    }
                    continue
                }
                val listed = parsed.sentences.map { it.file }.toSet()
                for (pcm in listChapterPcm(spoolDir, io, chapter)) {
                    if (pcm.substringAfterLast('/') !in listed) {
                        deleteQuiet(io, pcm, deleted)
                    }
                }
            }
            val allPcm = try {
                io.listFiles(spoolDir, "ch", ".pcm")
            } catch (_: Exception) {
                emptyList()
            }
            for (pcm in allPcm) {
                val chapter = chapterNumberFromName(pcm.substringAfterLast('/'), ".pcm")
                    ?: continue
                if (chapter !in indexedChapters) {
                    deleteQuiet(io, pcm, deleted)
                }
            }
        } catch (_: Exception) {
        }
        return deleted.sorted()
    }

    private fun listChapterPcm(spoolDir: String, io: SpoolIo, chapter: Int): List<String> =
        try {
            io.listFiles(spoolDir, SpoolFiles.chapterPrefix(chapter), ".pcm")
        } catch (_: Exception) {
            emptyList()
        }

    private fun deleteQuiet(io: SpoolIo, path: String, deleted: MutableList<String>) {
        try {
            io.deleteIfExists(path)
            deleted.add(path)
        } catch (_: Exception) {
        }
    }

    /** `chNNN-...` file name to its 1-based chapter number, or null. */
    internal fun chapterNumberFromName(fileName: String, suffix: String): Int? {
        if (!fileName.startsWith("ch") || !fileName.endsWith(suffix)) return null
        val digits = fileName.removePrefix("ch").takeWhile { it.isDigit() }
        if (digits.isEmpty()) return null
        return digits.toIntOrNull()?.takeIf { it >= 1 }
    }

    /** Rewrites a timed chapter back to its untimed 2.0 shape (recovery rollback). */
    internal fun stripTimings(textRel: String, timed: ChapterText): String? {
        if (timed.specVersion != "2.0") return null
        if (timed.durationMs == null) return null
        val untimed = timed.copy(
            durationMs = null,
            pages = null,
            blocks = timed.blocks.map { block ->
                block.copy(
                    sentences = block.sentences.map { sentence ->
                        sentence.copy(startMs = null, endMs = null)
                    }
                )
            }
        )
        val text = prettyJson.encodeToString(ChapterText.serializer(), untimed)
        val check = ChapterTextLoader.parse(textRel, text).getOrNull() ?: return null
        if (check.durationMs != null) return null
        return text
    }

    private fun join(dir: String, rel: String): String =
        dir.trimEnd('/') + '/' + rel.trimStart('/')
}

/** Human one-liner for a manifest-affecting repair (the raw text stays internal). */
private fun RenderRecovery.ChapterRepair.describe(): String =
    when (kind) {
        RenderRecovery.RepairKind.ManifestCompleted ->
            "chapter $chapterNumber: manifest entry completed from timed JSON " +
                "(no fingerprint invented)"
        RenderRecovery.RepairKind.ManifestDowngraded ->
            "chapter $chapterNumber: manifest entry downgraded to unrendered " +
                "(files missing)"
        else -> detail
    }
