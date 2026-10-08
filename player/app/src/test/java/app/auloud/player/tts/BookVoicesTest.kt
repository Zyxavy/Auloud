package app.auloud.player.tts

import app.auloud.player.bundle.Manifest
import app.auloud.player.storage.FakeSharedPreferences
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VS1: per-book voice model (D-113 read/write plus fallback, D-118
 * read-only, unset dialogue means same as narrator).
 *
 * Pure JVM: fake engines plus fake prefs, no Android, no storage.
 */
class BookVoicesTest {

    private fun voiceEntry(engine: String, voice: String, speed: Double = 1.0) =
        buildJsonObject {
            put("engine", engine)
            put("voice", voice)
            put("speed", speed)
            put("pitch", 1.0)
        }

    private fun deviceManifest(
        narrator: String = "system",
        narratorVoice: String = "default",
        dialogue: String = "system",
        dialogueVoice: String = "default",
        narratorSpeed: Double = 1.0,
        dialogueSpeed: Double = 1.0,
        specVersion: String = "2.0",
        extraKeys: Map<String, String> = emptyMap()
    ): Manifest {
        val voices = linkedMapOf(
            "narrator" to voiceEntry(narrator, narratorVoice, narratorSpeed),
            "dialogue" to voiceEntry(dialogue, dialogueVoice, dialogueSpeed)
        )
        for ((key, value) in extraKeys) {
            voices[key] = voiceEntry("kokoro", value)
        }
        return Manifest(
            specVersion = specVersion,
            id = "book-1",
            title = "Test Book",
            type = "epub",
            chapters = emptyList(),
            voices = voices
        )
    }

    private fun globals(
        narratorId: String = "system:global-narr",
        dialogueId: String = "system:global-dial",
        narratorSpeed: Float = 1.0f,
        dialogueSpeed: Float = 1.1f
    ): PrefsTtsStore {
        val store = PrefsTtsStore(FakeSharedPreferences())
        if (narratorId.isNotBlank()) store.setVoiceId(TtsRole.Narrator, narratorId)
        if (dialogueId.isNotBlank()) store.setVoiceId(TtsRole.Dialogue, dialogueId)
        store.setSpeed(TtsRole.Narrator, narratorSpeed)
        store.setSpeed(TtsRole.Dialogue, dialogueSpeed)
        return store
    }

    private fun registry(): EngineRegistry {
        val system = FakeTtsEngine(
            namespace = "system",
            voiceIds = listOf("system:global-narr", "system:global-dial", "system:other")
        )
        val piper = FakeTtsEngine(
            namespace = "piper",
            voiceIds = listOf("piper:en_US-lessac-low", "piper:en_US-ryan-low")
        )
        return EngineRegistry(listOf(system, piper))
    }

    @Test
    fun roundTrip_realEntriesPreserved() {
        val original = BookVoices(
            narratorVoiceId = "system:global-narr",
            dialogueVoiceId = "piper:en_US-lessac-low",
            narratorSpeed = 1.25f,
            dialogueSpeed = 0.75f,
            readOnly = false
        )
        val manifest = Manifest(
            specVersion = "2.0",
            id = "book-1",
            title = "T",
            type = "epub",
            chapters = emptyList(),
            voices = original.toManifestVoices()
        )
        val back = BookVoices.read(manifest, globals())
        assertEquals("system:global-narr", back.narratorVoiceId)
        assertEquals("piper:en_US-lessac-low", back.dialogueVoiceId)
        assertEquals(1.25f, back.narratorSpeed, 0f)
        assertEquals(0.75f, back.dialogueSpeed, 0f)
        assertFalse(back.readOnly)
    }

    @Test
    fun fallback_placeholdersUseGlobals() {
        val manifest = deviceManifest()
        val read = BookVoices.read(manifest, globals())
        assertEquals("system:global-narr", read.narratorVoiceId)
        assertEquals("system:global-dial", read.dialogueVoiceId)
        assertEquals(1.0f, read.narratorSpeed, 0f)
        assertEquals(1.1f, read.dialogueSpeed, 0f)
        assertFalse(read.readOnly)
    }

