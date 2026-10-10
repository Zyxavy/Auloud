package app.auloud.player.data

import app.auloud.player.bundle.Manifest
import app.auloud.player.storage.BundleStorage
import app.auloud.player.storage.SafPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * Runs [block] in one Room transaction. Production passes
 * `database.withTransaction`; the default runs the block directly (unit
 * tests over fake DAOs, callers without a second DAO).
 */
typealias RoomTransaction = suspend (suspend () -> Unit) -> Unit

/**
 * WP4: [LibraryRepository] over Room + [BundleStorage].
 *
 * File state is read only through [BundleStorage] (`exists` for the manifest
 * and the optional cover); `java.io.File` never appears here. [now] supplies
 * epoch millis for [BookEntity.addedAt] (injectable for deterministic tests).
 *
 * API 24 safe: string path joins, `System.currentTimeMillis`, no `java.time`.
 */
class RoomLibraryRepository(
    private val bookDao: BookDao,
    private val storage: BundleStorage,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * FP2: progress rows fall with the book row in one [inTransaction].
     * Null keeps the row-only delete (callers that never delete books).
     */
    private val progressDao: ProgressDao? = null,
    private val inTransaction: RoomTransaction = { it() }
) : LibraryRepository {

    override fun books(): Flow<List<BookEntity>> =
        bookDao.observeBooks().catch { e ->
            if (e is CancellationException) throw e
            throw DataError.Local(e)
        }

    override suspend fun importBundle(bundleDir: String, manifest: Manifest): Result<BookEntity> {
        if (manifest.id.isBlank() || manifest.title.isBlank()) {
            return Result.failure(
                DataError.InvalidBundle("manifest.json: missing required field id/title")
            )
        }
        return runBoundary {
            if (!storage.exists(join(bundleDir, MANIFEST_FILE))) {
                throw DataError.InvalidBundle("$bundleDir: manifest.json not found or not readable")
            }
            // CP4: covers resolve through BundleStorage.coverUri at import, so
            // file books store the file path and SAF books store a content://
            // document URI string (both loadable via Coil). Null stays null;
            // a bad cover (missing file, bundle escape) also stores null
            // rather than failing the import.
            val coverRel = manifest.cover?.takeIf { it.isNotBlank() }
            val coverPath = if (coverRel == null) {
                null
            } else {
                val fullPath = join(bundleDir, coverRel)
                if (!storage.exists(fullPath)) {
                    null
                } else {
                    try {
                        storage.coverUri(bundleDir, coverRel)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            val existing = bookDao.getById(manifest.id)
            // FP2: the same id in two folders must not flap the row. The
            // first import wins; the second skips and the rescan reports
            // this failure as the bundle error (no schema change: the key
            // stays the manifest id).
            if (existing != null &&
                existing.bundlePath.trimEnd('/') != bundleDir.trimEnd('/')
            ) {
                throw DataError.InvalidBundle(
                    "$bundleDir/manifest.json: duplicate book id ${manifest.id} " +
                        "(already imported from ${existing.bundlePath})"
                )
            }
            val book = BookEntity(
                id = manifest.id,
                title = manifest.title,
                author = manifest.author,
                bundlePath = bundleDir,
                coverPath = coverPath,
                // IN1: unrendered 2.0 chapters carry no duration_ms (null);
                // the book totals 0 ms until Slice 10 renders audio.
                durationMs = manifest.chapters.sumOf { it.durationMs ?: 0L },
                addedAt = existing?.addedAt ?: now(),
                isMissing = false
            )
            bookDao.upsert(book)
            book
        }
    }

    /**
     * IN8: deletes the book folder first (best-effort, never throws by
     * contract) and then the row, so a row never points at a folder the
     * delete meant to remove. An unknown id fails naming the file and
     * the rule and deletes nothing.
     *
     * FP2: the folder delete is verified before the row delete (a failed
     * delete keeps its row); book plus progress rows fall in one Room
     * transaction, so a kill between the steps can never orphan a saved
     * position a later re-import would resurrect. SAF trees have no
     * delete support (the `BundleStorage` default is a no-op), so only
     * file folders verify.
     */
    override suspend fun deleteBook(bookId: String): Result<Unit> =
        runBoundary {
            val existing = bookDao.getById(bookId)
                ?: throw DataError.InvalidBundle("$bookId: book not in library (nothing to delete)")
            storage.deleteRecursively(existing.bundlePath)
            if (!SafPaths.isSafPath(existing.bundlePath) &&
                storage.exists(join(existing.bundlePath, MANIFEST_FILE))
            ) {
                throw DataError.InvalidBundle("$bookId: could not delete book folder (delete failed)")
            }
            inTransaction {
                bookDao.deleteById(bookId)
                progressDao?.deleteById(bookId)
            }
        }

    override suspend fun refreshMissing(presentBundleDirs: Collection<String>): Result<Unit> =
        runBoundary {
            // Stored paths and listBundleDirs output may differ by a trailing
            // slash; comparing them raw would flip missing flags, so both
            // sides are normalized first.
            val present = presentBundleDirs.map { it.trimEnd('/') }.toSet()
            for (book in bookDao.getAll()) {
                val missing = book.bundlePath.trimEnd('/') !in present
                if (book.isMissing != missing) {
                    bookDao.setMissing(book.id, missing)
                }
            }
        }

    private fun join(dir: String, rel: String): String =
        dir.trimEnd('/') + '/' + rel.trimStart('/')

    private companion object {
        const val MANIFEST_FILE = "manifest.json"
    }
}
