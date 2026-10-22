package com.soulseekplus.core.upnp

import com.soulseekplus.core.api.Logger
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** SSDP (UPnP keşfi): istek metni, yanıt ayrıştırma ve çok noktaya yayın keşfi. */
object Ssdp {

    const val MULTICAST_ADDRESS = "239.255.255.250"
    const val PORT = 1900

    /** Aranan hedefler, tercih sırasına göre (Nicotine+). */
    val SEARCH_TARGETS = listOf(
        "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
        "urn:schemas-upnp-org:service:WANIPConnection:2",
        "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
        "urn:schemas-upnp-org:service:WANIPConnection:1",
        "urn:schemas-upnp-org:service:WANPPPConnection:1",
    )

    fun searchRequest(searchTarget: String): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $MULTICAST_ADDRESS:$PORT\r\n" +
            "ST: $searchTarget\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 1\r\n" +
            "USER-AGENT: Soulseek+\r\n" +
            "\r\n"

    /** Bir SSDP yanıtındaki `LOCATION` değeri; yanıt değilse ya da yoksa null. */
    fun parseLocation(response: String): String? {
        val lines = response.split("\r\n", "\n")
        if (lines.isEmpty() || !lines[0].trim().uppercase().startsWith("HTTP/1.")) return null
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            if (line.substring(0, idx).trim().equals("LOCATION", ignoreCase = true)) {
                val value = line.substring(idx + 1).trim()
                return value.takeIf { it.startsWith("http://") }
            }
        }
        return null
    }

    /**
     * Yerel arayüzden çok noktaya yayınla arama yapar ve benzersiz `LOCATION` adreslerini döndürür. Engelleyicidir (IO'da çağır).
     * Her hedef için 1,1 sn yanıt bekler.
     */
    fun discover(local: Inet4Address?, logger: Logger, receiveTimeoutMillis: Int = 1_100): List<String> {
        val locations = LinkedHashSet<String>()
        val socket = try {
            if (local != null) MulticastSocket(InetSocketAddress(local, 0)) else MulticastSocket(0)
        } catch (e: IOException) {
            logger.debug(TAG, "SSDP soketi açılamadı: ${e.message}")
            return emptyList()
        }
        socket.use { s ->
            try {
                if (local != null) {
                    val nif = NetworkInterface.getByInetAddress(local)
                    if (nif != null) s.networkInterface = nif
                }
                s.timeToLive = 2
                s.soTimeout = receiveTimeoutMillis
            } catch (e: IOException) {
                logger.debug(TAG, "SSDP soket ayarı başarısız: ${e.message}")
            }
            val group = InetAddress.getByName(MULTICAST_ADDRESS)
            val buf = ByteArray(2048)
            for (target in SEARCH_TARGETS) {
                val payload = searchRequest(target).toByteArray(Charsets.US_ASCII)
                try {
                    s.send(DatagramPacket(payload, payload.size, group, PORT))
                } catch (e: IOException) {
                    logger.debug(TAG, "SSDP gönderilemedi: ${e.message}")
                    continue
                }
                while (true) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        break
                    } catch (e: IOException) {
                        break
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                    parseLocation(text)?.let { locations.add(it) }
                }
            }
        }
        return locations.toList()
    }

    private const val TAG = "SSDP"
}
