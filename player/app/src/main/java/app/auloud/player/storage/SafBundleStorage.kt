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

    /** UTF-8 manifest/file text via the backend. Failures propagate; messages never carry raw tokens. */
    override fun readText(path: String): String {
        // requireRelInTree enforces the shared containment check and returns
        // the sanitized rel, so `..` escapes are rejected exactly like audioUri.
        val rel = SafPaths.requireRelInTree(path, treeUri)
        return backend.readText(rel)
    }

    override fun exists(path: String): Boolean {
        val split = SafPaths.splitToken(path) ?: return false
        if (split.first != treeUri || split.second.isBlank()) return false
        val rel = try {
            SafPaths.sanitizeTreeRel(split.second, "bundle path")
        } catch (e: IllegalArgumentException) {
            return false
        }
        return try {
            backend.exists(rel)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Document `Uri` for [relPath] under [bundleDir].
     * @throws IllegalArgumentException if [bundleDir] is not a bundle token
     * of this tree, or [relPath] is blank or escapes the bundle dir via `..`
     * (same containment rule as `FileBundleStorage`; messages use rels and
     * folder labels, never raw tokens).
     */
    override fun audioUri(bundleDir: String, relPath: String): Uri {
        val split = SafPaths.splitToken(bundleDir)
            ?: throw IllegalArgumentException("$bundleDir: not a SAF bundle path")
        if (split.first != treeUri) {
            val rel = split.second.ifBlank { "<root>" }
            throw IllegalArgumentException(
                "$rel: not in this watch folder " +
                    WatchFolders.treeDocumentLabel(treeUri)
            )
        }
        if (split.second.isBlank()) {
            throw IllegalArgumentException(
                "${WatchFolders.treeDocumentLabel(treeUri)}: empty bundle path"
            )
        }
        val bundleRel = try {
            SafPaths.sanitizeTreeRel(split.second, "bundle path")
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("${split.second}: path escapes watch folder", e)
        }
        val fullRel = SafPaths.resolveInBundle(bundleRel, relPath)
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
