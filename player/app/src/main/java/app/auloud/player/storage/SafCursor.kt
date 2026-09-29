package app.auloud.player.storage

/**
 * WP3/WP5 refinement: pure cursor-row parsing for
 * [FrameworkSafBackend.children], extracted so the mapping (blank names
 * skipped, directory flag from the MIME type) is unit-testable on plain JVM
 * without a `Cursor` or `DocumentsContract`.
 *
 * API 24 safe: string ops only. The production directory MIME comes from
 * `DocumentsContract.Document.MIME_TYPE_DIR` at the call site; [DIR_MIME]
 * pins the same value for tests.
 */
object SafCursor {

    /** Value of `DocumentsContract.Document.MIME_TYPE_DIR`. */
    const val DIR_MIME = "vnd.android.document/directory"

    /**
     * Maps one child row to a [SafChild], or null when the row must be
     * skipped (missing/blank display name).
     */
    fun parseRow(
        displayName: String?,
        mimeType: String?,
        dirMime: String = DIR_MIME
    ): SafChild? {
        val name = displayName?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        return SafChild(name, mimeType == dirMime)
    }
}
