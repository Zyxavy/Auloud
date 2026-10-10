package app.auloud.player.ingest

import java.io.File

/**
 * IN4: structure/cleaning pipeline wiring IN3 to the cleaner (Slice 9).
 *
 * Contract with IN3: the caller goes through [EpubContainerReader.read]
 * first and passes the accepted [EpubBook] in. Spine bytes are fetched
 * with [EpubContainerReader.readSpineBytes] ONE document at a time;
 * parsed DOMs are never held across documents (only cleaned blocks
 * accumulate, which the tiny-merge needs). The seam skips DRM and total
 * size re-checks by design (see [EpubContainerReader] KDoc), so this
 * pipeline must only ever fetch entries from a book `read` accepted.
 *
 * Two phases keep Scribe's drop order exactly:
 *
 * 1. Spine order: non-linear skips, unreadable items, parse failures and
 *    footnote-strip notices land in drops (one entry per document, in
 *    spine order, before any cleaning drop).
 * 2. Spine order: each parsed document is cleaned ([StructureCleaner]);
 *    surviving raw chapters merge ([StructureCleaner.mergeTiny]) and
 *    renumber ([StructureCleaner.assemble]).
 *
 * Container-level [EpubBook.warnings] (language, dangling idrefs, TOC
 * notices) are copied first; they predate every per-document entry.
 *
 * The [readBytes] seam defaults to the IN3 reader and exists so JVM
 * tests can inject unreadable entries and synthetic bytes without
 * building archives.
 *
 * API 24 safe: `java.io.File` only. No Android classes, JVM-testable.
 */
object EpubStructurePipeline {

    /**
     * Runs structure and cleaning over [book] from [epubFile].
     *
     * Never throws for content reasons: every per-document problem
     * becomes a drop entry naming the file and the reason.
     */
    fun ingest(
        epubFile: File,
        book: EpubBook,
        readBytes: (File, String) -> Result<ByteArray> = { file, entry ->
            EpubContainerReader.readSpineBytes(file, entry)
        }
    ): StructureResult {
        val drops = ArrayList<String>()
        drops.addAll(book.warnings)

        val inputs = ArrayList<SpineDocInput?>()
        for (doc in book.spine) {
            if (!doc.isLinear) {
                drops.add("${doc.href}: skipped non-linear spine item (linear='no')")
                inputs.add(null)
                continue
            }
            val bytes = readBytes(epubFile, doc.href)
            if (bytes.isFailure) {
                val cause = bytes.exceptionOrNull()?.message ?: "unreadable"
                drops.add("${doc.href}: could not read item ($cause)")
                inputs.add(null)
                continue
            }
            val parsed = EpubStructureWalker.parseDocument(bytes.getOrThrow(), doc.href)
            if (parsed.isFailure) {
                val cause = parsed.exceptionOrNull()?.message ?: "unparseable"
                drops.add(cause)
                inputs.add(null)
                continue
            }
            val body = parsed.getOrThrow()
            if (body.footnoteStrips > 0) {
                drops.add("${doc.href}: stripped ${body.footnoteStrips} footnote marker(s)")
            }
            inputs.add(
                SpineDocInput(
                    href = doc.href,
                    tocTitle = doc.tocTitle,
                    isNav = doc.isNav,
                    hasImages = body.hasImages,
                    blocks = body.blocks,
                    footnoteStrips = body.footnoteStrips
                )
            )
        }

        val raw = ArrayList<RawIngestChapter>()
        for (input in inputs) {
            if (input == null) continue
            val chapter = StructureCleaner.cleanDocument(input, drops)
            if (chapter != null) raw.add(chapter)
        }
        val merged = StructureCleaner.mergeTiny(raw, drops)
        return StructureResult(chapters = StructureCleaner.assemble(merged), drops = drops)
    }
}
