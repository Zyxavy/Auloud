package app.auloud.player.ingest

import app.auloud.player.storage.BundleStorage

/**
 * IN8: stray import-temp sweep (Slice 9).
 *
 * A sudden process kill between manifest write and rename leaves a
 * `<booksRoot>/.tmp-<bookId>` folder behind (only sudden death does;
 * cancel and failure clean up, IN7-tested). The folder carries a
 * `manifest.json`, so without a sweep the library rescan would list it
 * as a book. The sweep deletes every such folder before the rescan
 * lists anything.
 *
 * Naming note: the `.tmp-` prefix must match the pipeline temp
 * (`IngestPipeline` builds `".tmp-$bookId"`) and the storage listing
 * (`FileBundleStorage.STRAY_TEMP_PREFIX`). Final book folders are bare
 * `<bookId>`, so sweeping this prefix can never delete a real book.
 *
 * API 24 safe: string ops plus the storage seam, no Android classes,
 * JVM-testable through fakes.
 */
object StrayTempSweep {

    /** Import temp folder name prefix (see the naming note above). */
    const val TMP_PREFIX = ".tmp-"

    /**
     * True when [dirPath] names an import temp (last segment starts with
     * [.TMP_PREFIX]). The rescan import loop skips these defensively, so
     * a temp created by a concurrent import mid-rescan is never read as
     * a book even if the pre-listing sweep already ran.
     */
    fun isStrayDir(dirPath: String): Boolean {
        val name = dirPath.trimEnd('/').substringAfterLast('/')
        return name.startsWith(TMP_PREFIX)
    }

    /**
     * Deletes every stray temp under [root] via [storage]. Best effort
     * and never throwing: listing failures read as no strays, and
     * [BundleStorage.deleteRecursively] never throws by contract. Returns
     * the deleted paths (sorted), so tests and logs can assert on them.
     */
    fun sweep(root: String, storage: BundleStorage): List<String> {
        val strays = try {
            storage.listStrayTempDirs(root)
        } catch (_: Exception) {
            return emptyList()
        }
        val deleted = ArrayList<String>(strays.size)
        for (path in strays.sorted()) {
            storage.deleteRecursively(path)
            deleted.add(path)
        }
        return deleted
    }
}
