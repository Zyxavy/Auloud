package app.auloud.player.render

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * RN4: spool file seam (Slice 10, D-091).
 *
 * Spool files are temp render workspace (PCM per sentence plus a small
 * per-chapter index), not bundle content, so they stay off the
 * `BundleStorage` interface: RN8 picks the spool dir (internal cache or a
 * `spool/` folder under the book) and this seam keeps the naming, PCM and
 * index logic JVM-testable with an in-memory fake. Production uses
 * [JavaFileSpoolIo] (`java.io.File` only).
 *
 * API 24 safe: plain strings and bytes, no `java.time`, no Android types.
 */
interface SpoolIo {
    fun exists(path: String): Boolean
    fun readText(path: String): String
    fun writeBytes(path: String, bytes: ByteArray)
    fun writeText(path: String, text: String)
    fun deleteIfExists(path: String)
    fun listFiles(dir: String, prefix: String, suffix: String): List<String>
}

/** Production [SpoolIo] over `java.io.File` (API 24 safe). */
class JavaFileSpoolIo : SpoolIo {

    override fun exists(path: String): Boolean = File(path).isFile

    override fun readText(path: String): String {
        val file = File(path)
        if (!file.isFile) throw IOException("$path: file not found or not readable")
        return file.readText(Charsets.UTF_8)
    }

