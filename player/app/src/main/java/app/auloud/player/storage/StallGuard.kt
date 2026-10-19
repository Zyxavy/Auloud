package app.auloud.player.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * OPEN3: a guard for blocking storage reads, because `withTimeout` cannot
 * interrupt them.
 *
 * `withTimeout` over `withContext(Dispatchers.IO) { File.readText(path) }`
 * resumes the caller only when the blocking call returns (measured: a
 * 100 ms timeout over a 5 s blocked read took 5.016 s in ChapterLoadTest).
 * A read stuck in the kernel therefore left the hub on `Loading book…`
 * with the 20 s timeout never firing: the owner's third device report.
 *
 * [guardedRead] runs [block] on its own IO job and waits at a real
 * suspension point ([kotlinx.coroutines.Deferred.await]), so the caller
 * always resumes: with the value, with the [Throwable] the block threw, or
 * with a stall once [timeoutMs] passes. A stalled read keeps its thread
 * until the storage answers; the caller is free to name the failure and
 * offer a retry.
 *
 * OPEN5: [block] may be a whole load (reads plus parsing plus Room
 * queries), so a silent hang anywhere inside it names the stage it
 * reached instead of leaving the owner on a spinner.
 *
 * API 24 safe: coroutines only.
 */

/** Default stall budget for one storage read (same as OPEN1's timeout). */
internal const val STALL_TIMEOUT_MS = 20_000L

internal sealed interface ReadGuardOutcome<out T> {
    data class Ok<T>(val value: T) : ReadGuardOutcome<T>

    /**
     * [stalled] true: the timeout won while the block was still running
     * ([cause] null). [stalled] false: the block threw ([cause] set; an
     * `Error` included, so a linking death is named instead of silent).
     */
    data class Failed(val stalled: Boolean, val cause: Throwable?) : ReadGuardOutcome<Nothing>
}

internal suspend fun <T> guardedRead(
    timeoutMs: Long = STALL_TIMEOUT_MS,
    workDispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: suspend () -> T
): ReadGuardOutcome<T> {
    // Detached on purpose: a stalled read must not hold up the caller, and
    // cancelling the job could not unblock it anyway (cancellation cannot
    // preempt a non-suspending call). [workDispatcher] is a seam for tests:
    // the default keeps the read off Main, while a test dispatcher keeps
    // the race deterministic under a virtual-time scheduler.
    val work = CoroutineScope(SupervisorJob() + workDispatcher).async { block() }
    return try {
        withTimeoutOrNull(timeoutMs) { work.await() }
        // Reaching this line with the work still running means the timeout
        // won (await resumes on cancellation, whatever the read is doing).
        if (work.isCompleted) {
            // Completed: await returns at once, or rethrows the block's
            // failure into the catch below.
            ReadGuardOutcome.Ok(work.await())
        } else {
            ReadGuardOutcome.Failed(stalled = true, cause = null)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        ReadGuardOutcome.Failed(stalled = false, cause = e)
    }
}

/** `20000` reads as `20s`; a sub-second test budget reads as `1s`. */
internal fun secondsText(timeoutMs: Long): String = "${(timeoutMs + 999) / 1000}s"
