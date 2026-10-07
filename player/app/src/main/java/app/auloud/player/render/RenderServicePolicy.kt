package app.auloud.player.render

/**
 * RN8: render-service policy (Slice 10).
 *
 * Everything the foreground service decides that is testable without
 * Android: task-removal behavior, restart behavior after process death,
 * guard-to-message mapping, media-to-chapter save conversion for partial
 * books, and the spool-dir location. The service (thin, Android-only)
 * calls these; JVM tests pin them here.
 *
 * Defined behaviors:
 * - task-removed: pause plus persist. A RUNNING job moves to PAUSED
 *   (keeping finished chapters plus the spool, so resume skips finished
 *   work); any other state is already parked and passes through unchanged.
 *   The service persists the result, releases the wake lock, and stays
 *   foreground-paused so the user can resume from the notification.
 * - restart after process death: never auto-start. The persisted file is
 *   recovered through `RenderStateStore.markInterruptedIfActive`
 *   (RUNNING or PAUSED left by the dead process becomes INTERRUPTED) and
 *   the job waits for an explicit resume, requeue or abandon per the RN3
 *   queue rule (PAUSED keeps its runner claim, INTERRUPTED waits). Spool
 *   resume skips finished sentences (RN4 index plus PCM match), so the
 *   next explicit start continues where the kill landed.
 * - auto-resume after a guard pause: only when the pause came from the
 *   guards ([autoPaused]) and the guards now report PROCEED. An explicit
 *   user pause never auto-resumes on a charger or temperature signal.
 * - partial-book saves: playback positions are media (playlist) indexes;
 *   the progress store is keyed by manifest chapter position, so saves
 *   convert through [ChapterMediaMap]. Unmapped media indexes save
 *   nothing (null) instead of landing on the wrong chapter.
 * - finished saves: only fully rendered (identity-mapped) books mark the
 *   book finished at the end of the playlist. A partial book stopping at
 *   the end of its rendered portion publishes [END_OF_RENDERED_MESSAGE]
 *   through the service notice channel instead.
 *
 * API 24 safe: pure Kotlin, no Android types, no `java.time`.
 */
object RenderServicePolicy {

    /** What the service does with a stored job after a (re)start. */
    enum class RestartAction {
        /** No state file: nothing to recover, stay idle. */
        NO_JOB,
        /** Job needs an explicit resume, requeue or abandon (no auto-start). */
        WAIT_EXPLICIT,
        /** Terminal or DONE job: nothing to resume, stay idle. */
        NOTHING_TO_RESUME
    }

    /**
     * Task-removal move for [job]: RUNNING becomes PAUSED, everything else
     * passes through unchanged (PAUSED, INTERRUPTED and QUEUED are already
     * parked; terminal jobs free the runner by rule).
     */
    fun onTaskRemoved(
        job: RenderJob,
        clock: () -> Long = System::currentTimeMillis
    ): RenderJob =
        if (job.state == RenderJobState.RUNNING) {
            RenderJobs.transition(job, RenderJobState.PAUSED, clock)
        } else {
            job
        }

    /**
     * Restart decision for a stored [job] (null when no state file).
     * Recovery (orphan temps, chapter repairs, INTERRUPTED marking) still
     * runs first; this only decides whether rendering starts by itself
     * (it never does: the answer is always explicit resume or nothing).
     */
    fun restartAction(job: RenderJob?): RestartAction {
        if (job == null) return RestartAction.NO_JOB
        return when (job.state) {
            RenderJobState.RUNNING,
            RenderJobState.PAUSED,
            RenderJobState.INTERRUPTED,
            RenderJobState.QUEUED,
            RenderJobState.FAILED,
            RenderJobState.CANCELLED -> RestartAction.WAIT_EXPLICIT
            RenderJobState.DONE -> RestartAction.NOTHING_TO_RESUME
        }
    }

    /**
     * True when the service may auto-resume a guard-paused job: the pause
     * came from the guards ([autoPaused]) and they now report PROCEED.
     */
    fun shouldAutoResume(autoPaused: Boolean, decision: RenderGuardDecision): Boolean =
        autoPaused && decision == RenderGuardDecision.PROCEED

    /**
     * Plain-language pause reason for a guard [decision] (null for
     * PROCEED, which needs no message). Shown in the render notification;
     * RN9 owns the full error copy.
     */
    fun pauseMessage(decision: RenderGuardDecision): String? = when (decision) {
        RenderGuardDecision.PROCEED -> null
        RenderGuardDecision.PAUSE_CHARGER -> "Paused - connect the charger to keep rendering"
        RenderGuardDecision.PAUSE_TEMPERATURE -> "Paused - letting the battery cool down"
        RenderGuardDecision.PAUSE_STORAGE -> "Paused - low storage (free space to keep rendering)"
    }

    /**
     * Manifest chapter position for a playlist [mediaIndex] (the
     * media-to-chapter save conversion, D-099).
     *
     * Null [map] (service not loaded yet) passes the index through, so
     * callers keep working before the first book loads. A present map
     * converts strictly: null when the media index names no chapter, so
     * the caller skips the save instead of writing the wrong chapter.
     */
    fun chapterPosForMedia(mediaIndex: Int, map: ChapterMediaMap?): Int? {
        if (map == null) return mediaIndex
        return map.chapterPosOf(mediaIndex)
    }

    /**
     * True when reaching the end of the playlist may mark the book
     * finished: only identity-mapped (fully rendered) books. Partial
     * books stop with [END_OF_RENDERED_MESSAGE] instead (the book is not
     * done, its rendered portion is).
     */
    fun finishedSaveAllowed(map: ChapterMediaMap?): Boolean =
        map == null || map.isIdentity

    /**
     * Spool workspace dir for [bookId] under the app [cacheDirPath]
     * (`<cache>/render-spool/<bookId>`).
     *
     * Spool files are temp render workspace, not bundle content, so they
     * stay out of the book folder (the validator never sees them and no
     * rescan is needed when they come and go); per-chapter spool is
     * deleted after finalize and orphan spool is swept by recovery.
     */
    fun spoolDirFor(cacheDirPath: String, bookId: String): String {
        require(bookId.isNotBlank()) { "bookId must not be blank." }
        require(cacheDirPath.isNotBlank()) { "cacheDirPath must not be blank." }
        return cacheDirPath.trimEnd('/') + "/render-spool/" + bookId
    }
}
