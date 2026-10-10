package app.auloud.player.ingest

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader

/**
 * IN3: EPUB container reader (Slice 9).
 *
 * Reads an EPUB as a ZIP archive with the Slice 9 safety limits (plan
 * decision 9): entries are read by name (never extracted to arbitrary
 * paths), names containing `..` or absolute paths are rejected, per-entry
 * and total uncompressed sizes are capped, and `META-INF/encryption.xml`
 * refuses the book as DRM with a clear message.
 *
 * Container steps: `META-INF/container.xml` gives the OPF path; the OPF
 * gives Dublin Core metadata (title, author, language), the manifest
 * (id, href, media-type, properties) and the spine (idrefs in order with
 * linear flags). The table of contents comes from both the EPUB 3 nav
 * document (`properties="nav"`) and the older NCX
 * (`application/x-dtbncx+xml` or the spine `toc` reference); entries are
 * flattened in document order to a first-wins map keyed by lowercase
 * basename without fragment, nav first so nav wins on conflict. Cover
 * lookup mirrors Scribe's writer (`scribe/bundle/writer.py`
 * `extract_cover`): the OPF `cover` meta id first, then `cover-image`
 * properties, then the first JPEG-sniffed image; only JPEG bytes
 * (`FF D8`) are ever returned, anything else means no cover. SHA-256 of
 * the source bytes streams (Scribe `draft.sha256_of_file` parity, 8 KB
 * chunks, lowercase hex).
 *
 * Handoff for IN4: [EpubBook.spine] is the ordered spine-document list.
 * Each [EpubSpineDocument] carries the zip entry path in [EpubSpineDocument.href]
 * plus `isLinear`, `isNav` and `tocTitle`. IN4 reads one document at a
 * time with [readSpineBytes] (never hold all docs), drops nav and
 * non-linear items per the ingestion rules, computes `hasImages` from the
 * bytes itself, and falls back to first heading or `Chapter N` when
 * `tocTitle` is null. No HTML parsing happens here (jsoup lands in IN4);
 * the small container/OPF/NCX/nav XML files use the platform XML parser.
 *
 * Errors name the file and the rule, e.g.
 * `book.epub: found META-INF/encryption.xml (DRM-protected books are not supported)`
 * or `EPUB/content.opf: spine has no itemrefs (missing spine)`.
 * Warnings (non-fatal, in [EpubBook.warnings]) cover the non-English
 * language notice, skipped spine items and unparsable TOC files. A
 * missing cover is normal and never warns. Title falls back to the file
 * stem and author to null (Scribe `read_book_metadata` parity), never
 * failing.
 *
 * Seams: [readSpineBytes] and [readCoverBytes] enforce only the entry
 * name and per-entry caps; they do not re-check
 * `META-INF/encryption.xml` or the total uncompressed cap, so callers
 * must go through [read] first and only fetch entries from a book it
 * accepted. Entries with unknown size (-1) are skipped in the declared
 * total; per-entry streaming caps still bound each read.
 *
 * API 24 safe: `java.io`, `java.util.zip`, `javax.xml`, `java.security`
 * and `java.net.URLDecoder` only. No `java.time`, no `java.nio.file`, no Android classes, so this
 * is JVM-testable. No new dependencies.
 */
object EpubContainerReader {

    /** Per-entry uncompressed cap (32 MiB; rejects zip bombs, allows real images). */
    const val MAX_ENTRY_BYTES: Long = 32L * 1024L * 1024L

    /** Total uncompressed cap (256 MiB; allows illustrated books on 1.5 GB devices). */
    const val MAX_TOTAL_BYTES: Long = 256L * 1024L * 1024L

    /** OPF cover meta name (OPF2 style `<meta name="cover" content="id"/>`). */
    internal const val COVER_META_NAME = "cover"

    /** OPF properties token for the EPUB3 cover image. */
    internal const val COVER_IMAGE_PROPERTY = "cover-image"

    /** OPF properties token marking the EPUB3 nav document. */
    internal const val NAV_PROPERTY = "nav"

    /** NCX media-type (EPUB2 TOC). */
    internal const val NCX_MEDIA_TYPE = "application/x-dtbncx+xml"

    /** Container descriptor path. */
    internal const val CONTAINER_PATH = "META-INF/container.xml"

    /** DRM marker; presence refuses the book. */
    internal const val ENCRYPTION_PATH = "META-INF/encryption.xml"

