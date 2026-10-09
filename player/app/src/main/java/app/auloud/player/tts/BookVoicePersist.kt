package app.auloud.player.tts

import app.auloud.player.bundle.BundleParser
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * VS4: persists edited [BookVoices] into a manifest JSON string.
 *
 * Raw-object edit like the VS3 first-render copy: unknown manifest keys
 * are preserved, only `voices` is replaced with
 * [BookVoices.toManifestVoices] (both roles written, dialogue resolved,
 * speeds clamped, pitch 1.0). Refuses read-only books with the model
 * refusal message (the screen shows the same text as its banner).
 *
 * Pure Kotlin, API 24 safe, no storage access (the view-model writes
 * the returned string through `BundleStorage`).
 */
object BookVoicePersist {

    private val prettyJson = Json { prettyPrint = true; explicitNulls = false }

    /**
     * Returns the updated manifest JSON with [voices] written, or a
     * failure naming the file and the rule when the manifest is
     * unreadable, the book is read-only, or a voice id is blank or has
     * no engine namespace.
     */
    fun writeBookVoices(rawManifestJson: String, voices: BookVoices): Result<String> {
        val manifest = BundleParser.parseText(rawManifestJson).getOrElse {
            return Result.failure(it)
        }
        if (BookVoices.isReadOnly(manifest)) {
            return Result.failure(IOException("manifest.json: ${BookVoices.READ_ONLY_MESSAGE}"))
        }
        if (voices.readOnly) {
            return Result.failure(IOException("manifest.json: ${BookVoices.READ_ONLY_MESSAGE}"))
        }
        val entries = try {
            voices.toManifestVoices()
        } catch (e: Exception) {
            return Result.failure(
                IOException("manifest.json: book voices invalid (${e.message})", e)
            )
        }
        val root = try {
            BundleParser.json.parseToJsonElement(rawManifestJson) as? JsonObject
                ?: return Result.failure(
                    IOException("manifest.json: manifest must be an object")
                )
        } catch (e: Exception) {
            return Result.failure(
                IOException("manifest.json: manifest unreadable (${e.message})", e)
            )
        }
        val voicesObj = kotlinx.serialization.json.buildJsonObject {
            for ((role, entry) in entries.toSortedMap()) put(role, entry)
        }
        val edited = root.toMutableMap()
        edited["voices"] = voicesObj
        val updated = JsonObject(edited)
        val text = prettyJson.encodeToString(JsonObject.serializer(), updated)
        val check = BundleParser.parseText(text)
        if (check.isFailure) {
            return Result.failure(
                IOException(
                    "manifest.json: voice update failed self-check " +
                        "(${check.exceptionOrNull()?.message})"
                )
            )
        }
        return Result.success(text)
    }
}
