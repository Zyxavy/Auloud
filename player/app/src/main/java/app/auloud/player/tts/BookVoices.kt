package app.auloud.player.tts

import app.auloud.player.bundle.Manifest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * VS1: per-book voice settings (D-113 global defaults plus per-book
 * voices, D-118 Scribe read-only).
 *
 * A book keeps its own narrator voice, dialogue voice and per-role
 * speeds in its manifest `voices` entries. Each entry holds
 * `{engine, voice, speed, pitch}` (pitch always written 1.0 and ignored
 * on read). The namespaced TTS id is `engine` plus `:` plus `voice`
 * (for example manifest `system` plus `default` reads as
 * `system:default`), matching the [TtsVoice] convention.
 *
 * Dialogue unset (null or blank) means "same as narrator": the book
 * carries a single set role in memory. On disk both entries are still
 * written (dialogue resolved to the narrator id) so the 2.0 validator
 * keeps passing; VS2 owns the fingerprint narrowing and never sees a
 * format change from here.
 *
 * Read-only: Scribe (PC) bundles (spec other than `2.0`, or any voice
 * key outside narrator plus dialogue) expose their voices for display
 * but every editing path refuses with [READ_ONLY_MESSAGE] (plain text;
 * UI polish is VS4). Global [TtsVoiceStore] values are the fallback for
 * editable books only: missing, blank or placeholder entries fall back
 * to globals, and fresh imports (placeholder `system` plus `default`
 * from ingest) read as globals until the first render persists them.
 *
 * First-render seam (VS3 owns the wiring, VS1 provides pure helpers
 * only): before the first render the service must call
 * [needsFirstRenderCopy], then [firstRenderCopy] plus
 * [toManifestVoices] plus a storage write, then resolve from the book.
 * Call site: `RenderService.resolveVoices` (reads globals today) called
 * from the render loop before the first chapter. No service change in
 * VS1; this file never touches storage.
 *
 * Pure Kotlin, API 24 safe: kotlinx.serialization only, no java.time,
 * no nio, no Android types.
 */
