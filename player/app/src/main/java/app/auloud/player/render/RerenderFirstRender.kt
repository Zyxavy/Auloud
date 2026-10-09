package app.auloud.player.render

import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.Manifest
import app.auloud.player.tts.BookVoices
import app.auloud.player.tts.TtsRole
import app.auloud.player.tts.TtsVoiceStore
import app.auloud.player.tts.clampTtsSpeed
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * VS3: first-render voice seam (VS1 helpers wired here, D-113).
 *
 * New books copy the global narrator and dialogue voices at first
 * render (manifest placeholders `system` plus `default` from ingest);
 * each book then keeps its own voices. Changing globals never touches
 * existing books.
 *
 * Wiring (exact VS1 seam): before voice resolution in the render path,
 * call [BookVoices.needsFirstRenderCopy], then [BookVoices.firstRenderCopy]
 * plus [BookVoices.toManifestVoices] plus a storage write, then resolve
 * from the book. The service calls [ensureFirstRenderCopy] which does
 * the pure JSON edit; the atomic write itself rides
 * [RenderFinalize.atomicWriteText] like every other manifest write.
 *
 * [BookVoiceStore] adapts an in-memory [BookVoices] to [TtsVoiceStore]
 * so the existing [RenderVoices.resolveForRoles] seam resolves
 * per-book voices with no new engine code (narrowed synthesis in VS3
 * resolves only used roles through this adapter).
 *
 * Pure apart from the passed store; API 24 safe, no new dependency.
 */
class BookVoiceStore(bookVoices: BookVoices) : TtsVoiceStore {
    var narratorId: String = bookVoices.narratorVoiceId
    var dialogueId: String = bookVoices.resolvedDialogueVoiceId()
    var narratorSpeed: Float = clampTtsSpeed(bookVoices.narratorSpeed)
    var dialogueSpeed: Float = clampTtsSpeed(bookVoices.dialogueSpeed)

    override fun voiceId(role: TtsRole): String =
        if (role == TtsRole.Narrator) narratorId else dialogueId

    override fun setVoiceId(role: TtsRole, voiceId: String) {
        if (role == TtsRole.Narrator) narratorId = voiceId else dialogueId = voiceId
    }

    override fun speed(role: TtsRole): Float =
        if (role == TtsRole.Narrator) narratorSpeed else dialogueSpeed

    override fun setSpeed(role: TtsRole, speed: Float) {
        val clamped = clampTtsSpeed(speed)
        if (role == TtsRole.Narrator) narratorSpeed = clamped else dialogueSpeed = clamped
    }
}

object RerenderFirstRender {

    private val prettyJson = Json { prettyPrint = true; explicitNulls = false }

    /**
     * Returns the updated manifest JSON with globals copied into the
     * book voices, or null when no copy is needed (already real voices
     * or a read-only Scribe book). Unknown manifest keys are preserved
     * (raw object edit, same shape as [RenderFinalize]).
     */
    fun ensureFirstRenderCopy(
        rawManifestJson: String,
        globals: TtsVoiceStore
    ): Result<String?> {
        val manifest = BundleParser.parseText(rawManifestJson).getOrElse {
            return Result.failure(it)
        }
        if (!BookVoices.needsFirstRenderCopy(manifest)) {
            return Result.success(null)
        }
        val copy = BookVoices.firstRenderCopy(globals)
        val entries = try {
            copy.toManifestVoices()
        } catch (e: Exception) {
            return Result.failure(
                IOException("manifest.json: first-render voices invalid (${e.message})", e)
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
                    "manifest.json: first-render voices failed self-check " +
                        "(${check.exceptionOrNull()?.message})"
                )
            )
        }
        return Result.success(text)
    }

    /**
     * Reads per-book voices for rendering (manifest plus global
     * fallback, read-only flag included). Thin wrapper so the service
     * seam stays explicit in reviews.
     */
    fun readBookVoices(manifest: Manifest, globals: TtsVoiceStore): BookVoices =
        BookVoices.read(manifest, globals)
}