    /** JPEG magic (cover sniffing, Scribe parity). */
    internal val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

    /**
     * Reads container metadata for [epubFile] (TOC, cover entry, SHA).
     * Spine document bytes are NOT loaded here; IN4 fetches one at a
     * time via [readSpineBytes]. Returns failure with a file+rule
     * message for: missing file, bad ZIP, traversal names, oversize
     * archive, DRM, missing/corrupt container.xml or OPF, or an OPF
     * with no usable manifest/spine.
     */
    fun read(epubFile: File): Result<EpubBook> {
        return readWithCaps(epubFile, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES)
    }

    /**
     * Test seam: same as [read] with explicit caps so JVM tests trigger
     * the oversize paths with small files (no 32 MB fixtures). Production
     * callers use [read] (fixed caps above).
     */
    internal fun readWithCaps(epubFile: File, maxEntry: Long, maxTotal: Long): Result<EpubBook> {
        if (!epubFile.isFile) {
            return Result.failure(
                IOException("${epubFile.name}: cannot read source file (missing EPUB)")
            )
        }
        val sha: String
        try {
            sha = sha256Hex(epubFile)
        } catch (e: IOException) {
            return Result.failure(
                IOException("${epubFile.name}: cannot read source file (${e.message})", e)
            )
        } catch (e: SecurityException) {
            return Result.failure(
                IOException("${epubFile.name}: cannot read source file (${e.message})", e)
            )
        }
        val zip: ZipFile
        try {
            zip = ZipFile(epubFile)
        } catch (e: IOException) {
            return Result.failure(
                IOException("${epubFile.name}: not a valid ZIP archive (${e.message})", e)
            )
        } catch (e: SecurityException) {
            return Result.failure(
                IOException("${epubFile.name}: cannot open ZIP archive (${e.message})", e)
            )
        }
        try {
            return readFromZip(epubFile.name, zip, sha, maxEntry, maxTotal)
        } finally {
            zip.closeQuietly()
        }
    }

    /**
     * Reads one spine document's bytes by zip entry path (IN4 calls this
     * once per spine item). Enforces the traversal and per-entry caps.
     * Failure names the file and the rule, never throws.
     */
    fun readSpineBytes(epubFile: File, entryName: String): Result<ByteArray> {
        return readSpineBytesWithCaps(epubFile, entryName, MAX_ENTRY_BYTES)
    }

    /** Test seam for [readSpineBytes] with an explicit cap. */
    internal fun readSpineBytesWithCaps(
        epubFile: File,
        entryName: String,
        maxEntry: Long
    ): Result<ByteArray> {
        val check = checkEntryName(entryName)
        if (check != null) {
            return Result.failure(
                IOException("${epubFile.name}: entry '$entryName' rejected ($check)")
            )
        }
        val zip: ZipFile
        try {
            zip = ZipFile(epubFile)
        } catch (e: IOException) {
            return Result.failure(
                IOException("${epubFile.name}: not a valid ZIP archive (${e.message})", e)
            )
        }
        try {
            val entry = zip.getEntry(entryName)
                ?: return Result.failure(
                    IOException("${epubFile.name}: entry '$entryName' missing in archive")
                )
            return try {
                Result.success(readEntryCapped(zip, entry, maxEntry))
            } catch (e: OversizeEntryException) {
                Result.failure(
                    IOException(
                        "${epubFile.name}: entry '$entryName' exceeds per-entry limit " +
                            "(size ${e.size} over $maxEntry bytes)"
                    )
                )
            } catch (e: IOException) {
                Result.failure(
                    IOException("${epubFile.name}: entry '$entryName' cannot be read (${e.message})", e)
                )
            }
        } finally {
            zip.closeQuietly()
        }
    }

    /**
     * Cover bytes for [book] (re-opens [epubFile], reads [EpubBook.coverEntry]).
     * Returns null when there is no cover, the entry is missing, oversize,
     * unreadable or not JPEG. Cover problems never fail the import
     * (Scribe parity: `extract_cover` returns null instead of failing).
     */
    fun readCoverBytes(epubFile: File, book: EpubBook): ByteArray? {
        val entryName = book.coverEntry ?: return null
        val result = readSpineBytes(epubFile, entryName)
        if (result.isFailure) return null
        val bytes = result.getOrThrow()
        if (!isJpeg(bytes)) return null
        return bytes
    }

