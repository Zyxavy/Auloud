package app.auloud.player.storage

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * WP3: default "books folder" resolution.
 *
 * Default is microSD root `Auloud/` when a removable SD card is mounted, else
 * internal `Auloud/`. [resolve] is a pure function of candidate roots so it
 * is unit-testable without the Android framework; the `Context`-dependent
 * candidate discovery stays in thin helpers below.
 *
 * API 24 safe: `getExternalFilesDirs` + `Environment.isExternalStorageRemovable(File)`
 * (both available since API 21 or earlier), `java.io.File` only.
 */
object BooksRootResolver {

    const val BOOKS_DIR_NAME = "Auloud"

    /**
     * Pure resolution: removable SD root wins when present (non-blank),
     * otherwise [internalRoot]. Either way the result is `<base>/Auloud`.
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
     * Finds the removable SD card volume root, or null when none is mounted.
     * Heuristic: strip the `/Android/...` app-specific suffix from the
     * removable files dir (e.g. `/storage/1234-ABCD/Android/data/...` gives
     * `/storage/1234-ABCD`).
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

    /** Full default: [resolve] applied to this device's candidate roots. */
    fun defaultBooksRoot(context: Context): String =
        resolve(findRemovableRoot(context), context.filesDir.absolutePath)
}
