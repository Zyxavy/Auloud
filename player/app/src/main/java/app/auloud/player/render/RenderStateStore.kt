package app.auloud.player.render

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * RN3: per-book render-job state file (Slice 10).
 *
 * One small JSON file per book at `<bundleDir>/render-job.json`, written
 * atomically as temp-then-rename (`<bundleDir>/render-job.json.tmp`).
 * The file lives inside the book folder so it travels with the book,
 * needs no new storage root, and is ignored by the bundle validator
 * (which only checks listed manifest, audio and text files); the library
 * rescan refreshes render chips after Slice 10 rewrites `render_state`,
 * so a stale file can never pass as book state (the D-097 counter point).
 * No Room migration: the state is machine-local queue data, not
 * relational data, and migrations are unverifiable on the JVM here (the
 * IN1 lesson).
 *
 * Windows rename answer (the D-054 concern, Kotlin edition): Scribe UI4
 * hit `PermissionError` on `os.replace` when monitor and API threads
 * raced, and fixed it with a 20 x 50 ms retry on writes plus 5 x 20 ms
 * on reads. Kotlin `File.renameTo` is weaker still: it returns false
 * instead of throwing, and on Windows it fails when the target exists
 * or is open (an in-flight reader or virus scanner holds it). So [save]
 * writes the temp file fully, then retries up to [WRITE_RETRIES] times
 * (50 ms apart, same budget as Scribe): try the rename first (atomic
 * overwrite on POSIX, success on the common path), and only when it
 * returns false with the target present, delete the target best-effort
 * and retry. After the budget it fails shaped naming the file and the
 * rule. [load] retries transient read failures up to [READ_RETRIES]
 * times (20 ms apart, Scribe parity) but fails fast on corrupt JSON:
 * torn bytes are transient, a bad schema is not.
 *
 * Everything runs through [RenderFileIo] so the retry math is JVM
 * testable with a fake (no real sleeps in tests); production uses
 * [JavaFileRenderIo] (`java.io.File` only). No `BundleStorage` use:
 * the interface has no file-rename op and this work package must not
 * change it; RN7/RN8 wire the bundle dir plus the scheduler calls.
 * Epoch millis only, no `java.time`. No Android types.
 */
interface RenderFileIo {
    fun exists(path: String): Boolean
    fun readText(path: String): String
    fun writeText(path: String, text: String)
    fun renameTempToTarget(tmpPath: String, targetPath: String): Boolean
    fun deleteIfExists(path: String)
}

/** Production [RenderFileIo] over `java.io.File` (API 24 safe). */
class JavaFileRenderIo : RenderFileIo {
    override fun exists(path: String): Boolean = File(path).exists()

    override fun readText(path: String): String {
        val file = File(path)
        if (!file.isFile) throw IOException("$path: file not found or not readable")
        return file.readText(Charsets.UTF_8)
    }

    override fun writeText(path: String, text: String) {
        try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(text, Charsets.UTF_8)
        } catch (e: IOException) {
            throw IOException("$path: cannot write file: ${e.message}", e)
        } catch (e: SecurityException) {
            throw IOException("$path: cannot write file: ${e.message}", e)
        }
    }

    override fun renameTempToTarget(tmpPath: String, targetPath: String): Boolean =
        File(tmpPath).renameTo(File(targetPath))

    override fun deleteIfExists(path: String) {
        try {
            val file = File(path)
            if (file.isFile) file.delete()
        } catch (_: Exception) {
        }
    }
}

object RenderStateStore {

    /** State file name inside each book folder. */
    const val STATE_FILE = "render-job.json"

    /** Temp file name for atomic writes (same directory, same volume). */
    const val TEMP_SUFFIX = ".tmp"

    /** File format version (bump when the JSON shape changes). */
    const val FORMAT_VERSION = 1

    /** Write retry budget (Scribe D-054 parity: 20 x 50 ms). */
    const val WRITE_RETRIES = 20

    /** Write retry delay in millis. */
    const val WRITE_RETRY_DELAY_MS = 50L

    /** Read retry budget (Scribe D-054 parity: 5 x 20 ms). */
    const val READ_RETRIES = 5

    /** Read retry delay in millis. */
    const val READ_RETRY_DELAY_MS = 20L

    private val json = Json { prettyPrint = true }

    /** `<bundleDir>/render-job.json`. */
    fun statePath(bundleDir: String): String =
        bundleDir.trimEnd('/') + '/' + STATE_FILE

    /** `<bundleDir>/render-job.json.tmp`. */
    fun tempPath(bundleDir: String): String = statePath(bundleDir) + TEMP_SUFFIX

