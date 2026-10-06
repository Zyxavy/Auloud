package app.auloud.player.ingest

import app.auloud.player.bundle.ChapterInfo
import app.auloud.player.bundle.Manifest
import app.auloud.player.bundle.SourceInfo
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * IN7: writer for spec 2.0 unrendered books (Slice 9).
 *
 * Writes the book IN3-IN6 produce into a temp folder inside the books
 * root: `manifest.json`, `source/book.epub`, `text/chNNN.json` and the
 * cover. The manifest carries `render_state: none`, `source` with the
 * hash, default `voices` for the two reserved roles, and chapter entries
 * with `text` only (no `audio`, no `duration_ms`). Chapter files carry
 * `spec_version: 2.0`, blocks with untimed sentences (`sid`, `speaker`,
 * `text`, optional `spans`), and no timings anywhere.
 *
 * Quote numbers and split pairs from [ChapterDialogue] are deliberately
 * dropped on write: bundle 2.0 has no fields for them, and Slice 10
 * recomputes tag pauses from the sentences when it renders. No manifest
 * field is invented beyond the spec section 3 shape (IN1's [Manifest]
 * plus the golden fixture pin it).
 *
 * Order inside the temp folder is chapters first, then the source copy,
 * then the cover, then the manifest LAST, so an interrupted temp folder
 * without a manifest is never mistaken for a book. [IngestPipeline]
 * self-validates the temp folder with IN1's validator and loaders before
 * the atomic rename and deletes every residue on failure or cancel.
 *
 * API 24 safe: string building plus kotlinx.serialization JSON only, no
 * `java.time`, no Android classes. Storage writes go through the
 * `BundleStorage` write ops (D-081); `java.io.File` never appears here.
 */
object IngestWriter {

    /** Manifest `generator` value for device imports (golden parity). */
    const val GENERATOR = "auloud-player 2.0"

    /** Source path inside every imported book folder. */
    const val SOURCE_REL = "source/book.epub"

    /** Cover path inside the book folder (only when a cover exists). */
    const val COVER_REL = "cover.jpg"

    /** Manifest file name inside the book folder. */
    const val MANIFEST_REL = "manifest.json"

    private val prettyJson = Json { prettyPrint = true }

    /**
     * Chapter text path for [index] of [total] chapters: `text/ch001.json`
     * (4 digits past 999 chapters, spec section 1).
     */
    fun chapterRel(index: Int, total: Int): String {
        return if (total > 999) {
            "text/ch%04d.json".format(index)
        } else {
            "text/ch%03d.json".format(index)
        }
    }

    /**
     * Everything the manifest needs, before JSON rendering.
     */
    data class ManifestInput(
        val bookId: String,
        val title: String,
        val author: String?,
        val language: String?,
        val sha256Hex: String,
        val coverRel: String?,
        val chapters: List<ChapterEntry>,
        val createdAt: String
    )

    /** One manifest chapter entry (unrendered: title plus text path only). */
    data class ChapterEntry(
        val index: Int,
        val title: String,
        val textRel: String
    )

    /**
     * Builds the [Manifest] model for an unrendered book. The model feeds
     * IN1's validator and the library upsert; [renderManifestJson] renders
     * the byte-identical file form from the same input.
     */
    fun buildManifestModel(input: ManifestInput): Manifest {
        return Manifest(
            specVersion = "2.0",
            id = input.bookId,
            title = input.title,
            type = "epub",
            chapters = input.chapters.map { entry ->
                ChapterInfo(index = entry.index, title = entry.title, text = entry.textRel)
            },
            audio = null,
            renderState = "none",
            author = input.author,
            language = input.language,
            source = SourceInfo(file = SOURCE_REL, sha256 = input.sha256Hex),
            cover = input.coverRel,
            voices = defaultVoices(),
            createdAt = input.createdAt,
            generator = GENERATOR
        )
    }

    /**
     * Default `voices` for the two reserved 2.0 roles (placeholder
     * `system`/`default` values until Slice 10 rendering, golden parity).
     */
    fun defaultVoices(): Map<String, JsonObject> {
        val narrator = buildJsonObject {
            put("engine", "system")
            put("voice", "default")
            put("speed", 1.0)
            put("pitch", 1.0)
        }
        val dialogue = buildJsonObject {
            put("engine", "system")
            put("voice", "default")
            put("speed", 1.0)
            put("pitch", 1.0)
        }
        return mapOf(SPEAKER_NARRATOR to narrator, SPEAKER_DIALOGUE to dialogue)
    }

