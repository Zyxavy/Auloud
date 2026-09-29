package app.auloud.player.storage

import java.io.IOException

/**
 * WP3/WP5 refinement: pure string mapping between SAF tree URIs and the
 * opaque `BundleStorage` path tokens [SafBundleStorage] lists.
 *
 * Token shape: `<treeUri>|<relPath>` where `relPath` is the document path
 * below the tree root (`book1`, `book1/manifest.json`). The `'|'` separator
 * never appears in a `content://` URI, so plain `'/'` joins by callers (e.g.
 * `LibraryViewModel`'s `dir + "/manifest.json"`) keep working: they append
 * to the `relPath` half and [splitToken] recovers both halves.
 *
 * No Android types here: document-URI construction from these strings lives
 * in [FrameworkSafBackend] (DocumentsContract), and `Uri.parse` at the
 * [SafBundleStorage.audioUri] edge. Everything below is plain-JVM-tested.
 *
 * API 24 safe: string ops only.
 */
object SafPaths {

    const val SEPARATOR = '|'

    /** True for tree roots (`content://...`) and tokens (`content://...|rel`). */
    fun isSafPath(path: String): Boolean = path.startsWith("content://")

    /**
     * The tree-URI half of a root or token: substring before the first `'|'`,
     * or the whole string for a bare tree root.
     */
    fun treeOf(path: String): String = path.substringBefore(SEPARATOR)

    /**
     * Splits a token into `(treeUri, relPath)`. Returns null when [token] is
     * not a SAF token (no separator or non-`content://` tree half).
     * A bare tree root yields `(root, "")`.
     */
    fun splitToken(token: String): Pair<String, String>? {
        if (!isSafPath(token)) return null
        if (SEPARATOR !in token) return token to ""
        val tree = token.substringBefore(SEPARATOR)
        if (!isSafPath(tree)) return null
        return tree to token.substringAfter(SEPARATOR)
    }

    /** Token for an immediate bundle dir under the tree root. */
    fun bundleToken(treeUri: String, bundleName: String): String {
        require(treeUri.isNotBlank() && isSafPath(treeUri)) {
            "$treeUri: not a tree URI"
        }
        val name = bundleName.trim().trim('/')
        require(name.isNotBlank() && '/' !in name && name != "." && name != "..") {
            "$treeUri: invalid bundle name: $bundleName"
        }
        return "$treeUri$SEPARATOR$name"
    }

    /**
     * Resolves [relPath] (e.g. `manifest.json`, `audio/ch001.mp3`) against the
     * bundle-relative dir [bundleRel] (e.g. `book1`). Returns the combined
     * rel (`book1/audio/ch001.mp3`).
     *
     * @throws IllegalArgumentException if [relPath] is blank or escapes the
     * bundle dir via `..` (mirrors `FileBundleStorage` containment).
     */
    fun resolveInBundle(bundleRel: String, relPath: String): String {
        require(relPath.isNotBlank()) { "$bundleRel: blank audio path" }
        val stack = ArrayDeque<String>()
        for (base in bundleRel.split('/')) {
            if (base.isNotBlank() && base != ".") stack.addLast(base)
        }
        val baseDepth = stack.size
        for (seg in relPath.split('/')) {
            when {
                seg.isBlank() || seg == "." -> Unit
                seg == ".." -> {
                    if (stack.size <= baseDepth) {
                        throw IllegalArgumentException(
                            "$bundleRel: audio path escapes bundle dir: $relPath"
                        )
                    }
                    stack.removeLast()
                }
                else -> stack.addLast(seg)
            }
        }
        if (stack.size <= baseDepth) {
            throw IllegalArgumentException("$bundleRel: audio path escapes bundle dir: $relPath")
        }
        return stack.joinToString("/")
    }

    /**
     * Document ID for [relPath] below the tree ([treeDocId] is the
     * `DocumentsContract.getTreeDocumentId` value, e.g. `primary:Auloud`).
     * Empty [relPath] is the tree itself.
     */
    fun documentId(treeDocId: String, relPath: String): String {
        val rel = relPath.trim().trim('/')
        return if (rel.isBlank()) treeDocId else "$treeDocId/$rel"
    }

    /** Requires [token] to belong to [treeUri]; returns its rel half. */
    fun requireRelInTree(token: String, treeUri: String): String {
        val split = splitToken(token)
            ?: throw IOException("$token: not a SAF bundle path")
        if (split.first != treeUri) {
            throw IOException("$token: not in this watch folder")
        }
        if (split.second.isBlank()) {
            throw IOException("$token: no bundle path")
        }
        return split.second
    }
}
