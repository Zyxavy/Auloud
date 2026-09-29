package app.auloud.player.storage

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP3/WP5 refinement: default-folder creation verifies on plain JVM with
 * lambdas — existing folders are a no-op success, creation success/failure
 * map to success/failure results, and throwing `mkdirs` (e.g. denied)
 * becomes a failure naming the path. Never throws.
 */
class DefaultFolderEnsurerTest {

    private val path = "/storage/emulated/0/Auloud"

    @Test
    fun existing_isSuccessWithoutCreating() {
        var created = false

        val result = DefaultFolderEnsurer.ensure(
            exists = true,
            mkdirs = { created = true; true },
            pathForMessage = path
        )

        assertTrue(result.isSuccess)
        assertTrue(!created)
    }

    @Test
    fun missingAndMkdirsTrue_isSuccess() {
        val result = DefaultFolderEnsurer.ensure(
            exists = false,
            mkdirs = { true },
            pathForMessage = path
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun missingAndMkdirsFalse_isFailureNamingPath() {
        val result = DefaultFolderEnsurer.ensure(
            exists = false,
            mkdirs = { false },
            pathForMessage = path
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message!!.contains(path))
    }

    @Test
    fun mkdirsThrowing_isFailureNeverThrows() {
        val result = DefaultFolderEnsurer.ensure(
            exists = false,
            mkdirs = { throw SecurityException("denied") },
            pathForMessage = path
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message!!.contains(path))
    }
}
