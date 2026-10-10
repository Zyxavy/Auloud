package app.auloud.player.render

import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.BundleValidator
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN7: [RenderRecovery] startup recovery (D-096).
 *
 * Crash simulation at each finalize step (audio done/JSON missing, JSON
 * done/manifest stale, temp residue) plus the interrupted-job resume:
 * every repaired state validates clean through the real
 * [BundleValidator], and consistent books are left untouched.
 */
class RenderRecoveryTest {

    private class FakeIo : RenderFileIo {
        val files = HashMap<String, String>()
        var sleeps = 0
        val sleeper: (Long) -> Unit = { sleeps++ }

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun readText(path: String): String =
            files[path] ?: throw IOException("$path: file not found or not readable")

        override fun writeText(path: String, text: String) {
            files[path] = text
        }

        override fun renameTempToTarget(tmpPath: String, targetPath: String): Boolean {
            val text = files[tmpPath] ?: return false
            files[targetPath] = text
            files.remove(tmpPath)
            return true
        }

        override fun deleteIfExists(path: String) {
            files.remove(path)
        }
    }

    private class FakeSpool : SpoolIo {
        val files = HashMap<String, ByteArray>()
        val texts = HashMap<String, String>()

        override fun exists(path: String): Boolean =
            files.containsKey(path) || texts.containsKey(path)

        override fun readText(path: String): String =
            texts[path] ?: throw IOException("$path: file not found")

        override fun writeBytes(path: String, bytes: ByteArray) {
            files[path] = bytes
        }

        override fun writeText(path: String, text: String) {
            texts[path] = text
        }

        override fun deleteIfExists(path: String) {
            files.remove(path)
            texts.remove(path)
        }

        override fun listFiles(dir: String, prefix: String, suffix: String): List<String> {
            val root = dir.trimEnd('/') + '/'
            return (files.keys + texts.keys)
                .filter { it.startsWith(root) && it.substringAfterLast('/').startsWith(prefix) && it.endsWith(suffix) }
                .sorted()
        }
    }

    private val bundleDir = "/books/b1"
    private val spoolDir = "/spool/b1"

    private fun untimedChapterJson(chapter: Int = 1): String =
        """{"spec_version":"2.0","chapter":$chapter,"title":"Ch $chapter","blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","text":"Hello. "},{"sid":2,"speaker":"dialogue","text":"Hi. "}]}]}"""

    private fun timedChapterJson(): String =
        """{"spec_version":"2.0","chapter":1,"title":"Ch 1","duration_ms":2000,"blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","start_ms":0,"end_ms":500,"text":"Hello. "},{"sid":2,"speaker":"dialogue","start_ms":750,"end_ms":1250,"text":"Hi. "}]}]}"""

    private fun manifestNoneJson(): String = """
        {
          "spec_version": "2.0",
          "id": "b1",
          "title": "Unrendered",
          "type": "epub",
          "render_state": "none",
          "source": {"file": "source/book.epub", "sha256": "abc"},
          "voices": {
            "narrator": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0},
            "dialogue": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0}
          },
          "chapters": [
            {"index": 1, "title": "Ch 1", "text": "text/ch001.json"},
            {"index": 2, "title": "Ch 2", "text": "text/ch002.json"}
          ]
        }
        """.trimIndent()

    private fun fingerprint(): RenderFingerprint = RenderFingerprint(
        engine = "system",
        voices = mapOf("narrator" to "system:narrator", "dialogue" to "system:dialogue"),
        speeds = mapOf("narrator" to 1.0f, "dialogue" to 1.0f),
        engineVersions = mapOf("system" to "test-1")
    )

    private fun timings(): List<AssemblySentenceTiming> = listOf(
        AssemblySentenceTiming(sid = 1, startMs = 0, endMs = 500),
        AssemblySentenceTiming(sid = 2, startMs = 750, endMs = 1250)
    )

    private fun validateClean(io: FakeIo): List<String> {
        val raw = io.files["$bundleDir/manifest.json"] ?: return listOf("manifest missing")
        val manifest = BundleParser.parseText(raw).getOrThrow()
        return BundleValidator.validate(
            bundleDir,
            manifest,
            exists = { io.files.containsKey(it) },
            readText = { io.files[it] }
        )
    }

    private fun runningJobJson(): String {
        val io = FakeIo()
        val plan = RenderPlan(
            bookId = "b1", chapterCount = 2, startChapter = 0,
            scope = RenderScope.WholeBook, orderedChapters = listOf(0, 1), createdAt = 1L
        )
        val job = RenderJob(
            bookId = "b1", state = RenderJobState.RUNNING, plan = plan,
            completedChapters = listOf(0), currentChapter = 1,
            createdAt = 1L, updatedAt = 2L
        )
        RenderStateStore.save(bundleDir, job, io, { })
        return io.files["$bundleDir/render-job.json"] ?: error("no job json")
    }

