package app.auloud.player.receive

import app.auloud.player.bundle.Manifest
import app.auloud.player.data.BookEntity
import app.auloud.player.data.LibraryRepository
import app.auloud.player.storage.BundleStorage
import app.auloud.player.storage.FileBundleStorage
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Slice 20 TR1: the Wi-Fi receiver over loopback (JVM has no permission
 * model, so binding 127.0.0.1 here proves protocol plus pipeline, never
 * device networking). The tablet proof stays owner-run.
 */
class ReceiveServerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class FakeLibrary : LibraryRepository {
        val imported = mutableListOf<Pair<String, Manifest>>()
        override fun books(): Flow<List<BookEntity>> = emptyFlow()
        override suspend fun importBundle(bundleDir: String, manifest: Manifest) =
            Result.success(
                BookEntity(
                    id = manifest.id,
                    title = manifest.title,
                    author = null,
                    bundlePath = bundleDir,
                    coverPath = null,
                    durationMs = 0L,
                    addedAt = 0L
                )
            ).also { imported.add(bundleDir to manifest) }
        override suspend fun refreshMissing(presentBundleDirs: Collection<String>) =
            Result.success(Unit)
        override suspend fun deleteBook(bookId: String) = Result.success(Unit)
    }

    private val storage: BundleStorage = FileBundleStorage()
    private val library = FakeLibrary()
    private val code = "123456"

    private fun booksRoot(): String = temp.newFolder("books").absolutePath

    private fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun multipartBody(
        boundary: String,
        field: String,
        filename: String,
        bytes: ByteArray
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val head = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"$field\"; filename=\"$filename\"\r\n" +
            "Content-Type: application/zip\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun post(
        port: Int,
        code: String?,
        sha: String?,
        body: ByteArray,
        method: String = "POST",
        multipart: Boolean = true
    ): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port/bundle").openConnection()
            as HttpURLConnection
        try {
            connection.requestMethod = method
            if (code != null) connection.setRequestProperty("X-Pairing-Code", code)
            if (sha != null) connection.setRequestProperty("X-Sha256", sha)
            if (method == "POST") {
                connection.doOutput = true
                val payload = if (multipart) {
                    val boundary = "auloud-test-boundary"
                    connection.setRequestProperty(
                        "Content-Type",
                        "multipart/form-data; boundary=$boundary"
                    )
                    multipartBody(boundary, "bundle", "bundle.zip", body)
                } else {
                    body
                }
                connection.outputStream.use { it.write(payload) }
            }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.readText() ?: ""
            return status to text
        } finally {
            connection.disconnect()
        }
    }

    private fun validEntries(id: String = "wifi-book-1"): Map<String, ByteArray> {
        val manifest = """
            {"spec_version":"1.0","id":"$id","title":"Wi-Fi Book","type":"epub",
             "audio":{"format":"mp3","channels":1,"sample_rate":24000,"bitrate_kbps":64,"cbr":true},
             "voices":{"narrator":{"engine":"kokoro","voice":"af_heart","speed":1.0,"pitch":1.0}},
             "chapters":[{"index":1,"title":"Chapter One","audio":"audio/ch001.mp3",
                          "text":"text/ch001.json","duration_ms":2000}]}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val text = """
            {"spec_version":"1.0","chapter":1,"title":"Chapter One","duration_ms":2000,
             "blocks":[{"id":1,"type":"para","sentences":[
               {"sid":1,"speaker":"narrator","start_ms":0,"end_ms":1000,"text":"First."},
               {"sid":2,"speaker":"narrator","start_ms":1000,"end_ms":2000,"text":"Second."}]}]}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        return mapOf(
            "manifest.json" to manifest,
            "text/ch001.json" to text,
            "audio/ch001.mp3" to byteArrayOf(0x49, 0x44, 0x33, 0x21)
        )
    }

    private fun strayTemps(root: String): List<String> =
        java.io.File(root).listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(".tmp-") }
            ?.map { it.name } ?: emptyList()

    @Test
    fun wrongCodeIs403() {
        val root = booksRoot()
        val server = ReceiveServer(0, code, root, storage, library)
        server.start()
        try {
            val (status, body) = post(server.listeningPort, "000000", sha256Hex(ByteArray(0)), ByteArray(0))
            assertEquals(403, status)
            assertTrue(body.contains("bad-code"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun getIs404() {
        val root = booksRoot()
        val server = ReceiveServer(0, code, root, storage, library)
        server.start()
        try {
            val (status, _) = post(server.listeningPort, code, null, ByteArray(0), method = "GET")
            assertEquals(404, status)
        } finally {
            server.stop()
        }
    }

    @Test
    fun missingShaIs400() {
        val root = booksRoot()
        val body = zipBytes(validEntries())
        val server = ReceiveServer(0, code, root, storage, library)
        server.start()
        try {
            val (status, text) = post(server.listeningPort, code, null, body)
            assertEquals(400, status)
            assertTrue(text.contains("missing-sha256"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun hashMismatchIs409() {
        val root = booksRoot()
        val body = zipBytes(validEntries())
        val server = ReceiveServer(0, code, root, storage, library)
        server.start()
        try {
            val (status, text) = post(server.listeningPort, code, "00".repeat(32), body)
            assertEquals(409, status)
            assertTrue(text.contains("hash-mismatch"))
            assertTrue(strayTemps(root).isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun emptyZipIs400() {
        // An empty file part carries no bundle at all (NanoHTTPD drops it
        // before the pipeline runs); a well-formed zip with zero entries
        // reaches the pipeline instead (see entrylessZipIs400).
        val root = booksRoot()
        val body = ByteArray(0)
        val server = ReceiveServer(0, code, root, storage, library)
        server.start()
        try {
            val (status, text) = post(server.listeningPort, code, sha256Hex(body), body)
            assertEquals(400, status)
            assertTrue(text.contains("empty-body"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun entrylessZipIs400() {
        val root = booksRoot()
        val body = zipBytes(emptyMap())
        val server = ReceiveServer(0, code, root, storage, library)
        server.start()
        try {
            val (status, text) = post(server.listeningPort, code, sha256Hex(body), body)
            assertEquals(400, status)
            assertTrue(text.contains("empty-zip"))
            assertTrue(strayTemps(root).isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun zipSlipIsRejected() {
        val root = booksRoot()
        val body = zipBytes(mapOf("../evil.txt" to "x".toByteArray()))
        val server = ReceiveServer(0, code, root, storage, library)
        server.start()
        try {
            val (status, text) = post(server.listeningPort, code, sha256Hex(body), body)
            assertEquals(400, status)
            assertTrue(text.contains("path-escape"))
            assertTrue(strayTemps(root).isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun invalidManifestIsSkippedWithCleanup() {
        val root = booksRoot()
        val body = zipBytes(mapOf("manifest.json" to "{oops".toByteArray()))
        val server = ReceiveServer(0, code, root, storage, library)
        server.start()
        try {
            val (status, text) = post(server.listeningPort, code, sha256Hex(body), body)
            assertEquals(400, status)
            assertTrue(text.contains("manifest.json"))
            assertTrue(strayTemps(root).isEmpty())
            assertTrue(library.imported.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun missingAudioIsSkippedWithCleanup() {
        val root = booksRoot()
        val entries = validEntries().filterKeys { it != "audio/ch001.mp3" }
        val body = zipBytes(entries)
        val server = ReceiveServer(0, code, root, storage, library)
        server.start()
        try {
            val (status, text) = post(server.listeningPort, code, sha256Hex(body), body)
            assertEquals(400, status)
            assertTrue(text.contains("audio file missing"))
            assertTrue(strayTemps(root).isEmpty())
            assertTrue(library.imported.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun validBundleImportsAndResendIsDuplicate() {
        val root = booksRoot()
        val body = zipBytes(validEntries())
        val server = ReceiveServer(0, code, root, storage, library)
        server.start()
        try {
            val (first, firstBody) = post(server.listeningPort, code, sha256Hex(body), body)
            assertEquals(200, first)
            assertTrue(firstBody.contains("\"ok\":true"))
            assertEquals(1, library.imported.size)
            assertEquals("wifi-book-1", library.imported.single().second.id)
            assertTrue(java.io.File(root, "wifi-book-1/manifest.json").isFile)
            assertTrue(strayTemps(root).isEmpty())

            val (second, secondBody) = post(server.listeningPort, code, sha256Hex(body), body)
            assertEquals(200, second)
            assertTrue(secondBody.contains("\"duplicate\":true"))
            assertEquals(1, library.imported.size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun badBundleIdIsRejected() {
        val root = booksRoot()
        val body = zipBytes(validEntries(id = "../evil"))
        val server = ReceiveServer(0, code, root, storage, library)
        server.start()
        try {
            val (status, text) = post(server.listeningPort, code, sha256Hex(body), body)
            assertEquals(400, status)
            assertTrue(text.contains("bad-id"))
            assertTrue(strayTemps(root).isEmpty())
            assertTrue(library.imported.isEmpty())
            assertTrue(!java.io.File(root, "../evil").canonicalFile.exists())
        } finally {
            server.stop()
        }
    }

    @Test
    fun updatedBundleReplacesInsteadOfDuplicating() {
        val root = booksRoot()
        val first = zipBytes(validEntries())
        val server = ReceiveServer(0, code, root, storage, library)
        server.start()
        try {
            val (one, _) = post(server.listeningPort, code, sha256Hex(first), first)
            assertEquals(200, one)
            assertEquals(1, library.imported.size)
            // Same id, new bytes (retitled re-render): second send
            // replaces instead of reporting a duplicate.
            val entries = validEntries().toMutableMap()
            entries["manifest.json"] = String(entries.getValue("manifest.json"), Charsets.UTF_8)
                .replace("Wi-Fi Book", "Wi-Fi Book v2")
                .toByteArray(Charsets.UTF_8)
            val second = zipBytes(entries)
            val (two, twoBody) = post(server.listeningPort, code, sha256Hex(second), second)
            assertEquals(200, two)
            assertTrue(!twoBody.contains("\"duplicate\":true"))
            assertEquals(2, library.imported.size)
            val installed = java.io.File(root, "wifi-book-1/manifest.json").readText()
            assertTrue(installed.contains("Wi-Fi Book v2"))
            assertTrue(strayTemps(root).isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun folderWrappedZipImports() {
        val root = booksRoot()
        val wrapped = validEntries().mapKeys { "SomeBook/${it.key}" }
        val body = zipBytes(wrapped)
        val server = ReceiveServer(0, code, root, storage, library)
        server.start()
        try {
            val (status, text) = post(server.listeningPort, code, sha256Hex(body), body)
            assertEquals(200, status)
            assertTrue(text.contains("\"ok\":true"))
            assertEquals(1, library.imported.size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun plainBinaryPostIs400NotDropped() {
        // NanoHTTPD percent-decodes non-multipart POST bodies, which dies
        // on binary zips; the server must answer 400, never drop the
        // connection (the real sender always uses multipart).
        val root = booksRoot()
        val body = zipBytes(validEntries())
        val server = ReceiveServer(0, code, root, storage, library)
        server.start()
        try {
            val (status, text) = post(
                server.listeningPort, code, sha256Hex(body), body, multipart = false
            )
            assertEquals(400, status)
            assertTrue(text.contains("bad-body"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun pairingCodeIsSixDigits() {
        val first = ReceiveServer.newPairingCode()
        val second = ReceiveServer.newPairingCode()
        assertTrue(first.matches(Regex("\\d{6}")))
        assertTrue(second.matches(Regex("\\d{6}")))
    }

    @Test
    fun codeCompareIsExact() {
        assertTrue(ReceiveServer.constantTimeEquals("123456", "123456"))
        assertTrue(!ReceiveServer.constantTimeEquals("123457", "123456"))
        assertTrue(!ReceiveServer.constantTimeEquals(null, "123456"))
    }

    @Test
    fun deviceIpNeverThrows() {
        val ip = ReceiveAddresses.deviceIpv4()
        if (ip != null) {
            assertTrue(ip.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")))
        }
    }
}