    /**
     * Atomically persists [job] into [bundleDir]. Writes the temp file,
     * then renames with the retry loop documented above. A lost race
     * after the full budget fails shaped (never half-written: readers
     * only ever see the old file or the new file).
     */
    fun save(
        bundleDir: String,
        job: RenderJob,
        io: RenderFileIo,
        sleeper: (Long) -> Unit = Thread::sleep
    ): Result<Unit> {
        val target = statePath(bundleDir)
        val tmp = tempPath(bundleDir)
        try {
            io.writeText(tmp, toJson(job))
        } catch (e: Exception) {
            return Result.failure(
                IOException("$target: cannot write render state (${e.message})", e)
            )
        }
        var attempt = 0
        while (true) {
            val renamed = try {
                io.renameTempToTarget(tmp, target)
            } catch (_: Exception) {
                false
            }
            if (renamed) {
                io.deleteIfExists(tmp)
                return Result.success(Unit)
            }
            // Windows refuses the rename when the target exists or is
            // open, so clear the target best-effort and retry. POSIX
            // never reaches here on the happy path (rename overwrites).
            if (io.exists(target)) io.deleteIfExists(target)
            attempt++
            if (attempt >= WRITE_RETRIES) {
                io.deleteIfExists(tmp)
                return Result.failure(
                    IOException(
                        "$target: cannot move temp file into place " +
                            "(render state kept in memory)"
                    )
                )
            }
            try {
                sleeper(WRITE_RETRY_DELAY_MS)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Loads the persisted job, or success-with-null when no state file
     * exists yet. Transient read failures retry ([READ_RETRIES] x
     * [READ_RETRY_DELAY_MS]); corrupt JSON fails shaped with file plus
     * rule, never a crash.
     */
    fun load(
        bundleDir: String,
        io: RenderFileIo,
        sleeper: (Long) -> Unit = Thread::sleep
    ): Result<RenderJob?> {
        val target = statePath(bundleDir)
        if (!io.exists(target)) return Result.success(null)
        var attempt = 0
        var raw: String? = null
        var lastError: Exception? = null
        while (raw == null && attempt < READ_RETRIES) {
            try {
                raw = io.readText(target)
            } catch (e: Exception) {
                lastError = e
                attempt++
                if (attempt < READ_RETRIES) {
                    try {
                        sleeper(READ_RETRY_DELAY_MS)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        if (raw == null) {
            return Result.failure(
                IOException("$target: cannot read render state (${lastError?.message})", lastError)
            )
        }
        return fromJson(raw)
    }

    /**
     * Startup recovery: loads the state and, when the dead process left
     * a RUNNING or PAUSED job behind, marks it INTERRUPTED (resumable;
     * finished chapters plus the spool are kept) and persists the move.
     * Any other state (including absent) loads through untouched.
     */
    fun markInterruptedIfActive(
        bundleDir: String,
        io: RenderFileIo,
        clock: () -> Long = System::currentTimeMillis,
        sleeper: (Long) -> Unit = Thread::sleep
    ): Result<RenderJob?> {
        val loaded = load(bundleDir, io, sleeper).getOrElse { return Result.failure(it) }
            ?: return Result.success(null)
        if (loaded.state != RenderJobState.RUNNING && loaded.state != RenderJobState.PAUSED) {
            return Result.success(loaded)
        }
        val marked = RenderJobs.transition(loaded, RenderJobState.INTERRUPTED, clock)
        save(bundleDir, marked, io, sleeper).getOrElse { return Result.failure(it) }
        return Result.success(marked)
    }

    /** Serializes [job] to the v1 file form (internal for tests). */
    internal fun toJson(job: RenderJob): String {
        val scopeObj = when (val scope = job.plan.scope) {
            is RenderScope.WholeBook -> JsonObject(
                mapOf("kind" to JsonPrimitive("WHOLE_BOOK"), "n" to JsonNull)
            )
            is RenderScope.FromHere -> JsonObject(
                mapOf("kind" to JsonPrimitive("FROM_HERE"), "n" to JsonNull)
            )
            is RenderScope.NextN -> JsonObject(
                mapOf("kind" to JsonPrimitive("NEXT_N"), "n" to JsonPrimitive(scope.chapters))
            )
        }
        val root = JsonObject(
            mapOf(
                "version" to JsonPrimitive(FORMAT_VERSION),
                "bookId" to JsonPrimitive(job.bookId),
                "state" to JsonPrimitive(job.state.name),
                "plan" to JsonObject(
                    mapOf(
                        "bookId" to JsonPrimitive(job.plan.bookId),
                        "chapterCount" to JsonPrimitive(job.plan.chapterCount),
                        "startChapter" to JsonPrimitive(job.plan.startChapter),
                        "scope" to scopeObj,
                        "orderedChapters" to JsonArray(
                            job.plan.orderedChapters.map { JsonPrimitive(it) }
                        ),
                        "createdAt" to JsonPrimitive(job.plan.createdAt)
                    )
                ),
                "completedChapters" to JsonArray(
                    job.completedChapters.map { JsonPrimitive(it) }
                ),
                "currentChapter" to (job.currentChapter?.let { JsonPrimitive(it) } ?: JsonNull),
                "error" to (job.error?.let { JsonPrimitive(it) } ?: JsonNull),
                "createdAt" to JsonPrimitive(job.createdAt),
                "updatedAt" to JsonPrimitive(job.updatedAt)
            )
        )
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /** Parses the v1 file form (internal for tests). */
    internal fun fromJson(text: String): Result<RenderJob> {
        val file = "render-job.json"
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
                ?: return Result.failure(
                    IOException("$file: render state must be an object (bad version)")
                )
        } catch (e: Exception) {
            return Result.failure(
                IOException("$file: render state is not valid JSON (${e.message})", e)
            )
        }
        val version = (root["version"] as? JsonPrimitive)?.intOrNull
        if (version != FORMAT_VERSION) {
            return Result.failure(
                IOException("$file: render state version $version must be $FORMAT_VERSION")
            )
        }
        val bookId = stringOf(root["bookId"])
        if (bookId.isNullOrBlank()) {
            return Result.failure(IOException("$file: render state needs a bookId"))
        }
        val stateName = stringOf(root["state"])
        val state = try {
            RenderJobState.valueOf(stateName ?: "")
        } catch (_: Exception) {
            return Result.failure(
                IOException("$file: render state '$stateName' must be a job state")
            )
        }
        val planObj = root["plan"] as? JsonObject
            ?: return Result.failure(IOException("$file: render state needs a plan"))
        val scopeObj = planObj["scope"] as? JsonObject
            ?: return Result.failure(IOException("$file: render state plan needs a scope"))
        val scope: RenderScope = when (stringOf(scopeObj["kind"])) {
            "WHOLE_BOOK" -> RenderScope.WholeBook
            "FROM_HERE" -> RenderScope.FromHere
            "NEXT_N" -> {
                val n = (scopeObj["n"] as? JsonPrimitive)?.intOrNull
                if (n == null || n < 1) {
                    return Result.failure(
                        IOException("$file: render state scope NEXT_N needs chapters >= 1")
                    )
                }
                RenderScope.NextN(n)
            }
            else -> return Result.failure(
                IOException("$file: render state scope kind is unknown")
            )
        }
        val ordered = planObj["orderedChapters"] as? JsonArray
            ?: return Result.failure(
                IOException("$file: render state plan needs orderedChapters")
            )
        val orderedChapters = ArrayList<Int>(ordered.size)
        for (element in ordered) {
            val chapter = (element as? JsonPrimitive)?.intOrNull
                ?: return Result.failure(
                    IOException("$file: render state orderedChapters must be integers")
                )
            orderedChapters.add(chapter)
        }
        val completedRaw = (root["completedChapters"] as? JsonArray)
            ?: JsonArray(emptyList())
        val completed = ArrayList<Int>(completedRaw.size)
        for (element in completedRaw) {
            val chapter = (element as? JsonPrimitive)?.intOrNull
                ?: return Result.failure(
                    IOException("$file: render state completedChapters must be integers")
                )
            completed.add(chapter)
        }
        val currentElement = root["currentChapter"]
        val currentChapter = if (currentElement == null || currentElement is JsonNull) {
            null
        } else {
            (currentElement as? JsonPrimitive)?.intOrNull
                ?: return Result.failure(
                    IOException("$file: render state currentChapter must be an integer")
                )
        }
        val errorElement = root["error"]
        val error = if (errorElement == null || errorElement is JsonNull) {
            null
        } else {
            stringOf(errorElement)
        }
        val plan = RenderPlan(
            bookId = stringOf(planObj["bookId"]) ?: bookId,
            chapterCount = (planObj["chapterCount"] as? JsonPrimitive)?.intOrNull ?: 0,
            startChapter = (planObj["startChapter"] as? JsonPrimitive)?.intOrNull ?: 0,
            scope = scope,
            orderedChapters = orderedChapters,
            createdAt = (planObj["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L
        )
        return Result.success(
            RenderJob(
                bookId = bookId,
                state = state,
                plan = plan,
                completedChapters = completed,
                currentChapter = currentChapter,
                error = error,
                createdAt = (root["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
                updatedAt = (root["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L
            )
        )
    }

    /** String content of [element], or null for missing/non-string values. */
    private fun stringOf(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
}