    @Test
    fun crashAudioOnly_orphanAudioDeleted_manifestUntouched() {
        val io = FakeIo()
        io.files["$bundleDir/manifest.json"] = manifestNoneJson()
        io.files["$bundleDir/text/ch001.json"] = untimedChapterJson(1)
        io.files["$bundleDir/text/ch002.json"] = untimedChapterJson(2)
        io.files["$bundleDir/audio/ch001.m4a"] = "orphan-audio"

        val report = RenderRecovery.recoverBook(bundleDir, io, sleeper = io.sleeper).getOrThrow()

        assertFalse(io.files.containsKey("$bundleDir/audio/ch001.m4a"))
        assertEquals(1, report.repairs.size)
        assertEquals(1, report.repairs[0].chapterNumber)
        assertEquals(RenderRecovery.RepairKind.OrphanAudio, report.repairs[0].kind)
        assertFalse(report.manifestRewritten)
        assertTrue(validateClean(io).isEmpty())
    }

    @Test
    fun crashJsonDone_completesManifestEntry_withoutInventedFields() {
        val io = FakeIo()
        io.files["$bundleDir/manifest.json"] = manifestNoneJson()
        io.files["$bundleDir/text/ch001.json"] = timedChapterJson()
        io.files["$bundleDir/text/ch002.json"] = untimedChapterJson(2)
        io.files["$bundleDir/audio/ch001.m4a"] = "audio-bytes"

        val report = RenderRecovery.recoverBook(bundleDir, io, sleeper = io.sleeper).getOrThrow()

        assertEquals(1, report.repairs.size)
        assertEquals(RenderRecovery.RepairKind.ManifestCompleted, report.repairs[0].kind)
        assertTrue(report.manifestRewritten)
        val manifest = BundleParser.parseText(io.files["$bundleDir/manifest.json"]!!).getOrThrow()
        assertEquals("partial", manifest.renderState)
        assertEquals("audio/ch001.m4a", manifest.chapters[0].audio)
        assertEquals(2000L, manifest.chapters[0].durationMs)
        // Nothing invented: fingerprint, gain and offset stay absent.
        assertTrue(manifest.chapters[0].renderFingerprint == null)
        assertTrue(manifest.gainDb == null)
        assertTrue(manifest.encoderOffsetMs == null)
        assertTrue("expected clean, got: ${validateClean(io)}", validateClean(io).isEmpty())
    }

    @Test
    fun crashJsonDoneWithoutAudio_stripsTimings() {
        val io = FakeIo()
        io.files["$bundleDir/manifest.json"] = manifestNoneJson()
        io.files["$bundleDir/text/ch001.json"] = timedChapterJson()
        io.files["$bundleDir/text/ch002.json"] = untimedChapterJson(2)

        val report = RenderRecovery.recoverBook(bundleDir, io, sleeper = io.sleeper).getOrThrow()

        assertEquals(1, report.repairs.size)
        assertEquals(RenderRecovery.RepairKind.TimingsStripped, report.repairs[0].kind)
        assertFalse(io.files["$bundleDir/text/ch001.json"]!!.contains("start_ms"))
        assertTrue(validateClean(io).isEmpty())
    }

    @Test
    fun manifestWithoutFiles_downgradesEntry() {
        val io = FakeIo()
        // Finalize wrote the manifest, then the audio file was lost.
        io.files["$bundleDir/manifest.json"] = manifestNoneJson()
        io.files["$bundleDir/text/ch001.json"] = untimedChapterJson(1)
        io.files["$bundleDir/text/ch002.json"] = untimedChapterJson(2)
        io.files["$bundleDir/audio/ch001.m4a"] = "audio-bytes"
        RenderFinalize.finalizeChapter(
            bundleDir, 1, "audio/ch001.m4a", timings(), 2000,
            fingerprint(), mapOf("narrator" to 0.0), 0, io, io.sleeper
        ).getOrThrow()
        io.files.remove("$bundleDir/audio/ch001.m4a")

        val report = RenderRecovery.recoverBook(bundleDir, io, sleeper = io.sleeper).getOrThrow()

        assertEquals(1, report.repairs.size)
        assertEquals(RenderRecovery.RepairKind.ManifestDowngraded, report.repairs[0].kind)
        val manifest = BundleParser.parseText(io.files["$bundleDir/manifest.json"]!!).getOrThrow()
        assertEquals("none", manifest.renderState)
        assertTrue(manifest.chapters[0].audio.isBlank())
        assertTrue(manifest.chapters[0].durationMs == null)
        assertTrue(validateClean(io).isEmpty())
    }

    @Test
    fun orphanTemps_swept() {
        val io = FakeIo()
        io.files["$bundleDir/manifest.json"] = manifestNoneJson()
        io.files["$bundleDir/text/ch001.json"] = untimedChapterJson(1)
        io.files["$bundleDir/text/ch002.json"] = untimedChapterJson(2)
        io.files["$bundleDir/audio/ch001.m4a.tmp"] = "torn-audio"
        io.files["$bundleDir/text/ch001.json.tmp"] = "torn-json"
        io.files["$bundleDir/manifest.json.tmp"] = "torn-manifest"

        val report = RenderRecovery.recoverBook(bundleDir, io, sleeper = io.sleeper).getOrThrow()

        assertEquals(
            listOf(
                "$bundleDir/audio/ch001.m4a.tmp",
                "$bundleDir/manifest.json.tmp",
                "$bundleDir/text/ch001.json.tmp"
            ),
            report.sweptTemps
        )
        assertTrue(io.files.keys.none { it.endsWith(".tmp") })
        assertTrue(report.repairs.isEmpty())
        assertTrue(validateClean(io).isEmpty())
    }

