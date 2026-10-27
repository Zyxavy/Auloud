package app.auloud.player.receive

import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.BundleValidator
import app.auloud.player.data.LibraryRepository
import app.auloud.player.storage.BundleStorage
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipInputStream

/**
 * Slice 20 TR1: landing path for Wi-Fi pushed bundles (tablet-hosts).
 *
 * Flow: hash the received zip against the claimed sha256, unpack into a
 * `.tmp-rcv-*` folder under the books root (the existing stray-temp sweep
 * owns that prefix, so crash residue is collected), parse plus validate
 * with the same checks as library rescan, then move into place and upsert
 * the library row. Any failure deletes the temp folder and names the file
 * plus the rule, never crashing the receiver.
 *
 * File-branch only: the temp folder and the final folder live under the
 * file books root (picked SAF trees never host receives), so unzip and
 * hashing use `java.io` streams with an 8 KB buffer and the whole bundle
 * is never held in memory. Moves and deletes go through [BundleStorage]
 * like the EPUB import pipeline.
 *
 * API 24 safe: `java.io` plus `java.security` only. JVM-testable: pass a
 * [FileBundleStorage] rooted at a test folder and a fake [LibraryRepository].
 */
sealed interface ReceiveOutcome {
    data class Imported(val bookId: String, val title: String) : ReceiveOutcome
    data class Duplicate(val bookId: String, val title: String) : ReceiveOutcome
    data class Failed(val problems: List<String>) : ReceiveOutcome
}

object ReceivePipeline {

    /** Bundle ids are UUID hex in every real build (full and range
     * alike); anything else (slashes, `..`) must never become a folder. */
    internal val BUNDLE_ID_SHAPE = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    /** More entries than any real bundle; a zip bomb trips here first. */
    const val MAX_ZIP_ENTRIES = 2000

    /** Unpacked-byte cap; a zip bomb trips here second. */
    const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L

    /**
     * Receive temp prefix. Shares the stray-temp sweep prefix (see
     * `FileBundleStorage.STRAY_TEMP_PREFIX`), so a kill between unpack
     * and rename leaves residue the next rescan already collects.
     */
    const val TEMP_PREFIX = ".tmp-rcv-"

    /**
     * Prefix of the sibling folder holding the old bundle during a
     * replacement swap. Deliberately not the stray-temp prefix: a crash must
     * not let the sweeper delete the only remaining copy. The parked
     * manifest is renamed to [MANIFEST_BAK] so a rescan does not list it.
     */
    const val BACKUP_PREFIX = ".bak-rcv-"

    /** Parked manifest name inside [BACKUP_PREFIX] dirs (see above). */
    const val MANIFEST_BAK = "manifest.json.auloud-bak"

    private const val BUFFER_SIZE = 8192

