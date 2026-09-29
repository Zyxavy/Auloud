package app.auloud.player.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * WP3/WP5 refinement: [WatchFolderStore] over framework `SharedPreferences`
 * (no new dependency).
 *
 * Storage: one `'\n'`-joined string of [WatchFolders.encode] values under
 * [KEY_WATCH_FOLDERS] (order-preserving; neither file paths nor tree URIs
 * contain newlines). Presence of the key distinguishes "user cleared them
 * all" (present but empty) from "never set" (absent → [defaultFolder], with
 * a one-time migration from the legacy WP3 single `books_folder` value when
 * the new key is absent).
 *
 * API 24 safe: `SharedPreferences` only.
 */
class PrefsWatchFolderStore(
    private val prefs: SharedPreferences,
    private val defaultFolder: WatchFolder
) : WatchFolderStore {

    override fun getWatchFolders(): List<WatchFolder> {
        if (!prefs.contains(KEY_WATCH_FOLDERS)) {
            return migrateOrDefault()
        }
        val raw = prefs.getString(KEY_WATCH_FOLDERS, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split('\n')
            .mapNotNull { WatchFolders.decode(it) }
            .filter { WatchFolders.isValid(it) }
            .dedupe()
    }

    override fun setWatchFolders(folders: List<WatchFolder>) {
        val clean = folders
            .filter { WatchFolders.isValid(it) }
            .dedupe()
        prefs.edit()
            .putString(KEY_WATCH_FOLDERS, clean.joinToString("\n") { WatchFolders.encode(it) })
            .apply()
    }

    override fun addFolder(folder: WatchFolder) {
        if (!WatchFolders.isValid(folder)) return
        val current = getWatchFolders()
        if (current.any { WatchFolders.same(it, folder) }) return
        setWatchFolders(current + folder)
    }

    override fun removeFolder(folder: WatchFolder) {
        val current = getWatchFolders()
        val kept = current.filterNot { WatchFolders.same(it, folder) }
        if (kept.size != current.size) {
            setWatchFolders(kept)
        }
    }

    /**
     * Legacy WP3 single-folder value becomes a one-entry watch list so an
     * existing install keeps its chosen folder; otherwise the internal
     * `/Auloud` default. A stored blank is treated as unset.
     */
    private fun migrateOrDefault(): List<WatchFolder> {
        val legacy = prefs.getString(KEY_BOOKS_FOLDER_LEGACY, null)
            ?.takeIf { it.isNotBlank() }
            ?.trim()
        if (!legacy.isNullOrBlank()) {
            val folder = WatchFolders.decode(legacy)
            if (folder != null && WatchFolders.isValid(folder)) {
                return listOf(folder)
            }
        }
        return listOf(defaultFolder)
    }

    private fun List<WatchFolder>.dedupe(): List<WatchFolder> {
        val out = mutableListOf<WatchFolder>()
        for (folder in this) {
            if (out.none { WatchFolders.same(it, folder) }) {
                out.add(folder)
            }
        }
        return out
    }

    companion object {
        const val PREFS_NAME = "auloud_settings"
        const val KEY_WATCH_FOLDERS = "watch_folders"
        /** WP3 single-folder key, read once for migration; never written here. */
        const val KEY_BOOKS_FOLDER_LEGACY = "books_folder"

        fun fromContext(context: Context): PrefsWatchFolderStore =
            PrefsWatchFolderStore(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                WatchFolder.FilePath(BooksRootResolver.defaultBooksRoot(context))
            )
    }
}
