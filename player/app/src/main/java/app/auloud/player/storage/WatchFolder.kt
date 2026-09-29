package app.auloud.player.storage

/**
 * WP3/WP5 refinement: a watch folder is either a file-system path
 * ([FilePath], e.g. shared-internal `/storage/emulated/0/Auloud`) or a
 * Storage Access Framework tree ([TreeUri], the `content://...` URI string
 * kept from the system folder picker with a persistable permission).
 *
 * Pure Kotlin, no Android types: the URI stays an opaque string here so this
 * mapping is unit-testable on plain JVM. Only [SafBundleStorage] (via its
 * [SafBackend] seam) and [RoutingBundleStorage] interpret tree strings as
 * document URIs; `java.io.File` never sees them.
 *
 * API 24 safe: string ops only.
 */
sealed interface WatchFolder {

    /** A file-system directory (absolute path). */
    data class FilePath(val path: String) : WatchFolder

    /** A persisted SAF tree URI (`content://...`, from ACTION_OPEN_DOCUMENT_TREE). */
    data class TreeUri(val uriString: String) : WatchFolder
}

/**
 * Pure helpers over [WatchFolder]: persistence encoding, display names and
 * the file-vs-tree discriminator used by rescan routing.
 */
object WatchFolders {

    private const val FILE_PREFIX = "file:"
    private const val TREE_PREFIX = "tree:"

    /** The string rescan lists: the path for [WatchFolder.FilePath], the URI for [WatchFolder.TreeUri]. */
    fun rootString(folder: WatchFolder): String = when (folder) {
        is WatchFolder.FilePath -> folder.path
        is WatchFolder.TreeUri -> folder.uriString
    }

    /**
     * Persistence encoding for `SharedPreferences` (one folder per line).
     * `file:` + path, `tree:` + URI. Neither side ever contains `'\n'`.
     */
    fun encode(folder: WatchFolder): String = when (folder) {
        is WatchFolder.FilePath -> FILE_PREFIX + folder.path
        is WatchFolder.TreeUri -> TREE_PREFIX + folder.uriString
    }

    /**
     * Inverse of [encode]. Bare paths decode as [WatchFolder.FilePath] and
     * bare `content://` strings as [WatchFolder.TreeUri] (robustness for
     * hand-edited prefs). Blank input yields null.
     */
    fun decode(encoded: String?): WatchFolder? {
        if (encoded.isNullOrBlank()) return null
        val s = encoded.trim()
        if (s.isBlank()) return null
        return when {
            s.startsWith(TREE_PREFIX) -> {
                val rest = s.removePrefix(TREE_PREFIX).trim()
                if (rest.isBlank()) null else WatchFolder.TreeUri(rest)
            }
            s.startsWith(FILE_PREFIX) -> {
                val rest = s.removePrefix(FILE_PREFIX).trim()
                if (rest.isBlank()) null else WatchFolder.FilePath(rest)
            }
            s.startsWith("content://") -> WatchFolder.TreeUri(s)
            else -> WatchFolder.FilePath(s)
        }
    }

    /** True when the rescan root/paths belong to SAF (a `content://` URI or token). */
    fun isSafPath(path: String): Boolean = path.startsWith("content://")

    /** File-path equality ignoring a single trailing slash (except root `/`). */
    fun sameFilePath(a: String, b: String): Boolean =
        normalizeFilePath(a) == normalizeFilePath(b)

    fun normalizeFilePath(path: String): String {
        val t = path.trim()
        if (t.length > 1 && t.endsWith('/')) return t.trimEnd('/')
        return t
    }

    /** Short human label for Settings rows: last path segment, or the tree's document tail. */
    fun displayName(folder: WatchFolder): String = when (folder) {
        is WatchFolder.FilePath -> {
            val n = normalizeFilePath(folder.path)
            val last = n.substringAfterLast('/')
            if (last.isBlank()) n else last
        }
        is WatchFolder.TreeUri -> displayNameForTreeUri(folder.uriString)
    }

    /**
     * `content://.../tree/primary%3AAuloud` -> `Auloud`;
     * `content://.../tree/primary%3AAuloud%2FBooks` -> `Books`.
     * Falls back to the raw URI when nothing parseable is found.
     */
    fun displayNameForTreeUri(uriString: String): String {
        val tail = uriString.substringAfterLast("/tree/", "")
            .ifBlank { uriString.substringAfterLast('/') }
        if (tail.isBlank()) return uriString
        val decoded = tail.replace("%3A", ":").replace("%3a", ":")
            .replace("%2F", "/").replace("%2f", "/")
        val last = decoded.substringAfterLast('/').substringAfterLast(':')
        return last.ifBlank { uriString }
    }

    /** Structural equality across encodings (file slash-insensitive, tree exact). */
    fun same(a: WatchFolder, b: WatchFolder): Boolean = when {
        a is WatchFolder.FilePath && b is WatchFolder.FilePath ->
            sameFilePath(a.path, b.path)
        a is WatchFolder.TreeUri && b is WatchFolder.TreeUri ->
            a.uriString.trim() == b.uriString.trim()
        else -> false
    }

    /** True for persistable folder values (non-blank path / `content://` URI). */
    fun isValid(folder: WatchFolder): Boolean = when (folder) {
        is WatchFolder.FilePath -> folder.path.isNotBlank()
        is WatchFolder.TreeUri ->
            folder.uriString.isNotBlank() && folder.uriString.startsWith("content://")
    }
}