    /** Streaming lowercase hex SHA-256 of a file (Scribe `sha256_of_file` parity). */
    internal fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun isJpeg(bytes: ByteArray): Boolean {
        if (bytes.size < 2) return false
        return bytes[0] == JPEG_MAGIC[0] && bytes[1] == JPEG_MAGIC[1]
    }

    private fun readFromZip(
        fileName: String,
        zip: ZipFile,
        sha: String,
        maxEntry: Long,
        maxTotal: Long
    ): Result<EpubBook> {
        val entries = try {
            zip.entries().toList()
        } catch (e: Exception) {
            return Result.failure(
                IOException("$fileName: cannot list ZIP entries (${e.message})", e as? IOException)
            )
        }
        for (entry in entries) {
            val problem = checkEntryName(entry.name)
            if (problem != null) {
                return Result.failure(
                    IOException("$fileName: entry '${entry.name}' rejected ($problem)")
                )
            }
            val declared = entry.size
            if (declared >= 0 && declared > maxEntry) {
                return Result.failure(
                    IOException(
                        "$fileName: entry '${entry.name}' exceeds per-entry limit " +
                            "(size $declared over $maxEntry bytes)"
                    )
                )
            }
        }
        var total: Long = 0
        for (entry in entries) {
            if (entry.isDirectory) continue
            val size = entry.size
            if (size >= 0) {
                total += size
                if (total > maxTotal) {
                    return Result.failure(
                        IOException(
                            "$fileName: total uncompressed size exceeds limit " +
                                "(over $maxTotal bytes)"
                        )
                    )
                }
            }
        }
        if (zip.getEntry(ENCRYPTION_PATH) != null) {
            return Result.failure(
                IOException(
                    "$fileName: found $ENCRYPTION_PATH " +
                        "(DRM-protected books are not supported)"
                )
            )
        }
        val containerEntry = zip.getEntry(CONTAINER_PATH)
            ?: return Result.failure(
                IOException("$CONTAINER_PATH: missing file in archive (no container descriptor)")
            )
        val containerBytes = try {
            readEntryCapped(zip, containerEntry, maxEntry)
        } catch (e: OversizeEntryException) {
            return Result.failure(
                IOException(
                    "$CONTAINER_PATH: entry exceeds per-entry limit " +
                        "(size ${e.size} over $maxEntry bytes)"
                )
            )
        } catch (e: IOException) {
            return Result.failure(
                IOException("$CONTAINER_PATH: cannot be read (${e.message})", e)
            )
        }
        val opfPath = try {
            parseContainerOpfPath(containerBytes)
        } catch (e: EpubParseException) {
            return Result.failure(IOException(e.message, e))
        }
        if (opfPath.isBlank()) {
            return Result.failure(
                IOException("$CONTAINER_PATH: no rootfile with full-path (missing OPF)")
            )
        }
        val opfCheck = checkEntryName(opfPath)
        if (opfCheck != null) {
            return Result.failure(
                IOException("$CONTAINER_PATH: OPF path '$opfPath' rejected ($opfCheck)")
            )
        }
        val opfEntry = zip.getEntry(opfPath)
            ?: return Result.failure(
                IOException("$opfPath: missing file in archive (OPF not found)")
            )
        val opfBytes = try {
            readEntryCapped(zip, opfEntry, maxEntry)
        } catch (e: OversizeEntryException) {
            return Result.failure(
                IOException(
                    "$opfPath: entry exceeds per-entry limit " +
                        "(size ${e.size} over $maxEntry bytes)"
                )
            )
        } catch (e: IOException) {
            return Result.failure(
                IOException("$opfPath: cannot be read (${e.message})", e)
            )
        }
        val opf = try {
            parseOpf(opfBytes, opfPath)
        } catch (e: EpubParseException) {
            return Result.failure(IOException(e.message, e))
        }
        if (opf.manifest.isEmpty()) {
            return Result.failure(
                IOException("$opfPath: manifest missing or empty (no items)")
            )
        }
        if (opf.spine.isEmpty()) {
            return Result.failure(
                IOException("$opfPath: spine missing or empty (no itemrefs)")
            )
        }
        val warnings = ArrayList<String>()
        val language = opf.language
        if (language != null && !isEnglish(language)) {
            warnings.add(
                "$opfPath: language '$language' is not English " +
                    "(English only for v2; continuing with a warning)"
            )
        }
        val tocMap = LinkedHashMap<String, String>()
        tocMap.putAll(readNavToc(zip, opf, warnings, maxEntry))
        for ((key, value) in readNcxToc(zip, opf, warnings, maxEntry)) {
            if (!tocMap.containsKey(key)) tocMap[key] = value
        }
        val spineDocs = ArrayList<EpubSpineDocument>()
        for (ref in opf.spine) {
            val item = opf.manifest[ref.idref]
            if (item == null) {
                warnings.add(
                    "$opfPath: spine idref '${ref.idref}' has no manifest item; skipped"
                )
                continue
            }
            if (item.hrefEntry.isBlank()) {
                warnings.add(
                    "$opfPath: manifest id '${item.id}' has no href; skipped"
                )
                continue
            }
            val base = tocKey(item.hrefEntry)
            val tocTitle = if (base.isEmpty()) null else tocMap[base]
            val isNav = isNavItem(item, item.hrefEntry)
            spineDocs.add(
                EpubSpineDocument(
                    itemId = item.id,
                    href = item.hrefEntry,
                    isLinear = ref.isLinear,
                    isNav = isNav,
                    tocTitle = tocTitle
                )
            )
        }
        val coverEntry = findCoverEntry(zip, opf, opfPath, warnings, maxEntry)
        val fallbackTitle = fileName.substringBeforeLast('.').ifBlank { fileName }
        val title = opf.title?.ifBlank { null } ?: fallbackTitle
        return Result.success(
            EpubBook(
                title = title,
                author = opf.author,
                language = language,
                sha256Hex = sha.lowercase(),
                opfPath = opfPath,
                spine = spineDocs,
                coverEntry = coverEntry,
                warnings = warnings.toList(),
                tocTitles = tocMap.toMap()
            )
        )
    }

