package app.auloud.player.data

import app.auloud.player.bundle.Manifest
import app.auloud.player.storage.BundleStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

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
    private val now: () -> Long = System::currentTimeMillis
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
            val coverPath = manifest.cover
                ?.takeIf { it.isNotBlank() }
                ?.let { join(bundleDir, it) }
                ?.takeIf { storage.exists(it) }
            val existing = bookDao.getById(manifest.id)
            val book = BookEntity(
                id = manifest.id,
                title = manifest.title,
                author = manifest.author,
                bundlePath = bundleDir,
                coverPath = coverPath,
                durationMs = manifest.chapters.sumOf { it.durationMs },
                addedAt = existing?.addedAt ?: now(),
                isMissing = false
            )
            bookDao.upsert(book)
            book
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
