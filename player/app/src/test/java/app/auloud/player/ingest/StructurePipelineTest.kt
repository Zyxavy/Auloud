package app.auloud.player.ingest

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * IN4: pipeline end-to-end over synthetic EPUBs (JVM only).
 *
 * Mirrors Scribe's mini-EPUB (`test_extract_epub.py`:
 * `test_mini_epub_chapters_blocks_spans`,
 * `test_non_linear_spine_items_skipped`,
 * `test_naval_history_chapter_survives`) through the real
 * [EpubContainerReader] plus [EpubStructurePipeline]: nav and image-only
 * drops, tiny-merge with `Chapter N` fallback, span offsets, and every
 * spine-level skip. The large-chapter test proves one-document-at-a-time
 * processing stays memory-safe.
 */
class StructurePipelineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun longPara(word: String, repeats: Int): String {
        return (1..repeats).joinToString(" ") { "The quiet $word fox crosses the mossy hill at dawn." }
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

    private fun navXhtml(pairs: List<Pair<String, String>>): ByteArray {
        val items = pairs.joinToString("") { "<li><a href=\"${it.first}\">${it.second}</a></li>" }
        return (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<html xmlns=\"http://www.w3.org/1999/xhtml\" " +
                "xmlns:epub=\"http://www.idpf.org/2007/ops\">" +
                "<head><title>Nav</title></head>" +
                "<body><nav epub:type=\"toc\"><ol>$items</ol></nav></body></html>"
            ).toByteArray(Charsets.UTF_8)
    }