    override fun writeBytes(path: String, bytes: ByteArray) {
        try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        } catch (e: IOException) {
            throw IOException("$path: cannot write spool file (${e.message})", e)
        } catch (e: SecurityException) {
            throw IOException("$path: cannot write spool file (${e.message})", e)
        }
    }

    override fun writeText(path: String, text: String) {
        writeBytes(path, text.toByteArray(Charsets.UTF_8))
    }

    override fun deleteIfExists(path: String) {
        try {
            val file = File(path)
            if (file.isFile) file.delete()
        } catch (_: Exception) {
        }
    }

    override fun listFiles(dir: String, prefix: String, suffix: String): List<String> {
        return try {
            val root = File(dir)
            if (!root.isDirectory) return emptyList()
            root.listFiles()
                ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(suffix) }
                ?.map { it.absolutePath }
                ?.sorted().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * RN4: spool file naming.
 *
 * One PCM per sentence: `chNNN-sMMM-<fpTag>.pcm` (NNN is the 1-based
 * manifest chapter index, MMM the sentence sid, both `%03d` minimum width
 * with no truncation past 999). The fingerprint tag in the name is what
 * makes a mismatch self-invalidating: old files simply never match the
 * current name, so resume never reuses stale audio (RN4 also deletes them
 * best-effort to free disk; RN7 recovery owns the final sweep).
 *
 * The per-chapter index (`chNNN-index.json`) holds the full fingerprint
 * plus one entry per sentence (file name, rate, length, split pair, peak),
 * which is what RN5 assembles from. Paths join with `/` (bundle-dir style,
 * same as [RenderStateStore]).
 */
object SpoolFiles {

    /** `chNNN-sMMM-<fpTag>.pcm` file name for one sentence. */
    fun pcmName(chapterNumber: Int, sid: Int, fpTag: String): String =
        "ch%03d-s%03d-%s.pcm".format(chapterNumber, sid, fpTag)

    /** Absolute spool path for one sentence. */
    fun pcmPath(spoolDir: String, chapterNumber: Int, sid: Int, fpTag: String): String =
        join(spoolDir, pcmName(chapterNumber, sid, fpTag))

    /** Absolute path of the per-chapter spool index. */
    fun indexPath(spoolDir: String, chapterNumber: Int): String =
        join(spoolDir, "ch%03d-index.json".format(chapterNumber))

    /** Absolute path for a spool file name inside [spoolDir]. */
    fun path(spoolDir: String, fileName: String): String =
        join(spoolDir, fileName)

    /** File-name prefix of every spool file of a chapter (stale cleanup). */
    fun chapterPrefix(chapterNumber: Int): String =
        "ch%03d-".format(chapterNumber)

    private fun join(dir: String, name: String): String =
        dir.trimEnd('/') + '/' + name.trimStart('/')
}

/**
 * RN4: 16-bit mono PCM spool encoding (D-091: spool as PCM).
 *
 * Engines return float PCM at their native rate; the spool stores
 * little-endian signed 16-bit mono at that same rate (RN5 resamples to
 * 24 kHz at assembly, reading the rate from the index). Conversion clamps
 * to [-1, 1]; non-finite samples encode as silence so one bad engine
 * value cannot poison the file. Peak measurement skips non-finite values
 * for the same reason; it feeds the D-095 book gain input (measured here,
 * applied in RN5, never in the spool bytes).
 */
object SpoolPcm {

    /** Float [-1, 1] to little-endian 16-bit mono bytes. */
    fun encodeFloatToPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            var value = samples[i]
            if (!value.isFinite()) value = 0f
            value = value.coerceIn(-1f, 1f)
            var code = (value * 32767f).toInt()
            if (code > 32767) code = 32767
            if (code < -32768) code = -32768
            out[i * 2] = (code and 0xFF).toByte()
            out[i * 2 + 1] = ((code shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Decodes little-endian 16-bit mono bytes back to float [-1, 1]. */
    fun decodePcm16(bytes: ByteArray): FloatArray {
        require(bytes.size % 2 == 0) { "pcm16 needs an even byte count, got ${bytes.size}" }
        val out = FloatArray(bytes.size / 2)
        for (i in out.indices) {
            val low = bytes[i * 2].toInt() and 0xFF
            val high = bytes[i * 2 + 1].toInt()
            val code = (high shl 8) or low
            out[i] = code / 32767f
        }
        return out
    }

    /** Peak absolute sample value (0 when empty or all non-finite). */
    fun peakOf(samples: FloatArray): Float {
        var peak = 0f
        for (value in samples) {
            if (!value.isFinite()) continue
            val magnitude = if (value < 0f) -value else value
            if (magnitude > peak) peak = magnitude
        }
        return peak
    }
}

/**
 * RN4: one spooled sentence in the per-chapter index.
 *
 * This is the kind/sid/rate metadata RN5 needs: `sid` plus `role`
 * (narrator/dialogue, the kind encoding) locate the sentence in reading
 * order, `sampleRateHz` plus `samples` size the assembly resample,
 * `splitPair` flags same-block split-pair halves (100 ms tag pause
 * downstream, recomputed via the shared tagger since the bundle carries no
 * split fields), `peak` feeds the D-095 per-role levels, and `file` is the
 * spool PCM name in the same dir.
 */
data class SpoolSentenceEntry(
    val sid: Int,
    val role: String,
    val file: String,
    val sampleRateHz: Int,
    val samples: Int,
    val splitPair: Int?,
    val peak: Float
)

/**
 * RN4: per-chapter spool index (temp JSON, not bundle content).
 *
 * Written incrementally after every sentence, so a kill mid-chapter keeps
 * sentence-level resume: entries present with their PCM on disk are
 * skipped, everything else re-renders. `peaks` is the running per-role max
 * over entries (final values feed D-095); the fingerprint decides whether
 * the whole index is still valid.
 */
data class SpoolChapterIndex(
    val chapter: Int,
    val fingerprint: RenderFingerprint,
    val sentences: List<SpoolSentenceEntry>,
    val peaks: Map<String, Float>
)

/**
 * RN4: spool index JSON read/write (internal shape, versioned).
 *
 * Parsing never throws: corrupt bytes read as null, which the renderer
 * treats as "no valid index" (re-render the chapter, never crash). Floats
 * round-trip exactly (float to double widening is exact, and back).
 */
object SpoolIndex {

    /** Index file format version (bump when the shape changes). */
    const val VERSION = 1

    /** Renders [index] to its file form. */
    fun render(index: SpoolChapterIndex): String {
        val root = buildJsonObject {
            put("version", VERSION)
            put("chapter", index.chapter)
            put("fingerprint", index.fingerprint.toJsonObject())
            putJsonArray("sentences") {
                for (entry in index.sentences.sortedBy { it.sid }) {
                    add(
                        buildJsonObject {
                            put("sid", entry.sid)
                            put("role", entry.role)
                            put("file", entry.file)
                            put("sampleRateHz", entry.sampleRateHz)
                            put("samples", entry.samples)
                            if (entry.splitPair != null) {
                                put("splitPair", entry.splitPair)
                            }
                            put("peak", JsonPrimitive(entry.peak.toDouble()))
                        }
                    )
                }
            }
            putJsonObject("peaks") {
                for ((role, peak) in index.peaks.toSortedMap()) {
                    put(role, JsonPrimitive(peak.toDouble()))
                }
            }
        }
        return kotlinx.serialization.json.Json.encodeToString(
            JsonObject.serializer(),
            root
        )
    }

    /** Parses the file form, or null when the bytes are not a valid index. */
    fun parse(text: String): SpoolChapterIndex? {
        val root = try {
            kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val version = (root["version"] as? JsonPrimitive)?.intOrNull
        if (version != VERSION) return null
        val chapter = (root["chapter"] as? JsonPrimitive)?.intOrNull
            ?.takeIf { it >= 1 } ?: return null
        val fingerprint = (root["fingerprint"] as? JsonObject)
            ?.let { RenderFingerprint.fromJsonObject(it) } ?: return null
        val rawSentences = root["sentences"] as? JsonArray ?: return null
        val sentences = ArrayList<SpoolSentenceEntry>(rawSentences.size)
        for (element in rawSentences) {
            sentences.add(parseEntry(element as? JsonObject ?: return null) ?: return null)
        }
        val peaksObj = root["peaks"] as? JsonObject ?: return null
        val peaks = LinkedHashMap<String, Float>()
        for ((role, value) in peaksObj) {
            val peak = (value as? JsonPrimitive)
                ?.takeIf { !it.isString }?.doubleOrNull
                ?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
            peaks[role] = peak.toFloat()
        }
        return SpoolChapterIndex(
            chapter = chapter,
            fingerprint = fingerprint,
            sentences = sentences,
            peaks = peaks
        )
    }

    private fun parseEntry(obj: JsonObject): SpoolSentenceEntry? {
        val sid = (obj["sid"] as? JsonPrimitive)?.intOrNull
            ?.takeIf { it >= 1 } ?: return null
        val role = (obj["role"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() } ?: return null
        val file = (obj["file"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() } ?: return null
        val rate = (obj["sampleRateHz"] as? JsonPrimitive)?.intOrNull
            ?.takeIf { it > 0 } ?: return null
        val samples = (obj["samples"] as? JsonPrimitive)?.intOrNull
            ?.takeIf { it > 0 } ?: return null
        val splitPair = when (val raw = obj["splitPair"]) {
            null, is kotlinx.serialization.json.JsonNull -> null
            is JsonPrimitive -> raw.intOrNull?.takeIf { it >= 1 } ?: return null
            else -> return null
        }
        val peak = (obj["peak"] as? JsonPrimitive)
            ?.takeIf { !it.isString }?.doubleOrNull
            ?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        return SpoolSentenceEntry(
            sid = sid,
            role = role,
            file = file,
            sampleRateHz = rate,
            samples = samples,
            splitPair = splitPair,
            peak = peak.toFloat()
        )
    }
}
