package app.auloud.player.storage

import android.net.Uri

/**
 * WP3/WP5 refinement: one [BundleStorage] that routes file paths to
 * [FileBundleStorage] and `content://` roots/tokens to the [SafBundleStorage]
 * for that tree.
 *
 * Routing rule: anything starting with `content://` is SAF (see
 * [SafPaths.isSafPath]); everything else is a file path. `safForTree` builds
 * (or returns a cached) SAF storage for a tree URI — production passes
 * `{ tree -> SafBundleStorage(tree, FrameworkSafBackend(resolver, tree)) }`,
 * tests pass fakes. `java.io.File` still never leaves the file branch.
 *
 * API 24 safe: delegation only, no new APIs.
 */
class RoutingBundleStorage(
    private val fileStorage: BundleStorage = FileBundleStorage(),
    private val safForTree: (treeUri: String) -> BundleStorage
) : BundleStorage {

    override fun listBundleDirs(root: String): List<String> =
        if (SafPaths.isSafPath(root)) {
            safForTree(SafPaths.treeOf(root)).listBundleDirs(root)
        } else {
            fileStorage.listBundleDirs(root)
        }

    override fun readText(path: String): String =
        if (SafPaths.isSafPath(path)) {
            safForTree(SafPaths.treeOf(path)).readText(path)
        } else {
            fileStorage.readText(path)
        }

    override fun exists(path: String): Boolean =
        if (SafPaths.isSafPath(path)) {
            try {
                safForTree(SafPaths.treeOf(path)).exists(path)
            } catch (e: Exception) {
                false
            }
        } else {
            try {
                fileStorage.exists(path)
            } catch (e: Exception) {
                false
            }
        }

    override fun audioUri(bundleDir: String, relPath: String): Uri =
        if (SafPaths.isSafPath(bundleDir)) {
            safForTree(SafPaths.treeOf(bundleDir)).audioUri(bundleDir, relPath)
        } else {
            fileStorage.audioUri(bundleDir, relPath)
        }

    override fun coverUri(bundleDirPath: String, coverRel: String): String? =
        if (SafPaths.isSafPath(bundleDirPath)) {
            try {
                safForTree(SafPaths.treeOf(bundleDirPath)).coverUri(bundleDirPath, coverRel)
            } catch (e: Exception) {
                null
            }
        } else {
            try {
                fileStorage.coverUri(bundleDirPath, coverRel)
            } catch (e: Exception) {
                null
            }
        }
}
