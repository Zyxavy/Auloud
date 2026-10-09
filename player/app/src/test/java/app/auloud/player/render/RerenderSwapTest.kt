package app.auloud.player.render

import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.BundleValidator
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VS3: versioned swap write order plus crash simulation (D-116, D-096, D-109).
 *
 * Uses fakes: no Android, no encoder, no service. The new audio file is
 * seeded as fake bytes (the encoder path only gates on presence); the
 * JSON plus manifest writes ride the real [RenderFinalize] order
 * (audio, then JSON, then manifest, each temp-then-rename).
 */
class RerenderSwapTest {

    private class FakeIo : RenderFileIo {
        val files = HashMap<String, String>()
        var sleeps = 0
        val sleeper: (Long) -> Unit = { sleeps++ }
        var deleteBlocked = mutableSetOf<String>()

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
            if (path in deleteBlocked) return
            files.remove(path)
        }
    }

    private val bundleDir = "/books/b1"

    private fun timedChapterJson(durationMs: Long = 2000L): String =
        """{"spec_version":"2.0","chapter":1,"title":"Ch 1","duration_ms":$durationMs,"blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","start_ms":0,"end_ms":500,"text":"Hello. "},{"sid":2,"speaker":"dialogue","start_ms":750,"end_ms":1250,"text":"Hi. "}]}]}"""

    private fun manifestRenderedJson(audioRel: String = "audio/ch001.m4a"): String = """
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

    private fun untimedChapterJson(): String =
        """{"spec_version":"2.0","chapter":2,"title":"Ch 2","blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","text":"Hello. "}]}]}"""

    private fun fingerprint(
        narratorVoice: String = "system:narr",
        dialogueVoice: String = "system:dial"
    ) = RenderFingerprint(
        engine = "system",
        voices = mapOf("narrator" to narratorVoice, "dialogue" to dialogueVoice),
        speeds = mapOf("narrator" to 1.0f, "dialogue" to 1.0f),
        engineVersions = mapOf("system" to "v1")
    )

    private fun newFingerprint() = RenderFingerprint(
        engine = "system",
        voices = mapOf("narrator" to "system:new-narr", "dialogue" to "system:dial"),
        speeds = mapOf("narrator" to 1.0f, "dialogue" to 1.0f),
        engineVersions = mapOf("system" to "v1")
    )

    private fun timings(): List<AssemblySentenceTiming> = listOf(
        AssemblySentenceTiming(sid = 1, startMs = 0, endMs = 600),
        AssemblySentenceTiming(sid = 2, startMs = 850, endMs = 1350)
    )

    private fun seedRendered(io: FakeIo, audioRel: String = "audio/ch001.m4a") {
        io.files["$bundleDir/manifest.json"] = manifestRenderedJson(audioRel)
        io.files["$bundleDir/text/ch001.json"] = timedChapterJson()
        io.files["$bundleDir/text/ch002.json"] = untimedChapterJson()
        io.files["$bundleDir/$audioRel"] = "old-audio"
    }

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

    @Test
    fun versionedName_staysInsideSpec() {
        val rel = RerenderSwap.versionedAudioRel(1, newFingerprint())
        // Spec section 7: per-chapter audio must end in .mp3 or .m4a.
        assertTrue(rel.endsWith(".m4a"))
        // Spec section 1: ASCII lowercase plus zero-padded chapter number.
        assertTrue(rel.startsWith("audio/ch001-"))
        assertEquals(rel, rel.lowercase())
        assertTrue(rel.all { it.code in 32..126 })
        assertTrue(RerenderSwap.isVersionedAudioRel(rel))
        // The finished book still validates clean after a swap (see happy path).
        assertEquals(1, RerenderSwap.chapterNumberFromAudioRel(rel))
        // Validator accepts the extension (per-chapter format from extension).
        assertEquals("m4a", BundleValidator.audioFormatForExtension(rel))
    }

    @Test
    fun swap_happyPath_switchesManifestThenDeletesOld() {
        val io = FakeIo()
        seedRendered(io)
        val newFp = newFingerprint()
        val newRel = RerenderSwap.versionedAudioRel(1, newFp)
        io.files["$bundleDir/$newRel"] = "new-audio"

        val result = RerenderSwap.finalizeRerender(
            bundleDir = bundleDir,
            chapterNumber = 1,
            oldAudioRel = "audio/ch001.m4a",
            newAudioRel = newRel,
            timings = timings(),
            durationMs = 2100,
            fingerprint = newFp,
            gainDb = null,
            encoderOffsetMs = 0,
            io = io,
            sleeper = io.sleeper
        )

        assertTrue(result.isSuccess)
        val swapped = result.getOrThrow()
        assertEquals(newRel, swapped.audioRel)
        assertEquals(null, swapped.deferredOldAudio)
        // Manifest switched to the versioned rel with the new fingerprint.
        val manifest = BundleParser.parseText(io.files["$bundleDir/manifest.json"]!!).getOrThrow()
        assertEquals(newRel, manifest.chapters[0].audio)
        assertEquals(2100L, manifest.chapters[0].durationMs)
        val stored = (manifest.chapters[0].renderFingerprint as JsonObject)
            .let { RenderFingerprint.fromJsonObject(it) }!!
        assertEquals("system:new-narr", stored.voices["narrator"])
        // Old file deleted after the switch; new file kept.
        assertFalse(io.files.containsKey("$bundleDir/audio/ch001.m4a"))
        assertTrue(io.files.containsKey("$bundleDir/$newRel"))
        // Timed JSON carries the new timings.
        val chapterRaw = io.files["$bundleDir/text/ch001.json"]!!
        assertTrue(chapterRaw.contains("850"))
        assertTrue(io.files.keys.none { it.endsWith(".tmp") })
        assertTrue("expected clean, got: ${validateClean(io)}", validateClean(io).isEmpty())
    }

    @Test
    fun swap_newAudioMissing_failsLeavingOldUntouched() {
        val io = FakeIo()
        seedRendered(io)
        val newFp = newFingerprint()
        val newRel = RerenderSwap.versionedAudioRel(1, newFp)

        val result = RerenderSwap.finalizeRerender(
            bundleDir, 1, "audio/ch001.m4a", newRel, timings(), 2100,
            newFp, null, 0, io, io.sleeper
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("audio") == true)
        // Old audio plus old fingerprint untouched; chapter still stale-capable.
        assertTrue(io.files.containsKey("$bundleDir/audio/ch001.m4a"))
        val manifest = BundleParser.parseText(io.files["$bundleDir/manifest.json"]!!).getOrThrow()
        assertEquals("audio/ch001.m4a", manifest.chapters[0].audio)
        assertTrue(validateClean(io).isEmpty())
    }

    @Test
    fun crashSimulation_audioDoneJsonMissing_manifestStillOld() {
        // Kill after the new audio rename, before the JSON write.
        val io = FakeIo()
        seedRendered(io)
        val newRel = RerenderSwap.versionedAudioRel(1, newFingerprint())
        io.files["$bundleDir/$newRel"] = "new-audio-orphan"

        // Manifest still points at old; JSON still old timings.
        val manifest = BundleParser.parseText(io.files["$bundleDir/manifest.json"]!!).getOrThrow()
        assertEquals("audio/ch001.m4a", manifest.chapters[0].audio)
        assertFalse(io.files["$bundleDir/text/ch001.json"]!!.contains("850"))
        assertTrue(validateClean(io).isEmpty())
        // The orphan pick finds the unreferenced versioned file.
        val orphans = RerenderSwap.pickSwapOrphans(
            bundleDir,
            referenced = setOf("audio/ch001.m4a"),
            existing = listOf("$bundleDir/audio/ch001.m4a", "$bundleDir/$newRel")
        )
        assertEquals(listOf("$bundleDir/$newRel"), orphans)
    }

    @Test
    fun crashSimulation_jsonDoneManifestStale_finalizeRetryCompletes() {
        // Kill after the JSON rename, before the manifest write: JSON has
        // new timings, manifest still points at old. The live retry path
        // re-runs the swap finalize end to end (same order as live).
        // The recovery forward-complete for this window lives in
        // RerenderRecoveryTest.jsonDoneManifestStale_forwardCompletes.
        val io = FakeIo()
        seedRendered(io)
        val newFp = newFingerprint()
        val newRel = RerenderSwap.versionedAudioRel(1, newFp)
        io.files["$bundleDir/$newRel"] = "new-audio"
        val retimed = RerenderSwap.buildRetimedChapterJson(
            1, io.files["$bundleDir/text/ch001.json"]!!, timings(), 2100, 0
        ).getOrThrow()
        RenderFinalize.atomicWriteText("$bundleDir/text/ch001.json", retimed, io, io.sleeper)

        // State before recovery: JSON new, manifest old (inconsistent pair).
        assertTrue(io.files["$bundleDir/text/ch001.json"]!!.contains("850"))
        val before = BundleParser.parseText(io.files["$bundleDir/manifest.json"]!!).getOrThrow()
        assertEquals("audio/ch001.m4a", before.chapters[0].audio)
        // Forward completion via the swap finalize (same order as live).
        val result = RerenderSwap.finalizeRerender(
            bundleDir, 1, "audio/ch001.m4a", newRel, timings(), 2100,
            newFp, null, 0, io, io.sleeper
        )
        assertTrue(result.isSuccess)
        assertTrue(validateClean(io).isEmpty())
    }

    @Test
    fun crashSimulation_manifestDoneOldPending_deletesOld() {
        val io = FakeIo()
        seedRendered(io)
        val newFp = newFingerprint()
        val newRel = RerenderSwap.versionedAudioRel(1, newFp)
        io.files["$bundleDir/$newRel"] = "new-audio"
        RerenderSwap.finalizeRerender(
            bundleDir, 1, "audio/ch001.m4a", newRel, timings(), 2100,
            newFp, null, 0, io, io.sleeper
        ).getOrThrow()

        // Both files briefly present would resolve to old deleted; here the
        // swap already deleted old. Simulate the crash window by restoring
        // old and re-sweeping: unreferenced old is picked, referenced new kept.
        io.files["$bundleDir/audio/ch001.m4a"] = "old-audio"
        val orphans = RerenderSwap.pickSwapOrphans(
            bundleDir,
            referenced = setOf(newRel),
            existing = listOf("$bundleDir/audio/ch001.m4a", "$bundleDir/$newRel")
        )
        assertEquals(listOf("$bundleDir/audio/ch001.m4a"), orphans)
    }

    @Test
    fun swap_oldDeleteDeferredWhileOpen() {
        val io = FakeIo()
        seedRendered(io)
        val newFp = newFingerprint()
        val newRel = RerenderSwap.versionedAudioRel(1, newFp)
        io.files["$bundleDir/$newRel"] = "new-audio"
        io.deleteBlocked.add("$bundleDir/audio/ch001.m4a")

        val result = RerenderSwap.finalizeRerender(
            bundleDir, 1, "audio/ch001.m4a", newRel, timings(), 2100,
            newFp, null, 0, io, io.sleeper
        )

        assertTrue(result.isSuccess)
        assertEquals("audio/ch001.m4a", result.getOrThrow().deferredOldAudio)
        // Manifest still switched (new playable); old stays until the next sweep.
        val manifest = BundleParser.parseText(io.files["$bundleDir/manifest.json"]!!).getOrThrow()
        assertEquals(newRel, manifest.chapters[0].audio)
        assertTrue(io.files.containsKey("$bundleDir/audio/ch001.m4a"))
    }

    @Test
    fun retimedJson_sidMismatch_fails() {
        val io = FakeIo()
        seedRendered(io)
        val result = RerenderSwap.buildRetimedChapterJson(
            1,
            io.files["$bundleDir/text/ch001.json"]!!,
            listOf(AssemblySentenceTiming(1, 0, 500)),
            1000,
            0
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun gainOverwrite_replacesEvenWhenPresent() {
        val io = FakeIo()
        seedRendered(io)
        val newFp = newFingerprint()
        val newRel = RerenderSwap.versionedAudioRel(1, newFp)
        io.files["$bundleDir/$newRel"] = "new-audio"

        val result = RerenderSwap.finalizeRerender(
            bundleDir, 1, "audio/ch001.m4a", newRel, timings(), 2100,
            newFp, mapOf("narrator" to 2.5, "dialogue" to -1.0), 0, io, io.sleeper
        )

        assertTrue(result.isSuccess)
        val manifest = BundleParser.parseText(io.files["$bundleDir/manifest.json"]!!).getOrThrow()
        val gain = manifest.gainDb as? JsonObject ?: error("no gain_db")
        assertEquals(2.5, (gain["narrator"] as? JsonPrimitive)?.doubleOrNull ?: Double.NaN, 0.0)
    }

    @Test
    fun orphanPick_keepsReferenced_deletesTemps() {
        val newRel = RerenderSwap.versionedAudioRel(1, newFingerprint())
        val orphans = RerenderSwap.pickSwapOrphans(
            bundleDir,
            referenced = setOf(newRel),
            existing = listOf(
                "$bundleDir/$newRel",
                "$bundleDir/audio/ch001.m4a",
                "$bundleDir/audio/ch002-abcdef12.m4a.tmp",
                "$bundleDir/text/ch001.json"
            )
        )
        assertEquals(
            listOf("$bundleDir/audio/ch001.m4a", "$bundleDir/audio/ch002-abcdef12.m4a.tmp"),
            orphans
        )
    }
}
