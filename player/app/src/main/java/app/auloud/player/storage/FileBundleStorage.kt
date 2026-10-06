package app.auloud.player.storage

import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * WP3: path-based [BundleStorage] using `java.io.File` internally.
 *
 * The SAF version arrives in v3; this class is the only place that touches
 * `java.io.File` for bundle access. API 24 safe: `java.io.File` only, UTF-8
 * text, no `java.time`, no `java.nio.file`.
 */
class FileBundleStorage : BundleStorage {

    /**
     * Lists immediate subdirectories of [root] that look like bundles (i.e.
     * contain a `manifest.json` file). Returns absolute paths, sorted.
     * A missing or unreadable root yields an empty list, never an exception.
     */
    override fun listBundleDirs(root: String): List<String> {
        val children = File(root).listFiles() ?: return emptyList()
        return children
            .filter { it.isDirectory && File(it, "manifest.json").isFile }
            .map { it.absolutePath }
            .sorted()
    }

    /**
     * Reads a text file as UTF-8.
     * @throws IOException if the file is missing or unreadable.
     */
    override fun readText(path: String): String {
        val file = File(path)
        if (!file.isFile) {
            throw IOException("$path: file not found or not readable")
        }
        return file.readText(Charsets.UTF_8)
    }

    override fun exists(path: String): Boolean = File(path).exists()

    /**
     * Resolves [relPath] (e.g. `audio/ch001.mp3`) against [bundleDir] as a
     * `file://` URI.
     * @throws IllegalArgumentException if [relPath] is blank or escapes the
     * bundle dir via `..`.
     */
    override fun audioUri(bundleDir: String, relPath: String): Uri =
        Uri.fromFile(resolveAudioFile(bundleDir, relPath))

    /**
     * CP4: file-path cover resolves to the in-bundle file path. Returns null
     * on blank rel or bundle escape (same containment rule as [audioUri]);
     * callers treat null as "no cover", never an import failure.
     */
    override fun coverUri(bundleDirPath: String, coverRel: String): String? {
        if (coverRel.isBlank()) return null
        return try {
            resolveAudioFile(bundleDirPath, coverRel).absolutePath
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * IN7: file write for the import pipeline. Parents are created;
     * failures throw [IOException] naming the file and the rule.
     */
    override fun writeBytes(path: String, bytes: ByteArray) {
        try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        } catch (e: SecurityException) {
            throw IOException("$path: cannot write file: ${e.message}", e)
        } catch (e: IOException) {
            throw IOException("$path: cannot write file: ${e.message}", e)
        }
    }

    /**
     * IN7: streaming file copy for the import pipeline (the source EPUB
     * is copied into `source/book.epub` without holding it in memory).
     */
    override fun copySourceFile(srcPath: String, dstPath: String) {
        try {
            val src = File(srcPath)
            if (!src.isFile) {
                throw IOException("$srcPath: cannot read source file (missing EPUB)")
            }
            val dst = File(dstPath)
            dst.parentFile?.mkdirs()
            src.inputStream().use { input ->
                dst.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                    }
                }
            }
        } catch (e: IOException) {
            throw IOException("$dstPath: cannot copy source file: ${e.message}", e)
        } catch (e: SecurityException) {
            throw IOException("$dstPath: cannot copy source file: ${e.message}", e)
        }
    }

    /**
     * IN7: atomic-ish rename of the temp import folder to the final book
     * folder (same parent, so `renameTo` does not cross volumes).
     */
    override fun movePath(fromPath: String, toPath: String) {
        try {
            val dst = File(toPath)
            if (dst.exists()) {
                throw IOException("$toPath: book folder already exists (duplicate import?)")
            }
            if (!File(fromPath).renameTo(dst)) {
                throw IOException("$toPath: cannot move temp folder into place")
            }
        } catch (e: SecurityException) {
            throw IOException("$toPath: cannot move temp folder into place: ${e.message}", e)
        }
    }

    /**
     * IN7: best-effort recursive delete for import cleanup. Never throws.
     */
    override fun deleteRecursively(path: String) {
        try {
            File(path).deleteRecursively()
        } catch (_: Exception) {
        }
    }

    /**
     * Pure path half of [audioUri], kept `internal` so unit tests can verify
     * in-bundle containment without the Android framework. Not for use outside
     * the storage layer: callers take the [Uri], never the [File].
     */
    internal fun resolveAudioFile(bundleDir: String, relPath: String): File {
        require(relPath.isNotBlank()) { "$bundleDir: blank audio path" }
        val base = File(bundleDir).canonicalFile
        val target = File(base, relPath).canonicalFile
        if (target != base && !target.path.startsWith(base.path + File.separatorChar)) {
            throw IllegalArgumentException("$bundleDir: audio path escapes bundle dir: $relPath")
        }
        return target
    }
}
