package app.auloud.player.render

import app.auloud.player.bundle.BundleParser
import app.auloud.player.tts.BookVoices
import app.auloud.player.tts.TtsRole
import app.auloud.player.tts.TtsVoiceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VS3: first-render voice copy seam (VS1 helpers wired, D-113).
 *
 * Pure JVM: manifest JSON plus a map globals store, no Android.
 */
class RerenderFirstRenderTest {

    private class MapGlobals(
        var narratorId: String = "system:global-narr",
        var dialogueId: String = "system:global-dial",
        var narratorSpeed: Float = 1.0f,
        var dialogueSpeed: Float = 1.0f
    ) : TtsVoiceStore {
        override fun voiceId(role: TtsRole): String =
            if (role == TtsRole.Narrator) narratorId else dialogueId
        override fun setVoiceId(role: TtsRole, voiceId: String) {
            if (role == TtsRole.Narrator) narratorId = voiceId else dialogueId = voiceId
        }
        override fun speed(role: TtsRole): Float =
            if (role == TtsRole.Narrator) narratorSpeed else dialogueSpeed
        override fun setSpeed(role: TtsRole, speed: Float) {
            if (role == TtsRole.Narrator) narratorSpeed = speed else dialogueSpeed = speed
        }
    }

    private fun placeholderManifest(): String = """
        {
          "spec_version": "2.0",
          "id": "b1",
          "title": "Fresh",
          "type": "epub",
          "render_state": "none",
          "voices": {
            "narrator": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0},
            "dialogue": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0}
          },
          "chapters": [{"index": 1, "title": "Ch 1", "text": "text/ch001.json"}]
        }
        """.trimIndent()

    private fun realVoicesManifest(): String = """
        {
          "spec_version": "2.0",
          "id": "b1",
          "title": "Started",
          "type": "epub",
          "render_state": "none",
          "voices": {
            "narrator": {"engine": "system", "voice": "global-narr", "speed": 1.0, "pitch": 1.0},
            "dialogue": {"engine": "system", "voice": "global-dial", "speed": 1.0, "pitch": 1.0}
          },
          "chapters": [{"index": 1, "title": "Ch 1", "text": "text/ch001.json"}]
        }
        """.trimIndent()

    private fun scribeManifest(): String = """
        {
          "spec_version": "1.1",
          "id": "s1",
          "title": "PC book",
          "type": "epub",
          "audio": {"format": "mp3", "channels": 1, "sample_rate": 24000, "bitrate_kbps": 64, "cbr": true},
          "voices": {
            "narrator": {"engine": "kokoro", "voice": "af_heart", "speed": 1.0, "pitch": 1.0},
            "Ana": {"engine": "kokoro", "voice": "bf_emma", "speed": 1.0, "pitch": 1.0}
          },
          "chapters": [{"index": 1, "title": "Ch 1", "audio": "audio/ch001.mp3", "text": "text/ch001.json", "duration_ms": 1000}]
        }
        """.trimIndent()

    @Test
    fun placeholder_needsCopy_andCopiesGlobals() {
        val updated = RerenderFirstRender.ensureFirstRenderCopy(placeholderManifest(), MapGlobals())
            .getOrThrow()
        assertTrue(updated != null)
        val manifest = BundleParser.parseText(updated!!).getOrThrow()
        val voices = BookVoices.read(manifest, MapGlobals())
        assertEquals("system:global-narr", voices.narratorVoiceId)
        assertEquals("system:global-dial", voices.resolvedDialogueVoiceId())
        assertEquals(false, voices.readOnly)
    }

    @Test
    fun realVoices_needNoCopy() {
        val updated = RerenderFirstRender.ensureFirstRenderCopy(realVoicesManifest(), MapGlobals())
            .getOrThrow()
        assertNull(updated)
    }

    @Test
    fun scribeBook_needNoCopy() {
        val updated = RerenderFirstRender.ensureFirstRenderCopy(scribeManifest(), MapGlobals())
            .getOrThrow()
        assertNull(updated)
    }

    @Test
    fun bookVoiceStore_adaptsBookVoices() {
        val voices = BookVoices(
            narratorVoiceId = "system:narr",
            dialogueVoiceId = null,
            narratorSpeed = 1.25f,
            dialogueSpeed = 1.0f,
            readOnly = false
        )
        val store = BookVoiceStore(voices)
        assertEquals("system:narr", store.voiceId(TtsRole.Narrator))
        // Unset dialogue resolves to narrator (single role).
        assertEquals("system:narr", store.voiceId(TtsRole.Dialogue))
        assertEquals(1.25f, store.speed(TtsRole.Narrator))
    }
}
