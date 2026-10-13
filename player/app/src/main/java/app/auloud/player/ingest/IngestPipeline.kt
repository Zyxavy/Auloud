package app.auloud.player.ingest

import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.BundleValidator
import app.auloud.player.bundle.Manifest
import app.auloud.player.data.LibraryRepository
import app.auloud.player.storage.BundleStorage
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * IN7: per-chapter import progress for the EPUB import pipeline.
 *
 * Emitted after each chapter file lands in the temp folder, in chapter
 * order. [chaptersDone] runs 1..[totalChapters]; [chapterIndex] is the
 * 1-based chapter just finished, [chapterTitle] its title. Callbacks run
 * on the import dispatcher thread (IO); IN8 forwards them to Main.
 */
data class ImportProgress(
    val chapterIndex: Int,
    val chaptersDone: Int,
    val totalChapters: Int,
    val chapterTitle: String
)

/**
 * IN7: import report for one imported book.
 *
 * [chapters] and [words] count the written book ([words] sums
 * [IngestChapter.wordCount]); [drops] is the IN4 structure drop log in
 * order (container warnings first, each naming the file and the reason);
 * [warnings] holds the IN5 alignment and IN6 dialogue notices in chapter
 * order; [elapsedMs] is `end - start` on the injected clock.
 */
data class ImportReport(
    val bookId: String,
    val bundleDir: String,
    val title: String,
    val author: String?,
    val chapters: Int,
    val words: Int,
    val drops: List<String>,
    val warnings: List<String>,
    val elapsedMs: Long
)

/**
 * IN7: outcome of [IngestPipeline.importEpub].
 *
 * Coroutine cancellation is NOT an outcome: a cancelled import throws
 * [CancellationException] after deleting every residue (temp folder,
 * final folder when the rename already happened, no library row).
 */
sealed interface IngestOutcome {
    /** Import finished: book on disk, library row upserted. */
    data class Imported(val report: ImportReport) : IngestOutcome

    /** Fast reject: a book with the same source hash is already imported. */
    data class Duplicate(val bookId: String, val bundleDir: String) : IngestOutcome

    /** Import failed: every entry names the file and the rule broken. */
    data class Failed(val errors: List<String>) : IngestOutcome
}

/**
 * IN7: EPUB import pipeline composing IN3-IN6 with a spec 2.0 writer
 * (Slice 9).
 *
 * Flow for [importEpub]: hash the source, reject duplicates by hash
 * BEFORE doing work, read the container (IN3), clean structure (IN4),
 * split sentences (IN5), tag dialogue (IN6), write the unrendered book
 * to a temp folder inside [booksRoot], self-validate the temp folder
 * with IN1's validator and loaders, atomically rename to the final
 * folder (`<booksRoot>/<bookId>`), and upsert the library row.
 *
 * Storage writes go through [BundleStorage] (D-081): the book folder
 * layout (`manifest.json`, `source/book.epub`, `text/chNNN.json`,
 * `cover.jpg`) matches every other bundle, so the library, the
 * validator and Slice 10 rendering work unchanged. The final folder is
 * named by the deterministic device id, so a re-import lands on the same
 * path and the duplicate scan finds it by hash first.
 *
 * Cancellation is cooperative between chapters (each written chapter is
 * a checkpoint via [ensureActive]); a cancelled import leaves no
 * folder, no library row and no temp residue. Everything runs on
 * [ioDispatcher] (default `Dispatchers.IO`), never the caller's thread.
 * Concurrent imports of the SAME book are serialized by [ImportLocks]
 * (IN8, keyed by source hash): the waiter blocks until the first import
 * finishes, then reports `Duplicate`. A rename refusal stays a shaped
 * `Failed` as defense in depth.
 *
 * API 24 safe: `java.io.File` for the source EPUB only (the picker hands
 * a file), `java.text` for the timestamp, coroutines for threading. No
 * `java.time`, no Android classes besides the storage interface.
 */
object IngestPipeline {

