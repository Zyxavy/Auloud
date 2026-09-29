package app.auloud.player.storage

import android.net.Uri

/**
 * WP3: file access for book bundles. WP3/WP5 refinement: file paths route to
 * [FileBundleStorage], picked trees to [SafBundleStorage] (opaque
 * `<treeUri>|<relPath>` tokens, see [SafPaths]) via [RoutingBundleStorage].
 *
 * All file I/O goes through this interface so callers never touch storage
 * details. `java.io.File` must not leak past the storage layer.
 *
 * A bundle dir holds `manifest.json`, `audio/chNNN.mp3` and `text/chNNN.json`
 * per `docs/03-BundleSpec.md` section 1. API 24 safe: plain strings and
 * [Uri], no `java.time`, no `java.nio.file`.
 */
interface BundleStorage {
    fun listBundleDirs(root: String): List<String>
    fun readText(path: String): String
    fun exists(path: String): Boolean
    fun audioUri(bundleDir: String, relPath: String): Uri
}
