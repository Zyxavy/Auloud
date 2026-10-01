package app.auloud.player.bundle

import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * WP2: reads and parses `manifest.json` from a bundle directory.
 *
 * Returns `Result.success(Manifest)` on valid input, `Result.failure` with a
 * message naming `manifest.json` and the rule broken otherwise (missing file,
 * malformed JSON, missing required field).
 *
 * API 24 safe: uses `java.io.File` only.
 */
object BundleParser {

    internal val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun parse(bundleDir: File): Result<Manifest> {
        val manifestFile = File(bundleDir, "manifest.json")
        val text: String
        try {
            text = manifestFile.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            return Result.failure(
                IOException("manifest.json: cannot read ${manifestFile.path}: ${e.message}", e)
            )
        } catch (e: SecurityException) {
            return Result.failure(
                IOException("manifest.json: cannot read ${manifestFile.path}: ${e.message}", e)
            )
        }
        return parseText(text)
    }

    /** Parses an in-memory `manifest.json` payload (used by tests for the unknown-keys case). */
    fun parseText(text: String): Result<Manifest> {
        return try {
            Result.success(json.decodeFromString(Manifest.serializer(), text))
        } catch (e: SerializationException) {
            Result.failure(
                IllegalArgumentException("manifest.json: invalid or incomplete manifest: ${e.message}", e)
            )
        } catch (e: IllegalArgumentException) {
            Result.failure(
                IllegalArgumentException("manifest.json: invalid or incomplete manifest: ${e.message}", e)
            )
        }
    }
}
