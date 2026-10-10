package app.auloud.player.ingest

import android.net.Uri
import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.BundleValidator
import app.auloud.player.bundle.ChapterTextLoader
import app.auloud.player.bundle.Manifest
import app.auloud.player.data.BookEntity
import app.auloud.player.data.LibraryRepository
import app.auloud.player.storage.BundleStorage
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

/**
 * IN7: pipeline and bundle writer tests (Slice 9, JVM only).
 *
 * End-to-end imports run over the real golden EPUBs through an
 * in-memory [BundleStorage] fake plus a map-backed [LibraryRepository]
 * fake: no Android framework, no Robolectric. Every written book is
 * proven conforming by reading it back with IN1's own validator and
 * chapter loader (pipeline writes, validator plus [ChapterTextLoader]
 * read).
 */
class IngestPipelineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class MemStorage : BundleStorage {
        val files = mutableMapOf<String, ByteArray>()

        override fun listBundleDirs(root: String): List<String> {
            val prefix = root.trimEnd('/') + "/"
            return files.keys.mapNotNull { key ->
                if (!key.startsWith(prefix)) {
                    null
                } else {
                    val rest = key.removePrefix(prefix)
                    if ('/' !in rest) null else prefix + rest.substringBefore('/')
                }
            }.filter { dir ->
                files.keys.any { it == "$dir/manifest.json" }
            }.distinct().sorted()
        }

        override fun readText(path: String): String =
            files[path]?.toString(Charsets.UTF_8)
                ?: throw IOException("missing: $path")

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun audioUri(bundleDir: String, relPath: String): Uri =
            throw UnsupportedOperationException("not used by the pipeline")

        override fun coverUri(bundleDirPath: String, coverRel: String): String? = null

        override fun writeBytes(path: String, bytes: ByteArray) {
            files[path] = bytes.copyOf()
        }

        override fun copySourceFile(srcPath: String, dstPath: String) {
            val src = File(srcPath)
            if (!src.isFile) throw IOException("$srcPath: cannot read source file (missing EPUB)")
            files[dstPath] = src.readBytes()
        }

        override fun movePath(fromPath: String, toPath: String) {
            val fromPrefix = fromPath.trimEnd('/') + "/"
            val toPrefix = toPath.trimEnd('/') + "/"
            if (files.keys.any { it == toPath || it.startsWith(toPrefix) }) {
                throw IOException("$toPath: book folder already exists (duplicate import?)")
            }
            val moved = files.keys.filter { it == fromPath || it.startsWith(fromPrefix) }
            if (moved.isEmpty()) throw IOException("$toPath: cannot move temp folder into place")
            for (key in moved) {
                val rel = if (key == fromPath) "" else key.removePrefix(fromPrefix)
                files[toPrefix + rel] = files.remove(key)!!
            }
        }

        override fun deleteRecursively(path: String) {
            val prefix = path.trimEnd('/') + "/"
            files.keys.filter { it == path || it.startsWith(prefix) }.forEach { files.remove(it) }
        }

