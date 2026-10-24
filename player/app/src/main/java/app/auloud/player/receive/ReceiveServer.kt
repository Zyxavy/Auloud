package app.auloud.player.receive

import app.auloud.player.data.LibraryRepository
import app.auloud.player.storage.BundleStorage
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Slice 20 TR1: the tablet side of Wi-Fi transfer (tablet-hosts, NanoHTTPD).
 *
 * One route only: `POST /bundle` as `multipart/form-data` with the zipped
 * bundle in the file part named `bundle`, an `X-Pairing-Code` header
 * (compared in constant time against the code shown on the Receive
 * screen) and an `X-Sha256` header for the zip. Multipart is required
 * because NanoHTTPD percent-decodes plain POST bodies, which corrupts
 * (and kills the connection for) binary zips; a non-multipart POST gets
 * a clean 400 instead of a dropped connection. The part streams to a
 * NanoHTTPD temp file, never into memory; the [ReceivePipeline] consumes
 * it during `serve`, after which NanoHTTPD cleans its temp files.
 *
 * Status codes use only long-stable values: 200 imported (or a duplicate
 * resend), 400 malformed/unreadable/invalid, 403 wrong pairing code, 409
 * hash mismatch. Every error body is `{"ok":false,"error":"<file: rule:
 * detail>"}` in the codebase's file-and-rule shape.
 *
 * Lifetime: the Receive screen starts the server on open and stops it on
 * leave (plus an explicit Stop), so no socket listens in the background.
 * API 24 safe: NanoHTTPD is Java 6+ bytecode; this file adds plain
 * strings plus coroutines only.
 */
class ReceiveServer(
    port: Int,
    private val pairingCode: String,
    private val booksRoot: String,
    private val storage: BundleStorage,
    private val library: LibraryRepository,
    private val onReceiveStarted: () -> Unit = {},
    private val onReceiveFinished: (ReceiveOutcome) -> Unit = {},
    /** Wrong-code brake; tests pass 0 to exercise the cap without waiting. */
    private val badCodeDelayMs: Long = 1000L
) : NanoHTTPD(port) {

    /** TR1 hardening: wrong pairing codes seen this server lifetime. */
    private val badGuesses = AtomicInteger(0)

    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.POST || session.uri != "/bundle") {
            return json(
                Response.Status.NOT_FOUND,
                ok = false,
                error = "bundle.zip: not-found: POST /bundle only"
            )
        }
        if (!constantTimeEquals(session.headers["x-pairing-code"], pairingCode)) {
            // One-second brake on wrong guesses (each connection has its
            // own thread), plus a hard cap so a neighbor cannot guess
            // forever: after MAX_BAD_GUESSES the server refuses until it is
            // restarted (the Receive screen owns its lifetime).
            if (badGuesses.incrementAndGet() > MAX_BAD_GUESSES) {
                return json(
                    Response.Status.TOO_MANY_REQUESTS,
                    ok = false,
                    error = "bundle.zip: too-many-attempts: too many wrong pairing codes"
                )
            }
            try {
                Thread.sleep(badCodeDelayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return json(
                Response.Status.FORBIDDEN,
                ok = false,
                error = "bundle.zip: bad-code: wrong pairing code"
            )
        }
        badGuesses.set(0)
        if (oversizeDeclaredLength(session.headers["content-length"])) {
            // Refuse a declared oversize upload before NanoHTTPD streams it
            // to a temp file; the pipeline's unpacked cap stays the second
            // line of defense.
            return json(
                Response.Status.PAYLOAD_TOO_LARGE,
                ok = false,
                error = "bundle.zip: too-large: upload exceeds $MAX_UPLOAD_BYTES bytes"
            )
        }
        val sha = session.headers["x-sha256"]
        val files = mutableMapOf<String, String>()
        try {
            session.parseBody(files)
        } catch (e: IOException) {
            return json(
                Response.Status.BAD_REQUEST,
                ok = false,
                error = "bundle.zip: unreadable-body: ${e.message ?: "cannot read upload"}"
            )
        } catch (e: ResponseException) {
            return json(
                Response.Status.BAD_REQUEST,
                ok = false,
                error = "bundle.zip: unreadable-body: ${e.message ?: "cannot read upload"}"
            )
        } catch (e: RuntimeException) {
            // NanoHTTPD percent-decodes non-multipart POST bodies, which
            // throws on binary zips before serve logic runs. Answer 400
            // instead of dropping the connection.
            return json(
                Response.Status.BAD_REQUEST,
                ok = false,
                error = "bundle.zip: bad-body: send multipart/form-data with the bundle file part"
            )
        }
        val body = files["bundle"]?.let(::File)?.takeIf { it.isFile }
            ?: return json(
                Response.Status.BAD_REQUEST,
                ok = false,
                error = "bundle.zip: empty-body: no bundle file part received"
            )
        onReceiveStarted()
        val outcome = runBlocking {
            ReceivePipeline.receive(body, sha, booksRoot, storage, library)
        }
        onReceiveFinished(outcome)
        return when (outcome) {
            is ReceiveOutcome.Imported ->
                json(Response.Status.OK, ok = true, bookId = outcome.bookId, title = outcome.title)
            is ReceiveOutcome.Duplicate ->
                json(
                    Response.Status.OK,
                    ok = true,
                    bookId = outcome.bookId,
                    title = outcome.title,
                    duplicate = true
                )
            is ReceiveOutcome.Failed -> {
                val status = if (outcome.problems.any { it.contains("hash-mismatch") }) {
                    Response.Status.CONFLICT
                } else {
                    Response.Status.BAD_REQUEST
                }
                json(status, ok = false, error = outcome.problems.joinToString("; "))
            }
        }
    }

    companion object {
        /** Fixed LAN port shown on the Receive screen as `IP:PORT`. */
        const val RECEIVE_PORT = 8089

        /** Wrong pairing codes allowed before the server refuses (429). */
        const val MAX_BAD_GUESSES = 10

        /** Declared-upload ceiling (4 GiB; the pipeline caps unpacked bytes). */
        const val MAX_UPLOAD_BYTES = 4L * 1024L * 1024L * 1024L

        /** Six-digit code, regenerated every time the server starts. */
        fun newPairingCode(random: SecureRandom = SecureRandom()): String =
            String.format(Locale.US, "%06d", random.nextInt(1_000_000))

        /** True when a declared `Content-Length` is past [MAX_UPLOAD_BYTES]. */
        internal fun oversizeDeclaredLength(header: String?): Boolean =
            (header?.trim()?.toLongOrNull() ?: 0L) > MAX_UPLOAD_BYTES

        internal fun constantTimeEquals(candidate: String?, expected: String): Boolean =
            MessageDigest.isEqual(
                candidate?.toByteArray(Charsets.UTF_8) ?: ByteArray(0),
                expected.toByteArray(Charsets.UTF_8)
            )

        internal fun body(
            ok: Boolean,
            error: String? = null,
            bookId: String? = null,
            title: String? = null,
            duplicate: Boolean = false
        ): String = buildJsonObject {
            put("ok", ok)
            if (error != null) put("error", error)
            if (bookId != null) put("bookId", bookId)
            if (title != null) put("title", title)
            if (duplicate) put("duplicate", true)
        }.toString()
    }

    private fun json(
        status: Response.Status,
        ok: Boolean,
        error: String? = null,
        bookId: String? = null,
        title: String? = null,
        duplicate: Boolean = false
    ): Response = newFixedLengthResponse(
        status,
        "application/json",
        body(ok, error, bookId, title, duplicate)
    )
}
