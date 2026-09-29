package app.auloud.player.storage

/**
 * WP3/WP5 refinement: pure persistable-permission reconciliation for watch
 * folders.
 *
 * A picked tree is usable only while its URI survives in the
 * `ContentResolver.persistedUriPermissions` set (grants can vanish across
 * reboots or revocation). [prune] splits the stored list into entries that
 * are still granted ([Pruned.kept]) and tree entries that lost their grant
 * ([Pruned.dropped]); file-path folders never need a grant and are always
 * kept. The caller persists [Pruned.kept], releases nothing (nothing was
 * taken for a missing grant), and surfaces [Pruned.dropped] through the
 * library notices channel — never silently.
 *
 * Pure strings in/out so this is unit-testable on plain JVM; the activity
 * supplies `persistedUriPermissions.map { it.uri.toString() }`.
 *
 * API 24 safe: string ops only.
 */
object WatchFolderGrants {

    data class Pruned(val kept: List<WatchFolder>, val dropped: List<WatchFolder>)

    fun prune(
        folders: List<WatchFolder>,
        grantedUriStrings: Set<String>
    ): Pruned {
        val granted = grantedUriStrings.map { it.trim() }.toSet()
        val kept = mutableListOf<WatchFolder>()
        val dropped = mutableListOf<WatchFolder>()
        for (folder in folders) {
            if (folder is WatchFolder.TreeUri && folder.uriString.trim() !in granted) {
                dropped.add(folder)
            } else {
                kept.add(folder)
            }
        }
        return Pruned(kept, dropped)
    }
}
