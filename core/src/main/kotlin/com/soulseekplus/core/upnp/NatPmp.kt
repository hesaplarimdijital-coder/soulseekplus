package com.soulseekplus.core.upnp

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** NAT-PMP (RFC 6886) paketleri ve istemci. Ağ bayt sırası (big-endian). */
object NatPmp {

    const val PORT = 5351
    const val OPCODE_EXTERNAL_ADDRESS = 0
    const val OPCODE_MAP_TCP = 2
    const val LEASE_SECONDS = 43_200

    class Result(val resultCode: Int, val privatePort: Int, val publicPort: Int, val lifetimeSeconds: Long)

    class NatPmpException(message: String) : IOException(message)

    /** 12 baytlık eşleme isteği: sürüm 0, işlem 2, ayrılmış 0, özel port, önerilen genel port, ömür. */
    fun mapRequest(privatePort: Int, suggestedPublicPort: Int, lifetimeSeconds: Long): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .put(0).put(OPCODE_MAP_TCP.toByte()).putShort(0)
            .putShort(privatePort.toShort()).putShort(suggestedPublicPort.toShort())
            .putInt(lifetimeSeconds.toInt())
            .array()

    /** Dış adres isteği: sürüm 0, işlem 0. */
    fun externalAddressRequest(): ByteArray = byteArrayOf(0, OPCODE_EXTERNAL_ADDRESS.toByte())

    /** 16 baytlık eşleme yanıtı: sürüm, işlem(130), sonuç kodu, dönem, özel port, genel port, ömür. */
    fun parseMapResponse(data: ByteArray, length: Int = data.size): Result {
        if (length < 16) throw NatPmpException("Short NAT-PMP response ($length bytes)")
        val b = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
        val version = b.get().toInt() and 0xFF
        val opcode = b.get().toInt() and 0xFF
        val result = b.short.toInt() and 0xFFFF
        b.int // dönem saniyesi
        val privatePort = b.short.toInt() and 0xFFFF
        val publicPort = b.short.toInt() and 0xFFFF
        val lifetime = b.int.toLong() and 0xFFFFFFFFL
        if (version != 0) throw NatPmpException("Unsupported NAT-PMP version $version")
        if (opcode != 128 + OPCODE_MAP_TCP) throw NatPmpException("Unexpected NAT-PMP opcode $opcode")
        return Result(result, privatePort, publicPort, lifetime)
    }

    /** 12 baytlık dış adres yanıtı: sürüm, işlem(128), sonuç, dönem, IPv4. Başarıda adresi, değilse hata verir. */
    fun parseExternalAddressResponse(data: ByteArray, length: Int = data.size): String {
        if (length < 12) throw NatPmpException("Short NAT-PMP address response")
        val result = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        if ((data[1].toInt() and 0xFF) != 128 + OPCODE_EXTERNAL_ADDRESS) throw NatPmpException("Unexpected opcode")
        if (result != 0) throw NatPmpException("NAT-PMP error code $result")
        return "${data[8].toInt() and 0xFF}.${data[9].toInt() and 0xFF}.${data[10].toInt() and 0xFF}.${data[11].toInt() and 0xFF}"
    }

    /** İstek gönderir, 250 ms sonra 500 ms bekleyerek iki deneme yapar (Nicotine+). Engelleyicidir. */
    private fun exchange(gateway: InetAddress, port: Int, request: ByteArray, bind: InetAddress?): DatagramPacket {
        var lastError: IOException = SocketTimeoutException("NAT-PMP gateway did not answer")
        for (timeout in intArrayOf(250, 500)) {
            val socket = try {
                if (bind != null) DatagramSocket(InetSocketAddress(bind, 0)) else DatagramSocket()
            } catch (e: IOException) {
                throw e
            }
            socket.use { s ->
                s.soTimeout = timeout
                s.send(DatagramPacket(request, request.size, gateway, port))
                val buf = ByteArray(64)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    while (true) {
                        s.receive(packet)
                        if (packet.address == gateway) return packet
                    }
                } catch (e: SocketTimeoutException) {
                    lastError = e
                }
            }
        }
        throw lastError
    }

    /**
     * [gateway]'e TCP port eşlemesi ister. @return gerçekte verilen ömür (sn).
     * @throws IOException ağ geçidi yanıt vermedi ya da hata kodu döndü.
     */
    fun mapPort(
        gateway: InetAddress,
        port: Int,
        lifetimeSeconds: Long = LEASE_SECONDS.toLong(),
        bind: InetAddress? = null,
        gatewayPort: Int = PORT,
    ): Long {
        val packet = exchange(gateway, gatewayPort, mapRequest(port, port, lifetimeSeconds), bind)
        val r = parseMapResponse(packet.data, packet.length)
        if (r.resultCode != 0) throw NatPmpException("NAT-PMP error code ${r.resultCode}")
        return r.lifetimeSeconds
    }

    /** Eşlemeyi kaldırır (genel port 0, ömür 0). */
    fun unmapPort(gateway: InetAddress, port: Int, bind: InetAddress? = null, gatewayPort: Int = PORT) {
        val packet = exchange(gateway, gatewayPort, mapRequest(port, 0, 0), bind)
        val r = parseMapResponse(packet.data, packet.length)
        if (r.resultCode != 0) throw NatPmpException("NAT-PMP error code ${r.resultCode}")
    }

    /** Ağ geçidinin bildirdiği dış IPv4; alınamazsa null. */
    fun externalAddress(gateway: InetAddress, bind: InetAddress? = null, gatewayPort: Int = PORT): String? =
        try {
            val packet = exchange(gateway, gatewayPort, externalAddressRequest(), bind)
            parseExternalAddressResponse(packet.data, packet.length)
        } catch (_: IOException) {
            null
        }
}
