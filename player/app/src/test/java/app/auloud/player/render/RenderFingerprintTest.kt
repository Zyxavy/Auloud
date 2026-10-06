package app.auloud.player.render

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN4: [RenderFingerprint] plus [SpoolIndex] unit tests (pure JVM).
 */
class RenderFingerprintTest {

    private fun fingerprint() = RenderFingerprint(
        engine = "system",
        voices = mapOf("narrator" to "system:narr", "dialogue" to "system:dial"),
        speeds = mapOf("narrator" to 1.0f, "dialogue" to 1.05f),
        engineVersions = mapOf("system" to "tab-e-1")
    )

    @Test
    fun fileTag_isStableEightHex() {
        val first = fingerprint()
        assertEquals(first.fileTag(), fingerprint().fileTag())
        assertTrue(first.fileTag().matches(Regex("[0-9a-f]{8}")))
    }

    @Test
    fun fileTag_changesWithAnyKeyPart() {
        val base = fingerprint().fileTag()
        val voices = fingerprint().copy(
            voices = mapOf("narrator" to "system:other", "dialogue" to "system:dial")
        )
        val speeds = fingerprint().copy(
            speeds = mapOf("narrator" to 1.0f, "dialogue" to 1.5f)
        )
        val versions = fingerprint().copy(engineVersions = mapOf("system" to "tab-e-2"))
        val engine = fingerprint().copy(engine = "piper")
        for (other in listOf(voices, speeds, versions, engine)) {
            assertTrue(other.fileTag() != base)
        }
    }

    @Test
    fun toJsonObject_matchesSpecShape() {
        val obj = fingerprint().toJsonObject()
        assertEquals("system", (obj["engine"] as JsonPrimitive).content)
        val voices = obj["voices"] as JsonObject
        assertEquals("system:narr", (voices["narrator"] as JsonPrimitive).content)
        val speeds = obj["speeds"] as JsonObject
        val speed = (speeds["dialogue"] as JsonPrimitive).doubleOrNull
        assertTrue(speed != null && kotlin.math.abs(speed - 1.05) < 1e-6)
        val versions = obj["engine_versions"] as JsonObject
        assertEquals("tab-e-1", (versions["system"] as JsonPrimitive).content)
    }

    @Test
    fun fromJsonObject_roundTrips() {
        val original = fingerprint()
        assertEquals(original, RenderFingerprint.fromJsonObject(original.toJsonObject()))
    }

    @Test
    fun fromJsonObject_rejectsMalformed() {
        val obj = fingerprint().toJsonObject()
        val noSpeeds = buildJsonObject {
            put("engine", "system")
            put("voices", obj["voices"] as JsonObject)
            put("engine_versions", obj["engine_versions"] as JsonObject)
        }
        assertNull(RenderFingerprint.fromJsonObject(noSpeeds))
        val blankVersion = fingerprint().copy(engineVersions = mapOf("system" to " ")).toJsonObject()
        assertNull(RenderFingerprint.fromJsonObject(blankVersion))
    }

    @Test
    fun combineEngine_singleAndMixed() {
        assertEquals("system", RenderFingerprint.combineEngine("system", "system"))
        assertEquals("piper+system", RenderFingerprint.combineEngine("system", "piper"))
    }

    @Test
    fun spoolIndex_roundTripsWithSplitPairs() {
        val index = SpoolChapterIndex(
            chapter = 7,
            fingerprint = fingerprint(),
            sentences = listOf(
                SpoolSentenceEntry(1, "narrator", "ch007-s001-ab12cd34.pcm", 22050, 2200, null, 0.5f),
                SpoolSentenceEntry(2, "dialogue", "ch007-s002-ab12cd34.pcm", 22050, 1100, 1, 0.8f)
            ),
            peaks = mapOf("narrator" to 0.5f, "dialogue" to 0.8f)
        )
        assertEquals(index, SpoolIndex.parse(SpoolIndex.render(index)))
    }

    @Test
    fun spoolIndex_rejectsCorrupt() {
        assertNull(SpoolIndex.parse("not json"))
        assertNull(SpoolIndex.parse("{}"))
        val tampered = SpoolIndex.render(
            SpoolChapterIndex(7, fingerprint(), emptyList(), mapOf("narrator" to 0f))
        ).replace("\"version\":1", "\"version\":99")
        assertNull(SpoolIndex.parse(tampered))
    }
}
