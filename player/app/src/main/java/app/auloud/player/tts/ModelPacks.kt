package app.auloud.player.tts

import java.io.File

/**
 * PW7a: sideloaded model-pack discovery (D-067), engine binding gated to PW7b.
 *
 * Packs are plain folders the user copies with a file manager — no picker,
 * no new dependency, no SAF: `<shared>/Auloud/models/<pack>/` and, when a
 * microSD card is mounted, `<sd>/Auloud/models/<pack>/` (File API sees
 * removable storage on API 24 with the storage permission). A pack is any
 * directory holding `.onnx` files; voice ids are the model stems (the
 * engine binding in PW7b resolves them per engine format).
 *
 * Nothing persists: scanning is a filename listing, cheap enough to run
 * on every settings visit. Deleting files is the user's file manager
 * (the app never deletes user models).
 *
 * Pure `java.io.File`: JVM-testable.
 */
object ModelPacks {

    const val MODELS_DIR_NAME = "models"

    private const val TOKENS_FILENAME = "tokens.txt"
    private const val ESPEAK_DIRNAME = "espeak-ng-data"

    /** KT2: an incomplete pack attempt plus the human reason. */
    data class PackProblem(
        val label: String,
        val reason: String
    )

    /** Pack roots to scan: shared-internal always, microSD when mounted. */
    fun roots(internalAuloudDir: File, removableRoot: String?): List<File> {
        val out = mutableListOf(File(internalAuloudDir, MODELS_DIR_NAME))
        if (!removableRoot.isNullOrBlank()) {
            out.add(File(File(removableRoot.trimEnd('/')), "Auloud/$MODELS_DIR_NAME"))
        }
        return out
    }

    /**
     * Scan [roots] (depth 1: the root itself plus its immediate
     * subdirectories). Returns packs sorted by label; skips missing dirs,
     * empty dirs, and dirs without `.onnx` files. Never throws (I/O
     * errors read as absent).
     */
    fun scan(roots: List<File>): List<ModelPack> {
        val packs = mutableListOf<ModelPack>()
        roots.forEach { root ->
            val candidates = listOf(root) + safeChildren(root)
            candidates.forEach { dir ->
                packOf(dir)?.let { packs.add(it) }
            }
        }
        return packs.sortedBy { it.label }
    }

    /**
     * KT2: incomplete pack attempts with the reason (null when none).
     *
     * Same depth rule as [scan]; complete packs never appear here. Only
     * dirs that look like model attempts qualify (an `.onnx` file, or a
     * Kitten marker): plain folders stay silent. A dir with `model*.onnx`
     * plus tokens and espeak data but no `voices.bin` reads as a Piper
     * pack (it plays as one), never as a problem.
     */
    fun scanProblems(roots: List<File>): List<PackProblem> {
        val out = mutableListOf<PackProblem>()
        roots.forEach { root ->
            val candidates = listOf(root) + safeChildren(root)
            candidates.forEach { dir ->
                packProblemOf(dir)?.let { out.add(it) }
            }
        }
        return out.sortedBy { it.label }
    }

    private fun packProblemOf(dir: File): PackProblem? {
        val files: Set<String> = try {
            if (!dir.isDirectory) return null
            dir.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty().toSet()
        } catch (_: Exception) {
            return null
        }
        // A pack any engine can bind is never a problem (generic packs,
        // complete Piper dirs, complete Kitten packs all land here).
        if (detectKittenPack(dir) != null) return null
        val hasOnnx = files.any { it.substringAfterLast('.', "").lowercase() == "onnx" }
        val kittenAttempt = files.contains(KITTEN_VOICES_FILENAME) ||
            files.contains(KITTEN_PACK_FILENAME)
        if (!hasOnnx && !kittenAttempt) return null
        if (packOf(dir) != null && !kittenAttempt &&
            files.contains(TOKENS_FILENAME) && hasEspeakDir(dir)
        ) {
            return null
        }
        val label = dir.name.ifBlank { dir.absolutePath }
        if (kittenAttempt) {
            if (!hasOnnx) return PackProblem(label, "no .onnx model file")
            if (!files.contains(KITTEN_VOICES_FILENAME)) return PackProblem(label, "missing voices.bin")
        }
        if (!files.contains(TOKENS_FILENAME)) return PackProblem(label, "missing tokens.txt")
        if (!hasEspeakDir(dir)) return PackProblem(label, "missing espeak-ng-data")
        return null
    }

    private fun hasEspeakDir(dir: File): Boolean {
        return try {
            File(dir, ESPEAK_DIRNAME).isDirectory
        } catch (_: Exception) {
            false
        }
    }

    private fun safeChildren(dir: File): List<File> {
        return try {
            if (!dir.isDirectory) return emptyList()
            dir.listFiles()?.filter { it.isDirectory }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun packOf(dir: File): ModelPack? {
        val models = try {
            dir.listFiles()?.filter { it.isFile && it.extension == "onnx" }.orEmpty()
        } catch (_: Exception) {
            return null
        }
        if (models.isEmpty()) return null
        val bytes = try {
            dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } catch (_: Exception) {
            models.map { it.length() }.sum()
        }
        return ModelPack(
            label = dir.name,
            dirPath = dir.absolutePath,
            voices = models.map { it.nameWithoutExtension }.sorted(),
            bytesTotal = bytes
        )
    }
}

/** One discovered model pack (files only; engine binding is PW7b). */
data class ModelPack(
    /** Folder name (display label). */
    val label: String,
    val dirPath: String,
    /** Model stems (engine binding interprets them per format). */
    val voices: List<String>,
    val bytesTotal: Long
)