    suspend fun receive(
        zipFile: File,
        claimedSha256: String?,
        booksRoot: String,
        storage: BundleStorage,
        library: LibraryRepository
    ): ReceiveOutcome {
        if (claimedSha256.isNullOrBlank()) {
            return ReceiveOutcome.Failed(
                listOf("bundle.zip: missing-sha256: the X-Sha256 header is required")
            )
        }
        val actual = sha256Hex(zipFile)
            ?: return ReceiveOutcome.Failed(
                listOf("bundle.zip: unreadable: cannot read the received file")
            )
        if (!actual.equals(claimedSha256.trim(), ignoreCase = true)) {
            return ReceiveOutcome.Failed(
                listOf("bundle.zip: hash-mismatch: sha256 does not match (resend the bundle)")
            )
        }
        val root = File(booksRoot)
        val tempDir = File(root, TEMP_PREFIX + randomHex())
        try {
            unzipGuarded(zipFile, tempDir)
        } catch (e: IOException) {
            storage.deleteRecursively(tempDir.path)
            return ReceiveOutcome.Failed(
                listOf(e.message ?: "bundle.zip: bad-zip: cannot unpack the bundle")
            )
        }
        // Tolerance for senders that zip the folder instead of its
        // contents: a single top-level folder holding the manifest counts
        // as the bundle. Anything else must carry manifest.json at top.
        var bundleDir = tempDir
        if (!File(bundleDir, "manifest.json").isFile) {
            val kids = bundleDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            val single = kids.singleOrNull { File(it, "manifest.json").isFile }
            if (single != null) bundleDir = single
        }
        val manifest = BundleParser.parse(bundleDir).getOrElse { cause ->
            storage.deleteRecursively(tempDir.path)
            return ReceiveOutcome.Failed(
                listOf(cause.message ?: "manifest.json: invalid manifest")
            )
        }
        if (!BUNDLE_ID_SHAPE.matches(manifest.id)) {
            storage.deleteRecursively(tempDir.path)
            return ReceiveOutcome.Failed(
                listOf(
                    "manifest.json: bad-id: bundle id must be letters, " +
                        "digits, dot, dash or underscore"
                )
            )
        }
        // CP4: the receive path must run the same text checks as import, so a
        // pushed bundle with bad chapter JSON is refused here instead of
        // failing later at open. The bundle is on disk under the file root.
        val problems = BundleValidator.validate(
            bundleDir,
            manifest,
            readText = { path -> File(path).takeIf { it.isFile }?.readText() }
        )
        if (problems.isNotEmpty()) {
            storage.deleteRecursively(tempDir.path)
            return ReceiveOutcome.Failed(problems)
        }
        val finalDir = File(root, manifest.id)
        val backupDir = File(root, BACKUP_PREFIX + manifest.id)
        // Crash recovery: a previous replacement may have parked the old
        // bundle in the backup (manifest renamed so a rescan does not list
        // it) and died before the new one landed. Put it back first.
        if (!finalDir.exists() && backupDir.exists()) {
            restoreBackup(storage, backupDir, finalDir)
        } else if (finalDir.exists() && backupDir.exists()) {
            storage.deleteRecursively(backupDir.path)
        }
        var replacedExisting = false
        if (finalDir.exists()) {
            val installed = readManifestText(finalDir)
            val incoming = readManifestText(bundleDir)
            if (installed != null && installed == incoming) {
                storage.deleteRecursively(tempDir.path)
                return ReceiveOutcome.Duplicate(manifest.id, manifest.title)
            }
            // Updated bundle (same id, new bytes; already validated above):
            // move the old folder aside first, never delete it, so a failed
            // replace (move error, crash, library write error) restores the
            // previous bundle instead of leaving the book gone.
            try {
                storage.movePath(finalDir.path, backupDir.path)
            } catch (e: IOException) {
                storage.deleteRecursively(tempDir.path)
                return ReceiveOutcome.Failed(
                    listOf(e.message ?: "${manifest.id}: cannot replace old bundle")
                )
            }
            // Park the old manifest under a name a rescan ignores until the
            // swap completes.
            try {
                storage.movePath(
                    File(backupDir, "manifest.json").path,
                    File(backupDir, MANIFEST_BAK).path
                )
            } catch (_: IOException) {
                // Unreadable backup manifest: keep going; recovery renames
                // it back best-effort.
            }
            replacedExisting = true
        }
        try {
            storage.movePath(bundleDir.path, finalDir.path)
        } catch (e: IOException) {
            storage.deleteRecursively(tempDir.path)
            if (replacedExisting) restoreBackup(storage, backupDir, finalDir)
            return ReceiveOutcome.Failed(
                listOf(e.message ?: "${finalDir.name}: cannot move book into place")
            )
        }
        if (bundleDir != tempDir) storage.deleteRecursively(tempDir.path)
        val upserted = library.importBundle(finalDir.path, manifest)
        if (upserted.isFailure) {
            storage.deleteRecursively(finalDir.path)
            if (replacedExisting) restoreBackup(storage, backupDir, finalDir)
            return ReceiveOutcome.Failed(
                listOf(upserted.exceptionOrNull()?.message ?: "library upsert failed")
            )
        }
        if (replacedExisting) storage.deleteRecursively(backupDir.path)
        return ReceiveOutcome.Imported(manifest.id, manifest.title)
    }

    /** Puts a parked backup back at [finalDir] (best effort, never throws). */
    private fun restoreBackup(storage: BundleStorage, backupDir: File, finalDir: File) {
        if (!backupDir.exists() || finalDir.exists()) return
        val parked = File(backupDir, MANIFEST_BAK)
        val live = File(backupDir, "manifest.json")
        if (!live.exists() && parked.exists()) {
            try {
                storage.movePath(parked.path, live.path)
            } catch (_: IOException) {
            }
        }
        try {
            storage.movePath(backupDir.path, finalDir.path)
        } catch (_: IOException) {
        }
    }

    internal fun sha256Hex(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    digest.update(buf, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    internal fun randomHex(): String {
        val bytes = ByteArray(4)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Installed manifest bytes, or null when unreadable (never throws). */
    internal fun readManifestText(bundleDir: File): String? {
        return try {
            File(bundleDir, "manifest.json").takeIf { it.isFile }?.readText()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Unpacks [zipFile] into [destDir], streaming entry by entry. Rejects
     * blank names, entries escaping [destDir] (zip slip), entry counts and
     * unpacked totals past the caps above, and empty zips.
     */
    @Throws(IOException::class)
    internal fun unzipGuarded(zipFile: File, destDir: File) {
        val base = destDir.canonicalFile
        var entries = 0
        var total = 0L
        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    entries++
                    if (entries > MAX_ZIP_ENTRIES) {
                        throw IOException(
                            "bundle.zip: too-many-files: more than $MAX_ZIP_ENTRIES entries"
                        )
                    }
                    val name = entry.name
                    if (name.isBlank()) {
                        throw IOException("bundle.zip: bad-entry: blank zip entry name")
                    }
                    val target = File(base, name).canonicalFile
                    if (target != base &&
                        !target.path.startsWith(base.path + File.separatorChar)
                    ) {
                        throw IOException(
                            "bundle.zip: path-escape: entry escapes the bundle: $name"
                        )
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { out ->
                            val buf = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val read = zip.read(buf)
                                if (read <= 0) break
                                total += read
                                if (total > MAX_TOTAL_BYTES) {
                                    throw IOException(
                                        "bundle.zip: too-large: over $MAX_TOTAL_BYTES bytes unpacked"
                                    )
                                }
                                out.write(buf, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("bundle.zip: bad-zip: ${e.message ?: "cannot unpack"}", e)
        }
        if (entries == 0) throw IOException("bundle.zip: empty-zip: no entries")
    }
}