    /**
     * Renders `manifest.json` for [input]. Keys match the spec section 3
     * example exactly; absent-for-unrendered keys (`audio`, per-chapter
     * `audio`/`duration_ms`) are omitted, never null.
     */
    fun renderManifestJson(input: ManifestInput): String {
        val root = buildJsonObject {
            put("spec_version", "2.0")
            put("render_state", "none")
            put("id", input.bookId)
            put("title", input.title)
            if (input.author != null) put("author", input.author)
            if (input.language != null) put("language", input.language)
            put("type", "epub")
            putJsonObject("source") {
                put("file", SOURCE_REL)
                put("sha256", input.sha256Hex)
            }
            if (input.coverRel != null) put("cover", input.coverRel)
            putJsonObject("voices") {
                put(SPEAKER_NARRATOR, defaultVoices().getValue(SPEAKER_NARRATOR))
                put(SPEAKER_DIALOGUE, defaultVoices().getValue(SPEAKER_DIALOGUE))
            }
            putJsonArray("chapters") {
                for (entry in input.chapters) {
                    add(
                        buildJsonObject {
                            put("index", entry.index)
                            put("title", entry.title)
                            put("text", entry.textRel)
                        }
                    )
                }
            }
            put("created_at", input.createdAt)
            put("generator", GENERATOR)
        }
        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * Renders one `text/chNNN.json` from a tagged chapter. Sentences are
     * untimed (`sid`, `speaker`, `text`, `spans` only); quote numbers and
     * split pairs ride no field and are dropped here by design.
     */
    fun renderChapterJson(chapter: IngestChapter, dialogue: ChapterDialogue): String {
        require(dialogue.chapter == chapter.index) {
            "chapter ${chapter.index}: dialogue is for chapter ${dialogue.chapter}"
        }
        val byBlock = dialogue.blocks.associateBy { it.block }
        val root = buildJsonObject {
            put("spec_version", "2.0")
            put("chapter", chapter.index)
            put("title", chapter.title)
            putJsonArray("blocks") {
                for ((pos, block) in chapter.blocks.withIndex()) {
                    val blockId = pos + 1
                    val tagged = byBlock[blockId]?.sentences ?: emptyList()
                    when (block.kind) {
                        BLOCK_HEADING -> {
                            add(
                                buildJsonObject {
                                    put("id", blockId)
                                    put("type", BLOCK_HEADING)
                                    put("level", block.level ?: 1)
                                    put("text", block.text)
                                    putJsonArray("sentences") {
                                        for (sentence in tagged) addSentence(sentence)
                                    }
                                }
                            )
                        }
                        BLOCK_PARA, BLOCK_QUOTE -> {
                            add(
                                buildJsonObject {
                                    put("id", blockId)
                                    put("type", block.kind)
                                    putJsonArray("sentences") {
                                        for (sentence in tagged) addSentence(sentence)
                                    }
                                }
                            )
                        }
                        else -> {
                            add(
                                buildJsonObject {
                                    put("id", blockId)
                                    put("type", BLOCK_BREAK)
                                }
                            )
                        }
                    }
                }
            }
        }
        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addSentence(sentence: TaggedSentence) {
        add(
            buildJsonObject {
                put("sid", sentence.sid)
                put("speaker", sentence.speaker)
                put("text", sentence.text)
                if (sentence.spans.isNotEmpty()) {
                    putJsonArray("spans") {
                        for (span in sentence.spans) {
                            add(
                                buildJsonObject {
                                    put("start", span.start)
                                    put("end", span.end)
                                    put("style", span.style)
                                }
                            )
                        }
                    }
                }
            }
        )
    }

    /**
     * Writes one full book into [tempDir] through [storage]: chapter files
     * first, then the source EPUB copy, then the cover, then the manifest
     * last. [onChapterWritten] runs after each chapter file lands, so the
     * caller ([IngestPipeline]) can emit per-chapter progress and honor
     * cooperative cancellation between chapters. Returns the manifest
     * model plus its file form. Throws [IOException] naming the file and
     * the rule on any write failure; the caller deletes the temp residue.
     */
    suspend fun writeBook(
        storage: app.auloud.player.storage.BundleStorage,
        tempDir: String,
        manifestInput: ManifestInput,
        chapters: List<IngestChapter>,
        dialogues: Map<Int, ChapterDialogue>,
        sourcePath: String,
        coverBytes: ByteArray?,
        onChapterWritten: suspend (IngestChapter) -> Unit = {}
    ): Manifest {
        val manifest = buildManifestModel(manifestInput)
        val total = chapters.size
        for (chapter in chapters) {
            val dialogue = dialogues[chapter.index]
                ?: throw IOException(
                    "text/${chapterRel(chapter.index, total)}: " +
                        "missing dialogue tags for chapter ${chapter.index} (internal error)"
                )
            val rel = chapterRel(chapter.index, total)
            storage.writeText(join(tempDir, rel), renderChapterJson(chapter, dialogue))
            onChapterWritten(chapter)
        }
        storage.copySourceFile(sourcePath, join(tempDir, SOURCE_REL))
        if (coverBytes != null) {
            storage.writeBytes(join(tempDir, COVER_REL), coverBytes)
        }
        storage.writeText(join(tempDir, MANIFEST_REL), renderManifestJson(manifestInput))
        return manifest
    }

    private fun join(dir: String, rel: String): String =
        dir.trimEnd('/') + '/' + rel.trimStart('/')
}
