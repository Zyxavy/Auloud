package app.auloud.player.receive

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Slice 20 TR1: the device IP shown on the Receive screen.
 *
 * Plain `NetworkInterface` enumeration needs no permission (which is why
 * TR1 ships no `ACCESS_WIFI_STATE`). Prefers a site-local IPv4 address
 * (the usual same-Wi-Fi case, including hotspot mode), else the first
 * non-loopback IPv4, else null (the screen then says no address found).
 *
 * Pure `java.net`: JVM-testable (asserts no-throw plus shape when non-null).
 */
object ReceiveAddresses {

    fun deviceIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList()
                ?: return null
            var fallback: String? = null
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (address in ni.inetAddresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val host = address.hostAddress ?: continue
                        if (address.isSiteLocalAddress) return host
                        if (fallback == null) fallback = host
                    }
                }
            }
            fallback
        } catch (_: Exception) {
            null
        }
    }
}
