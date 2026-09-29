package app.auloud.player.storage

/**
 * WP3/WP5 refinement: the persisted watch-folder list.
 *
 * The first-run default is the shared-internal `/Auloud` entry supplied by
 * the caller (see `PrefsWatchFolderStore.fromContext`, which uses
 * [BooksRootResolver.defaultBooksRoot] — internal, never SD-first). Users add
 * folders via the system picker (SAF tree URIs) and remove them in Settings;
 * `LibraryViewModel.rescan` scans every entry.
 *
 * API 24 safe: no framework types in the contract (implementations use
 * `SharedPreferences`).
 */
interface WatchFolderStore {

    /** All watch folders, in insertion order. Never null; may be empty. */
    fun getWatchFolders(): List<WatchFolder>

    /** Replaces the whole list (drops invalid entries, dedupes, keeps order). */
    fun setWatchFolders(folders: List<WatchFolder>)

    /** Adds one folder; a structural duplicate is a no-op. */
    fun addFolder(folder: WatchFolder)

    /** Removes every structural match; missing entries are a no-op. */
    fun removeFolder(folder: WatchFolder)
}