    @Test
    fun fallback_missingKeysUseGlobals() {
        val manifest = Manifest(
            specVersion = "2.0",
            id = "book-1",
            title = "T",
            type = "epub",
            chapters = emptyList(),
            voices = emptyMap()
        )
        val read = BookVoices.read(manifest, globals())
        assertEquals("system:global-narr", read.narratorVoiceId)
        assertEquals("system:global-dial", read.dialogueVoiceId)
    }

    @Test
    fun unsetDialogue_meansSameAsNarrator() {
        val voices = BookVoices(
            narratorVoiceId = "system:global-narr",
            dialogueVoiceId = null,
            narratorSpeed = 1.0f,
            dialogueSpeed = 1.0f,
            readOnly = false
        )
        assertEquals("system:global-narr", voices.resolvedDialogueVoiceId())
        val written = voices.toManifestVoices()
        assertEquals("system:global-narr", voices.resolvedDialogueVoiceId())
        assertTrue(written.containsKey("narrator"))
        assertTrue(written.containsKey("dialogue"))
    }

    @Test
    fun read_singleRoleManifestUsesNarratorForDialogue() {
        val manifest = Manifest(
            specVersion = "2.0",
            id = "book-1",
            title = "T",
            type = "epub",
            chapters = emptyList(),
            voices = mapOf("narrator" to voiceEntry("system", "global-narr", 1.25))
        )
        val read = BookVoices.read(manifest, globals())
        assertEquals("system:global-narr", read.narratorVoiceId)
        assertNull(read.dialogueVoiceId)
        assertEquals("system:global-narr", read.resolvedDialogueVoiceId())
    }

    @Test
    fun readOnly_scribe11SingleNarratorFlagged() {
        val manifest = Manifest(
            specVersion = "1.1",
            id = "scribe-1",
            title = "Scribe Book",
            type = "epub",
            chapters = emptyList(),
            voices = mapOf("narrator" to voiceEntry("kokoro", "af_heart"))
        )
        val read = BookVoices.read(manifest, globals())
        assertTrue(read.readOnly)
        assertEquals("kokoro:af_heart", read.narratorVoiceId)
        assertNull(read.dialogueVoiceId)
        assertEquals("kokoro:af_heart", read.resolvedDialogueVoiceId())
    }

    @Test
    fun readOnly_extraKeysFlaggedEvenOn20() {
        val manifest = deviceManifest(extraKeys = mapOf("Alice" to "bf_isabella"))
        val read = BookVoices.read(manifest, globals())
        assertTrue(read.readOnly)
    }

    @Test
    fun readOnly_editingRefusedWithPlainMessage() {
        val manifest = Manifest(
            specVersion = "1.1",
            id = "scribe-1",
            title = "Scribe Book",
            type = "epub",
            chapters = emptyList(),
            voices = mapOf("narrator" to voiceEntry("kokoro", "af_heart"))
        )
        val read = BookVoices.read(manifest, globals())
        val narrator = read.withNarratorVoice("system:other")
        assertTrue(narrator.isFailure)
        assertTrue((narrator.exceptionOrNull()?.message ?: "").contains("read-only"))
        val dialogue = read.withDialogueVoice("system:other")
        assertTrue(dialogue.isFailure)
        val speed = read.withSpeed(TtsRole.Narrator, 1.5f)
        assertTrue(speed.isFailure)
        val target = FakeTtsEngine("system", listOf("system:other"))
        assertTrue(read.switchEngine(target).isFailure)
    }

    @Test
    fun validation_okForKnownVoices() {
        val voices = BookVoices(
            narratorVoiceId = "system:global-narr",
            dialogueVoiceId = "piper:en_US-lessac-low",
            narratorSpeed = 1.0f,
            dialogueSpeed = 1.0f,
            readOnly = false
        )
        assertTrue(voices.validate(registry()).isSuccess)
    }

