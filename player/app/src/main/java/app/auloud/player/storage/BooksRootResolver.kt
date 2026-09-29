package app.auloud.player.storage

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * WP3 default "books folder" resolution, WP3/WP5 refinement: the default is
 * the shared-internal `/Auloud` (user-approved; needs WRITE_EXTERNAL_STORAGE).
 * The SD-first preference is dropped: SD cards still work when the user picks
 * them (persistable SAF grant) or when a migrated file path points at one,
 * but the default never prefers removable storage.
 *
 * [resolve] and [findRemovableRoot] are retained untouched for existing
 * installs/tests that reference the legacy SD-first rule; new code uses
 * [internalSharedDefault]/[defaultBooksRoot]. [resolve] is a pure function of
 * candidate roots so it stays unit-testable without the Android framework.
 *
 * API 24 safe: `getExternalFilesDirs` + `Environment.isExternalStorageRemovable(File)`
 * (both available since API 21 or earlier), `java.io.File` only.
 */
object BooksRootResolver {

    const val BOOKS_DIR_NAME = "Auloud"

    /**
     * Legacy WP3 resolution: removable SD root wins when present (non-blank),
     * otherwise [internalRoot]. Either way the result is `<base>/Auloud`.
     * Retained untouched for existing installs/tests; the default no longer
     * uses it (see [defaultBooksRoot]).
     */
    fun resolve(removableSdRoot: String?, internalRoot: String): String {
        val base = if (!removableSdRoot.isNullOrBlank()) {
            removableSdRoot.trimEnd('/')
        } else {
            internalRoot.trimEnd('/')
        }
        return "$base/$BOOKS_DIR_NAME"
    }

    /**
     * Pure internal default: `<externalRoot>/Auloud` (shared internal
     * storage, e.g. `/storage/emulated/0/Auloud`). Plain-JVM-testable.
     */
    fun internalSharedDefault(externalRoot: String): String {
        val base = externalRoot.trimEnd('/')
        return "$base/$BOOKS_DIR_NAME"
    }

    /**
     * Finds the removable SD card volume root, or null when none is mounted.
     * Heuristic: strip the `/Android/...` app-specific suffix from the
     * removable files dir (e.g. `/storage/1234-ABCD/Android/data/...` gives
     * `/storage/1234-ABCD`).
     *
     * Legacy WP3 helper, retained for existing installs that still reference
     * SD file paths. The default no longer uses it.
     */
    fun findRemovableRoot(context: Context): String? {
        val dirs = context.getExternalFilesDirs(null) ?: return null
        for (dir in dirs) {
            if (dir == null) continue
            if (!Environment.isExternalStorageRemovable(dir)) continue
            var root: File = dir
            // Walk up past the app-specific ".../Android/data/<pkg>/..." segment.
            var cur: File? = dir
            while (cur != null) {
                if (cur.name == "Android") {
                    root = cur.parentFile ?: cur
                    break
                }
                cur = cur.parentFile
            }
            return root.absolutePath
        }
        return null
    }

    /**
     * Full default: shared-internal `/Auloud`
     * (`Environment.getExternalStorageDirectory`, the primary shared volume).
     * Falls back to the app-private files dir only when shared storage is
     * unavailable (never to the SD card — SD arrives via the picker now).
     */
    fun defaultBooksRoot(context: Context): String {
        val shared = try {
            Environment.getExternalStorageDirectory()?.absolutePath
        } catch (e: Exception) {
            null
        }
        val base = if (!shared.isNullOrBlank()) {
            shared
        } else {
            context.filesDir.absolutePath
        }
        return internalSharedDefault(base)
    }
}