    /**
     * Imports [epubFile] into [booksRoot].
     *
     * @param clock epoch millis source for [ImportReport.elapsedMs] and
     * the manifest `created_at` (injectable, so tests never assert on
     * the wall clock).
     * @param onProgress per-chapter completion callback (IO thread).
     * @param readBook container-read seam (default IN3, injectable).
     * @param readSpine spine-bytes seam (default IN3, injectable).
     */
    suspend fun importEpub(
        epubFile: File,
        booksRoot: String,
        storage: BundleStorage,
        library: LibraryRepository,
        clock: () -> Long = System::currentTimeMillis,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        onProgress: suspend (ImportProgress) -> Unit = {},
        readBook: (File) -> Result<EpubBook> = EpubContainerReader::read,
        readSpine: (File, String) -> Result<ByteArray> = EpubContainerReader::readSpineBytes
    ): IngestOutcome = withContext(ioDispatcher) {
        val startMs = clock()
        var tempDir: String? = null
        var movedDir: String? = null
        try {
            val sha = hashSource(epubFile)
                ?: return@withContext IngestOutcome.Failed(
                    listOf("${epubFile.name}: cannot read source file (missing EPUB)")
                )
            val bookId = DeviceBookIds.idForSha256(sha)
            val finalDir = join(booksRoot, bookId)
            val temp = join(booksRoot, ".tmp-$bookId")
            tempDir = temp

            // IN8: same-book serialization. The duplicate scan plus every
            // later step run under the per-hash lock, so a concurrent
            // same-book import waits here and then reports Duplicate
            // instead of racing into the rename.
            return@withContext ImportLocks.withBookLock(sha.lowercase()) {
                findDuplicate(storage, booksRoot, sha)?.let { existing ->
                    return@withBookLock IngestOutcome.Duplicate(existing.first, existing.second)
                }

                val book = readBook(epubFile).getOrElse { cause ->
                    return@withBookLock IngestOutcome.Failed(
                        listOf(cause.message ?: "${epubFile.name}: cannot read EPUB (unknown error)")
                    )
                }

                val structure = EpubStructurePipeline.ingest(epubFile, book, readSpine)
                if (structure.chapters.isEmpty()) {
                    return@withBookLock IngestOutcome.Failed(
                        listOf(
                            "${epubFile.name}: no readable chapters " +
                                "(every spine item was skipped or dropped)"
                        )
                    )
                }

                // A stale temp folder from a crashed process (same deterministic
                // name) is removed before writing, so crash residue can never
                // merge with a fresh import.
                storage.deleteRecursively(temp)

                val warnings = ArrayList<String>()
                val dialogues = LinkedHashMap<Int, ChapterDialogue>()
                for (chapter in structure.chapters) {
                    ensureActive()
                    val split = SentenceSplitter.splitChapter(chapter, warnings)
                    dialogues[chapter.index] = DialogueTagger.tagChapter(chapter, split, warnings)
                }

                val total = structure.chapters.size
                val coverBytes = EpubContainerReader.readCoverBytes(epubFile, book)
                val manifestInput = IngestWriter.ManifestInput(
                    bookId = bookId,
                    title = book.title,
                    author = book.author,
                    language = book.language,
                    sha256Hex = sha,
                    coverRel = if (coverBytes != null) IngestWriter.COVER_REL else null,
                    chapters = structure.chapters.map { chapter ->
                        IngestWriter.ChapterEntry(
                            index = chapter.index,
                            title = chapter.title,
                            textRel = IngestWriter.chapterRel(chapter.index, total)
                        )
                    },
                    createdAt = formatCreatedAt(startMs)
                )

            var done = 0
            val manifest: Manifest
            try {
                manifest = IngestWriter.writeBook(
                    storage = storage,
                    tempDir = temp,
                    manifestInput = manifestInput,
                    chapters = structure.chapters,
                    dialogues = dialogues,
                    sourcePath = epubFile.absolutePath,
                    coverBytes = coverBytes,
                    onChapterWritten = { chapter ->
                        ensureActive()
                        done++
                        onProgress(
                            ImportProgress(
                                chapterIndex = chapter.index,
                                chaptersDone = done,
                                totalChapters = total,
                                chapterTitle = chapter.title
                            )
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return@withBookLock failAndClean(storage, temp, e.message ?: "cannot write book")
            } catch (e: Exception) {
                return@withBookLock failAndClean(
                    storage, temp, e.message ?: "cannot write book"
                )
            }

            ensureActive()
            val problems = selfValidate(storage, temp, manifest)
            if (problems.isNotEmpty()) {
                return@withBookLock failAndClean(storage, temp, problems)
            }

            ensureActive()
            try {
                storage.movePath(temp, finalDir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withBookLock failAndClean(
                    storage, temp, e.message ?: "cannot move book into place"
                )
            }
            movedDir = finalDir

            ensureActive()
            val upserted = library.importBundle(finalDir, manifest)
            if (upserted.isFailure) {
                val reason = upserted.exceptionOrNull()?.message ?: "library upsert failed"
                storage.deleteRecursively(finalDir)
                movedDir = null
                return@withBookLock IngestOutcome.Failed(listOf(reason))
            }

            IngestOutcome.Imported(
                ImportReport(
                    bookId = bookId,
                    bundleDir = finalDir,
                    title = book.title,
                    author = book.author,
                    chapters = total,
                    words = structure.chapters.sumOf { it.wordCount },
                    drops = structure.drops.toList(),
                    warnings = warnings.toList(),
                    elapsedMs = clock() - startMs
                )
            )
            }
        } catch (e: CancellationException) {
            if (tempDir != null) storage.deleteRecursively(tempDir)
            // Copy out: the lock body mutates movedDir through the
            // closure, so no smart cast applies here.
            val moved = movedDir
            if (moved != null) storage.deleteRecursively(moved)
            throw e
        }
    }

    private fun hashSource(epubFile: File): String? {
        return try {
            EpubContainerReader.sha256Hex(epubFile)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Duplicate scan by source hash over the already-imported manifests
     * (fast reject before any chapter work). Returns the existing book id
     * plus bundle dir, or null. Unreadable entries are skipped (rescan
     * reports them); only storage-listing failure aborts the import.
     */
    private fun findDuplicate(
        storage: BundleStorage,
        booksRoot: String,
        sha: String
    ): Pair<String, String>? {
        val dirs = try {
            storage.listBundleDirs(booksRoot)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        for (dir in dirs) {
            val manifest = try {
                val text = storage.readText(join(dir, IngestWriter.MANIFEST_REL))
                BundleParser.parseText(text).getOrNull() ?: continue
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            if (manifest.source?.sha256?.lowercase() == sha.lowercase()) {
                return Pair(manifest.id, dir)
            }
        }
        return null
    }

    /**
     * Validates the temp book with IN1's validator (manifest rules plus
     * the chapter text-file checks through the storage seams) so only
     * conforming books ever reach the rename. Returns the error list
     * (empty means valid).
     */
    private fun selfValidate(
        storage: BundleStorage,
        tempDir: String,
        manifest: Manifest
    ): List<String> {
        return try {
            BundleValidator.validate(tempDir, manifest, storage::exists, storage::readText, storage::sizeBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listOf(
                "$tempDir: ${IngestWriter.MANIFEST_REL} self-check failed (${e.message})"
            )
        }
    }

    private fun failAndClean(
        storage: BundleStorage,
        tempDir: String,
        error: String
    ): IngestOutcome.Failed {
        storage.deleteRecursively(tempDir)
        return IngestOutcome.Failed(listOf(error))
    }

    private fun failAndClean(
        storage: BundleStorage,
        tempDir: String,
        errors: List<String>
    ): IngestOutcome.Failed {
        storage.deleteRecursively(tempDir)
        return IngestOutcome.Failed(errors)
    }

    private fun formatCreatedAt(epochMs: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(epochMs))
    }

    private fun join(dir: String, rel: String): String =
        dir.trimEnd('/') + '/' + rel.trimStart('/')
}
