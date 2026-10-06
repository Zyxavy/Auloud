package app.auloud.player.ingest

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * IN7: device-namespace book id tests (Slice 9).
 *
 * The id vector is pinned against the IN1 `unrendered-golden` fixture
 * (its README records the device id for the placeholder source bytes),
 * so the Kotlin derivation agrees with Scribe's `uuid5` by construction.
 */
class DeviceBookIdsTest {

    @Test
    fun goldenSourceSha_derivesGoldenDeviceId() {
        val sha = "9fdc3abf2e08eb43834276e67c2d304758acbd15f051e94b960b5d3ddfce13f1"
        assertEquals("f8be80a6-3e94-56fa-9fb7-c88444bf239d", DeviceBookIds.idForSha256(sha))
    }

    @Test
    fun deviceId_neverCollidesWithPcIdForSameBytes() {
        val sha = "9fdc3abf2e08eb43834276e67c2d304758acbd15f051e94b960b5d3ddfce13f1"
        val device = DeviceBookIds.idForSha256(sha)
        val pc = DeviceBookIds.uuid5(DeviceBookIds.NAMESPACE_URL, "auloud:book:$sha").toString()
        assertNotEquals(pc, device)
    }

    @Test
    fun derivedId_isUuidVersion5Rfc4122() {
        val id = UUID.fromString(DeviceBookIds.idForSha256("ab".repeat(32)))
        assertEquals(5, id.version())
        assertEquals(2, id.variant())
    }

    @Test
    fun derivation_ignoresHexCase() {
        val lower = "9fdc3abf2e08eb43834276e67c2d304758acbd15f051e94b960b5d3ddfce13f1"
        assertEquals(DeviceBookIds.idForSha256(lower), DeviceBookIds.idForSha256(lower.uppercase()))
    }
}
