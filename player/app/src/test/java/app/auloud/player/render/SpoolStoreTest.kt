package app.auloud.player.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN4: [SpoolFiles], [SpoolPcm] and [SpoolIo] naming/encoding tests.
 */
class SpoolStoreTest {

    @Test
    fun pcmName_keysChapterSidAndFingerprint() {
        assertEquals(
            "ch007-s012-ab12cd34.pcm",
            SpoolFiles.pcmName(7, 12, "ab12cd34")
        )
        assertEquals(
            "spool/ch007-s001-ab12cd34.pcm",
            SpoolFiles.pcmPath("spool", 7, 1, "ab12cd34")
        )
        assertEquals("spool/ch007-index.json", SpoolFiles.indexPath("spool", 7))
        assertEquals("ch007-", SpoolFiles.chapterPrefix(7))
    }

    @Test
    fun pcm16_roundTripsLevels() {
        val samples = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f)
        val decoded = SpoolPcm.decodePcm16(SpoolPcm.encodeFloatToPcm16(samples))
        assertEquals(samples.size, decoded.size)
        for (i in samples.indices) {
            assertTrue(kotlin.math.abs(decoded[i] - samples[i]) < 1e-4f)
        }
    }

    @Test
    fun pcm16_clampsAndSilencesNonFinite() {
        val decoded = SpoolPcm.decodePcm16(
            SpoolPcm.encodeFloatToPcm16(
                floatArrayOf(2f, -2f, Float.NaN, Float.POSITIVE_INFINITY)
            )
        )
        assertTrue(kotlin.math.abs(decoded[0] - 1f) < 1e-4f)
        assertTrue(kotlin.math.abs(decoded[1] + 1f) < 1e-4f)
        assertEquals(0f, decoded[2])
        assertEquals(0f, decoded[3])
    }

    @Test
    fun peakOf_skipsNonFinite() {
        assertEquals(0.8f, SpoolPcm.peakOf(floatArrayOf(0.5f, -0.8f, 0.1f)))
        assertEquals(0.5f, SpoolPcm.peakOf(floatArrayOf(Float.NaN, 0.5f)))
        assertEquals(0f, SpoolPcm.peakOf(floatArrayOf()))
    }

    @Test
    fun javaIo_listsChapterPrefix() {
        val io = JavaFileSpoolIo()
        val dir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "auloud-spool-test-${System.nanoTime()}"
        )
        try {
            val spoolDir = dir.absolutePath
            io.writeBytes("$spoolDir/ch007-s001-ab12cd34.pcm", byteArrayOf(1, 2))
            io.writeBytes("$spoolDir/ch007-s002-zz99zz99.pcm", byteArrayOf(3, 4))
            io.writeBytes("$spoolDir/ch008-s001-ab12cd34.pcm", byteArrayOf(5, 6))
            val listed = io.listFiles(spoolDir, "ch007-", ".pcm")
            assertEquals(2, listed.size)
            assertTrue(listed.all { it.contains("ch007-") && it.endsWith(".pcm") })
            io.deleteIfExists("$spoolDir/ch007-s001-ab12cd34.pcm")
            assertEquals(1, io.listFiles(spoolDir, "ch007-", ".pcm").size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun indexParse_rejectsEmptySentences() {
        val index = SpoolChapterIndex(
            chapter = 1,
            fingerprint = RenderFingerprint(
                engine = "system",
                voices = mapOf("narrator" to "system:n", "dialogue" to "system:d"),
                speeds = mapOf("narrator" to 1f, "dialogue" to 1f),
                engineVersions = mapOf("system" to "v")
            ),
            sentences = emptyList(),
            peaks = mapOf("narrator" to 0f, "dialogue" to 0f)
        )
        assertEquals(index, SpoolIndex.parse(SpoolIndex.render(index)))
        assertNull(SpoolIndex.parse(""))
    }
}
