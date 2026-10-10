package app.auloud.player.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VC1: the `core` licenses screen must list exactly what `core` ships.
 * No bundled-GPL rows, jsoup present (the D-126 survey gap), and a
 * header that is true for this flavor (Apache-2.0, no GPL shipped).
 */
class FlavorLicensesTest {

    @Test
    fun core_shipsJsoup() {
        val jsoup = flavorLicenses.first { it.name == "jsoup" }
        assertEquals("1.23.2", jsoup.version)
        assertEquals("MIT", jsoup.license)
    }

    @Test
    fun core_claimsNoBundledGpl() {
        assertTrue(
            "no full-only bundled rows in core",
            flavorLicenses.none {
                it.name == "sherpa-onnx" ||
                    it.name == "onnxruntime" ||
                    it.name == "espeak-ng (bundled)"
            }
        )
        flavorLicenses.filter { it.license.contains("GPL") }.forEach {
            assertTrue("GPL row must read not-bundled: ${it.name}", it.note.contains("Not bundled"))
        }
    }

    @Test
    fun core_headerIsTrueForThisFlavor() {
        assertTrue(flavorLicenseHeader.contains("Apache-2.0"))
        assertTrue(flavorLicenseHeader.contains("no GPL"))
    }
}