    @Test
    fun validation_singleRoleValidatesNarratorOnce() {
        val voices = BookVoices(
            narratorVoiceId = "system:global-narr",
            dialogueVoiceId = null,
            narratorSpeed = 1.0f,
            dialogueSpeed = 1.0f,
            readOnly = false
        )
        assertTrue(voices.validate(registry()).isSuccess)
    }

    @Test
    fun validation_blankNarratorNamesRole() {
        val voices = BookVoices(
            narratorVoiceId = "",
            dialogueVoiceId = null,
            narratorSpeed = 1.0f,
            dialogueSpeed = 1.0f,
            readOnly = false
        )
        val message = voices.validate(registry()).exceptionOrNull()?.message ?: ""
        assertTrue("narrator" in message)
    }

    @Test
    fun validation_idWithoutNamespaceNamesVoice() {
        val voices = BookVoices(
            narratorVoiceId = "no-namespace",
            dialogueVoiceId = null,
            narratorSpeed = 1.0f,
            dialogueSpeed = 1.0f,
            readOnly = false
        )
        val message = voices.validate(registry()).exceptionOrNull()?.message ?: ""
        assertTrue("no-namespace" in message)
    }

    @Test
    fun validation_unknownEngineNamesEngine() {
        val voices = BookVoices(
            narratorVoiceId = "kokoro:af_heart",
            dialogueVoiceId = null,
            narratorSpeed = 1.0f,
            dialogueSpeed = 1.0f,
            readOnly = false
        )
        val message = voices.validate(registry()).exceptionOrNull()?.message ?: ""
        assertTrue("kokoro:af_heart" in message)
        assertTrue("kokoro" in message)
    }

    @Test
    fun validation_missingVoiceNamesModelPack() {
        val voices = BookVoices(
            narratorVoiceId = "piper:gone-voice",
            dialogueVoiceId = null,
            narratorSpeed = 1.0f,
            dialogueSpeed = 1.0f,
            readOnly = false
        )
        val message = voices.validate(registry()).exceptionOrNull()?.message ?: ""
        assertTrue("piper:gone-voice" in message)
        assertTrue("model pack" in message)
    }

    @Test
    fun mapping_keepRuleWhenTargetOffersCurrent() {
        val voices = BookVoices(
            narratorVoiceId = "system:global-narr",
            dialogueVoiceId = "system:global-dial",
            narratorSpeed = 1.0f,
            dialogueSpeed = 1.0f,
            readOnly = false
        )
        val target = FakeTtsEngine(
            namespace = "system",
            voiceIds = listOf("system:global-dial", "system:global-narr", "system:other")
        )
        val preview = voices.previewEngineSwitch(target)!!
        assertEquals(VoiceMappingRule.KEPT, preview.mappings.getValue(TtsRole.Narrator).rule)
        assertEquals("system:global-narr", preview.mappings.getValue(TtsRole.Narrator).toVoiceId)
        assertEquals(VoiceMappingRule.KEPT, preview.mappings.getValue(TtsRole.Dialogue).rule)
    }

    @Test
    fun mapping_sameLocaleRule() {
        val voices = BookVoices(
            narratorVoiceId = "kokoro:shared",
            dialogueVoiceId = null,
            narratorSpeed = 1.0f,
            dialogueSpeed = 1.0f,
            readOnly = false
        )
        val target = FakeTtsEngine(
            namespace = "piper",
            voiceIds = listOf("piper:other", "piper:shared")
        )
        val preview = voices.previewEngineSwitch(target)!!
        assertEquals(VoiceMappingRule.SAME_LOCALE, preview.mappings.getValue(TtsRole.Narrator).rule)
        assertEquals("piper:shared", preview.mappings.getValue(TtsRole.Narrator).toVoiceId)
    }