    // ---- Safety helpers ----

    /**
     * Rejects absolute paths, drive/UNC forms and any `..` segment.
     * Returns the reason or null when the name is safe. Comparison is
     * on segments split by `/` and `\` so Windows-style traversal is
     * also caught. Empty names are rejected.
     */
    internal fun checkEntryName(name: String): String? {
        if (name.isEmpty()) return "empty entry name"
        if (name.startsWith("/") || name.startsWith("\\")) {
            return "absolute path"
        }
        if (name.contains(":")) {
            return "drive or UNC path"
        }
        val segments = name.split('/', '\\')
        if (segments.any { it == ".." }) {
            return "path traversal ('..' not allowed)"
        }
        return null
    }

    internal class OversizeEntryException(val size: Long) : IOException("oversize: $size")

    internal class EpubParseException(message: String, cause: Throwable? = null) :
        IllegalArgumentException(message, cause)

    internal fun readEntryCapped(zip: ZipFile, entry: ZipEntry, maxBytes: Long): ByteArray {
        zip.getInputStream(entry).use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total: Long = 0
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                total += read
                if (total > maxBytes) throw OversizeEntryException(total)
                out.write(buf, 0, read)
            }
            return out.toByteArray()
        }
    }

    // ---- XML helpers (platform parser, no new dependency) ----

    internal fun newDocumentBuilder(): javax.xml.parsers.DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        factory.isExpandEntityReferences = false
        try {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        } catch (_: Exception) {
        }
        try {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        } catch (_: Exception) {
        }
        try {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        } catch (_: Exception) {
        }
        return factory.newDocumentBuilder()
    }

    internal fun parseXml(bytes: ByteArray, fileLabel: String): org.w3c.dom.Document {
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            throw EpubParseException("$fileLabel: not UTF-8 text (${e.message})", e)
        }
        // Strip a leading BOM if present so the platform parser accepts it.
        val cleaned = if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text
        try {
            val builder = newDocumentBuilder()
            return builder.parse(InputSource(StringReader(cleaned)))
        } catch (e: EpubParseException) {
            throw e
        } catch (e: Exception) {
            throw EpubParseException("$fileLabel: invalid XML (${e.message})", e)
        }
    }

    internal fun childElements(parent: Element): List<Element> {
        val out = ArrayList<Element>()
        val nodes = parent.childNodes
        for (pos in 0 until nodes.length) {
            val node = nodes.item(pos)
            if (node.nodeType == Node.ELEMENT_NODE && node is Element) out.add(node)
        }
        return out
    }

    internal fun descendantElements(root: Element, localName: String): List<Element> {
        val out = ArrayList<Element>()
        fun visit(element: Element) {
            for (child in childElements(element)) {
                if (localTag(child) == localName) out.add(child)
                visit(child)
            }
        }
        visit(root)
        return out
    }

    internal fun localTag(element: Element): String {
        val tag = element.tagName ?: ""
        return if (tag.contains(":")) tag.substringAfterLast(":") else tag
    }

    internal fun attr(element: Element, name: String): String {
        if (element.hasAttribute(name)) return element.getAttribute(name) ?: ""
        // Be tolerant of namespaced forms (e.g. epub:type vs type).
        val attrs = element.attributes ?: return ""
        for (pos in 0 until attrs.length) {
            val item = attrs.item(pos)
            val nodeName = item?.nodeName ?: continue
            if (nodeName == name || nodeName.endsWith(":$name")) {
                return item.nodeValue ?: ""
            }
        }
        return ""
    }

    // ---- container.xml ----

    internal fun parseContainerOpfPath(containerBytes: ByteArray): String {
        val doc = parseXml(containerBytes, CONTAINER_PATH)
        val root = doc.documentElement
            ?: throw EpubParseException("$CONTAINER_PATH: invalid XML (no root element)")
        val rootfiles = descendantElements(root, "rootfile")
        for (element in rootfiles) {
            val path = attr(element, "full-path").trim()
            if (path.isNotEmpty()) return path
        }
        // Fall back to direct children scan for odd-but-valid files.
        for (child in childElements(root)) {
            if (localTag(child) == "rootfiles") {
                for (inner in childElements(child)) {
                    if (localTag(inner) == "rootfile") {
                        val path = attr(inner, "full-path").trim()
                        if (path.isNotEmpty()) return path
                    }
                }
            }
        }
        return ""
    }

    // ---- OPF ----

    internal data class ManifestItem(
        val id: String,
        val hrefEntry: String,
        val mediaType: String,
        val properties: String
    )

    internal data class SpineRef(
        val idref: String,
        val isLinear: Boolean
    )

    internal data class ParsedOpf(
        val title: String?,
        val author: String?,
        val language: String?,
        val manifest: Map<String, ManifestItem>,
        val manifestOrder: List<ManifestItem>,
        val spine: List<SpineRef>,
        val spineTocId: String?,
        val ncxId: String?,
        val coverId: String?
    )

    internal fun parseOpf(opfBytes: ByteArray, opfPath: String): ParsedOpf {
        val doc = parseXml(opfBytes, opfPath)
        val root = doc.documentElement
            ?: throw EpubParseException("$opfPath: invalid OPF (no package element)")
        if (localTag(root) != "package") {
            throw EpubParseException("$opfPath: invalid OPF (root is '${localTag(root)}', want 'package')")
        }
        var title: String? = null
        var author: String? = null
        var language: String? = null
        var coverId: String? = null
        val metadataElements = descendantElements(root, "metadata")
        val metadata = metadataElements.firstOrNull() ?: root
        for (child in childElements(metadata)) {
            when (localTag(child).lowercase()) {
                "title" -> if (title == null) {
                    val text = child.textContent?.trim() ?: ""
                    if (text.isNotEmpty()) title = text
                }
                "creator", "author" -> if (author == null) {
                    val text = child.textContent?.trim() ?: ""
                    if (text.isNotEmpty()) author = text
                }
                "language" -> if (language == null) {
                    val text = child.textContent?.trim() ?: ""
                    if (text.isNotEmpty()) language = text
                }
                "meta" -> {
                    val name = attr(child, "name")
                    if (name.equals(COVER_META_NAME, ignoreCase = true) && coverId == null) {
                        val content = attr(child, "content").trim()
                        if (content.isNotEmpty()) coverId = content
                    }
                }
            }
        }
        val opfDir = dirOf(opfPath)
        val manifest = LinkedHashMap<String, ManifestItem>()
        val manifestOrder = ArrayList<ManifestItem>()
        val manifestRoots = descendantElements(root, "manifest")
        val manifestEl = manifestRoots.firstOrNull()
        if (manifestEl != null) {
            for (item in childElements(manifestEl)) {
                if (localTag(item) != "item") continue
                val id = attr(item, "id").trim()
                if (id.isEmpty()) continue
                val rawHref = attr(item, "href").trim().substringBefore("#")
                if (rawHref.isEmpty()) continue
                val resolved = resolveHref(opfDir, rawHref)
                if (resolved == null || checkEntryName(resolved) != null) continue
                val mediaType = attr(item, "media-type").trim()
                val properties = attr(item, "properties").trim()
                if (!manifest.containsKey(id)) {
                    val manifestItem = ManifestItem(id, resolved, mediaType, properties)
                    manifest[id] = manifestItem
                    manifestOrder.add(manifestItem)
                }
            }
        }
        val spineRefs = ArrayList<SpineRef>()
        var spineTocId: String? = null
        val spineRoots = descendantElements(root, "spine")
        val spineEl = spineRoots.firstOrNull()
        if (spineEl != null) {
            val toc = attr(spineEl, "toc").trim()
            if (toc.isNotEmpty()) spineTocId = toc
            for (item in childElements(spineEl)) {
                if (localTag(item) != "itemref") continue
                val idref = attr(item, "idref").trim()
                if (idref.isEmpty()) continue
                val linearRaw = attr(item, "linear").trim()
                val isLinear = linearRaw.isEmpty() || linearRaw.equals("yes", ignoreCase = true)
                spineRefs.add(SpineRef(idref, isLinear))
            }
        }
        var ncxId: String? = spineTocId
        if (ncxId == null) {
            for (item in manifestOrder) {
                if (item.mediaType.equals(NCX_MEDIA_TYPE, ignoreCase = true)) {
                    ncxId = item.id
                    break
                }
            }
        }
        return ParsedOpf(title, author, language, manifest, manifestOrder, spineRefs, spineTocId, ncxId, coverId)
    }

    internal fun dirOf(opfPath: String): String {
        val slash = opfPath.lastIndexOf('/')
        return if (slash < 0) "" else opfPath.substring(0, slash + 1)
    }

    /**
     * Resolves an OPF-relative href against [opfDir] (both `/`-separated).
     * Returns null when the result would escape or is blank. Percent
     * escapes are decoded once for lookup (raw form tried first by the
     * caller passing the raw href; here the decoded form feeds the zip
     * name match). Fragments are stripped by callers before this.
     */
    internal fun resolveHref(opfDir: String, rawHref: String): String? {
        if (rawHref.isBlank()) return null
        val decoded = try {
            java.net.URLDecoder.decode(rawHref, "UTF-8")
        } catch (_: Exception) {
            rawHref
        }
        val combined = opfDir + decoded
        val parts = ArrayList<String>()
        for (segment in combined.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> {
                    if (parts.isEmpty()) return null
                    parts.removeAt(parts.size - 1)
                }
                else -> parts.add(segment)
            }
        }
        if (parts.isEmpty()) return null
        return parts.joinToString("/")
    }

    internal fun isEnglish(language: String): Boolean {
        val tag = language.trim().lowercase()
        return tag == "en" || tag.startsWith("en-") || tag.startsWith("en_")
    }

    // ---- TOC (nav + NCX) ----

    /**
     * Exact-stem nav rule (ingestion-rules 2.5): a spine item is nav when
     * its manifest properties contain the `nav` token, or when its file
     * stem (lowercased, no directory, query or extension) is exactly
     * `nav`, `toc` or `ncx`. `naval-history.xhtml` stays content.
     */
    internal fun isNavItem(item: ManifestItem, entryName: String): Boolean {
        val tokens = item.properties.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.any { it.equals(NAV_PROPERTY, ignoreCase = true) }) return true
        var base = entryName.substringAfterLast('/').substringBefore('?')
        base = base.substringBefore('#')
        val stem = if (base.contains(".")) base.substringBeforeLast(".") else base
        return stem.lowercase() == "nav" ||
            stem.lowercase() == "toc" ||
            stem.lowercase() == "ncx"
    }

    /**
     * TOC key: lowercase basename without fragment, up to `#`, after the
     * last `/` (Scribe `toc_title_map` parity, first map entry wins per
     * basename). Empty when the href carries no file name.
     */
    internal fun tocKey(href: String): String {
        val noFragment = href.split("#", limit = 2).firstOrNull() ?: ""
        val trimmed = noFragment.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.substringAfterLast('/').lowercase()
    }

    private fun readNavToc(
        zip: ZipFile,
        opf: ParsedOpf,
        warnings: MutableList<String>,
        maxEntry: Long
    ): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val navItems = opf.manifestOrder.filter { item ->
            item.properties.split(Regex("\\s+")).any { it.equals(NAV_PROPERTY, ignoreCase = true) }
        }
        val targets = if (navItems.isNotEmpty()) {
            navItems
        } else {
            // Fallback: an exact-stem nav file present in the manifest but
            // missing the properties token (messy EPUB2-era files).
            opf.manifestOrder.filter { item -> isNavItem(item, item.hrefEntry) }
        }
        for (item in targets) {
            val entry = zip.getEntry(item.hrefEntry) ?: continue
            val bytes = try {
                readEntryCapped(zip, entry, maxEntry)
            } catch (_: Exception) {
                warnings.add(
                    "${item.hrefEntry}: nav TOC cannot be read (titles fall back to headings)"
                )
                continue
            }
            val entries = try {
                parseNavLinks(bytes, item.hrefEntry)
            } catch (e: EpubParseException) {
                warnings.add("${item.hrefEntry}: nav TOC invalid (${e.message})")
                continue
            }
            for ((href, title) in entries) {
                val key = tocKey(href)
                if (key.isEmpty() || title.isBlank()) continue
                if (!map.containsKey(key)) map[key] = title.trim()
            }
            // Only the first readable nav document feeds the map; extra
            // nav files are ignored (first-wins stays deterministic).
            if (entries.isNotEmpty()) break
        }
        return map
    }

    internal fun parseNavLinks(navBytes: ByteArray, fileLabel: String): List<Pair<String, String>> {
        val doc = parseXml(navBytes, fileLabel)
        val root = doc.documentElement
            ?: throw EpubParseException("$fileLabel: invalid nav (no root element)")
        val navs = descendantElements(root, "nav")
        val nav = navs.firstOrNull { element ->
            val epubType = attr(element, "type")
            val role = attr(element, "role")
            epubType.split(Regex("\\s+")).any { it.equals("toc", ignoreCase = true) } ||
                role.split(Regex("\\s+")).any { it.equals("doc-toc", ignoreCase = true) }
        } ?: navs.firstOrNull()
        val scope = nav ?: root
        val links = descendantElements(scope, "a")
        val out = ArrayList<Pair<String, String>>()
        for (link in links) {
            val href = attr(link, "href").trim()
            if (href.isEmpty()) continue
            val title = (link.textContent ?: "").trim()
            if (title.isEmpty()) continue
            out.add(Pair(href, title))
        }
        return out
    }

    private fun readNcxToc(
        zip: ZipFile,
        opf: ParsedOpf,
        warnings: MutableList<String>,
        maxEntry: Long
    ): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val candidates = ArrayList<ManifestItem>()
        val byId = opf.ncxId?.let { opf.manifest[it] }
        if (byId != null) candidates.add(byId)
        for (item in opf.manifestOrder) {
            if (item.mediaType.equals(NCX_MEDIA_TYPE, ignoreCase = true) && !candidates.contains(item)) {
                candidates.add(item)
            }
            val stem = item.hrefEntry.substringAfterLast('/').substringBeforeLast('.', item.hrefEntry)
                .substringAfterLast('/').lowercase()
            if (stem == "ncx" && !candidates.contains(item)) candidates.add(item)
        }
        for (item in candidates) {
            val entry = zip.getEntry(item.hrefEntry) ?: continue
            val bytes = try {
                readEntryCapped(zip, entry, maxEntry)
            } catch (_: Exception) {
                warnings.add(
                    "${item.hrefEntry}: NCX TOC cannot be read (titles fall back to headings)"
                )
                continue
            }
            val entries = try {
                parseNcxLinks(bytes, item.hrefEntry)
            } catch (e: EpubParseException) {
                warnings.add("${item.hrefEntry}: NCX TOC invalid (${e.message})")
                continue
            }
            for ((href, title) in entries) {
                val key = tocKey(href)
                if (key.isEmpty() || title.isBlank()) continue
                if (!map.containsKey(key)) map[key] = title.trim()
            }
            if (entries.isNotEmpty()) break
        }
        return map
    }

    internal fun parseNcxLinks(ncxBytes: ByteArray, fileLabel: String): List<Pair<String, String>> {
        val doc = parseXml(ncxBytes, fileLabel)
        val root = doc.documentElement
            ?: throw EpubParseException("$fileLabel: invalid NCX (no root element)")
        val points = descendantElements(root, "navPoint")
        val out = ArrayList<Pair<String, String>>()
        for (point in points) {
            var title: String? = null
            var src: String? = null
            for (label in descendantElements(point, "navLabel")) {
                for (child in childElements(label)) {
                    if (localTag(child) == "text") {
                        val text = (child.textContent ?: "").trim()
                        if (text.isNotEmpty()) {
                            title = text
                            break
                        }
                    }
                }
                if (title != null) break
            }
            // The point's own content element comes before nested
            // navPoints in valid NCX, so the first descendant wins.
            for (content in descendantElements(point, "content")) {
                val candidate = attr(content, "src").trim()
                if (candidate.isNotEmpty()) {
                    src = candidate
                    break
                }
            }
            if (src != null && title != null && title.isNotBlank()) {
                out.add(Pair(src, title))
            }
        }
        return out
    }

    // ---- Cover ----

    private fun findCoverEntry(
        zip: ZipFile,
        opf: ParsedOpf,
        opfPath: String,
        warnings: MutableList<String>,
        maxEntry: Long
    ): String? {
        val ordered = ArrayList<ManifestItem>()
        val seen = HashSet<String>()
        fun add(item: ManifestItem?) {
            if (item != null && seen.add(item.id)) ordered.add(item)
        }
        if (opf.coverId != null) add(opf.manifest[opf.coverId])
        for (item in opf.manifestOrder) {
            val tokens = item.properties.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.any { it.equals(COVER_IMAGE_PROPERTY, ignoreCase = true) }) add(item)
        }
        for (item in opf.manifestOrder) {
            if (item.mediaType.lowercase().startsWith("image/")) add(item)
        }
        for (item in opf.manifestOrder) {
            val lower = item.hrefEntry.lowercase()
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) add(item)
        }
        for (item in ordered) {
            val entry = zip.getEntry(item.hrefEntry) ?: continue
            if (entry.isDirectory) continue
            val bytes = try {
                readEntryCapped(zip, entry, maxEntry)
            } catch (_: Exception) {
                continue
            }
            if (isJpeg(bytes)) return item.hrefEntry
        }
        // No JPEG cover is normal (fixtures ship none); stay silent so
        // golden imports keep empty warnings. Only note an OPF problem
        // when the declared cover id points nowhere.
        if (opf.coverId != null && !opf.manifest.containsKey(opf.coverId)) {
            warnings.add("$opfPath: cover id '${opf.coverId}' has no manifest item (no cover)")
        }
        return null
    }

    private fun ZipFile.closeQuietly() {
        try {
            close()
        } catch (_: Exception) {
        }
    }
}

