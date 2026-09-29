package app.auloud.player.storage

/**
 * WP3: persisted user-configurable "books folder" setting.
 *
 * Returns the stored custom path when set, otherwise the default from
 * [BooksRootResolver] (microSD root `Auloud/`, else internal `Auloud/`).
 * The settings UI that edits this arrives in WP5; this is storage only.
 */
interface BooksFolderStore {
    /** Stored books folder, or the resolver default when unset/blank. */
    fun getBooksFolder(): String

    /** Persists a custom books folder path. */
    fun setBooksFolder(path: String)

    /** Clears the custom path so [getBooksFolder] falls back to the default. */
    fun clearBooksFolder()
}
