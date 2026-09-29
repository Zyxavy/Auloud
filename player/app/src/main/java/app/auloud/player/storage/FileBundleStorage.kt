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
