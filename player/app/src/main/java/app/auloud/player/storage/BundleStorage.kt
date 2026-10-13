package app.auloud.player.storage

import android.net.Uri
import java.io.IOException

/**
 * WP3: file access for book bundles. WP3/WP5 refinement: file paths route to
 * [FileBundleStorage], picked trees to [SafBundleStorage] (opaque
 * `<treeUri>|<relPath>` tokens, see [SafPaths]) via [RoutingBundleStorage].
 *
 * All file I/O goes through this interface so callers never touch storage
 * details. `java.io.File` must not leak past the storage layer.
 *
 * A bundle dir holds `manifest.json`, `audio/chNNN.mp3` and `text/chNNN.json`
 * per `docs/03-BundleSpec.md` section 1. API 24 safe: plain strings and
 * [Uri], no `java.time`, no `java.nio.file`.
 */
interface BundleStorage {
    fun listBundleDirs(root: String): List<String>
    fun readText(path: String): String
    fun exists(path: String): Boolean
    fun audioUri(bundleDir: String, relPath: String): Uri

    /**
     * CP4: resolved cover reference for [coverRel] inside [bundleDirPath].
     * File branch returns the file path; SAF branch returns a `content://`
     * document URI string for the cover inside the tree. Returns null when
     * the cover cannot be resolved (blank rel or bundle escape); callers
     * store null (no cover) rather than failing the import.
     *
     * API 24 safe: plain strings, no new APIs.
     */
    fun coverUri(bundleDirPath: String, coverRel: String): String?

    /**
     * IN7: writes [bytes] to [path], creating
     * parent folders as needed. Used by the EPUB import pipeline for
     * `manifest.json`, `text/chNNN.json` and cover files.
     *
     * Throws [IOException] when the write fails. The SAF branch does not
     * support writes (import targets are always file folders under the
     * books root); it throws. Default throws so read-only fakes and the
     * SAF branch compile without changes.
     *
     * API 24 safe: plain strings and bytes, no `java.time`, no `java.nio.file`.
     */
    fun writeBytes(path: String, bytes: ByteArray) {
        throw IOException("$path: this storage does not support writes")
    }

    /**
     * IN7: writes [text] as UTF-8 to [path]. Default routes through
     * [writeBytes]; implementations normally inherit this.
     */
    fun writeText(path: String, text: String) {
        writeBytes(path, text.toByteArray(Charsets.UTF_8))
    }

    /**
     * IN7: copies the plain-filesystem file at [srcPath] (for example the
     * picked EPUB) into storage at [dstPath], streaming so the whole file
     * is never held in memory. Throws [IOException] on failure. Default
     * throws; the file branch streams with an 8 KB buffer.
     */
    fun copySourceFile(srcPath: String, dstPath: String) {
        throw IOException("$dstPath: this storage does not support writes")
    }

    /**
     * IN7: moves [fromPath] to [toPath] (temp folder to final book folder
     * on import success). Both paths share the same parent so the rename
     * is atomic on the file branch. Throws [IOException] on failure,
     * including when [toPath] already exists.
     */
    fun movePath(fromPath: String, toPath: String) {
        throw IOException("$toPath: this storage does not support writes")
    }

    /**
     * IN7: deletes [path] and everything under it, best effort and never
     * throwing: import cleanup (cancel or failure) must not fail because
     * cleanup failed. The file branch deletes what exists and swallows
     * the rest; the default does nothing.
     */
    fun deleteRecursively(path: String) {
    }

    /**
     * FP4: byte size of the file at [path], or null when unknown
     * (missing file, SAF branch, or any failure). Lets import validation
     * refuse oversize chapter text BEFORE reading it into memory.
     * Default null so read-only fakes compile untouched.
     *
     * API 24 safe: plain strings, no `java.time`, no `java.nio.file`.
     */
    fun sizeBytes(path: String): Long? = null

    /**
     * IN8: immediate `.tmp-*` import-residue folders under [root] (a sudden
     * process kill between manifest write and rename leaves one; only
     * sudden death does, cancel/failure clean up). Returns absolute paths,
     * sorted; a missing or unreadable root yields an empty list. The file
     * branch lists them; SAF roots yield an empty list (imports never
     * target picked trees). Default returns an empty list so read-only
     * fakes compile untouched.
     *
     * API 24 safe: plain strings, no `java.time`, no `java.nio.file`.
     */
    fun listStrayTempDirs(root: String): List<String> = emptyList()
}
