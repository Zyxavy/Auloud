package app.auloud.player.data

import kotlinx.coroutines.CancellationException

/**
 * WP4: data-layer failures.
 *
 * The repositories are the error boundary (no domain layer in Slice 1):
 * platform exceptions (`SQLiteException`, `IOException`, ...) never leak to
 * ViewModels. `suspend` repository calls return `Result<T>` carrying a
 * [DataError]; the `books()` `Flow` rethrows [DataError.Local] downstream.
 *
 * API 24 safe: pure Kotlin, no `java.time`.
 */
sealed class DataError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Platform storage failure (SQLite, file I/O via [app.auloud.player.storage.BundleStorage]). */
    class Local(cause: Throwable) : DataError("Local storage error: ${cause.message}", cause)

    /** Bundle content fails the import guards (unreadable manifest, blank id/title). */
    class InvalidBundle(message: String, cause: Throwable? = null) : DataError(message, cause)
}

/**
 * Runs [block] as a repository error boundary: [DataError]s pass through,
 * coroutine cancellation is never swallowed, anything else becomes
 * [DataError.Local].
 */
internal suspend inline fun <T> runBoundary(crossinline block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: DataError) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(DataError.Local(e))
    }
}
