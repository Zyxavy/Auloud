package app.auloud.player.storage

import android.net.Uri
import java.io.IOException

/**
 * WP3/WP5 refinement: [BundleStorage] over a persisted SAF tree URI.
 *
 * The tree URI string (kept from the system picker with
 * `takePersistableUriPermission`) is bound at construction. `BundleStorage`
 * paths are opaque tokens `<treeUri>|<relPath>` (see [SafPaths]): callers
 * keep joining with plain `'/'` and this class recovers `(tree, rel)` via
 * [SafPaths.splitToken]. [audioUri] returns the real document `Uri` for
 * ExoPlayer; `java.io.File` never appears here.
 *
 * DEVICE-TEST (user on the Tab E): pick the microSD `Auloud/` folder and a
 * nested folder; confirm bundles list, rescan imports them, and audio plays
 * from the document URIs after a reboot (persisted permission).
 *
 * API 24 safe: `Uri` + [SafBackend] only; DocumentsContract lives in
 * [FrameworkSafBackend].
 */
class SafBundleStorage(
    private val treeUri: String,
    private val backend: SafBackend
) : BundleStorage {

    init {
        require(treeUri.isNotBlank() && SafPaths.isSafPath(treeUri)) {
            "$treeUri: not a tree URI"
        }
    }

    /**
     * Immediate subdirs of the tree that contain a `manifest.json`, as
     * `<treeUri>|<name>` tokens, sorted. A root from another tree yields an
     * empty list. Listing failures (e.g. lost URI permission) propagate so
     * rescan can surface them as skipped-with-reason errors.
     */
    override fun listBundleDirs(root: String): List<String> {
        val tree = SafPaths.treeOf(root)
        if (tree != treeUri) return emptyList()
        return backend.children("")
            .filter { it.isDirectory && isValidBundleName(it.name) }
            .filter { child ->
                try {
                    backend.exists("${child.name}/$MANIFEST_FILE")
                } catch (e: Exception) {
                    false
                }
            }
            .map { SafPaths.bundleToken(treeUri, it.name) }
            .sorted()
    }

    /** UTF-8 manifest/file text via the backend. Failures propagate with the rel in the message. */
    override fun readText(path: String): String {
        val rel = SafPaths.requireRelInTree(path, treeUri)
        return backend.readText(rel)
    }

    override fun exists(path: String): Boolean {
        val split = SafPaths.splitToken(path) ?: return false
        if (split.first != treeUri || split.second.isBlank()) return false
        return try {
            backend.exists(split.second)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Document `Uri` for [relPath] under [bundleDir].
     * @throws IllegalArgumentException if [relPath] is blank or escapes the
     * bundle dir via `..` (same containment rule as `FileBundleStorage`).
     */
    override fun audioUri(bundleDir: String, relPath: String): Uri {
        val split = SafPaths.splitToken(bundleDir)
            ?: throw IllegalArgumentException("$bundleDir: not a SAF bundle path")
        if (split.first != treeUri) {
            throw IllegalArgumentException("$bundleDir: not in this watch folder")
        }
        if (split.second.isBlank()) {
            throw IllegalArgumentException("$bundleDir: blank audio path")
        }
        val fullRel = SafPaths.resolveInBundle(split.second, relPath)
        return Uri.parse(backend.documentUri(fullRel))
    }

    private fun isValidBundleName(name: String): Boolean {
        val n = name.trim().trim('/')
        return n.isNotEmpty() && '/' !in n && n != "." && n != ".."
    }

    companion object {
        private const val MANIFEST_FILE = "manifest.json"

        /** Log tag (`Auloud*`, <= 23 chars for API 24). */
        const val TAG = "AuloudStorage"
    }
}