data class BookVoices(
    val narratorVoiceId: String,
    /** Null or blank means same as narrator (single set role). */
    val dialogueVoiceId: String?,
    val narratorSpeed: Float,
    val dialogueSpeed: Float,
    val readOnly: Boolean
) {

    /** Dialogue voice id with the same-as-narrator rule applied. */
    fun resolvedDialogueVoiceId(): String =
        if (dialogueVoiceId.isNullOrBlank()) narratorVoiceId else dialogueVoiceId

    /**
     * Manifest `voices` map for this book (both roles written, dialogue
     * resolved, speeds clamped, pitch 1.0). Throws when an id is blank
     * or has no engine namespace, so callers validate first.
     */
    fun toManifestVoices(): Map<String, JsonObject> {
        val narrator = splitVoiceId(narratorVoiceId, "narrator")
        val dialogueId = resolvedDialogueVoiceId()
        val dialogue = splitVoiceId(dialogueId, "dialogue")
        val narratorEntry = buildJsonObject {
            put("engine", narrator.first)
            put("voice", narrator.second)
            put("speed", clampTtsSpeed(narratorSpeed).toDouble())
            put("pitch", 1.0)
        }
        val dialogueEntry = buildJsonObject {
            put("engine", dialogue.first)
            put("voice", dialogue.second)
            put("speed", clampTtsSpeed(dialogueSpeed).toDouble())
            put("pitch", 1.0)
        }
        return mapOf("narrator" to narratorEntry, "dialogue" to dialogueEntry)
    }

    /**
     * Validation against live engines (engine present, voice offered;
     * Piper completeness rides in `voices()`, so a missing pack reads as
     * "missing model pack or voice"). Dialogue resolves first, so a
     * single-role book validates its one voice. First failure wins.
     */
    fun validate(registry: EngineRegistry): Result<Unit> {
        val narratorId = narratorVoiceId
        if (narratorId.isBlank()) {
            return Result.failure(
                IllegalStateException("narrator voice is not set (choose one in voice settings)")
            )
        }
        val parsedNarrator = TtsVoice.parse(narratorId)
            ?: return Result.failure(
                IllegalStateException(
                    "narrator voice \"$narratorId\" has no engine namespace " +
                        "(expected \"engine:voice\")"
                )
            )
        val engineNarrator = registry.engineFor(narratorId)
            ?: return Result.failure(
                IllegalStateException(
                    "narrator voice \"$narratorId\" needs engine \"${parsedNarrator.engine}\" " +
                        "(engine not installed)"
                )
            )
        if (engineNarrator.voices().none { it.id == narratorId }) {
            return Result.failure(
                IllegalStateException(
                    "narrator voice \"$narratorId\" is not available from " +
                        "engine \"${parsedNarrator.engine}\" (missing model pack or voice)"
                )
            )
        }
        val dialogueId = resolvedDialogueVoiceId()
        if (dialogueId.isBlank()) {
            return Result.failure(
                IllegalStateException("dialogue voice is not set (choose one in voice settings)")
            )
        }
        val parsedDialogue = TtsVoice.parse(dialogueId)
            ?: return Result.failure(
                IllegalStateException(
                    "dialogue voice \"$dialogueId\" has no engine namespace " +
                        "(expected \"engine:voice\")"
                )
            )
        val engineDialogue = registry.engineFor(dialogueId)
            ?: return Result.failure(
                IllegalStateException(
                    "dialogue voice \"$dialogueId\" needs engine \"${parsedDialogue.engine}\" " +
                        "(engine not installed)"
                )
            )
        if (engineDialogue.voices().none { it.id == dialogueId }) {
            return Result.failure(
                IllegalStateException(
                    "dialogue voice \"$dialogueId\" is not available from " +
                        "engine \"${parsedDialogue.engine}\" (missing model pack or voice)"
                )
            )
        }
        return Result.success(Unit)
    }

    /** Narrator voice change (refuses when read-only). */
    fun withNarratorVoice(voiceId: String): Result<BookVoices> {
        if (readOnly) return Result.failure(IllegalStateException(READ_ONLY_MESSAGE))
        if (voiceId.isBlank()) {
            return Result.failure(
                IllegalStateException("narrator voice is not set (choose one in voice settings)")
            )
        }
        return Result.success(copy(narratorVoiceId = voiceId))
    }

    /**
     * Dialogue voice change (refuses when read-only). Null or blank
     * clears to same-as-narrator (single set role).
     */
    fun withDialogueVoice(voiceId: String?): Result<BookVoices> {
        if (readOnly) return Result.failure(IllegalStateException(READ_ONLY_MESSAGE))
        if (voiceId.isNullOrBlank()) return Result.success(copy(dialogueVoiceId = null))
        return Result.success(copy(dialogueVoiceId = voiceId))
    }

    /** Per-role speed change, clamped (refuses when read-only). */
    fun withSpeed(role: TtsRole, speed: Float): Result<BookVoices> {
        if (readOnly) return Result.failure(IllegalStateException(READ_ONLY_MESSAGE))
        val clamped = clampTtsSpeed(speed)
        return if (role == TtsRole.Narrator) {
            Result.success(copy(narratorSpeed = clamped))
        } else {
            Result.success(copy(dialogueSpeed = clamped))
        }
    }

    /**
     * Mapping preview for switching both roles to [target] (null when
     * the target offers nothing). Uses [VoiceMapper] per role, then
     * labels which rule fired by comparing ids (kept, same local id,
     * else first sorted). Dialogue previews from the resolved id; the
     * switch itself keeps an unset dialogue unset.
     */
    fun previewEngineSwitch(target: TtsEngine): EngineSwitchPreview? {
        if (target.voices().isEmpty()) return null
        val narratorTo = VoiceMapper.mapVoice(
            narratorVoiceId.takeUnless { it.isBlank() }?.let { TtsVoice.parse(it) },
            target,
            TtsRole.Narrator
        ) ?: return null
        val dialogueFrom = resolvedDialogueVoiceId()
        val dialogueTo = VoiceMapper.mapVoice(
            dialogueFrom.takeUnless { it.isBlank() }?.let { TtsVoice.parse(it) },
            target,
            TtsRole.Dialogue
        ) ?: return null
        return EngineSwitchPreview(
            targetEngine = target.namespace,
            mappings = mapOf(
                TtsRole.Narrator to RoleVoiceMapping(
                    role = TtsRole.Narrator,
                    fromVoiceId = narratorVoiceId,
                    toVoiceId = narratorTo.id,
                    rule = ruleFor(narratorVoiceId, narratorTo)
                ),
                TtsRole.Dialogue to RoleVoiceMapping(
                    role = TtsRole.Dialogue,
                    fromVoiceId = dialogueFrom,
                    toVoiceId = dialogueTo.id,
                    rule = ruleFor(dialogueFrom, dialogueTo)
                )
            )
        )
    }

    /**
     * Switch both roles to [target] via [VoiceMapper] (refuses when
     * read-only, fails when the target offers nothing). Speeds stay put;
     * an unset dialogue stays unset (single role preserved).
     */
    fun switchEngine(target: TtsEngine): Result<Pair<BookVoices, EngineSwitchPreview>> {
        if (readOnly) return Result.failure(IllegalStateException(READ_ONLY_MESSAGE))
        val preview = previewEngineSwitch(target)
            ?: return Result.failure(
                IllegalStateException(
                    "engine \"${target.namespace}\" offers no voices (cannot switch)"
                )
            )
        val narratorTo = preview.mappings.getValue(TtsRole.Narrator).toVoiceId
        val dialogueTo = if (dialogueVoiceId.isNullOrBlank()) {
            null
        } else {
            preview.mappings.getValue(TtsRole.Dialogue).toVoiceId
        }
        return Result.success(copy(narratorVoiceId = narratorTo, dialogueVoiceId = dialogueTo) to preview)
    }

    companion object {
        /** Plain refusal for Scribe books (model level; UI polish is VS4). */
        const val READ_ONLY_MESSAGE =
            "Scribe books are read-only for voices (PC audio kept as rendered)"

        /** Placeholder ingest writes for unrendered device books. */
        const val PLACEHOLDER_ENGINE = "system"
        const val PLACEHOLDER_VOICE = "default"

        /**
         * True for Scribe (PC) books: spec other than `2.0`, or any voice
         * key outside narrator plus dialogue (per-character casts).
         */
        fun isReadOnly(manifest: Manifest): Boolean {
            if (manifest.specVersion != "2.0") return true
            for (key in manifest.voices.keys) {
                if (key != "narrator" && key != "dialogue") return true
            }
            return false
        }

        /**
         * Reads per-book voices from manifest entries with global
         * fallback (editable books only). Missing, blank or placeholder
         * narrator falls back to the global narrator; a real dialogue
         * entry wins, a placeholder dialogue falls back to the global
         * dialogue, a missing dialogue stays unset when the book has a
         * real narrator (single role) and falls back to the global
         * dialogue when the book has no real entries. Read-only books
         * never touch globals: narrator from the manifest (possibly
         * blank), dialogue from the manifest or unset, speeds from the
         * manifest or 1.0.
         */
        fun read(manifest: Manifest, globals: TtsVoiceStore): BookVoices {
            if (isReadOnly(manifest)) {
                val narratorEntry = manifest.voices["narrator"]
                val dialogueEntry = manifest.voices["dialogue"]
                val narratorId = voiceIdFromEntry(narratorEntry) ?: ""
                val dialogueId = voiceIdFromEntry(dialogueEntry)
                val narratorSpeed = speedFromEntry(narratorEntry) ?: DEFAULT_TTS_SPEED
                val dialogueSpeed = speedFromEntry(dialogueEntry) ?: narratorSpeed
                return BookVoices(
                    narratorVoiceId = narratorId,
                    dialogueVoiceId = dialogueId,
                    narratorSpeed = clampTtsSpeed(narratorSpeed),
                    dialogueSpeed = clampTtsSpeed(dialogueSpeed),
                    readOnly = true
                )
            }
            val narratorEntry = manifest.voices["narrator"]
            val dialogueEntry = manifest.voices["dialogue"]
            val bookNarrator = voiceIdFromEntry(narratorEntry)
                ?.takeUnless { isPlaceholder(narratorEntry) }
                ?.takeUnless { it.isBlank() }
            val narratorId = bookNarrator
                ?: globals.voiceId(TtsRole.Narrator).takeUnless { it.isBlank() }
                ?: ""
            val narratorSpeed = if (bookNarrator != null) {
                speedFromEntry(narratorEntry)
            } else {
                null
            } ?: globals.speed(TtsRole.Narrator)
            val bookDialogue = if (dialogueEntry == null) {
                null
            } else {
                voiceIdFromEntry(dialogueEntry)
                    ?.takeUnless { isPlaceholder(dialogueEntry) }
                    ?.takeUnless { it.isBlank() }
            }
            val dialogueId: String? = if (bookDialogue != null) {
                bookDialogue
            } else if (dialogueEntry != null && isPlaceholder(dialogueEntry)) {
                globals.voiceId(TtsRole.Dialogue).takeUnless { it.isBlank() }
            } else if (bookNarrator != null) {
                null
            } else {
                globals.voiceId(TtsRole.Dialogue).takeUnless { it.isBlank() }
            }
            val dialogueSpeed = if (bookDialogue != null) {
                speedFromEntry(dialogueEntry)
            } else {
                null
            } ?: if (dialogueId != null) {
                globals.speed(TtsRole.Dialogue)
            } else {
                narratorSpeed
            }
            return BookVoices(
                narratorVoiceId = narratorId,
                dialogueVoiceId = dialogueId,
                narratorSpeed = clampTtsSpeed(narratorSpeed),
                dialogueSpeed = clampTtsSpeed(dialogueSpeed),
                readOnly = false
            )
        }

        /**
         * True when an editable book still carries placeholder or missing
         * narrator (or a placeholder dialogue entry) and VS3 must persist
         * globals before the first render. Read-only books never need a
         * copy. A missing dialogue key is legal single-role and does not
         * trigger on its own.
         */
        fun needsFirstRenderCopy(manifest: Manifest): Boolean {
            if (isReadOnly(manifest)) return false
            if (isMissingOrPlaceholder(manifest.voices["narrator"])) return true
            val dialogueEntry = manifest.voices["dialogue"]
            if (dialogueEntry != null && isMissingOrPlaceholder(dialogueEntry)) return true
            return false
        }

        /**
         * Copies global defaults into a fresh [BookVoices] (blank global
         * dialogue stays unset, so a narrator-only setup renders single
         * role). VS3 persists this via [BookVoices.toManifestVoices].
         */
        fun firstRenderCopy(globals: TtsVoiceStore): BookVoices {
            val narratorId = globals.voiceId(TtsRole.Narrator)
            val dialogueId = globals.voiceId(TtsRole.Dialogue).takeUnless { it.isBlank() }
            return BookVoices(
                narratorVoiceId = narratorId,
                dialogueVoiceId = dialogueId,
                narratorSpeed = clampTtsSpeed(globals.speed(TtsRole.Narrator)),
                dialogueSpeed = clampTtsSpeed(globals.speed(TtsRole.Dialogue)),
                readOnly = false
            )
        }

        private fun voiceIdFromEntry(entry: JsonObject?): String? {
            if (entry == null) return null
            val engine = (entry["engine"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.trim()
                ?.takeUnless { it.isBlank() } ?: return null
            val voice = (entry["voice"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.trim()
                ?.takeUnless { it.isBlank() } ?: return null
            return "$engine:$voice"
        }

        private fun speedFromEntry(entry: JsonObject?): Float? {
            if (entry == null) return null
            val number = (entry["speed"] as? JsonPrimitive)
                ?.takeIf { !it.isString }?.doubleOrNull
                ?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            return number.toFloat()
        }

        private fun isPlaceholder(entry: JsonObject?): Boolean {
            if (entry == null) return false
            val engine = (entry["engine"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.trim()
            val voice = (entry["voice"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.trim()
            return engine == PLACEHOLDER_ENGINE && voice == PLACEHOLDER_VOICE
        }

        private fun isMissingOrPlaceholder(entry: JsonObject?): Boolean {
            if (entry == null) return true
            if (voiceIdFromEntry(entry).isNullOrBlank()) return true
            return isPlaceholder(entry)
        }

        private fun splitVoiceId(voiceId: String, role: String): Pair<String, String> {
            val parsed = TtsVoice.parse(voiceId)
                ?: throw IllegalArgumentException(
                    "$role voice \"$voiceId\" has no engine namespace " +
                        "(expected \"engine:voice\")"
                )
            return parsed.engine to voiceId.substringAfter(':')
        }

        private fun ruleFor(fromVoiceId: String, to: TtsVoice): VoiceMappingRule {
            if (fromVoiceId == to.id) return VoiceMappingRule.KEPT
            val fromLocal = fromVoiceId.substringAfter(':', "")
            val toLocal = to.id.substringAfter(':')
            if (fromLocal.isNotBlank() && fromLocal == toLocal) {
                return VoiceMappingRule.SAME_LOCALE
            }
            return VoiceMappingRule.FIRST_SORTED
        }
    }
}

/** Which VoiceMapper rule produced a mapping (in rank order). */
enum class VoiceMappingRule {
    KEPT,
    SAME_LOCALE,
    FIRST_SORTED
}

/** One role mapped from its current voice to the target engine voice. */
data class RoleVoiceMapping(
    val role: TtsRole,
    val fromVoiceId: String,
    val toVoiceId: String,
    val rule: VoiceMappingRule
)

/** Engine-switch preview: per-role mappings plus the target namespace. */
data class EngineSwitchPreview(
    val targetEngine: String,
    val mappings: Map<TtsRole, RoleVoiceMapping>
)
