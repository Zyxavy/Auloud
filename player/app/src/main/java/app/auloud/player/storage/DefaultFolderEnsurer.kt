package app.auloud.player.storage

import java.io.IOException

/**
 * WP3/WP5 refinement: auto-creates the shared-internal `/Auloud` default on
 * first launch. Pure seam over existence + creation so plain-JVM tests drive
 * it with lambdas; production passes `{ File(path).exists() }` and
 * `{ File(path).mkdirs() }`.
 *
 * Never throws: a failed creation is a `Result.failure` naming the path, so
 * callers log/surface it and rescan reports the missing folder instead of
 * crashing.
 *
 * API 24 safe: `java.io.File` calls live with the caller, not here.
 */
object DefaultFolderEnsurer {

    /**
     * @param exists true when the folder already exists.
     * @param mkdirs creates the folder (and parents), true on success.
     * @param pathForMessage the folder path, used only for error messages.
     */
    fun ensure(exists: Boolean, mkdirs: () -> Boolean, pathForMessage: String): Result<Unit> {
        if (exists) return Result.success(Unit)
        return try {
            if (mkdirs()) {
                Result.success(Unit)
            } else {
                Result.failure(
                    IOException("$pathForMessage: could not create books folder")
                )
            }
        } catch (e: SecurityException) {
            Result.failure(
                IOException("$pathForMessage: permission denied: ${e.message}", e)
            )
        } catch (e: Exception) {
            Result.failure(
                IOException("$pathForMessage: could not create books folder: ${e.message}", e)
            )
        }
    }
}
