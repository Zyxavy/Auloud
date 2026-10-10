package app.auloud.player.ingest

import app.auloud.player.storage.BundleStorage

/**
 * IN8: stray import-temp sweep (Slice 9).
 *
 * A sudden process kill between manifest write and rename leaves a
 * `<booksRoot>/.tmp-<bookId>` folder behind (only sudden death does;
 * cancel and failure clean up, IN7-tested). The folder carries a
 * `manifest.json`, so without a sweep the library rescan would list it
 * as a book. The sweep deletes every such folder before the rescan
 * lists anything.
 *
 * Naming note: the `.tmp-` prefix must match the pipeline temp
 * (`IngestPipeline` builds `".tmp-$bookId"`) and the storage listing
 * (`FileBundleStorage.STRAY_TEMP_PREFIX`). Final book folders are bare
 * `<bookId>`, so sweeping this prefix can never delete a real book.
 *
 * API 24 safe: string ops plus the storage seam, no Android classes,
 * JVM-testable through fakes.
 */
object StrayTempSweep {

    /** Import temp folder name prefix (see the naming note above). */
    const val TMP_PREFIX = ".tmp-"

    /**
     * True when [dirPath] names an import temp (last segment starts with
     * [.TMP_PREFIX]). The rescan import loop skips these defensively, so
     * a temp created by a concurrent import mid-rescan is never read as
     * a book even if the pre-listing sweep already ran.
     */
    fun isStrayDir(dirPath: String): Boolean {
        val name = dirPath.trimEnd('/').substringAfterLast('/')
        return name.startsWith(TMP_PREFIX)
    }

    /**
     * RN7: render temp file candidates inside one book folder (D-096).
     *
     * The finalize writes audio (`.m4a.tmp` via the encoder), timed text
     * (`.json.tmp`) and the manifest (`manifest.json.tmp`) plus the job
     * state (`render-job.json.tmp`, RN3) each temp-then-rename, so a kill
     * mid-write leaves at most these names behind. Candidates derive from
     * the manifest chapter entries (audio/text rels as listed, so Scribe
     * `.mp3` chapters and device `.m4a` chapters are both covered) plus
     * the two fixed names; paths that were never created simply do not
     * exist when the caller deletes them. Pure path computation, so both
     * the file-backed recovery and JVM tests share it with no parallel
     * sweeper: callers delete the candidates best-effort via their own
     * seam (`RenderFileIo.deleteIfExists` in recovery, which never throws
     * by the same contract as [BundleStorage.deleteRecursively]).
     */
    fun renderTempPaths(bundleDir: String, manifest: app.auloud.player.bundle.Manifest): List<String> {
        val root = bundleDir.trimEnd('/')
        val paths = ArrayList<String>(manifest.chapters.size * 2 + 3)
        for (chapter in manifest.chapters) {
            if (chapter.audio.isNotBlank()) {
                paths.add(join(root, chapter.audio) + RENDER_TMP_SUFFIX)
            } else {
                // Unrendered chapters carry no audio rel yet; the device
                // render target is still a predictable temp candidate.
                paths.add(join(root, renderAudioRel(chapter.index)) + RENDER_TMP_SUFFIX)
            }
            if (chapter.text.isNotBlank()) {
                paths.add(join(root, chapter.text) + RENDER_TMP_SUFFIX)
            }
        }
        paths.add(join(root, "manifest.json") + RENDER_TMP_SUFFIX)
        paths.add(join(root, "render-job.json") + RENDER_TMP_SUFFIX)
        return paths.sorted()
    }

    /**
     * Deletes every path in [temps] best-effort via [delete] (which must
     * never throw). Returns the paths that existed before the delete, so
     * tests and recovery reports can assert on them.
     */
    fun sweepPaths(temps: List<String>, exists: (String) -> Boolean, delete: (String) -> Unit): List<String> {
        val swept = ArrayList<String>()
        for (path in temps.sorted()) {
            val present = try {
                exists(path)
            } catch (_: Exception) {
                false
            }
            if (!present) continue
            try {
                delete(path)
            } catch (_: Exception) {
            }
            swept.add(path)
        }
        return swept
    }

    /** Device-rendered chapter audio rel (`audio/chNNN.m4a`). */
    private fun renderAudioRel(chapterIndex: Int): String =
        "audio/ch%03d.m4a".format(chapterIndex.coerceAtLeast(1))

    /** Render temp file suffix (matches the encoder plus finalize convention). */
    const val RENDER_TMP_SUFFIX = ".tmp"

    private fun join(dir: String, rel: String): String =
        dir.trimEnd('/') + '/' + rel.trimStart('/')

    /**
     * Deletes every stray temp under [root] via [storage]. Best effort
     * and never throwing: listing failures read as no strays, and
     * [BundleStorage.deleteRecursively] never throws by contract. Returns
     * the deleted paths (sorted), so tests and logs can assert on them.
     */
    fun sweep(root: String, storage: BundleStorage): List<String> {
        val strays = try {
            storage.listStrayTempDirs(root)
        } catch (_: Exception) {
            return emptyList()
        }
        val deleted = ArrayList<String>(strays.size)
        for (path in strays.sorted()) {
            storage.deleteRecursively(path)
            deleted.add(path)
        }
        return deleted
    }
}
