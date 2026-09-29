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
            .ifBlank { uriString.substringAfterLast('/', "") }
        if (tail.isBlank()) return uriString
        val decoded = percentDecode(tail)
        val last = decoded.substringAfterLast('/').substringAfterLast(':')
        return last.ifBlank { uriString }
    }

    /**
     * Human label for a whole tree grant, e.g.
     * `content://.../tree/primary%3AAuloud%2FBooks` ->
     * `primary:Auloud/Books`. Used anywhere a raw tree URI would otherwise
     * reach the UI (Settings subtitles, error reasons). Falls back to the
     * raw URI when nothing parseable is found.
     */
    fun treeDocumentLabel(uriString: String): String {
        val tail = uriString.substringAfter("/tree/", "")
            .ifBlank { uriString.substringAfterLast('/', "") }
        if (tail.isBlank()) return uriString
        return percentDecode(tail)
    }

    /**
     * Display-safe form of any rescan path for user-visible UI: SAF tokens
     * (`<tree>|<rel>`) collapse to their bundle rel (`book1`), bare tree
     * roots to their [treeDocumentLabel], file paths pass through untouched.
     * Raw `<tree>|<rel>` tokens must never render in the UI.
     */
    fun displayPath(path: String): String {
        val split = SafPaths.splitToken(path) ?: return path
        if (split.second.isBlank()) return treeDocumentLabel(split.first)
        return try {
            SafPaths.sanitizeTreeRel(split.second, "bundle path")
        } catch (e: IllegalArgumentException) {
            split.second
        }
    }

    private val TOKEN_REGEX = Regex("content://[^\\s|]*\\|[^\\s]*")
    private val TREE_REGEX = Regex("content://[^\\s|]*")

    /**
     * Last line of defense against token leakage: rewrites every embedded
     * SAF token in free text (e.g. storage exception messages) to its
     * [displayPath], and every bare tree URI to its [treeDocumentLabel].
     * File paths and plain text pass through untouched. Callers apply this
     * to error dirs AND reasons before they reach the UI.
     */
    fun sanitizeUiText(text: String): String {
        if ("content://" !in text) return text
        var out = TOKEN_REGEX.replace(text) { match -> displayPath(match.value) }
        out = TREE_REGEX.replace(out) { match -> treeDocumentLabel(match.value) }
        return out
    }

    /**
     * Full percent-decoding (`%XX` hex, multi-byte UTF-8 aware; `+` stays
     * `+`; malformed `%` passes through literally). Pure Kotlin so it runs
     * identically on-device and on plain-JVM tests — no `Uri.decode`
     * framework call needed.
     */
    fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder(s.length)
        val bytes = mutableListOf<Byte>()
        fun flush() {
            if (bytes.isNotEmpty()) {
                out.append(bytes.toByteArray().toString(Charsets.UTF_8))
                bytes.clear()
            }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 <= s.lastIndex) {
                val hex = s.substring(i + 1, i + 3)
                val byte = hex.toIntOrNull(16)
                if (byte != null) {
                    bytes.add(byte.toByte())
                    i += 3
                    continue
                }
            }
            flush()
            out.append(c)
            i++
        }
        flush()
        return out.toString()
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
