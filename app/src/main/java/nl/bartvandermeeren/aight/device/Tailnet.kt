package nl.bartvandermeeren.aight.device

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Tailscale gives devices addresses in 100.64.0.0/10 and fd7a:115c:a1e0::/48, on Android's VPN interface. */
object Tailnet {
    private val IPV6_PREFIX = byteArrayOf(0xfd.toByte(), 0x7a, 0x11, 0x5c, 0xa1.toByte(), 0xe0.toByte())

    fun isTailscaleAddress(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> address.address.let { b -> (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64 }
        is Inet6Address -> address.address.let { b -> IPV6_PREFIX.indices.all { b[it] == IPV6_PREFIX[it] } }
        else -> false
    }

    /**
     * True when [local] is this phone's own Tailscale address. Some mobile carriers hand out addresses in
     * the same 100.64/10 range, so the address must also sit on a VPN (tun) interface.
     */
    fun isOwnAddress(local: InetAddress): Boolean =
        isTailscaleAddress(local) && runCatching { NetworkInterface.getByInetAddress(local)?.name?.startsWith("tun") == true }.getOrDefault(false)

    /** This phone's Tailscale IPv4 address, or null while Tailscale is off. */
    fun ownAddress(): Inet4Address? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && it.name.startsWith("tun") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull(::isTailscaleAddress)
    }.getOrNull()
}
