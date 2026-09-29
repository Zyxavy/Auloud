package app.auloud.player.storage

/**
 * WP3/WP5 refinement: seam between [SafBundleStorage]'s pure token logic and
 * the framework (`ContentResolver` + `DocumentsContract`).
 *
 * Bound to ONE persisted tree at construction; all paths are tree-relative
 * (`""` is the tree root, `book1` a bundle dir, `book1/manifest.json` a
 * file). Production ([FrameworkSafBackend]) implements these with the
 * framework; unit tests use an in-memory fake. No Android types in this
 * contract, so the mapping logic stays plain-JVM-testable.
 *
 * API 24 safe by construction (implementations openings are API 19/21+ only).
 */
interface SafBackend {

    /** Immediate children of [parentRel] (`""` = the tree root). */
    fun children(parentRel: String): List<SafChild>

    /** True when a document exists at [relPath]. Never throws. */
    fun exists(relPath: String): Boolean

    /**
     * UTF-8 text of the document at [relPath].
     * @throws java.io.IOException when missing, unreadable or permission-lost.
     */
    fun readText(relPath: String): String

    /** Document-URI string for [relPath] (for `audioUri` resolution). */
    fun documentUri(relPath: String): String
}

/** One child row: display name plus the directory flag. */
data class SafChild(val name: String, val isDirectory: Boolean)
