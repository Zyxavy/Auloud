package app.auloud.player.ingest

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * IN3: EPUB container reader verifies (JVM only, no Android classes).
 *
 * Covers the brief Verify line: golden EPUBs (EPUB 2 NCX-only plus EPUB 3
 * nav-only synthetics, plus the real Scribe EPUB 3 fixtures with both
 * formats), malformed archives (traversal, oversize, missing OPF,
 * encrypted), spine order, titles and cover. Error cases assert the
 * file+rule message shape used across the repo.
 *
 * API 24 safe: `java.io` plus `java.util.zip` only in test setup; the
 * reader itself stays JVM-pure.
 */
class EpubContainerReaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fixtureFile(fixture: String): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val direct = File(userDir, "../../spec/fixtures/$fixture/source/book.epub")
        if (direct.isFile) return direct
        var cur: File? = userDir
        while (cur != null) {
            val candidate = File(cur, "spec/fixtures/$fixture/source/book.epub")
            if (candidate.isFile) return candidate
            cur = cur.parentFile
        }
        return direct
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

    private fun chapterXhtml(heading: String, para: String): ByteArray {
        return (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>$heading</title></head>" +
                "<body><h1>$heading</h1><p>$para</p></body></html>"
            ).toByteArray(Charsets.UTF_8)
    }

    private fun ncxXml(pairs: List<Pair<String, String>>): ByteArray {
        val points = pairs.mapIndexed { pos, pair ->
            "<navPoint id=\"np${pos + 1}\"><navLabel><text>${pair.second}</text></navLabel>" +
                "<content src=\"${pair.first}\"/></navPoint>"
        }.joinToString("")
        return (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">" +
                "<head><meta name=\"dtb:uid\" content=\"test-id\"/></head>" +
                "<docTitle><text>Test Book</text></docTitle>" +
                "<navMap>$points</navMap></ncx>"
            ).toByteArray(Charsets.UTF_8)
    }

    private fun navXhtml(pairs: List<Pair<String, String>>): ByteArray {
        val items = pairs.joinToString("") { "<li><a href=\"${it.first}\">${it.second}</a></li>" }
        return (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<html xmlns=\"http://www.w3.org/1999/xhtml\" " +
                "xmlns:epub=\"http://www.idpf.org/2007/ops\">" +
                "<head><title>Test Book</title></head>" +
                "<body><nav epub:type=\"toc\"><ol>$items</ol></nav></body></html>"
            ).toByteArray(Charsets.UTF_8)
    }

    private fun opfEpub2(
        title: String?,
        author: String?,
        language: String?,
        spineOrder: List<String> = listOf("ch1", "ch2"),
        manifestExtra: String = "",
        spineExtra: String = "",
        coverMeta: String = ""
    ): ByteArray {
        val titleEl = if (title != null) "<dc:title>$title</dc:title>" else ""
        val authorEl = if (author != null) "<dc:creator>$author</dc:creator>" else ""
        val langEl = if (language != null) "<dc:language>$language</dc:language>" else ""
        val spineRefs = spineOrder.joinToString("") { "<itemref idref=\"$it\"/>" }
        return (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"id\" version=\"2.0\">" +
                "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                "$titleEl$authorEl$langEl" +
                "<dc:identifier id=\"id\">test-id</dc:identifier>$coverMeta</metadata>" +
                "<manifest>" +
                "<item id=\"ch1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "<item id=\"ch2\" href=\"ch2.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>" +
                manifestExtra +
                "</manifest>" +
                "<spine toc=\"ncx\">$spineRefs$spineExtra</spine></package>"
            ).toByteArray(Charsets.UTF_8)
    }

    private fun opfEpub3(
        title: String?,
        author: String?,
        language: String?,
        spineOrder: List<String> = listOf("ch1", "ch2"),
        manifestExtra: String = "",
        spineExtra: String = "",
        includeNcx: Boolean = false
    ): ByteArray {
        val titleEl = if (title != null) "<dc:title>$title</dc:title>" else ""
        val authorEl = if (author != null) "<dc:creator>$author</dc:creator>" else ""
        val langEl = if (language != null) "<dc:language>$language</dc:language>" else ""
        val ncxItem = if (includeNcx) {
            "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>"
        } else {
            ""
        }
        val spineRefs = spineOrder.joinToString("") { "<itemref idref=\"$it\"/>" }
        return (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"id\" version=\"3.0\">" +
                "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                "$titleEl$authorEl$langEl" +
                "<dc:identifier id=\"id\">test-id</dc:identifier></metadata>" +
                "<manifest>" +
                "<item id=\"ch1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "<item id=\"ch2\" href=\"ch2.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" +
                ncxItem + manifestExtra +
                "</manifest>" +
                "<spine>$spineRefs$spineExtra</spine></package>"
            ).toByteArray(Charsets.UTF_8)
    }

    private fun epub2NcxOnly(
        target: File,
        title: String? = "Test Book",
        author: String? = "Test Author",
        language: String? = "en",
        tocPairs: List<Pair<String, String>> = listOf(
            Pair("ch1.xhtml", "Chapter One"),
            Pair("ch2.xhtml", "Chapter Two")
        )
    ) {
        writeZip(
            target,
            mapOf(
                "mimetype" to "application/epub+zip".toByteArray(Charsets.UTF_8),
                "META-INF/container.xml" to containerXml("OEBPS/content.opf"),
                "OEBPS/content.opf" to opfEpub2(title, author, language),
                "OEBPS/ch1.xhtml" to chapterXhtml("Chapter One", "First chapter text here."),
                "OEBPS/ch2.xhtml" to chapterXhtml("Chapter Two", "Second chapter text here."),
                "OEBPS/toc.ncx" to ncxXml(tocPairs)
            )
        )
    }

    private fun epub3NavOnly(
        target: File,
        title: String? = "Test Book",
        author: String? = "Test Author",
        language: String? = "en",
        tocPairs: List<Pair<String, String>> = listOf(
            Pair("ch1.xhtml", "Chapter One"),
            Pair("ch2.xhtml", "Chapter Two")
        ),
        spineOrder: List<String> = listOf("ch1", "ch2"),
        manifestExtra: String = "",
        spineExtra: String = ""
    ) {
        writeZip(
            target,
            mapOf(
                "mimetype" to "application/epub+zip".toByteArray(Charsets.UTF_8),
                "META-INF/container.xml" to containerXml("OEBPS/content.opf"),
                "OEBPS/content.opf" to opfEpub3(
                    title, author, language,
                    spineOrder = spineOrder,
                    manifestExtra = manifestExtra,
                    spineExtra = spineExtra
                ),
                "OEBPS/ch1.xhtml" to chapterXhtml("Chapter One", "First chapter text here."),
                "OEBPS/ch2.xhtml" to chapterXhtml("Chapter Two", "Second chapter text here."),
                "OEBPS/nav.xhtml" to navXhtml(tocPairs)
            )
        )
    }

    // ---- Golden EPUBs (real Scribe fixtures, EPUB 3 with both TOC formats) ----

    @Test
    fun scribeGolden_parsesWithExpectedMetadataSpineTitlesAndSha() {
        val file = fixtureFile("scribe-golden")
        assertTrue("fixture missing: ${file.path}", file.isFile)
        val result = EpubContainerReader.read(file)
        assertTrue(
            "expected success but got: ${result.exceptionOrNull()?.message}",
            result.isSuccess
        )
        val book = result.getOrThrow()
        assertEquals("Scribe Golden Bundle", book.title)
        assertEquals("Auloud Test", book.author)
        assertEquals("en", book.language)
        assertEquals(
            "a5963254577b9f582983a7c86bc1d8a9bc421389dcef841f4fc399260159d88b",
            book.sha256Hex
        )
        assertEquals("EPUB/content.opf", book.opfPath)
        assertEquals(2, book.spine.size)
        assertEquals("EPUB/ch1.xhtml", book.spine[0].href)
        assertEquals("EPUB/ch2.xhtml", book.spine[1].href)
        assertTrue(book.spine.all { it.isLinear })
        assertFalse(book.spine.any { it.isNav })
        assertEquals("Chapter One", book.spine[0].tocTitle)
        assertEquals("Chapter Two", book.spine[1].tocTitle)
        assertNull("goldens ship no cover", book.coverEntry)
        assertTrue("expected no warnings, got: ${book.warnings}", book.warnings.isEmpty())
        val bytes = EpubContainerReader.readSpineBytes(file, book.spine[0].href).getOrThrow()
        assertTrue(String(bytes, Charsets.UTF_8).contains("Chapter One"))
    }

    @Test
    fun partialGolden_parsesThreeChaptersWithTitles() {
        val file = fixtureFile("partial-golden")
        assertTrue("fixture missing: ${file.path}", file.isFile)
        val book = EpubContainerReader.read(file).getOrThrow()
        assertEquals("Partial Golden Miniature", book.title)
        assertEquals(
            "0fd32580acd2212933dd2889b3c11071b8792ecc03730180d8021be63729d7a9",
            book.sha256Hex
        )
        assertEquals(3, book.spine.size)
        assertEquals(
            listOf("EPUB/ch1.xhtml", "EPUB/ch2.xhtml", "EPUB/ch3.xhtml"),
            book.spine.map { it.href }
        )
        assertEquals("Chapter One", book.spine[0].tocTitle)
        assertEquals("Chapter Two", book.spine[1].tocTitle)
        assertEquals("Chapter Three", book.spine[2].tocTitle)
    }

    @Test
    fun multivoiceGolden_parsesSingleChapter() {
        val file = fixtureFile("multivoice-golden")
        assertTrue("fixture missing: ${file.path}", file.isFile)
        val book = EpubContainerReader.read(file).getOrThrow()
        assertEquals("Multivoice Golden Miniature", book.title)
        assertEquals(
            "baf814774fc22513b51f0405e7bf45a55f0de06b2682b44fff5fb44b07f9890e",
            book.sha256Hex
        )
        assertEquals(1, book.spine.size)
        assertEquals("Chapter One", book.spine[0].tocTitle)
    }

    @Test
    fun epub2MinimalFixture_parsesWithNcxTitles() {
        val file = fixtureFile("epub2-minimal")
        assertTrue("fixture missing: ${file.path}", file.isFile)
        val result = EpubContainerReader.read(file)
        assertTrue(
            "expected success but got: ${result.exceptionOrNull()?.message}",
            result.isSuccess
        )
        val book = result.getOrThrow()
        assertEquals("EPUB2 Minimal", book.title)
        assertEquals("Auloud Test", book.author)
        assertEquals("en", book.language)
        assertEquals(
            "df060df13672b412fb6a024c4210e055d8f390176fb944c8ce7a1b8ec415e7ce",
            book.sha256Hex
        )
        assertEquals("OEBPS/content.opf", book.opfPath)
        assertEquals(2, book.spine.size)
        assertEquals("OEBPS/ch1.xhtml", book.spine[0].href)
        assertEquals("OEBPS/ch2.xhtml", book.spine[1].href)
        assertTrue(book.spine.all { it.isLinear })
        assertFalse(book.spine.any { it.isNav })
        assertEquals("The Amber Lamp", book.spine[0].tocTitle)
        assertEquals("The Quiet River", book.spine[1].tocTitle)
        assertNull("minimal fixture ships no cover", book.coverEntry)
        assertTrue("expected no warnings, got: ${book.warnings}", book.warnings.isEmpty())
        val bytes = EpubContainerReader.readSpineBytes(file, book.spine[0].href).getOrThrow()
        assertTrue(String(bytes, Charsets.UTF_8).contains("amber lamp"))
    }

    // ---- EPUB 2 (NCX) and EPUB 3 (nav) synthetics ----

    @Test
    fun epub2_ncxOnly_titlesComeFromNcx() {
        val target = File(temp.root, "epub2.epub")
        epub2NcxOnly(target)
        val book = EpubContainerReader.read(target).getOrThrow()
        assertEquals("Test Book", book.title)
        assertEquals("Test Author", book.author)
        assertEquals("en", book.language)
        assertEquals(2, book.spine.size)
        assertEquals("OEBPS/ch1.xhtml", book.spine[0].href)
        assertEquals("Chapter One", book.spine[0].tocTitle)
        assertEquals("Chapter Two", book.spine[1].tocTitle)
        assertTrue(book.warnings.isEmpty())
    }

    @Test
    fun epub3_navOnly_titlesComeFromNav() {
        val target = File(temp.root, "epub3.epub")
        epub3NavOnly(target)
        val book = EpubContainerReader.read(target).getOrThrow()
        assertEquals("Test Book", book.title)
        assertEquals(2, book.spine.size)
        assertEquals("Chapter One", book.spine[0].tocTitle)
        assertEquals("Chapter Two", book.spine[1].tocTitle)
    }

    @Test
    fun tocConflict_navWinsOverNcx() {
        val target = File(temp.root, "conflict.epub")
        writeZip(
            target,
            mapOf(
                "mimetype" to "application/epub+zip".toByteArray(Charsets.UTF_8),
                "META-INF/container.xml" to containerXml("OEBPS/content.opf"),
                "OEBPS/content.opf" to opfEpub3(
                    "Test Book", "Test Author", "en",
                    includeNcx = true
                ),
                "OEBPS/ch1.xhtml" to chapterXhtml("Chapter One", "First chapter text here."),
                "OEBPS/ch2.xhtml" to chapterXhtml("Chapter Two", "Second chapter text here."),
                "OEBPS/nav.xhtml" to navXhtml(
                    listOf(
                        Pair("ch1.xhtml", "Nav Title One"),
                        Pair("ch2.xhtml", "Nav Title Two")
                    )
                ),
                "OEBPS/toc.ncx" to ncxXml(
                    listOf(
                        Pair("ch1.xhtml", "Ncx Title One"),
                        Pair("ch2.xhtml", "Ncx Title Two")
                    )
                )
            )
        )
        val book = EpubContainerReader.read(target).getOrThrow()
        // Same basenames disagree: nav is read first and wins (first-wins map).
        assertEquals("Nav Title One", book.spine[0].tocTitle)
        assertEquals("Nav Title Two", book.spine[1].tocTitle)
        assertEquals("Nav Title One", book.tocTitles["ch1.xhtml"])
        assertEquals("Nav Title Two", book.tocTitles["ch2.xhtml"])
    }

    @Test
    fun tocHrefFragment_strippedToBasename() {
        val target = File(temp.root, "frag.epub")
        epub2NcxOnly(
            target,
            tocPairs = listOf(
                Pair("ch1.xhtml#sec1", "Chapter One"),
                Pair("Text/ch2.xhtml#sec2", "Chapter Two")
            )
        )
        val book = EpubContainerReader.read(target).getOrThrow()
        // Basename without fragment still matches the spine hrefs.
        assertEquals("Chapter One", book.spine[0].tocTitle)
        // Second TOC href lives under a different directory, so its
        // basename still matches ch2.xhtml (first-wins per basename).
        assertEquals("Chapter Two", book.spine[1].tocTitle)
    }

    @Test
    fun spineOrder_followsSpineNotManifest() {
        val target = File(temp.root, "order.epub")
        // Manifest lists ch1 then ch2, but the spine is reversed.
        writeZip(
            target,
            mapOf(
                "mimetype" to "application/epub+zip".toByteArray(Charsets.UTF_8),
                "META-INF/container.xml" to containerXml("OEBPS/content.opf"),
                "OEBPS/content.opf" to opfEpub3(
                    "Test Book", "Test Author", "en",
                    spineOrder = listOf("ch2", "ch1")
                ),
                "OEBPS/ch1.xhtml" to chapterXhtml("Chapter One", "First."),
                "OEBPS/ch2.xhtml" to chapterXhtml("Chapter Two", "Second."),
                "OEBPS/nav.xhtml" to navXhtml(
                    listOf(
                        Pair("ch1.xhtml", "Chapter One"),
                        Pair("ch2.xhtml", "Chapter Two")
                    )
                )
            )
        )
        val book = EpubContainerReader.read(target).getOrThrow()
        assertEquals(
            listOf("OEBPS/ch2.xhtml", "OEBPS/ch1.xhtml"),
            book.spine.map { it.href }
        )
        assertEquals("Chapter Two", book.spine[0].tocTitle)
        assertEquals("Chapter One", book.spine[1].tocTitle)
    }

    // ---- Cover ----

    private fun jpegBytes(): ByteArray {
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            "fake-jpeg-cover".toByteArray(Charsets.UTF_8)
    }

    private fun pngBytes(): ByteArray {
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) +
            "fake-png".toByteArray(Charsets.UTF_8)
    }

    @Test
    fun cover_jpegFoundViaCoverImageProperty() {
        val target = File(temp.root, "cover.epub")
        writeZip(
            target,
            mapOf(
                "mimetype" to "application/epub+zip".toByteArray(Charsets.UTF_8),
                "META-INF/container.xml" to containerXml("OEBPS/content.opf"),
                "OEBPS/content.opf" to (
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"id\" version=\"3.0\">" +
                        "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                        "<dc:title>Test Book</dc:title><dc:language>en</dc:language>" +
                        "<dc:identifier id=\"id\">test-id</dc:identifier></metadata>" +
                        "<manifest>" +
                        "<item id=\"ch1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                        "<item id=\"cover\" href=\"cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>" +
                        "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" +
                        "</manifest>" +
                        "<spine><itemref idref=\"ch1\"/></spine></package>"
                    ).toByteArray(Charsets.UTF_8),
                "OEBPS/ch1.xhtml" to chapterXhtml("Chapter One", "Text."),
                "OEBPS/nav.xhtml" to navXhtml(listOf(Pair("ch1.xhtml", "Chapter One"))),
                "OEBPS/cover.jpg" to jpegBytes()
            )
        )
        val book = EpubContainerReader.read(target).getOrThrow()
        assertEquals("OEBPS/cover.jpg", book.coverEntry)
        val cover = EpubContainerReader.readCoverBytes(target, book)
        assertNotNull("cover bytes must be returned", cover)
        assertEquals(0xFF.toByte(), cover!![0])
        assertEquals(0xD8.toByte(), cover[1])
    }

    @Test
    fun cover_absent_returnsNull() {
        val target = File(temp.root, "nocover.epub")
        epub3NavOnly(target)
        val book = EpubContainerReader.read(target).getOrThrow()
        assertNull(book.coverEntry)
        assertNull(EpubContainerReader.readCoverBytes(target, book))
    }

    @Test
    fun cover_pngOnly_ignored() {
        val target = File(temp.root, "pngcover.epub")
        writeZip(
            target,
            mapOf(
                "mimetype" to "application/epub+zip".toByteArray(Charsets.UTF_8),
                "META-INF/container.xml" to containerXml("OEBPS/content.opf"),
                "OEBPS/content.opf" to (
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"id\" version=\"3.0\">" +
                        "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                        "<dc:title>Test Book</dc:title><dc:language>en</dc:language>" +
                        "<dc:identifier id=\"id\">test-id</dc:identifier></metadata>" +
                        "<manifest>" +
                        "<item id=\"ch1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                        "<item id=\"cover\" href=\"cover.png\" media-type=\"image/png\" properties=\"cover-image\"/>" +
                        "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" +
                        "</manifest>" +
                        "<spine><itemref idref=\"ch1\"/></spine></package>"
                    ).toByteArray(Charsets.UTF_8),
                "OEBPS/ch1.xhtml" to chapterXhtml("Chapter One", "Text."),
                "OEBPS/nav.xhtml" to navXhtml(listOf(Pair("ch1.xhtml", "Chapter One"))),
                "OEBPS/cover.png" to pngBytes()
            )
        )
        val book = EpubContainerReader.read(target).getOrThrow()
        assertNull("only JPEG covers are returned", book.coverEntry)
        assertNull(EpubContainerReader.readCoverBytes(target, book))
    }

    // ---- Nav exact-stem rule and non-linear flag ----

    @Test
    fun navalHistoryChapter_survivesWhileNavFlagged() {
        val target = File(temp.root, "naval.epub")
        writeZip(
            target,
            mapOf(
                "mimetype" to "application/epub+zip".toByteArray(Charsets.UTF_8),
                "META-INF/container.xml" to containerXml("OEBPS/content.opf"),
                "OEBPS/content.opf" to (
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"id\" version=\"3.0\">" +
                        "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                        "<dc:title>Naval Test</dc:title><dc:language>en</dc:language>" +
                        "<dc:identifier id=\"id\">test-id</dc:identifier></metadata>" +
                        "<manifest>" +
                        "<item id=\"naval\" href=\"naval-history.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                        "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" +
                        "</manifest>" +
                        "<spine><itemref idref=\"nav\"/><itemref idref=\"naval\"/></spine></package>"
                    ).toByteArray(Charsets.UTF_8),
                "OEBPS/naval-history.xhtml" to chapterXhtml("Naval History", "Harbor text."),
                "OEBPS/nav.xhtml" to navXhtml(listOf(Pair("naval-history.xhtml", "Naval History")))
            )
        )
        val book = EpubContainerReader.read(target).getOrThrow()
        assertEquals(2, book.spine.size)
        assertTrue("nav.xhtml must be flagged nav", book.spine[0].isNav)
        assertFalse("naval-history must stay content", book.spine[1].isNav)
        assertEquals("Naval History", book.spine[1].tocTitle)
    }

    @Test
    fun nonLinear_spineItemPreservedWithFlag() {
        val target = File(temp.root, "nonlinear.epub")
        writeZip(
            target,
            mapOf(
                "mimetype" to "application/epub+zip".toByteArray(Charsets.UTF_8),
                "META-INF/container.xml" to containerXml("OEBPS/content.opf"),
                "OEBPS/content.opf" to (
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"id\" version=\"3.0\">" +
                        "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                        "<dc:title>Test Book</dc:title><dc:language>en</dc:language>" +
                        "<dc:identifier id=\"id\">test-id</dc:identifier></metadata>" +
                        "<manifest>" +
                        "<item id=\"ch1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                        "<item id=\"hidden\" href=\"hidden.xhtml\" media-type=\"application/xhtml+xml\"/>" +
                        "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" +
                        "</manifest>" +
                        "<spine><itemref idref=\"ch1\"/>" +
                        "<itemref idref=\"hidden\" linear=\"no\"/>" +
                        "<itemref idref=\"nav\" linear=\"no\"/></spine></package>"
                    ).toByteArray(Charsets.UTF_8),
                "OEBPS/ch1.xhtml" to chapterXhtml("Chapter One", "Text."),
                "OEBPS/hidden.xhtml" to chapterXhtml("Hidden", "Hidden text."),
                "OEBPS/nav.xhtml" to navXhtml(listOf(Pair("ch1.xhtml", "Chapter One")))
            )
        )
        val book = EpubContainerReader.read(target).getOrThrow()
        assertEquals(3, book.spine.size)
        assertTrue(book.spine[0].isLinear)
        assertFalse("hidden cover page must keep linear=no", book.spine[1].isLinear)
        assertFalse(book.spine[2].isLinear)
    }

    // ---- Metadata fallbacks and language warning ----

    @Test
    fun nonEnglish_warnsButSucceeds() {
        val target = File(temp.root, "french.epub")
        epub3NavOnly(target, title = "Livre", language = "fr")
        val book = EpubContainerReader.read(target).getOrThrow()
        assertEquals("fr", book.language)
        assertTrue(
            "expected language warning, got: ${book.warnings}",
            book.warnings.any { it.contains("fr") && it.contains("English") }
        )
    }

    @Test
    fun englishVariant_noWarning() {
        val target = File(temp.root, "enus.epub")
        epub3NavOnly(target, language = "en-US")
        val book = EpubContainerReader.read(target).getOrThrow()
        assertEquals("en-US", book.language)
        assertTrue("expected no warnings, got: ${book.warnings}", book.warnings.isEmpty())
    }

    @Test
    fun missingTitle_fallsBackToFileStem() {
        val target = File(temp.root, "notitled.epub")
        epub3NavOnly(target, title = null)
        val book = EpubContainerReader.read(target).getOrThrow()
        assertEquals("notitled", book.title)
        assertEquals("Test Author", book.author)
    }

    // ---- Malformed archives ----

    @Test
    fun traversalEntry_rejectedWithFileAndRule() {
        val target = File(temp.root, "evil.epub")
        writeZip(
            target,
            mapOf(
                "mimetype" to "application/epub+zip".toByteArray(Charsets.UTF_8),
                "META-INF/container.xml" to containerXml("OEBPS/content.opf"),
                "OEBPS/content.opf" to opfEpub2("T", "A", "en"),
                "OEBPS/ch1.xhtml" to chapterXhtml("One", "Text."),
                "OEBPS/ch2.xhtml" to chapterXhtml("Two", "Text."),
                "OEBPS/toc.ncx" to ncxXml(
                    listOf(Pair("ch1.xhtml", "One"), Pair("ch2.xhtml", "Two"))
                ),
                "../evil.txt" to "evil".toByteArray(Charsets.UTF_8)
            )
        )
        val result = EpubContainerReader.read(target)
        assertTrue("traversal must fail", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue("must name the epub file, got: $message", message.contains("evil.epub"))
        assertTrue("must mention traversal, got: $message", message.contains(".."))
    }

    @Test
    fun oversizeEntry_rejectedWithFileAndRule() {
        val target = File(temp.root, "big.epub")
        epub3NavOnly(target)
        val result = EpubContainerReader.readWithCaps(target, 100L, 10L * 1024L * 1024L)
        assertTrue("oversize must fail", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue("must name per-entry limit, got: $message", message.contains("per-entry limit"))
    }

    @Test
    fun totalSize_rejectedWithFileAndRule() {
        val target = File(temp.root, "total.epub")
        epub3NavOnly(target)
        val result = EpubContainerReader.readWithCaps(target, 10L * 1024L * 1024L, 50L)
        assertTrue("total oversize must fail", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue("must name total limit, got: $message", message.contains("total"))
    }

    @Test
    fun spineBytes_oversizeCap_reportsFileAndRule() {
        val target = File(temp.root, "spinebig.epub")
        epub3NavOnly(target)
        val book = EpubContainerReader.read(target).getOrThrow()
        val result = EpubContainerReader.readSpineBytesWithCaps(target, book.spine[0].href, 10L)
        assertTrue("spine oversize must fail", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue("must name per-entry limit, got: $message", message.contains("per-entry limit"))
    }

    @Test
    fun streamingCap_reportsBytesBeyondLimit() {
        val target = File(temp.root, "stream.epub")
        epub3NavOnly(target)
        val book = EpubContainerReader.read(target).getOrThrow()
        val bytes = EpubContainerReader.readSpineBytes(target, book.spine[0].href).getOrThrow()
        assertTrue("chapter must be non-empty", bytes.isNotEmpty())
        // Direct streaming check: a cap below the real size must throw.
        val zip = java.util.zip.ZipFile(target)
        try {
            val entry = zip.getEntry(book.spine[0].href)!!
            var thrown = false
            try {
                EpubContainerReader.readEntryCapped(zip, entry, 10L)
            } catch (e: Exception) {
                thrown = true
                assertTrue(
                    "must be oversize, got: ${e.message}",
                    (e.message ?: "").contains("oversize")
                )
            }
            assertTrue("expected oversize throw", thrown)
        } finally {
            zip.close()
        }
    }

    @Test
    fun missingOpf_rejectedWithFileAndRule() {
        val target = File(temp.root, "missingopf.epub")
        writeZip(
            target,
            mapOf(
                "mimetype" to "application/epub+zip".toByteArray(Charsets.UTF_8),
                "META-INF/container.xml" to containerXml("OEBPS/missing.opf"),
                "OEBPS/ch1.xhtml" to chapterXhtml("One", "Text.")
            )
        )
        val result = EpubContainerReader.read(target)
        assertTrue("missing OPF must fail", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue("must name the OPF path, got: $message", message.contains("missing.opf"))
    }

    @Test
    fun missingContainer_rejectedWithFileAndRule() {
        val target = File(temp.root, "nocontainer.epub")
        writeZip(
            target,
            mapOf(
                "OEBPS/ch1.xhtml" to chapterXhtml("One", "Text.")
            )
        )
        val result = EpubContainerReader.read(target)
        assertTrue("missing container must fail", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue("must name container.xml, got: $message", message.contains("container.xml"))
    }

    @Test
    fun corruptOpf_rejectedWithFileAndRule() {
        val target = File(temp.root, "badopf.epub")
        writeZip(
            target,
            mapOf(
                "mimetype" to "application/epub+zip".toByteArray(Charsets.UTF_8),
                "META-INF/container.xml" to containerXml("OEBPS/content.opf"),
                "OEBPS/content.opf" to "<package><unclosed>".toByteArray(Charsets.UTF_8)
            )
        )
        val result = EpubContainerReader.read(target)
        assertTrue("corrupt OPF must fail", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue("must name the OPF path, got: $message", message.contains("content.opf"))
    }

    @Test
    fun encrypted_refusedWithDrmMessage() {
        val target = File(temp.root, "drm.epub")
        val entries = mutableMapOf(
            "mimetype" to "application/epub+zip".toByteArray(Charsets.UTF_8),
            "META-INF/container.xml" to containerXml("OEBPS/content.opf"),
            "OEBPS/content.opf" to opfEpub2("T", "A", "en"),
            "OEBPS/ch1.xhtml" to chapterXhtml("One", "Text."),
            "OEBPS/ch2.xhtml" to chapterXhtml("Two", "Text."),
            "OEBPS/toc.ncx" to ncxXml(
                listOf(Pair("ch1.xhtml", "One"), Pair("ch2.xhtml", "Two"))
            )
        )
        entries["META-INF/encryption.xml"] =
            "<encryption/>".toByteArray(Charsets.UTF_8)
        writeZip(target, entries)
        val result = EpubContainerReader.read(target)
        assertTrue("encrypted book must fail", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue("must name encryption.xml, got: $message", message.contains("encryption.xml"))
        assertTrue("must say DRM, got: $message", message.contains("DRM"))
    }

    @Test
    fun corruptZip_rejectedWithFileAndRule() {
        val target = File(temp.root, "corrupt.epub")
        target.writeText("this is not a zip archive", Charsets.UTF_8)
        val result = EpubContainerReader.read(target)
        assertTrue("corrupt zip must fail", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue("must name the epub file, got: $message", message.contains("corrupt.epub"))
        assertTrue("must mention ZIP, got: $message", message.contains("ZIP"))
    }

    @Test
    fun spineBytes_missingEntry_reportsFileAndRule() {
        val target = File(temp.root, "spinemissing.epub")
        epub3NavOnly(target)
        val result = EpubContainerReader.readSpineBytes(target, "OEBPS/nope.xhtml")
        assertTrue("missing spine entry must fail", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue("must name the entry, got: $message", message.contains("nope.xhtml"))
    }

    @Test
    fun spineBytes_traversalName_rejected() {
        val target = File(temp.root, "spinetrav.epub")
        epub3NavOnly(target)
        val result = EpubContainerReader.readSpineBytes(target, "../evil.xhtml")
        assertTrue("traversal spine name must fail", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue("must mention traversal, got: $message", message.contains(".."))
    }

    @Test
    fun checkEntryName_rejectsAbsoluteDriveAndTraversal() {
        assertNotNull(EpubContainerReader.checkEntryName("/abs/path.xhtml"))
        assertNotNull(EpubContainerReader.checkEntryName("C:/win/path.xhtml"))
        assertNotNull(EpubContainerReader.checkEntryName("a/../../b.xhtml"))
        assertNotNull(EpubContainerReader.checkEntryName("../evil.txt"))
        assertNull(EpubContainerReader.checkEntryName("OEBPS/ch1.xhtml"))
        assertNull(EpubContainerReader.checkEntryName("META-INF/container.xml"))
    }

    @Test
    fun sha256_streamsDeterministically() {
        val target = File(temp.root, "hash.epub")
        epub3NavOnly(target)
        val first = EpubContainerReader.sha256Hex(target)
        val second = EpubContainerReader.sha256Hex(target)
        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        val book = EpubContainerReader.read(target).getOrThrow()
        assertEquals(first, book.sha256Hex)
    }

    @Test
    fun danglingSpineIdref_skippedWithWarning() {
        val target = File(temp.root, "dangle.epub")
        writeZip(
            target,
            mapOf(
                "mimetype" to "application/epub+zip".toByteArray(Charsets.UTF_8),
                "META-INF/container.xml" to containerXml("OEBPS/content.opf"),
                "OEBPS/content.opf" to opfEpub3(
                    "Test Book", "Test Author", "en",
                    spineOrder = listOf("ch1", "ghost")
                ),
                "OEBPS/ch1.xhtml" to chapterXhtml("Chapter One", "Text."),
                "OEBPS/ch2.xhtml" to chapterXhtml("Chapter Two", "Text."),
                "OEBPS/nav.xhtml" to navXhtml(listOf(Pair("ch1.xhtml", "Chapter One")))
            )
        )
        val book = EpubContainerReader.read(target).getOrThrow()
        assertEquals(1, book.spine.size)
        assertEquals("OEBPS/ch1.xhtml", book.spine[0].href)
        assertTrue(
            "expected skip warning, got: ${book.warnings}",
            book.warnings.any { it.contains("ghost") && it.contains("skipped") }
        )
    }
}
