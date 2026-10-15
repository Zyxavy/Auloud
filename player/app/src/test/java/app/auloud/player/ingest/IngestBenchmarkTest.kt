package app.auloud.player.ingest

import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.BundleValidator
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Diagnostic only: times each silent import stage over a novel-size
 * synthetic EPUB to find where a big book stalls. Prints, asserts
 * nothing about speed (JVM speed never proves device speed).
 */
class IngestBenchmarkTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun bigPara(seed: Int): String {
        // ~250 words: keeps every synthetic chapter above the tiny-merge
        // threshold so chapter counts survive like a real webnovel.
        val out = StringBuilder("<p>")
        for (i in 0 until 28) {
            if ((i + seed) % 4 == 0) {
                out.append("\"What do you mean?\" she asked, and the room fell silent. ")
            } else {
                out.append("The quiet fox crosses the mossy hill at dawn, and the river keeps its counsel. ")
            }
        }
        return out.append("</p>").toString()
    }

    private fun bigEpub(target: File, chapters: Int) {
        val manifest = StringBuilder()
        val spine = StringBuilder()
        val entries = LinkedHashMap<String, ByteArray>()
        entries["mimetype"] = "application/epub+zip".toByteArray(Charsets.UTF_8)
        entries["META-INF/container.xml"] = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" version=\"1.0\">" +
                "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" " +
                "media-type=\"application/oebps-package+xml\"/></rootfiles></container>"
            ).toByteArray(Charsets.UTF_8)
        for (n in 1..chapters) {
            val name = "ch$n.xhtml"
            manifest.append("<item id=\"ch$n\" href=\"$name\" media-type=\"application/xhtml+xml\"/>")
            spine.append("<itemref idref=\"ch$n\"/>")
            val body = "<h1>Chapter $n</h1>" +
                bigPara(n) + bigPara(n + 1) + bigPara(n + 2) + bigPara(n + 3) +
                "<p>\"Go on,\" he said. \"The night is young and the road is long.\"</p>"
            entries["OEBPS/$name"] = body.toByteArray(Charsets.UTF_8)
        }
        val opf = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"id\" version=\"3.0\">" +
                "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                "<dc:title>Big Book</dc:title><dc:language>en</dc:language>" +
                "<dc:identifier id=\"id\">big-id</dc:identifier></metadata>" +
                "<manifest>$manifest</manifest>" +
                "<spine>$spine</spine></package>"
            ).toByteArray(Charsets.UTF_8)
        entries["OEBPS/content.opf"] = opf
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun heapMb(): Long {
        Runtime.getRuntime().gc()
        return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024
    }

    private fun bench(chapters: Int) {
        val target = File(temp.root, "big-$chapters.epub")
        bigEpub(target, chapters)
        println("BENCH chapters=$chapters fileKb=${target.length() / 1024} heapStart=${heapMb()}MB")
        var t = System.nanoTime()
        val book = EpubContainerReader.read(target).getOrThrow()
        println("BENCH read=${(System.nanoTime() - t) / 1_000_000}ms")
        t = System.nanoTime()
        val structure = EpubStructurePipeline.ingest(target, book)
        val structureMs = (System.nanoTime() - t) / 1_000_000
        println("BENCH structure=${structureMs}ms kept=${structure.chapters.size} heap=${heapMb()}MB")
        assertEquals(chapters, structure.chapters.size)
        t = System.nanoTime()
        val warnings = ArrayList<String>()
        var sentences = 0
        var splitMs = 0L
        var tagMs = 0L
        val dialogues = LinkedHashMap<Int, ChapterDialogue>()
        for (chapter in structure.chapters) {
            var s = System.nanoTime()
            val split = SentenceSplitter.splitChapter(chapter, warnings)
            splitMs += System.nanoTime() - s
            s = System.nanoTime()
            dialogues[chapter.index] = DialogueTagger.tagChapter(chapter, split, warnings)
            tagMs += System.nanoTime() - s
            sentences += dialogues.getValue(chapter.index).blocks.sumOf { it.sentences.size }
        }
        println(
            "BENCH split=${splitMs / 1_000_000}ms tag=${tagMs / 1_000_000}ms " +
                "sentences=$sentences warnings=${warnings.size} heap=${heapMb()}MB"
        )
        val storage = app.auloud.player.storage.FileBundleStorage()
        val tempDir = File(temp.root, "bench-$chapters").absolutePath
        val total = structure.chapters.size
        val input = IngestWriter.ManifestInput(
            bookId = "bench-$chapters",
            title = "Big Book",
            author = null,
            language = "en",
            sha256Hex = "00".repeat(32),
            coverRel = null,
            chapters = structure.chapters.map { chapter ->
                IngestWriter.ChapterEntry(
                    index = chapter.index,
                    title = chapter.title,
                    textRel = IngestWriter.chapterRel(chapter.index, total)
                )
            },
            createdAt = "2026-01-01T00:00:00Z"
        )
        t = System.nanoTime()
        kotlinx.coroutines.runBlocking {
            IngestWriter.writeBook(
                storage = storage,
                tempDir = tempDir,
                manifestInput = input,
                chapters = structure.chapters,
                dialogues = dialogues,
                sourcePath = target.absolutePath,
                coverBytes = null
            )
        }
        println("BENCH write=${(System.nanoTime() - t) / 1_000_000}ms heap=${heapMb()}MB")
        t = System.nanoTime()
        val manifest = BundleParser.parse(File(tempDir)).getOrThrow()
        val problems = BundleValidator.validate(
            tempDir,
            manifest,
            { File(it).isFile },
            { path -> File(path).takeIf { it.isFile }?.readText() },
            { path -> File(path).takeIf { it.isFile }?.length() }
        )
        println(
            "BENCH validate=${(System.nanoTime() - t) / 1_000_000}ms " +
                "problems=${problems.size} heap=${heapMb()}MB"
        )
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun bench200() {
        bench(200)
    }

    @Test
    fun bench1800() {
        bench(1800)
    }

    @Test
    fun benchPdfShapedChapter() {
        // One 20-page PDF-like chapter (~700 sentences, ~150 KB JSON):
        // the open path parses it once, then walks every sentence.
        val sentences = (1..700).joinToString(",") { sid ->
            """{"sid":$sid,"speaker":"narrator","text":"The quiet fox crosses the mossy hill at dawn. "}"""
        }
        val raw =
            """{"spec_version":"2.0","chapter":1,"title":"Pages 1-20","blocks":[{"id":1,"type":"para","sentences":[$sentences]}]}"""
        println("BENCH pdfChapterKb=${raw.length / 1024} heapStart=${heapMb()}MB")
        var t = System.nanoTime()
        val chapter = app.auloud.player.bundle.ChapterTextLoader
            .parse("text/ch001.json", raw).getOrThrow()
        println("BENCH pdfParse=${(System.nanoTime() - t) / 1_000_000}ms")
        t = System.nanoTime()
        val ordered = chapter.sentencesInOrder()
        println("BENCH pdfOrder=${(System.nanoTime() - t) / 1_000_000}ms sentences=${ordered.size}")
        t = System.nanoTime()
        val layouts = chapter.blocks.map { app.auloud.player.reader.layoutParagraph(it) }
        println(
            "BENCH pdfLayout=${(System.nanoTime() - t) / 1_000_000}ms heap=${heapMb()}MB " +
                "chars=${layouts.sumOf { it.text.length }}"
        )
        t = System.nanoTime()
        val index = app.auloud.player.reader.SentenceIndex(chapter.blocks)
        println("BENCH pdfIndex=${(System.nanoTime() - t) / 1_000_000}ms size=${index.size}")
    }
}