    private fun miniEpub(target: File) {
        val ch1 = (
            "<h1>Chapter One</h1>" +
                "<p>${longPara("alder", 25)}</p>" +
                "<p>She left <em>early</em> and <strong>ran fast</strong> home.</p>" +
                "<blockquote><p>The night was cold and still.</p></blockquote>" +
                "<hr/>" +
                "<p>* * *</p>" +
                "<p></p><p>   </p>" +
                "<p>Copyright 2026 Example Press. All rights reserved.</p>" +
                "<p>ISBN 978-0-00-000000-0</p>" +
                "<p>The trail continues.<sup><a href=\"#fn1\">[1]</a></sup> And the fox follows.</p>"
            ).toByteArray(Charsets.UTF_8)
        val ch2 = "<h2>Tiny Interlude</h2><p>A brief pause.</p>".toByteArray(Charsets.UTF_8)
        val ch3 = ("<h1>Chapter Three</h1><p>${longPara("bracken", 25)}</p>").toByteArray(Charsets.UTF_8)
        val ch4 = ("<p>${longPara("cedar", 25)}</p><p>Second untitled paragraph stays.</p>")
            .toByteArray(Charsets.UTF_8)
        val img = "<div><img src=\"pic.jpg\" alt=\"a picture\"/></div>".toByteArray(Charsets.UTF_8)
        val opf = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"id\" version=\"3.0\">" +
                "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                "<dc:title>Mini Book</dc:title><dc:language>en</dc:language>" +
                "<dc:identifier id=\"id\">mini-id</dc:identifier></metadata>" +
                "<manifest>" +
                "<item id=\"ch1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "<item id=\"ch2\" href=\"ch2.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "<item id=\"ch3\" href=\"ch3.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "<item id=\"ch4\" href=\"ch4.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "<item id=\"imgpage\" href=\"imgpage.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" +
                "</manifest>" +
                "<spine>" +
                "<itemref idref=\"nav\"/><itemref idref=\"imgpage\"/>" +
                "<itemref idref=\"ch1\"/><itemref idref=\"ch2\"/>" +
                "<itemref idref=\"ch3\"/><itemref idref=\"ch4\"/>" +
                "</spine></package>"
            ).toByteArray(Charsets.UTF_8)
        writeZip(
            target,
            mapOf(
                "mimetype" to "application/epub+zip".toByteArray(Charsets.UTF_8),
                "META-INF/container.xml" to containerXml("OEBPS/content.opf"),
                "OEBPS/content.opf" to opf,
                "OEBPS/ch1.xhtml" to ch1,
                "OEBPS/ch2.xhtml" to ch2,
                "OEBPS/ch3.xhtml" to ch3,
                "OEBPS/ch4.xhtml" to ch4,
                "OEBPS/imgpage.xhtml" to img,
                "OEBPS/nav.xhtml" to navXhtml(
                    listOf(
                        Pair("ch1.xhtml", "Chapter One"),
                        Pair("ch2.xhtml", "Tiny Interlude"),
                        Pair("ch3.xhtml", "Chapter Three")
                    )
                )
            )
        )
    }

    private fun ingestFile(target: File): StructureResult {
        val book = EpubContainerReader.read(target).getOrThrow()
        return EpubStructurePipeline.ingest(target, book)
    }

    @Test
    fun miniEpub_chaptersBlocksSpansAndDrops() {
        val target = File(temp.root, "mini.epub")
        miniEpub(target)
        val result = ingestFile(target)

        assertEquals(3, result.chapters.size)
        val first = result.chapters[0]
        val second = result.chapters[1]
        val third = result.chapters[2]
        assertEquals("Chapter One", first.title)
        assertEquals("Chapter Three", second.title)
        assertEquals("Chapter 3", third.title)

        assertEquals(
            listOf("heading", "para", "para", "quote", "break", "break", "para"),
            first.blocks.map { it.kind }
        )
        assertEquals("Chapter One", first.blocks[0].text)
        assertEquals(1, first.blocks[0].level)
        val formatted = first.blocks[2]
        assertEquals("She left early and ran fast home.", formatted.text)
        val byStyle = formatted.spans.associateBy { it.style }
        assertEquals(IngestSpan(9, 14, SPAN_ITALIC), byStyle[SPAN_ITALIC])
        assertEquals(IngestSpan(19, 27, SPAN_BOLD), byStyle[SPAN_BOLD])
        assertEquals("The night was cold and still.", first.blocks[3].text)

        val allText = result.chapters.flatMap { it.blocks }.joinToString(" ") { it.text }
        assertTrue(!allText.contains("Copyright"))
        assertTrue(!allText.contains("ISBN"))
        assertTrue(!allText.contains("[1]"))
        assertTrue(allText.contains("The trail continues."))
        assertTrue(allText.contains("And the fox follows."))

        val secondHeadings = second.blocks.filter { it.kind == "heading" }.map { it.text }
        assertEquals(listOf("Tiny Interlude", "Chapter Three"), secondHeadings)
        assertTrue(second.blocks.any { it.text.contains("A brief pause.") })

        val joined = result.drops.joinToString("\n")
        assertTrue(joined.contains("nav/TOC"))
        assertTrue(joined.contains("image-only"))
        assertTrue(joined.contains("empty paragraph"))
        assertTrue(joined.contains("boilerplate"))
        assertTrue(joined.contains("footnote"))
        assertTrue(joined.contains("merged tiny chapter(s)"))
    }

    @Test
    fun nonLinear_spineItemSkipped() {
        val target = File(temp.root, "nonlinear.epub")
        val ch = ("<h1>Chapter One</h1><p>${longPara("alder", 25)}</p>").toByteArray(Charsets.UTF_8)
        val hidden = ("<h1>Hidden Cover Text</h1><p>${longPara("hidden", 25)}</p>")
            .toByteArray(Charsets.UTF_8)
        val opf = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"id\" version=\"3.0\">" +
                "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                "<dc:title>NL</dc:title><dc:language>en</dc:language>" +
                "<dc:identifier id=\"id\">nl-id</dc:identifier></metadata>" +
                "<manifest>" +
                "<item id=\"ch1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "<item id=\"hidden\" href=\"hidden.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" +
                "</manifest>" +
                "<spine><itemref idref=\"ch1\"/>" +
                "<itemref idref=\"hidden\" linear=\"no\"/>" +
                "<itemref idref=\"nav\" linear=\"no\"/></spine></package>"
            ).toByteArray(Charsets.UTF_8)
        writeZip(
            target,
            mapOf(
                "mimetype" to "application/epub+zip".toByteArray(Charsets.UTF_8),
                "META-INF/container.xml" to containerXml("OEBPS/content.opf"),
                "OEBPS/content.opf" to opf,
                "OEBPS/ch1.xhtml" to ch,
                "OEBPS/hidden.xhtml" to hidden,
                "OEBPS/nav.xhtml" to navXhtml(listOf(Pair("ch1.xhtml", "Chapter One")))
            )
        )
        val result = ingestFile(target)
        assertEquals(1, result.chapters.size)
        assertEquals("Chapter One", result.chapters[0].title)
        val allText = result.chapters.flatMap { it.blocks }.joinToString(" ") { it.text }
        assertTrue(!allText.contains("Hidden Cover Text"))
        assertTrue(result.drops.any { it.contains("non-linear") })
    }

    @Test
    fun navalHistoryChapter_survives() {
        val target = File(temp.root, "naval.epub")
        val naval = ("<h1>Naval History</h1><p>${longPara("harbor", 25)}</p>")
            .toByteArray(Charsets.UTF_8)
        val opf = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"id\" version=\"3.0\">" +
                "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                "<dc:title>Naval</dc:title><dc:language>en</dc:language>" +
                "<dc:identifier id=\"id\">naval-id</dc:identifier></metadata>" +
                "<manifest>" +
                "<item id=\"naval\" href=\"naval-history.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" +
                "</manifest>" +
                "<spine><itemref idref=\"nav\"/><itemref idref=\"naval\"/></spine></package>"
            ).toByteArray(Charsets.UTF_8)
        writeZip(
            target,
            mapOf(
                "mimetype" to "application/epub+zip".toByteArray(Charsets.UTF_8),
                "META-INF/container.xml" to containerXml("OEBPS/content.opf"),
                "OEBPS/content.opf" to opf,
                "OEBPS/naval-history.xhtml" to naval,
                "OEBPS/nav.xhtml" to navXhtml(listOf(Pair("naval-history.xhtml", "Naval History")))
            )
        )
        val result = ingestFile(target)
        assertEquals(1, result.chapters.size)
        assertEquals("Naval History", result.chapters[0].title)
        assertTrue(result.drops.none { it.contains("naval-history") && it.contains("nav/TOC") })
    }

    @Test
    fun unreadableEntry_recordedAsDrop() {
        val book = EpubBook(
            title = "Ghost",
            author = null,
            language = "en",
            sha256Hex = "0".repeat(64),
            opfPath = "OEBPS/content.opf",
            spine = listOf(
                EpubSpineDocument("ch1", "EPUB/ch1.xhtml", true, false, "Chapter One"),
                EpubSpineDocument("ghost", "EPUB/ghost.xhtml", true, false, null)
            ),
            coverEntry = null,
            warnings = emptyList(),
            tocTitles = emptyMap()
        )
        val chapters = "<h1>Chapter One</h1><p>${longPara("alder", 25)}</p>"
            .toByteArray(Charsets.UTF_8)
        val result = EpubStructurePipeline.ingest(temp.root, book) { _, entry ->
            if (entry == "EPUB/ch1.xhtml") Result.success(chapters)
            else Result.failure(java.io.IOException("entry '$entry' missing in archive"))
        }
        assertEquals(1, result.chapters.size)
        assertEquals("Chapter One", result.chapters[0].title)
        assertTrue(result.drops.any { it.contains("ghost.xhtml") && it.contains("could not read") })
    }

    @Test
    fun bookWarnings_copiedFirst() {
        val book = EpubBook(
            title = "Warned",
            author = null,
            language = "fr",
            sha256Hex = "0".repeat(64),
            opfPath = "OEBPS/content.opf",
            spine = listOf(
                EpubSpineDocument("ch1", "EPUB/ch1.xhtml", true, false, "Chapter One")
            ),
            coverEntry = null,
            warnings = listOf("OEBPS/content.opf: language 'fr' is not English (continuing)"),
            tocTitles = emptyMap()
        )
        val chapters = "<h1>Chapter One</h1><p>${longPara("alder", 25)}</p>"
            .toByteArray(Charsets.UTF_8)
        val result = EpubStructurePipeline.ingest(temp.root, book) { _, _ ->
            Result.success(chapters)
        }
        assertEquals(1, result.chapters.size)
        assertTrue(result.drops.isNotEmpty())
        assertTrue(result.drops[0].contains("fr"))
    }

    @Test
    fun largeChapter_processesWithoutHoldingWholeBookDom() {
        val paras = (1..8000).joinToString("") { pos ->
            "<p>Paragraph number $pos carries enough words to look real here.</p>"
        }
        val html = "<h1>Big Chapter</h1>$paras".toByteArray(Charsets.UTF_8)
        val book = EpubBook(
            title = "Big",
            author = null,
            language = "en",
            sha256Hex = "0".repeat(64),
            opfPath = "OEBPS/content.opf",
            spine = listOf(
                EpubSpineDocument("big", "EPUB/big.xhtml", true, false, "Big Chapter")
            ),
            coverEntry = null,
            warnings = emptyList(),
            tocTitles = emptyMap()
        )
        val result = EpubStructurePipeline.ingest(temp.root, book) { _, _ ->
            Result.success(html)
        }
        assertEquals(1, result.chapters.size)
        assertEquals(8001, result.chapters[0].blocks.size)
        assertEquals("Big Chapter", result.chapters[0].blocks[0].text)
        assertTrue(result.chapters[0].blocks[8000].text.startsWith("Paragraph number 8000"))
        assertTrue(result.drops.isEmpty())
    }
}