    @Test
    fun consistentRenderedBook_untouched() {
        val io = FakeIo()
        io.files["$bundleDir/manifest.json"] = manifestNoneJson()
        io.files["$bundleDir/text/ch001.json"] = untimedChapterJson(1)
        io.files["$bundleDir/text/ch002.json"] = untimedChapterJson(2)
        io.files["$bundleDir/audio/ch001.m4a"] = "audio-bytes"
        RenderFinalize.finalizeChapter(
            bundleDir, 1, "audio/ch001.m4a", timings(), 2000,
            fingerprint(), emptyMap(), 0, io, io.sleeper
        ).getOrThrow()

        val report = RenderRecovery.recoverBook(bundleDir, io, sleeper = io.sleeper).getOrThrow()

        assertTrue(report.repairs.isEmpty())
        assertTrue(report.sweptTemps.isEmpty())
        assertFalse(report.manifestRewritten)
        assertTrue(validateClean(io).isEmpty())
    }

    @Test
    fun runningJob_markedInterrupted_finishedChaptersKept() {
        val io = FakeIo()
        io.files["$bundleDir/manifest.json"] = manifestNoneJson()
        io.files["$bundleDir/text/ch001.json"] = untimedChapterJson(1)
        io.files["$bundleDir/text/ch002.json"] = untimedChapterJson(2)
        io.files["$bundleDir/render-job.json"] = runningJobJson()

        val report = RenderRecovery.recoverBook(bundleDir, io, clock = { 99L }, sleeper = io.sleeper).getOrThrow()

        assertEquals(RenderJobState.INTERRUPTED, report.jobAfter?.state)
        assertEquals(listOf(0), report.jobAfter?.completedChapters)
        assertTrue(report.jobError == null)
    }

    @Test
    fun unreadableManifest_sweepsFixedTempsOnly() {
        val io = FakeIo()
        io.files["$bundleDir/manifest.json.tmp"] = "torn"

        val report = RenderRecovery.recoverBook(bundleDir, io, sleeper = io.sleeper).getOrThrow()

        assertFalse(report.manifestReadable)
        assertEquals(listOf("$bundleDir/manifest.json.tmp"), report.sweptTemps)
        assertTrue(report.repairs.isEmpty())
    }

    @Test
    fun spool_orphanPcmWithoutIndex_deleted() {
        val spool = FakeSpool()
        spool.files["$spoolDir/ch001-s001-abcdef12.pcm"] = byteArrayOf(1, 2)

        val deleted = RenderRecovery.sweepOrphanSpool(spoolDir, spool)

        assertEquals(listOf("$spoolDir/ch001-s001-abcdef12.pcm"), deleted)
        assertTrue(spool.files.isEmpty())
    }

    @Test
    fun spool_listedPcmKept_unlistedDeleted() {
        val spool = FakeSpool()
        val fp = fingerprint()
        val index = SpoolChapterIndex(
            chapter = 1,
            fingerprint = fp,
            sentences = listOf(
                SpoolSentenceEntry(
                    sid = 1, role = "narrator", file = "ch001-s001-${fp.fileTag()}.pcm",
                    sampleRateHz = 24000, samples = 240, splitPair = null, peak = 0.5f
                )
            ),
            peaks = mapOf("narrator" to 0.5f, "dialogue" to 0.0f)
        )
        spool.texts["$spoolDir/ch001-index.json"] = SpoolIndex.render(index)
        spool.files["$spoolDir/ch001-s001-${fp.fileTag()}.pcm"] = byteArrayOf(1, 2)
        spool.files["$spoolDir/ch001-s002-deadbeef.pcm"] = byteArrayOf(3, 4)

        val deleted = RenderRecovery.sweepOrphanSpool(spoolDir, spool)

        assertEquals(listOf("$spoolDir/ch001-s002-deadbeef.pcm"), deleted)
        assertTrue(spool.files.containsKey("$spoolDir/ch001-s001-${fp.fileTag()}.pcm"))
    }

    @Test
    fun spool_corruptIndex_deletesIndexAndPcm() {
        val spool = FakeSpool()
        spool.texts["$spoolDir/ch002-index.json"] = "not-json{{{"
        spool.files["$spoolDir/ch002-s001-abcdef12.pcm"] = byteArrayOf(1, 2)

        val deleted = RenderRecovery.sweepOrphanSpool(spoolDir, spool)

        assertEquals(
            listOf("$spoolDir/ch002-index.json", "$spoolDir/ch002-s001-abcdef12.pcm"),
            deleted
        )
    }
}
