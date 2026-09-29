package app.auloud.player.data

import app.auloud.player.bundle.Manifest
import kotlinx.coroutines.flow.Flow

/**
 * WP4: library of imported books.
 *
 * Callers (WP5) parse `manifest.json` with `BundleParser` and run
 * `BundleValidator` first; [importBundle] only applies the import guards that
 * protect row integrity (readable manifest, non-blank id/title) and maps the
 * [Manifest] plus `BundleStorage` file state into a [BookEntity].
 */
interface LibraryRepository {

    /** All books (including missing ones), newest first. Failures surface as [DataError.Local]. */
    fun books(): Flow<List<BookEntity>>

    /**
     * Inserts or updates the book for [manifest] (`id` is the upsert key).
     * Re-imports keep the original `addedAt` and clear [BookEntity.isMissing].
     * `coverPath` is set only when the manifest names a cover that exists.
     */
    suspend fun importBundle(bundleDir: String, manifest: Manifest): Result<BookEntity>

    /**
     * Marks every stored book whose [BookEntity.bundlePath] is absent from
     * [presentBundleDirs] as missing (and clears the flag for the rest).
     * Missing rows are kept, never deleted. Pass the paths from the same
     * `BundleStorage.listBundleDirs` listing used for imports; trailing-slash
     * variance between stored and listed paths is ignored.
     */
    suspend fun refreshMissing(presentBundleDirs: Collection<String>): Result<Unit>
}
