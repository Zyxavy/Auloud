package app.auloud.player.ingest

import java.security.MessageDigest
import java.util.UUID

/**
 * IN7: device-namespace book ids for on-device EPUB imports (Slice 9).
 *
 * Books imported on the device derive `id` as UUIDv5 over
 * `auloud:device-book:<sha256-hex>` in the URL namespace, exactly like
 * Scribe's PC ids (`auloud:book:<sha256-hex>`) but under a different
 * prefix. A PC-rendered bundle of the same EPUB therefore appears as a
 * separate library entry instead of clobbering the on-device book.
 * Merging them would need sentence-level progress mapping, which the
 * IN5/IN6 parity (run-level, not boundary-level) does not guarantee, so
 * the duplicate id is a deliberate trade-off: the same EPUB imported on
 * both sides is two rows, and re-importing the same file on the device
 * is detected by hash before any work happens (see [IngestPipeline]).
 *
 * API 24 safe: `java.security.MessageDigest` (SHA-1) plus `java.util.UUID`
 * only, no `java.time`, no Android classes, JVM-testable.
 */
object DeviceBookIds {

    /** Name prefix separating device imports from Scribe's PC ids. */
    const val DEVICE_PREFIX = "auloud:device-book:"

    /** Same UUID namespace Scribe uses (`uuid.NAMESPACE_URL`). */
    val NAMESPACE_URL: UUID = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")

    /**
     * Deterministic book id for source bytes with lowercase hex SHA-256
     * [shaHex]: `uuid5(NAMESPACE_URL, "auloud:device-book:<hex>")`.
     * Matches the spec section 3 device-namespace rule and the
     * `unrendered-golden` fixture id for its placeholder source bytes.
     */
    fun idForSha256(shaHex: String): String {
        val name = DEVICE_PREFIX + shaHex.lowercase()
        return uuid5(NAMESPACE_URL, name).toString()
    }

    /**
     * RFC 4122 version-5 (SHA-1 name-based) UUID. `java.util.UUID`
     * only ships v3 (`nameUUIDFromBytes`, MD5), so the SHA-1 variant is
     * computed here: `SHA-1(namespace bytes + name bytes)`, first 16
     * bytes with the version and variant bits set.
     */
    internal fun uuid5(namespace: UUID, name: String): UUID {
        val digest = MessageDigest.getInstance("SHA-1")
        val ns = ByteArray(16)
        var msb = namespace.mostSignificantBits
        var lsb = namespace.leastSignificantBits
        for (pos in 7 downTo 0) {
            ns[pos] = (msb and 0xFF).toByte()
            msb = msb shr 8
        }
        for (pos in 15 downTo 8) {
            ns[pos] = (lsb and 0xFF).toByte()
            lsb = lsb shr 8
        }
        digest.update(ns)
        digest.update(name.toByteArray(Charsets.UTF_8))
        val hash = digest.digest()
        var outMsb = 0L
        var outLsb = 0L
        for (pos in 0 until 8) {
            outMsb = (outMsb shl 8) or (hash[pos].toLong() and 0xFF)
        }
        for (pos in 8 until 16) {
            outLsb = (outLsb shl 8) or (hash[pos].toLong() and 0xFF)
        }
        // Version 5 (bits 12-15 of time_hi) and RFC 4122 variant
        // (top two bits of clock_seq_hi are 1 and 0).
        outMsb = (outMsb and 0xF000L.inv()) or 0x5000L
        outLsb = (outLsb and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE
        return UUID(outMsb, outLsb)
    }
}