        fun keysUnder(root: String): List<String> {
            val prefix = root.trimEnd('/') + "/"
            return files.keys.filter { it.startsWith(prefix) }
        }
    }

    private class MemLibrary : LibraryRepository {
        val books = mutableMapOf<String, BookEntity>()
        var nowMs = 1000L

        override fun books() = flowOf(books.values.toList())

        override suspend fun importBundle(bundleDir: String, manifest: Manifest): Result<BookEntity> {
            if (manifest.id.isBlank() || manifest.title.isBlank()) {
                return Result.failure(IllegalArgumentException("manifest.json: missing required field id/title"))
            }
            val existing = books[manifest.id]
            val book = BookEntity(
                id = manifest.id,
                title = manifest.title,
                author = manifest.author,
                bundlePath = bundleDir,
                coverPath = null,
                durationMs = manifest.chapters.sumOf { it.durationMs ?: 0L },
                addedAt = existing?.addedAt ?: nowMs,
                isMissing = false
            )
            books[manifest.id] = book
            return Result.success(book)
        }

        override suspend fun refreshMissing(presentBundleDirs: Collection<String>): Result<Unit> =
            Result.success(Unit)

        override suspend fun deleteBook(bookId: String): Result<Unit> {
            if (!books.containsKey(bookId)) {
                return Result.failure(IllegalArgumentException("$bookId: book not in library (nothing to delete)"))
            }
            books.remove(bookId)
            return Result.success(Unit)
        }
    }

    private fun repoRoot(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val direct = File(userDir, "../../spec/fixtures")
        if (direct.isDirectory) return direct.parentFile?.parentFile ?: userDir
        var cur: File? = userDir
        while (cur != null) {
            if (File(cur, "spec/fixtures").isDirectory) return cur
            cur = cur.parentFile
        }
        return userDir
    }

    private fun fixtureEpub(fixture: String): File {
        val epub = File(repoRoot(), "spec/fixtures/$fixture/source/book.epub")
        assertTrue("epub missing: ${epub.path}", epub.isFile)
        return epub
    }

    private fun parityWords(fixture: String): Int {
        val parity = File(repoRoot(), "spec/fixtures/ingest-parity/$fixture.json")
        assertTrue("parity missing: ${parity.path}", parity.isFile)
        val root = Json.parseToJsonElement(parity.readText(Charsets.UTF_8)).jsonObject
        return root["chapters"]!!.jsonArray.sumOf { chapter ->
            chapter.jsonObject["words"]!!.jsonPrimitive.int
        }
    }

    private fun writeZip(target: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun containerXml(opfPath: String): ByteArray {
        return (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" version=\"1.0\">" +
                "<rootfiles><rootfile full-path=\"$opfPath\" " +
                "media-type=\"application/oebps-package+xml\"/></rootfiles></container>"
            ).toByteArray(Charsets.UTF_8)
    }

    /** Two-chapter EPUB; [firstHeading] may be empty to pin the drop wording. */
    private fun twoChapterEpub(target: File, firstHeading: String, wordsPerChapter: Int = 250) {
        fun chapterDoc(heading: String, seed: String): ByteArray {
            val words = (1..wordsPerChapter).joinToString(" ") { "$seed$it" }
            return (
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>t</title></head>" +
                    "<body><h1>$heading</h1><p>$words.</p>" +
                    "<p>\"We should leave,\" she said.</p></body></html>"
                ).toByteArray(Charsets.UTF_8)
        }
        val opf = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"id\" version=\"2.0\">" +
                "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                "<dc:title>Synthetic Book</dc:title><dc:creator>Test Author</dc:creator>" +
                "<dc:language>en</dc:language>" +
                "<dc:identifier id=\"id\">test-id</dc:identifier></metadata>" +
                "<manifest>" +
                "<item id=\"ch1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "<item id=\"ch2\" href=\"ch2.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>" +
                "</manifest>" +
                "<spine toc=\"ncx\"><itemref idref=\"ch1\"/><itemref idref=\"ch2\"/></spine></package>"
            ).toByteArray(Charsets.UTF_8)
        val ncx = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">" +
                "<head><meta name=\"dtb:uid\" content=\"test-id\"/></head>" +
                "<docTitle><text>Synthetic Book</text></docTitle><navMap>" +
                "<navPoint id=\"np1\"><navLabel><text>First</text></navLabel>" +
                "<content src=\"ch1.xhtml\"/></navPoint>" +
                "<navPoint id=\"np2\"><navLabel><text>Second</text></navLabel>" +
                "<content src=\"ch2.xhtml\"/></navPoint>" +
                "</navMap></ncx>"
            ).toByteArray(Charsets.UTF_8)
        writeZip(
            target,
            mapOf(
                "META-INF/container.xml" to containerXml("EPUB/content.opf"),
                "EPUB/content.opf" to opf,
                "EPUB/toc.ncx" to ncx,
                "EPUB/ch1.xhtml" to chapterDoc(firstHeading, "alpha"),
                "EPUB/ch2.xhtml" to chapterDoc("Second Heading", "beta")
            )
        )
    }

    private fun runImport(
        epub: File,
        storage: MemStorage = MemStorage(),
        library: MemLibrary = MemLibrary(),
        clock: () -> Long = System::currentTimeMillis,
        onProgress: suspend (ImportProgress) -> Unit = {}
    ): Pair<IngestOutcome, Pair<MemStorage, MemLibrary>> = runBlocking {
        val outcome = IngestPipeline.importEpub(
            epubFile = epub,
            booksRoot = temp.root.absolutePath.replace('\\', '/') + "/Auloud",
            storage = storage,
            library = library,
            clock = clock,
            onProgress = onProgress
        )
        Pair(outcome, Pair(storage, library))
    }

    // End to end on a real golden EPUB, round-tripped through IN1.

    @Test
    fun scribeGolden_importsAndRoundTripsThroughValidatorAndLoader() {
        val epub = fixtureEpub("scribe-golden")
        val (outcome, pair) = runImport(epub)
        val (storage, library) = pair
        val imported = outcome as? IngestOutcome.Imported
            ?: failNow("want Imported, got $outcome")
        val report = imported.report

        assertEquals(2, report.chapters)
        assertEquals(parityWords("scribe-golden"), report.words)
        assertTrue("title non-blank, got: ${report.title}", report.title.isNotBlank())

        val sha = EpubContainerReader.sha256Hex(epub)
        assertEquals(DeviceBookIds.idForSha256(sha), report.bookId)

        val manifestRaw = storage.files[report.bundleDir + "/manifest.json"]?.toString(Charsets.UTF_8)
            ?: failNow("manifest missing in storage")
        assertTrue("manifest must be spec 2.0", "\"spec_version\": \"2.0\"" in manifestRaw)
        assertTrue("manifest must be unrendered", "\"render_state\": \"none\"" in manifestRaw)
        assertTrue("unrendered manifest carries no audio", "\"audio\"" !in manifestRaw)
        assertTrue("unrendered manifest carries no durations", "duration_ms" !in manifestRaw)

        val manifest = BundleParser.parseText(manifestRaw).getOrThrow()
        assertEquals(null, manifest.audio)
        assertEquals("none", manifest.renderState)
        assertTrue("voices needs narrator", manifest.voices.containsKey("narrator"))
        assertTrue("voices needs dialogue", manifest.voices.containsKey("dialogue"))
        assertEquals(report.bookId, manifest.id)

        val problems = BundleValidator.validate(
            report.bundleDir, manifest, storage::exists, storage::readText
        )
        assertTrue("written book must validate, got: $problems", problems.isEmpty())

        for (entry in manifest.chapters) {
            val raw = storage.readText(report.bundleDir + "/" + entry.text)
            assertTrue("no timings in ${entry.text}", "start_ms" !in raw)
            assertTrue("no durations in ${entry.text}", "duration_ms" !in raw)
            val parsed = ChapterTextLoader.parse(entry.text, raw).getOrThrow()
            assertEquals("2.0", parsed.specVersion)
            assertEquals(null, parsed.durationMs)
            for (sentence in parsed.sentencesInOrder()) {
                assertTrue(
                    "sid ${sentence.sid} speaker must be reserved, got ${sentence.speaker}",
                    sentence.speaker == "narrator" || sentence.speaker == "dialogue"
                )
            }
            val sids = parsed.sentencesInOrder().map { it.sid }
            assertEquals((1..sids.size).toList(), sids)
        }

        val row = library.books[report.bookId] ?: failNow("library row missing")
        assertEquals(report.bundleDir, row.bundlePath)
        assertEquals(0L, row.durationMs)

        val sourceBytes = storage.files[report.bundleDir + "/source/book.epub"]
            ?: failNow("source copy missing")
        assertEquals(sha, shaHex(sourceBytes))
    }

    @Test
    fun epub2Minimal_importsTwoChapters() {
        val (outcome, _) = runImport(fixtureEpub("epub2-minimal"))
        val imported = outcome as? IngestOutcome.Imported
            ?: failNow("want Imported, got $outcome")
        assertEquals(2, imported.report.chapters)
        assertEquals(parityWords("epub2-minimal"), imported.report.words)
    }

    // Duplicate fast reject.

    @Test
    fun duplicateImport_detectedBeforeWork() {
        val epub = fixtureEpub("multivoice-golden")
        val storage = MemStorage()
        val library = MemLibrary()
        val first = runImport(epub, storage, library).first
        val firstId = (first as? IngestOutcome.Imported ?: failNow("want Imported, got $first"))
            .report.bookId

        var reads = 0
        val second = runBlocking {
            IngestPipeline.importEpub(
                epubFile = epub,
                booksRoot = temp.root.absolutePath.replace('\\', '/') + "/Auloud",
                storage = storage,
                library = library,
                readBook = { file ->
                    reads++
                    EpubContainerReader.read(file)
                }
            )
        }
        val duplicate = second as? IngestOutcome.Duplicate
            ?: failNow("want Duplicate, got $second")
        assertEquals(firstId, duplicate.bookId)
        assertEquals(0, reads)
        assertEquals(1, library.books.size)
    }

    // Cancellation leaves nothing behind.

    @Test
    fun cancelMidImport_leavesNoFolderNoRowNoTemp() {
        val epub = temp.newFile("cancel.epub")
        twoChapterEpub(epub, "First Heading")
        val storage = MemStorage()
        val library = MemLibrary()
        val root = temp.root.absolutePath.replace('\\', '/') + "/Auloud"

        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val job = launch {
                IngestPipeline.importEpub(
                    epubFile = epub,
                    booksRoot = root,
                    storage = storage,
                    library = library,
                    onProgress = {
                        entered.complete(Unit)
                        release.await()
                    }
                )
            }
            entered.await()
            job.cancel()
            job.join()
            assertTrue("import job must end cancelled", job.isCancelled)
        }

        assertTrue(
            "no bundle residue, got: ${storage.keysUnder(root)}",
            storage.keysUnder(root).isEmpty()
        )
        assertTrue("no library row, got: ${library.books}", library.books.isEmpty())
    }

    // Same-book serialization (IN8): two concurrent imports of one book
    // end as one Imported plus one Duplicate, never a rename Failed.

    @Test
    fun concurrentSameBookImports_serializeToImportedPlusDuplicate() {
        val epub = temp.newFile("race.epub")
        twoChapterEpub(epub, "First Heading")
        val storage = MemStorage()
        val library = MemLibrary()
        val root = temp.root.absolutePath.replace('\\', '/') + "/Auloud"

        runBlocking {
            val first = async {
                IngestPipeline.importEpub(
                    epubFile = epub,
                    booksRoot = root,
                    storage = storage,
                    library = library,
                    onProgress = { delay(200) }
                )
            }
            // The second import starts while the first is mid-write, so
            // without the per-hash lock its duplicate scan would miss and
            // the rename would collide.
            delay(50)
            val second = async {
                IngestPipeline.importEpub(
                    epubFile = epub,
                    booksRoot = root,
                    storage = storage,
                    library = library
                )
            }
            val outcomes = awaitAll(first, second)
            val imported = outcomes.filterIsInstance<IngestOutcome.Imported>()
            val duplicates = outcomes.filterIsInstance<IngestOutcome.Duplicate>()
            assertEquals("one import must win, got: $outcomes", 1, imported.size)
            assertEquals("loser must be Duplicate, got: $outcomes", 1, duplicates.size)
            assertEquals(
                imported.single().report.bookId,
                duplicates.single().bookId
            )
        }

        assertEquals(1, library.books.size)
        assertTrue(
            "no temp residue, got: ${storage.keysUnder(root)}",
            storage.keysUnder(root).none { "/.tmp-" in it }
        )
    }

    // Failure shapes: file plus rule.

    @Test
    fun corruptEpub_failsNamingFileAndRule() {
        val epub = temp.newFile("corrupt.epub")
        epub.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val (outcome, pair) = runImport(epub)
        val failed = outcome as? IngestOutcome.Failed ?: failNow("want Failed, got $outcome")
        assertEquals(1, failed.errors.size)
        assertTrue("error names the file, got: ${failed.errors}", "corrupt.epub" in failed.errors.single())
        assertTrue("no residue, got: ${pair.first.files}", pair.first.files.isEmpty())
        assertTrue(pair.second.books.isEmpty())
    }

    @Test
    fun missingEpub_failsNamingFile() {
        val epub = File(temp.root, "absent.epub")
        val outcome = runImport(epub).first
        val failed = outcome as? IngestOutcome.Failed ?: failNow("want Failed, got $outcome")
        assertTrue("error names the file, got: ${failed.errors}", "absent.epub" in failed.errors.single())
    }

    @Test
    fun nonLinearOnlySpine_failsWithNoReadableChapters() {
        val target = temp.newFile("nonlinear.epub")
        val opf = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"id\" version=\"2.0\">" +
                "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                "<dc:title>Cover Only</dc:title><dc:language>en</dc:language>" +
                "<dc:identifier id=\"id\">test-id</dc:identifier></metadata>" +
                "<manifest>" +
                "<item id=\"cover\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "</manifest>" +
                "<spine><itemref idref=\"cover\" linear=\"no\"/></spine></package>"
            ).toByteArray(Charsets.UTF_8)
        val cover = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>c</title></head>" +
                "<body><p>Cover page.</p></body></html>"
            ).toByteArray(Charsets.UTF_8)
        writeZip(
            target,
            mapOf(
                "META-INF/container.xml" to containerXml("EPUB/content.opf"),
                "EPUB/content.opf" to opf,
                "EPUB/cover.xhtml" to cover
            )
        )
        val outcome = runImport(target).first
        val failed = outcome as? IngestOutcome.Failed ?: failNow("want Failed, got $outcome")
        assertTrue("rule named, got: ${failed.errors}", "no readable chapters" in failed.errors.single())
    }

    // Drop wording follows the rules doc, not Scribe.

    @Test
    fun emptyHeading_dropsWithRulesDocWording() {
        val epub = temp.newFile("drops.epub")
        twoChapterEpub(epub, "")
        val (outcome, _) = runImport(epub)
        val imported = outcome as? IngestOutcome.Imported
            ?: failNow("want Imported, got $outcome")
        assertEquals(2, imported.report.chapters)
        assertTrue(
            "rules-doc drop wording, got: ${imported.report.drops}",
            imported.report.drops.any { "dropped empty heading" in it }
        )
    }

    // Progress plus fakeable clock.

    @Test
    fun progressEvents_firePerChapterInOrder() {
        val epub = temp.newFile("progress.epub")
        twoChapterEpub(epub, "First Heading")
        val events = ArrayList<ImportProgress>()
        val (outcome, _) = runImport(epub, onProgress = { events.add(it) })
        val imported = outcome as? IngestOutcome.Imported
            ?: failNow("want Imported, got $outcome")
        assertEquals(2, events.size)
        assertEquals(listOf(1, 2), events.map { it.chaptersDone })
        assertEquals(listOf(1, 2), events.map { it.chapterIndex })
        assertTrue(events.all { it.totalChapters == 2 })
        assertEquals(
            listOf("First", "Second"),
            events.map { it.chapterTitle }
        )
    }

    @Test
    fun elapsedTime_comesFromInjectedClock() {
        val epub = fixtureEpub("multivoice-golden")
        var now = 5000L
        val (outcome, _) = runImport(
            epub,
            clock = { now },
            onProgress = { now = 6123L }
        )
        val imported = outcome as? IngestOutcome.Imported
            ?: failNow("want Imported, got $outcome")
        assertEquals(1123L, imported.report.elapsedMs)
    }

    private fun shaHex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** JUnit's `fail` returns Unit (poisoning elvis types), so tests use this. */
    private fun failNow(message: String): Nothing = throw AssertionError(message)
}
