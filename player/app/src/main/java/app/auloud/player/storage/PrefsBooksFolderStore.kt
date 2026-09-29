package app.auloud.player.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * WP3: [BooksFolderStore] backed by framework `SharedPreferences` (no new
 * dependency). The default is captured at construction from
 * [BooksRootResolver.defaultBooksRoot]; a stored blank is treated as unset.
 *
 * API 24 safe: `SharedPreferences` only.
 */
class PrefsBooksFolderStore(
    private val prefs: SharedPreferences,
    private val defaultBooksRoot: String
) : BooksFolderStore {

    override fun getBooksFolder(): String =
        prefs.getString(KEY_BOOKS_FOLDER, null)?.takeIf { it.isNotBlank() }
            ?: defaultBooksRoot

    override fun setBooksFolder(path: String) {
        prefs.edit().putString(KEY_BOOKS_FOLDER, path).apply()
    }

    override fun clearBooksFolder() {
        prefs.edit().remove(KEY_BOOKS_FOLDER).apply()
    }

    companion object {
        const val PREFS_NAME = "auloud_settings"
        const val KEY_BOOKS_FOLDER = "books_folder"

        fun fromContext(context: Context): PrefsBooksFolderStore =
            PrefsBooksFolderStore(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                BooksRootResolver.defaultBooksRoot(context)
            )
    }
}
