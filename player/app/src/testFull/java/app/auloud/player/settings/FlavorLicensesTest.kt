package app.auloud.player.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VC1: the `full` licenses screen must list the bundled speech runtime
 * plus the packs, and carry the GPL-3.0 combined-work header.
 */
class FlavorLicensesTest {

    @Test
    fun full_shipsJsoupInBothFlavors() {
        val jsoup = flavorLicenses.first { it.name == "jsoup" }
        assertEquals("1.23.2", jsoup.version)
        assertEquals("MIT", jsoup.license)
    }

    @Test
    fun full_listsBundledSpeechRuntime() {
        val sherpa = flavorLicenses.first { it.name == "sherpa-onnx" }
        assertTrue(sherpa.version.contains("1.13.8"))
        val ort = flavorLicenses.first { it.name == "onnxruntime" }
        assertEquals("MIT", ort.license)
        val bundled = flavorLicenses.first { it.name == "espeak-ng (bundled)" }
        assertEquals("GPL-3.0-or-later", bundled.license)
        assertTrue(flavorLicenses.any { it.name == "Piper voice packs" })
    }

    @Test
    fun full_headerCarriesGplNotice() {
        assertTrue(flavorLicenseHeader.contains("GPL-3.0"))
    }
}