    @Test
    fun mapping_firstSortedRule() {
        val voices = BookVoices(
            narratorVoiceId = "kokoro:gone",
            dialogueVoiceId = null,
            narratorSpeed = 1.0f,
            dialogueSpeed = 1.0f,
            readOnly = false
        )
        val target = FakeTtsEngine(
            namespace = "piper",
            voiceIds = listOf("piper:en_US-ryan-low", "piper:en_US-lessac-low")
        )
        val preview = voices.previewEngineSwitch(target)!!
        assertEquals(VoiceMappingRule.FIRST_SORTED, preview.mappings.getValue(TtsRole.Narrator).rule)
        assertEquals("piper:en_US-lessac-low", preview.mappings.getValue(TtsRole.Narrator).toVoiceId)
    }

    @Test
    fun switchEngine_remapsBothAndKeepsSpeeds() {
        val voices = BookVoices(
            narratorVoiceId = "system:global-narr",
            dialogueVoiceId = "system:global-dial",
            narratorSpeed = 1.25f,
            dialogueSpeed = 0.75f,
            readOnly = false
        )
        val target = FakeTtsEngine(
            namespace = "piper",
            voiceIds = listOf("piper:en_US-lessac-low")
        )
        val (switched, preview) = voices.switchEngine(target).getOrThrow()
        assertEquals("piper:en_US-lessac-low", switched.narratorVoiceId)
        assertEquals("piper:en_US-lessac-low", switched.dialogueVoiceId)
        assertEquals(1.25f, switched.narratorSpeed, 0f)
        assertEquals(0.75f, switched.dialogueSpeed, 0f)
        assertEquals("piper", preview.targetEngine)
    }

    @Test
    fun switchEngine_preservesUnsetDialogue() {
        val voices = BookVoices(
            narratorVoiceId = "system:global-narr",
            dialogueVoiceId = null,
            narratorSpeed = 1.0f,
            dialogueSpeed = 1.0f,
            readOnly = false
        )
        val target = FakeTtsEngine(
            namespace = "piper",
            voiceIds = listOf("piper:en_US-lessac-low")
        )
        val (switched, _) = voices.switchEngine(target).getOrThrow()
        assertNull(switched.dialogueVoiceId)
        assertEquals("piper:en_US-lessac-low", switched.resolvedDialogueVoiceId())
    }

    @Test
    fun needsCopy_placeholdersNeedCopyRealOnesDoNot() {
        assertTrue(BookVoices.needsFirstRenderCopy(deviceManifest()))
        val real = deviceManifest(
            narrator = "system",
            narratorVoice = "global-narr",
            dialogue = "system",
            dialogueVoice = "global-dial"
        )
        assertFalse(BookVoices.needsFirstRenderCopy(real))
        val singleRole = Manifest(
            specVersion = "2.0",
            id = "book-1",
            title = "T",
            type = "epub",
            chapters = emptyList(),
            voices = mapOf("narrator" to voiceEntry("system", "global-narr"))
        )
        assertFalse(BookVoices.needsFirstRenderCopy(singleRole))
        val scribe = Manifest(
            specVersion = "1.1",
            id = "s",
            title = "S",
            type = "epub",
            chapters = emptyList(),
            voices = mapOf("narrator" to voiceEntry("kokoro", "af_heart"))
        )
        assertFalse(BookVoices.needsFirstRenderCopy(scribe))
    }

    @Test
    fun firstRenderCopy_copiesGlobals() {
        val copied = BookVoices.firstRenderCopy(globals())
        assertEquals("system:global-narr", copied.narratorVoiceId)
        assertEquals("system:global-dial", copied.dialogueVoiceId)
        assertFalse(copied.readOnly)
    }

    @Test
    fun speeds_clampedOnRead() {
        val manifest = deviceManifest(
            narrator = "system",
            narratorVoice = "global-narr",
            dialogue = "system",
            dialogueVoice = "global-dial",
            narratorSpeed = 9.0,
            dialogueSpeed = 0.1
        )
        val read = BookVoices.read(manifest, globals())
        assertEquals(2.0f, read.narratorSpeed, 0f)
        assertEquals(0.5f, read.dialogueSpeed, 0f)
    }
}
