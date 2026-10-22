package com.soulseekplus.core.upnp

import com.soulseekplus.core.api.Logger
import com.soulseekplus.core.model.PortMappingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class UpnpTest {

    private val loopback: Inet4Address = InetAddress.getByName("127.0.0.1") as Inet4Address

    // ------------------------------------------------------------------------------------------------- SSDP

    @Test
    fun ssdpSearchRequestIsWellFormed() {
        val request = Ssdp.searchRequest(Ssdp.SEARCH_TARGETS[0])
        assertTrue(request.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(request.contains("HOST: 239.255.255.250:1900\r\n"))
        assertTrue(request.contains("ST: urn:schemas-upnp-org:device:InternetGatewayDevice:2\r\n"))
        assertTrue(request.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(request.endsWith("\r\n\r\n"))
    }

    @Test
    fun ssdpLocationParsing() {
        val ok = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nlocation: http://192.168.1.1:5000/rootDesc.xml\r\nST: x\r\n\r\n"
        assertEquals("http://192.168.1.1:5000/rootDesc.xml", Ssdp.parseLocation(ok))
        assertNull(Ssdp.parseLocation("NOTIFY * HTTP/1.1\r\nLOCATION: http://1.2.3.4/x\r\n\r\n"))
        assertNull(Ssdp.parseLocation("HTTP/1.1 200 OK\r\nST: x\r\n\r\n"))
        assertNull("yalnızca http", Ssdp.parseLocation("HTTP/1.1 200 OK\r\nLOCATION: file:///etc/passwd\r\n\r\n"))
    }

    // ------------------------------------------------------------------------------------------------- IGD tanımı ve SOAP

    private fun description(base: String?, controlUrl: String = "/ctl/IPConn", type: String = UpnpIgd.WAN_IP_1) =
        """<?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          ${if (base != null) "<URLBase>$base</URLBase>" else ""}
          <device><deviceList><device><deviceList><device><serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType><controlURL>/l3</controlURL></service>
            <service><serviceType>$type</serviceType><controlURL>$controlUrl</controlURL></service>
          </serviceList></device></deviceList></device></deviceList></device>
        </root>"""

    @Test
    fun descriptionPicksWanServiceAndResolvesControlUrl() {
        val svc = UpnpIgd.parseDescription(description(null), "http://192.168.1.1:5000/rootDesc.xml")!!
        assertEquals("http://192.168.1.1:5000/ctl/IPConn", svc.controlUrl)
        assertEquals(UpnpIgd.WAN_IP_1, svc.serviceType)
        val relative = UpnpIgd.parseDescription(description("http://192.168.1.1:5000/", "ctl/x"), "http://192.168.1.1:5000/d.xml")!!
        assertEquals("http://192.168.1.1:5000/ctl/x", relative.controlUrl)
    }

    @Test
    fun descriptionPrefersNewerServiceAndRejectsForeignControlHosts() {
        assertEquals(UpnpIgd.WAN_IP_2, UpnpIgd.parseDescription(description(null, type = UpnpIgd.WAN_IP_2), "http://10.0.0.1/d.xml")!!.serviceType)
        // Başka bir sunucuya işaret eden mutlak kontrol adresi kabul edilmez (SSRF).
        assertNull(UpnpIgd.parseDescription(description(null, controlUrl = "http://203.0.113.5/steal"), "http://10.0.0.1/d.xml"))
        assertNull(UpnpIgd.parseDescription(description(null, type = "urn:schemas-upnp-org:service:Other:1"), "http://10.0.0.1/d.xml"))
    }

    @Test
    fun descriptionWithDoctypeIsRejected() {
        val evil = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><root>&x;</root>"
        try {
            UpnpIgd.parseDescription(evil, "http://10.0.0.1/d.xml")
            fail("IOException bekleniyordu")
        } catch (_: IOException) {
        }
    }

    @Test
    fun soapBodiesCarryTheMappingParameters() {
        val body = UpnpIgd.addPortMappingBody(UpnpIgd.WAN_IP_1, 2234, "192.168.1.20", 43_200)
        assertTrue(body.contains("<u:AddPortMapping xmlns:u=\"urn:schemas-upnp-org:service:WANIPConnection:1\">"))
        assertTrue(body.contains("<NewExternalPort>2234</NewExternalPort>"))
        assertTrue(body.contains("<NewInternalPort>2234</NewInternalPort>"))
        assertTrue(body.contains("<NewProtocol>TCP</NewProtocol>"))
        assertTrue(body.contains("<NewInternalClient>192.168.1.20</NewInternalClient>"))
        assertTrue(body.contains("<NewLeaseDuration>43200</NewLeaseDuration>"))
        assertTrue(UpnpIgd.deletePortMappingBody(UpnpIgd.WAN_IP_1, 2234).contains("<u:DeletePortMapping"))
        assertTrue(UpnpIgd.externalIpBody(UpnpIgd.WAN_IP_1).contains("GetExternalIPAddress"))
    }

    @Test
    fun soapErrorsCarryTheUpnpErrorCode() {
        val fault = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><detail>
            <UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>725</errorCode><errorDescription>OnlyPermanentLeasesSupported</errorDescription></UPnPError>
            </detail></s:Fault></s:Body></s:Envelope>"""
        try {
            UpnpIgd.checkSoapResponse(500, fault)
            fail("SoapException bekleniyordu")
        } catch (e: UpnpIgd.SoapException) {
            assertEquals(725, e.errorCode)
        }
        assertNotNull(UpnpIgd.checkSoapResponse(200, "<a/>"))
        assertNull(UpnpIgd.checkSoapResponse(200, ""))
        try {
            UpnpIgd.checkSoapResponse(404, "")
            fail()
        } catch (_: UpnpIgd.SoapException) {
        }
    }

    // ------------------------------------------------------------------------------------------------- NAT-PMP paketleri

    @Test
    fun natPmpRequestLayout() {
        val request = NatPmp.mapRequest(2234, 2234, 43_200)
        assertEquals(12, request.size)
        assertArrayEquals(
            byteArrayOf(0, 2, 0, 0, 0x08, 0xBA.toByte(), 0x08, 0xBA.toByte(), 0, 0, 0xA8.toByte(), 0xC0.toByte()),
            request,
        )
        assertArrayEquals(byteArrayOf(0, 0), NatPmp.externalAddressRequest())
        // Kaldırma isteği: genel port 0, ömür 0.
        val unmap = NatPmp.mapRequest(2234, 0, 0)
        assertEquals(0, unmap[6].toInt())
        assertEquals(0, unmap[7].toInt())
        assertEquals(0, unmap[11].toInt())
    }

    @Test
    fun natPmpResponseParsing() {
        val response = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .put(0).put(130.toByte()).putShort(0).putInt(1234).putShort(2234).putShort(40000.toShort()).putInt(7200).array()
        val r = NatPmp.parseMapResponse(response)
        assertEquals(0, r.resultCode)
        assertEquals(2234, r.privatePort)
        assertEquals(40000, r.publicPort)
        assertEquals(7200L, r.lifetimeSeconds)
        try {
            NatPmp.parseMapResponse(ByteArray(8))
            fail()
        } catch (_: NatPmp.NatPmpException) {
        }
        val address = byteArrayOf(0, 128.toByte(), 0, 0, 0, 0, 0, 1, 203.toByte(), 0, 113, 9)
        assertEquals("203.0.113.9", NatPmp.parseExternalAddressResponse(address))
        address[3] = 3
        try {
            NatPmp.parseExternalAddressResponse(address)
            fail()
        } catch (_: NatPmp.NatPmpException) {
        }
    }

    @Test
    fun procNetRouteGatewayParsing() {
        val lines = listOf(
            "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT",
            "wlan0\t0001A8C0\t00000000\t0001\t0\t0\t0\t00FFFFFF\t0\t0\t0",
            "wlan0\t00000000\t0101A8C0\t0003\t0\t0\t0\t00000000\t0\t0\t0",
        )
        assertEquals("192.168.1.1", LocalNetwork.parseProcNetRoute(lines))
        assertNull(LocalNetwork.parseProcNetRoute(lines.take(2)))
        assertTrue(LocalNetwork.gatewayCandidates(listOf("10.9.8.1", "not-an-ip")).first() == "10.9.8.1")
    }

    @Test
    fun httpClientRefusesNonLocalHosts() {
        try {
            SimpleHttp.request("http://8.8.8.8/", "GET")
            fail("IOException bekleniyordu")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("non-local"))
        }
        try {
            SimpleHttp.request("https://192.168.1.1/", "GET")
            fail()
        } catch (_: IOException) {
        }
    }

    @Test
    fun httpResponseParsingHandlesChunkedBodies() {
        val raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
        val r = SimpleHttp.readResponse(ByteArrayInputStream(raw.toByteArray()))
        assertEquals(200, r.status)
        assertEquals("hello world", r.text)
        val fixed = SimpleHttp.readResponse(ByteArrayInputStream("HTTP/1.1 500 Err\r\nContent-Length: 3\r\n\r\nabcdef".toByteArray()))
        assertEquals(500, fixed.status)
        assertEquals("abc", fixed.text)
    }

    // ------------------------------------------------------------------------------------------------- sahte yönlendiriciler

    /** Yerel bir IGD: tanım XML'i ve SOAP eylemleri. İlk kalıcı olmayan kira 725 ile reddedilebilir. */
    private class FakeIgd(private val rejectTimedLease: Boolean) : AutoCloseable {
        private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        val actions = CopyOnWriteArrayList<String>()
        private val running = AtomicBoolean(true)
        private val thread = Thread {
            while (running.get()) {
                try {
                    server.accept().use { handle(it) }
                } catch (_: SocketException) {
                    break
                } catch (_: IOException) {
                }
            }
        }.apply { isDaemon = true; start() }

        val location: String get() = "http://127.0.0.1:$port/desc.xml"

        private fun handle(socket: java.net.Socket) {
            socket.soTimeout = 3_000
            val input = socket.getInputStream()
            val headBytes = java.io.ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) return
                headBytes.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) break
            }
            val head = headBytes.toString(Charsets.ISO_8859_1.name())
            val lines = head.split("\r\n")
            val requestLine = lines[0]
            val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
            val body = String(input.readNBytes(length), Charsets.UTF_8)
            val action = lines.firstOrNull { it.startsWith("SOAPACTION", true) }?.substringAfter('#')?.trim('"', ' ')
            val (status, payload) = when {
                requestLine.startsWith("GET /desc.xml") -> 200 to """<?xml version="1.0"?>
                    <root xmlns="urn:schemas-upnp-org:device-1-0"><URLBase>http://127.0.0.1:$port/</URLBase><device><deviceList><device><serviceList>
                    <service><serviceType>${UpnpIgd.WAN_IP_1}</serviceType><controlURL>/ctl</controlURL></service>
                    </serviceList></device></deviceList></device></root>"""
                action == "AddPortMapping" -> {
                    actions.add("Add:" + body)
                    if (rejectTimedLease && !body.contains("<NewLeaseDuration>0<")) {
                        500 to """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><detail>
                            <UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>725</errorCode></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""
                    } else {
                        200 to "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body/></s:Envelope>"
                    }
                }
                action == "GetExternalIPAddress" -> 200 to
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:R xmlns:u=\"x\"><NewExternalIPAddress>203.0.113.9</NewExternalIPAddress></u:R></s:Body></s:Envelope>"
                action == "DeletePortMapping" -> {
                    actions.add("Delete:" + body)
                    200 to "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body/></s:Envelope>"
                }
                else -> 404 to ""
            }
            val bytes = payload.toByteArray(Charsets.UTF_8)
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 $status X\r\nContent-Type: text/xml\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes)
            out.flush()
        }

        override fun close() {
            running.set(false)
            server.close()
            thread.join(2_000)
        }
    }

    @Test
    fun addPortMappingRetriesWithPermanentLeaseOnError725() {
        FakeIgd(rejectTimedLease = true).use { igd ->
            val service = UpnpIgd.fetchService(igd.location)!!
            val lease = UpnpIgd.addPortMapping(service, 2234, "127.0.0.1")
            assertEquals(0, lease)
            assertEquals(2, igd.actions.size)
            assertTrue(igd.actions[0].contains("<NewLeaseDuration>43200<"))
            assertTrue(igd.actions[1].contains("<NewLeaseDuration>0<"))
            assertEquals("203.0.113.9", UpnpIgd.externalIp(service))
        }
    }

    @Test
    fun portMapperMapsViaUpnpRenewsStateAndUnmapsOnStop() = runBlocking {
        FakeIgd(rejectTimedLease = false).use { igd ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val mapper = PortMapper(
                    scope = scope,
                    logger = Logger.NONE,
                    discover = { listOf(igd.location) },
                    localAddress = { loopback },
                )
                assertTrue(mapper.state.value is PortMappingState.Disabled)
                mapper.start(2234)
                val mapped = withTimeout(10_000) { mapper.state.first { it is PortMappingState.Mapped } } as PortMappingState.Mapped
                assertEquals("UPnP", mapped.method)
                assertEquals("203.0.113.9", mapped.externalIp)
                assertEquals(2234, mapped.externalPort)
                assertNotNull(mapped.leaseExpiresAtMillis)
                mapper.stopAndWait(5_000)
                assertTrue(mapper.state.value is PortMappingState.Disabled)
                assertTrue(igd.actions.any { it.startsWith("Delete:") && it.contains("<NewExternalPort>2234<") })
            } finally {
                scope.cancel()
            }
        }
    }

    /** Yerel UDP soketinde NAT-PMP ağ geçidi taklidi: eşleme ve dış adres isteklerini yanıtlar. */
    private class FakePmpGateway : AutoCloseable {
        val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val port: Int = socket.localPort
        val requests = CopyOnWriteArrayList<ByteArray>()
        private val thread = Thread {
            val buf = ByteArray(64)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: IOException) {
                    break
                }
                val data = packet.data.copyOf(packet.length)
                requests.add(data)
                val reply = when (data[1].toInt()) {
                    0 -> byteArrayOf(0, 128.toByte(), 0, 0, 0, 0, 0, 1, 198.toByte(), 51, 100, 7)
                    2 -> ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                        .put(0).put(130.toByte()).putShort(0).putInt(1).putShort(((data[4].toInt() and 0xFF) shl 8 or (data[5].toInt() and 0xFF)).toShort())
                        .putShort(((data[6].toInt() and 0xFF) shl 8 or (data[7].toInt() and 0xFF)).toShort())
                        .putInt(((data[8].toLong() and 0xFF) shl 24 or ((data[9].toLong() and 0xFF) shl 16) or ((data[10].toLong() and 0xFF) shl 8) or (data[11].toLong() and 0xFF)).toInt())
                        .array()
                    else -> continue
                }
                socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            socket.close()
            thread.join(2_000)
        }
    }

    @Test
    fun natPmpClientTalksToAGateway() {
        FakePmpGateway().use { gw ->
            val gateway = InetAddress.getByName("127.0.0.1")
            val lifetime = NatPmp.mapPort(gateway, 2234, 3_600, null, gw.port)
            assertEquals(3_600L, lifetime)
            assertEquals("198.51.100.7", NatPmp.externalAddress(gateway, null, gw.port))
            NatPmp.unmapPort(gateway, 2234, null, gw.port)
            val last = gw.requests.last()
            assertEquals(2, last[1].toInt())
            assertEquals(0, last[6].toInt() or last[7].toInt())
        }
    }

    @Test
    fun natPmpTimesOutWhenNobodyAnswers() {
        val silent = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        try {
            try {
                NatPmp.mapPort(InetAddress.getByName("127.0.0.1"), 2234, 60, null, silent.localPort)
                fail("IOException bekleniyordu")
            } catch (_: IOException) {
            }
        } finally {
            silent.close()
        }
    }

    @Test
    fun portMapperFallsBackToNatPmp() = runBlocking {
        FakePmpGateway().use { gw ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val mapper = PortMapper(
                    scope = scope,
                    logger = Logger.NONE,
                    gatewayHints = { listOf("127.0.0.1") },
                    discover = { emptyList() },
                    natPmpPort = gw.port,
                    localAddress = { loopback },
                )
                mapper.start(2234)
                val mapped = withTimeout(10_000) { mapper.state.first { it is PortMappingState.Mapped } } as PortMappingState.Mapped
                assertEquals("NAT-PMP", mapped.method)
                assertEquals("198.51.100.7", mapped.externalIp)
                mapper.stopAndWait(5_000)
                assertTrue(gw.requests.any { it[1].toInt() == 2 && it[6].toInt() == 0 && it[7].toInt() == 0 })
            } finally {
                scope.cancel()
            }
        }
    }
}
