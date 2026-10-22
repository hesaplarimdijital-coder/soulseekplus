package com.soulseekplus.core.upnp

import java.io.File
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Yerel IPv4 ve varsayılan ağ geçidi tahminleri (UPnP / NAT-PMP için). */
object LocalNetwork {

    /**
     * Yönlendiriciye giden arayüzün IPv4 adresi: ağa paket göndermeden bir UDP soketini "bağlayıp" yerel adresi okur.
     * Başarısızsa aktif, geri döngü olmayan ilk IPv4.
     */
    fun localIpv4(): Inet4Address? {
        try {
            DatagramSocket().use { s ->
                s.connect(InetAddress.getByName("10.255.255.255"), 1)
                val a = s.localAddress
                if (a is Inet4Address && !a.isAnyLocalAddress && !a.isLoopbackAddress) return a
            }
        } catch (_: Exception) {
        }
        return allLocalIpv4().firstOrNull()
    }

    /** Aktif, geri döngü olmayan tüm yerel IPv4 adresleri. */
    fun allLocalIpv4(): List<Inet4Address> {
        val result = ArrayList<Inet4Address>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in interfaces) {
                try {
                    if (!nif.isUp || nif.isLoopback) continue
                } catch (_: IOException) {
                    continue
                }
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) result.add(addr)
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    /**
     * Olası ağ geçidi adresleri, en olasıdan başlayarak: önce (okunabiliyorsa) Linux yönlendirme tablosundaki varsayılan
     * rota, ardından her yerel IPv4 /24 ağının `.1` adresi. Android 10+ uygulamaları `/proc/net/route` okuyamaz;
     * çağıran (hizmet) `LinkProperties`'ten gelen adresleri `hints` ile ekleyebilir.
     */
    fun gatewayCandidates(hints: List<String> = emptyList()): List<String> {
        val result = LinkedHashSet<String>()
        for (h in hints) if (isIpv4Literal(h)) result.add(h)
        defaultRouteGateway()?.let { result.add(it) }
        for (local in allLocalIpv4()) {
            val b = local.address
            result.add("${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.1")
        }
        return result.toList()
    }

    /** Linux `/proc/net/route` içindeki varsayılan rota ağ geçidi (okunamazsa null). */
    fun defaultRouteGateway(): String? {
        return try {
            val lines = File("/proc/net/route").readLines()
            parseProcNetRoute(lines)
        } catch (_: Exception) {
            null
        }
    }

    /** `/proc/net/route` satırlarından varsayılan ağ geçidini çıkarır (alanlar: Iface Destination Gateway Flags ...). */
    fun parseProcNetRoute(lines: List<String>): String? {
        for (line in lines.drop(1)) {
            val f = line.trim().split(Regex("\\s+"))
            if (f.size < 4) continue
            if (f[1] != "00000000") continue
            val flags = f[3].toIntOrNull(16) ?: continue
            if (flags and 0x2 == 0) continue // RTF_GATEWAY
            val gw = f[2].toLongOrNull(16) ?: continue
            if (gw == 0L) continue
            // Değer ana makine bayt sırasında (küçük-endian) saklanır.
            return "${gw and 0xFF}.${(gw shr 8) and 0xFF}.${(gw shr 16) and 0xFF}.${(gw shr 24) and 0xFF}"
        }
        return null
    }

    private fun isIpv4Literal(s: String): Boolean {
        val parts = s.split('.')
        return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }
}
