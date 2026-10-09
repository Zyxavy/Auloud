package app.auloud.player.render

import app.auloud.player.tts.BookVoices
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VS3: re-render recovery sweep (D-116 interrupted swap, D-109 rules).
 *
 * Old audio plus manifest pointing at old is fine (new unreferenced
 * deleted); versioned new referenced but JSON missing downgrades via
 * the base pass (refuse and record, never invented).
 */
class RerenderRecoveryTest {

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

    private val bundleDir = "/books/b1"

    private fun manifestPartialJson(audioRel: String = "audio/ch001.m4a"): String = """
        {
          "spec_version": "2.0",
          "id": "b1",
          "title": "Rendered",
          "type": "epub",
          "render_state": "partial",
          "audio": {"format": "m4a", "channels": 1, "sample_rate": 24000, "bitrate_kbps": 64, "cbr": true},
          "voices": {
            "narrator": {"engine": "system", "voice": "narr", "speed": 1.0, "pitch": 1.0},
            "dialogue": {"engine": "system", "voice": "dial", "speed": 1.0, "pitch": 1.0}
          },
          "chapters": [
            {"index": 1, "title": "Ch 1", "audio": "$audioRel", "text": "text/ch001.json", "duration_ms": 2000,
             "render_fingerprint": {"engine": "system", "voices": {"narrator": "system:narr", "dialogue": "system:dial"}, "speeds": {"narrator": 1.0, "dialogue": 1.0}, "engine_versions": {"system": "v1"}}},
            {"index": 2, "title": "Ch 2", "text": "text/ch002.json"}
          ]
        }
        """.trimIndent()

    private fun timedChapterJson(): String =
        """{"spec_version":"2.0","chapter":1,"title":"Ch 1","duration_ms":2000,"blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","start_ms":0,"end_ms":500,"text":"Hello. "},{"sid":2,"speaker":"dialogue","start_ms":750,"end_ms":1250,"text":"Hi. "}]}]}"""

    private fun untimedChapterJson(chapter: Int = 2): String =
        """{"spec_version":"2.0","chapter":$chapter,"title":"Ch $chapter","blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","text":"Hello. "}]}]}"""

    private fun newFingerprint() = RenderFingerprint(
        engine = "system",
        voices = mapOf("narrator" to "system:new-narr", "dialogue" to "system:dial"),
        speeds = mapOf("narrator" to 1.0f, "dialogue" to 1.0f),
        engineVersions = mapOf("system" to "v1")
    )

    @Test
    fun unreferencedVersioned_deleted_manifestPointingAtOldFine() {
        val io = FakeIo()
        io.files["$bundleDir/manifest.json"] = manifestPartialJson("audio/ch001.m4a")
        io.files["$bundleDir/text/ch001.json"] = timedChapterJson()
        io.files["$bundleDir/text/ch002.json"] = untimedChapterJson()
        io.files["$bundleDir/audio/ch001.m4a"] = "old-audio"
        val newRel = RerenderSwap.versionedAudioRel(1, newFingerprint())
        io.files["$bundleDir/$newRel"] = "orphan-new"

        val (base, sweep) = RerenderRecovery.recoverBookWithRerender(
            bundleDir = bundleDir,
            io = io,
            listAudioFiles = { listOf("$bundleDir/audio/ch001.m4a", "$bundleDir/$newRel") },
            sleeper = io.sleeper
        ).getOrThrow()

        assertTrue(base.repairs.isEmpty())
        assertEquals(listOf("$bundleDir/$newRel"), sweep.sweptAudio)
        assertTrue(sweep.deferred.isEmpty())
        assertFalse(io.files.containsKey("$bundleDir/$newRel"))
        assertTrue(io.files.containsKey("$bundleDir/audio/ch001.m4a"))
    }

    @Test
    fun audioTemps_swept() {
        val io = FakeIo()
        io.files["$bundleDir/manifest.json"] = manifestPartialJson()
        io.files["$bundleDir/text/ch001.json"] = timedChapterJson()
        io.files["$bundleDir/text/ch002.json"] = untimedChapterJson()
        io.files["$bundleDir/audio/ch001.m4a"] = "old-audio"

        val (base, sweep) = RerenderRecovery.recoverBookWithRerender(
            bundleDir = bundleDir,
            io = io,
            listAudioFiles = {
                listOf(
                    "$bundleDir/audio/ch001.m4a",
                    "$bundleDir/audio/ch001-deadbeef.m4a.tmp"
                )
            },
            sleeper = io.sleeper
        ).getOrThrow()

        assertTrue(base.repairs.isEmpty())
        assertEquals(listOf("$bundleDir/audio/ch001-deadbeef.m4a.tmp"), sweep.sweptAudio)
    }

