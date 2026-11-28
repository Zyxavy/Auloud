package app.auloud.player.bundle

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP13: the Player validator agrees with the Scribe validator on every
 * parity fixture that has a matching bundle under `spec/fixtures/`
 * (`parity/expected/<name>.json` holds the Scribe verdict and rule ids).
 * The Player side runs the import-path validation (with `readText`), so
 * text-level rules are compared too. Only `valid` is compared.
 */
class ValidatorParityTest {

    /**
     * Fixtures where the Player is known to disagree with Scribe. The test
     * fails if one of these starts agreeing, so the list cannot go stale.
     */
    private val KNOWN_PLAYER_SUBSET: Map<String, String> = emptyMap()

    private fun repoDir(rel: String): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val direct = File(userDir, "../../$rel")
        if (direct.isDirectory) return direct
        var cur: File? = userDir
        while (cur != null) {
            val candidate = File(cur, rel)
            if (candidate.isDirectory) return candidate
            cur = cur.parentFile
        }
        return direct
    }

    /** Same validation the import path runs: parse, then validate with text reads. */
    private fun playerValid(dir: File): Boolean =
        BundleParser.parse(dir).fold(
            { manifest ->
                BundleValidator.validate(
                    dir,
                    manifest,
                    readText = { path -> File(path).takeIf { it.isFile }?.readText() },
                    hashSource = true
                ).isEmpty()
            },
            { false }
        )

    @Test
    fun playerMatchesScribeVerdictOnParityFixtures() {
        val expectedFiles = repoDir("parity/expected").listFiles { f -> f.name.endsWith(".json") }
            ?.sortedBy { it.name }.orEmpty()
        assertTrue("no parity expected files found", expectedFiles.isNotEmpty())
        val fixtures = repoDir("spec/fixtures")
        val compared = mutableListOf<String>()
        val unexpected = mutableListOf<String>()
        val stale = mutableListOf<String>()
        for (file in expectedFiles) {
            val name = file.name.removeSuffix(".json")
            val dir = File(fixtures, name)
            if (!dir.isDirectory) continue
            val expected = Json.parseToJsonElement(file.readText()).jsonObject
            val scribeValid = expected.getValue("valid").jsonPrimitive.boolean
            val scribeRules = expected.getValue("errors").jsonArray.joinToString(", ") {
                it.jsonObject.getValue("rule").jsonPrimitive.content
            }
            val player = playerValid(dir)
            compared += name
            val disagrees = player != scribeValid
            val known = name in KNOWN_PLAYER_SUBSET
            if (disagrees && !known) {
                unexpected += "$name (scribe valid=$scribeValid, player valid=$player, scribe rules: [$scribeRules])"
            }
            if (!disagrees && known) stale += name
        }
        assertTrue("no parity fixtures compared", compared.isNotEmpty())
        assertTrue(
            "Player disagrees with Scribe on ${unexpected.size} fixture(s) not in KNOWN_PLAYER_SUBSET: " +
                unexpected.joinToString("; "),
            unexpected.isEmpty()
        )
        assertTrue(
            "KNOWN_PLAYER_SUBSET fixtures now agree with Scribe, remove them: " + stale.joinToString(", "),
            stale.isEmpty()
        )
    }
}