/**
 * IN3 handoff type for IN4 (structure/cleaning).
 *
 * One spine item in spine order. [href] is the full zip entry path
 * (e.g. `EPUB/ch1.xhtml`); IN4 fetches its bytes with
 * [EpubContainerReader.readSpineBytes] one document at a time and skips
 * entries where [isLinear] is false (cover etc.) or [isNav] is true
 * (TOC pages) per ingestion-rules 2.2 and 4.1. [tocTitle] is the
 * flattened TOC title for this href basename (nav first, first wins) or
 * null when the TOC has no entry; IN4 falls back to the first heading,
 * else `Chapter N` with the final index (rules 2.4 and 5.1). IN4
 * computes `hasImages` itself from the bytes; this type carries no HTML.
 */
data class EpubSpineDocument(
    val itemId: String,
    val href: String,
    val isLinear: Boolean,
    val isNav: Boolean,
    val tocTitle: String?
)

/**
 * IN3 container result: metadata plus the ordered spine handoff.
 *
 * [title] falls back to the file stem and [author] to null when OPF
 * metadata is missing (Scribe parity, never fails). [language] is the
 * raw OPF `dc:language` value or null. [sha256Hex] is the lowercase hex
 * SHA-256 of the source bytes. [opfPath] is the OPF zip entry path.
 * [spine] is in spine order and keeps non-linear and nav items (IN4
 * skips them, preserving order for the survivors). [coverEntry] is the
 * verified JPEG cover zip path or null when absent. [warnings] holds
 * non-fatal notices (non-English language, skipped spine items,
 * unparsable TOC, dangling cover id), each naming the file and reason.
 * [tocTitles] is the first-wins basename map (nav first) behind the
 * per-document [EpubSpineDocument.tocTitle] values.
 */
data class EpubBook(
    val title: String,
    val author: String?,
    val language: String?,
    val sha256Hex: String,
    val opfPath: String,
    val spine: List<EpubSpineDocument>,
    val coverEntry: String?,
    val warnings: List<String>,
    val tocTitles: Map<String, String>
)