    @Test
    fun referencedNewButJsonMissing_downgradesLikeD109() {
        val io = FakeIo()
        val newRel = RerenderSwap.versionedAudioRel(1, newFingerprint())
        io.files["$bundleDir/manifest.json"] = manifestPartialJson(newRel)
        // JSON missing entirely: manifest claims versioned audio with no text.
        io.files["$bundleDir/text/ch002.json"] = untimedChapterJson()
        io.files["$bundleDir/$newRel"] = "new-audio"

        val (base, sweep) = RerenderRecovery.recoverBookWithRerender(
            bundleDir = bundleDir,
            io = io,
            listAudioFiles = { listOf("$bundleDir/$newRel") },
            sleeper = io.sleeper
        ).getOrThrow()

        // Base pass downgrades the broken entry (refuse and record).
        assertEquals(1, base.repairs.size)
        assertEquals(RenderRecovery.RepairKind.ManifestDowngraded, base.repairs[0].kind)
        assertTrue(base.manifestRewritten)
        // The sweep itself never deletes a file the repaired manifest references;
        // here the entry was stripped, so nothing referenced remains.
        assertTrue(sweep.deferred.isEmpty())
    }

    @Test
    fun noLister_runsBasePassOnly() {
        val io = FakeIo()
        io.files["$bundleDir/manifest.json"] = manifestPartialJson()
        io.files["$bundleDir/text/ch001.json"] = timedChapterJson()
        io.files["$bundleDir/text/ch002.json"] = untimedChapterJson()
        io.files["$bundleDir/audio/ch001.m4a"] = "old-audio"

        val (base, sweep) = RerenderRecovery.recoverBookWithRerender(
            bundleDir = bundleDir,
            io = io,
            listAudioFiles = null,
            sleeper = io.sleeper
        ).getOrThrow()

        assertTrue(base.repairs.isEmpty())
        assertTrue(sweep.sweptAudio.isEmpty())
    }

    @Test
    fun jsonDoneManifestStale_forwardCompletes() {
        // Kill after the JSON rename, before the manifest write: the
        // JSON carries new timings (duration 2100) while the manifest
        // still points at old audio (duration 2000). Recovery must
        // forward-complete the manifest to the single versioned
        // candidate instead of sweeping the new audio as an orphan.
        val io = FakeIo()
        io.files["$bundleDir/manifest.json"] = manifestPartialJson("audio/ch001.m4a")
        io.files["$bundleDir/text/ch001.json"] = timedChapterJson()
        io.files["$bundleDir/text/ch002.json"] = untimedChapterJson()
        io.files["$bundleDir/audio/ch001.m4a"] = "old-audio"
        val newRel = RerenderSwap.versionedAudioRel(1, newFingerprint())
        io.files["$bundleDir/$newRel"] = "new-audio"
        val retimed = RerenderSwap.buildRetimedChapterJson(
            1, io.files["$bundleDir/text/ch001.json"]!!,
            listOf(
                AssemblySentenceTiming(sid = 1, startMs = 0, endMs = 600),
                AssemblySentenceTiming(sid = 2, startMs = 850, endMs = 1350)
            ),
            2100, 0
        ).getOrThrow()
        RenderFinalize.atomicWriteText("$bundleDir/text/ch001.json", retimed, io, io.sleeper)

        val (base, sweep) = RerenderRecovery.recoverBookWithRerender(
            bundleDir = bundleDir,
            io = io,
            listAudioFiles = { listOf("$bundleDir/audio/ch001.m4a", "$bundleDir/$newRel") },
            sleeper = io.sleeper
        ).getOrThrow()

        assertEquals(listOf(1), sweep.forwardCompleted)
        assertTrue(sweep.preservedForRetry.isEmpty())
        // Manifest now points at the versioned file with JSON duration.
        val manifest = app.auloud.player.bundle.BundleParser
            .parseText(io.files["$bundleDir/manifest.json"]!!).getOrThrow()
        assertEquals(newRel, manifest.chapters[0].audio)
        assertEquals(2100L, manifest.chapters[0].durationMs)
        // Fingerprint removed (absent means unknown, never invented).
        assertTrue(manifest.chapters[0].renderFingerprint == null)
        // Absent fingerprint reads STALE so the next run re-verifies.
        val voices = BookVoices(
            narratorVoiceId = "system:narr",
            dialogueVoiceId = "system:dial",
            narratorSpeed = 1.0f,
            dialogueSpeed = 1.0f,
            readOnly = false
        )
        assertEquals(
            ChapterStaleState.STALE,
            RenderStaleness.classifyChapter(
                manifest.chapters[0], voices, versionOf = { "v1" }, hasDialogue = true
            )
        )
        // New audio kept, old audio swept as unreferenced after the switch.
        assertTrue(io.files.containsKey("$bundleDir/$newRel"))
        assertEquals(listOf("$bundleDir/audio/ch001.m4a"), sweep.sweptAudio)
        assertTrue(
            "expected clean, got: ${
                app.auloud.player.bundle.BundleValidator.validate(
                    bundleDir, manifest,
                    exists = { io.files.containsKey(it) },
                    readText = { io.files[it] }
                )
            }",
            app.auloud.player.bundle.BundleValidator.validate(
                bundleDir, manifest,
                exists = { io.files.containsKey(it) },
                readText = { io.files[it] }
            ).isEmpty()
        )
        assertTrue(base.repairs.isEmpty())
    }
}
