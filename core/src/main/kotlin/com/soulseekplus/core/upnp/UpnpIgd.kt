package com.soulseekplus.core.upnp

import com.soulseekplus.core.api.Logger
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/** Bulunan bir IGD hizmeti: SOAP komutlarının gönderileceği adres ve hizmet türü. */
data class IgdService(val controlUrl: String, val serviceType: String)

/** UPnP IGD cihaz tanımı ayrıştırma, SOAP gövdeleri ve istemci (bkz. docs/slsk-protocol.md §10). */
object UpnpIgd {

    const val WAN_IP_2 = "urn:schemas-upnp-org:service:WANIPConnection:2"
    const val WAN_IP_1 = "urn:schemas-upnp-org:service:WANIPConnection:1"
    const val WAN_PPP_1 = "urn:schemas-upnp-org:service:WANPPPConnection:1"

    /** Tercih sırası: WANIPConnection:2 > WANIPConnection:1 > WANPPPConnection:1. */
    val SERVICE_PREFERENCE = listOf(WAN_IP_2, WAN_IP_1, WAN_PPP_1)

    const val DESCRIPTION = "Soulseek+"
    const val LEASE_SECONDS = 43_200

    class SoapException(val errorCode: Int?, message: String) : IOException(message)

    // ------------------------------------------------------------------------------------------------- XML

    private fun parseXml(xml: String): org.w3c.dom.Document {
        // Dış varlık / DTD saldırılarına karşı: DOCTYPE içeren belgeler reddedilir (taşınabilir savunma).
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) {
            throw IOException("XML DOCTYPE is not allowed")
        }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        for ((feature, value) in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
            "http://javax.xml.XMLConstants/feature/secure-processing" to true,
        )) {
            try {
                factory.setFeature(feature, value)
            } catch (_: Exception) {
                // Android'in ayrıştırıcısı bazı özellikleri bilmez; yukarıdaki DOCTYPE reddi geçerlidir.
            }
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        return try {
            builder.parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            throw IOException("Invalid XML: ${e.message}", e)
        }
    }

    private fun childText(parent: Element, localName: String): String? {
        var n: Node? = parent.firstChild
        while (n != null) {
            if (n is Element && localNameOf(n) == localName) return n.textContent?.trim()
            n = n.nextSibling
        }
        return null
    }

    private fun localNameOf(e: Element): String = e.localName ?: e.tagName.substringAfter(':')

    /**
     * Cihaz tanımı XML'inden en çok tercih edilen WAN bağlantı hizmetini bulur ve kontrol adresini çözer.
     * Mutlak adresler yalnızca tanım adresiyle aynı sunucuya (şema+ana makine+port) aitse kabul edilir.
     */
    fun parseDescription(xml: String, locationUrl: String): IgdService? {
        val doc = parseXml(xml)
        val location = try {
            URI(locationUrl)
        } catch (_: Exception) {
            return null
        }
        val base = doc.documentElement.let { root -> childText(root, "URLBase") }
        val baseUri = try {
            if (!base.isNullOrEmpty()) URI(base) else location
        } catch (_: Exception) {
            location
        }
        val found = LinkedHashMap<String, String>()
        val services = doc.getElementsByTagNameNS("*", "service")
        for (i in 0 until services.length) {
            val s = services.item(i) as? Element ?: continue
            val type = childText(s, "serviceType") ?: continue
            val control = childText(s, "controlURL") ?: continue
            if (type !in SERVICE_PREFERENCE || found.containsKey(type)) continue
            val resolved = resolveControl(control, baseUri, location) ?: continue
            found[type] = resolved
        }
        for (type in SERVICE_PREFERENCE) {
            val url = found[type] ?: continue
            return IgdService(url, type)
        }
        return null
    }

    private fun resolveControl(control: String, base: URI, location: URI): String? {
        return try {
            val resolved = if (control.startsWith("/")) {
                URI(base.scheme, null, base.host, base.port, null, null, null).resolve(control)
            } else {
                base.resolve(control)
            }
            val sameOrigin = resolved.scheme.equals(location.scheme, true) &&
                resolved.host.equals(location.host, true) &&
                (resolved.port == location.port || (resolved.port == -1 && location.port == 80) || (resolved.port == 80 && location.port == -1))
            if (sameOrigin) resolved.toString() else null
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------------------------------------- SOAP

    private const val ENVELOPE_HEAD =
        "<?xml version=\"1.0\"?>\r\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
    private const val ENVELOPE_TAIL = "</s:Body></s:Envelope>\r\n"

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun addPortMappingBody(serviceType: String, port: Int, localIp: String, leaseSeconds: Int, description: String = DESCRIPTION): String =
        ENVELOPE_HEAD +
            "<u:AddPortMapping xmlns:u=\"${xmlEscape(serviceType)}\"><NewRemoteHost></NewRemoteHost>" +
            "<NewExternalPort>$port</NewExternalPort><NewProtocol>TCP</NewProtocol><NewInternalPort>$port</NewInternalPort>" +
            "<NewInternalClient>${xmlEscape(localIp)}</NewInternalClient><NewEnabled>1</NewEnabled>" +
            "<NewPortMappingDescription>${xmlEscape(description)}</NewPortMappingDescription>" +
            "<NewLeaseDuration>$leaseSeconds</NewLeaseDuration></u:AddPortMapping>" + ENVELOPE_TAIL

    fun deletePortMappingBody(serviceType: String, port: Int): String =
        ENVELOPE_HEAD +
            "<u:DeletePortMapping xmlns:u=\"${xmlEscape(serviceType)}\"><NewRemoteHost></NewRemoteHost>" +
            "<NewExternalPort>$port</NewExternalPort><NewProtocol>TCP</NewProtocol></u:DeletePortMapping>" + ENVELOPE_TAIL

    fun externalIpBody(serviceType: String): String =
        ENVELOPE_HEAD + "<u:GetExternalIPAddress xmlns:u=\"${xmlEscape(serviceType)}\"></u:GetExternalIPAddress>" + ENVELOPE_TAIL

    /**
     * SOAP yanıtını değerlendirir. Başarı: hata yok. HTTP hata kodlu yanıtlar da SOAP hatası taşır.
     * @return yanıt gövdesi (başarı) — hata varsa [SoapException].
     */
    fun checkSoapResponse(status: Int, body: String): org.w3c.dom.Document? {
        val doc = try {
            if (body.isBlank()) null else parseXml(body)
        } catch (e: IOException) {
            if (status in 200..299) return null
            throw SoapException(null, "HTTP $status")
        }
        if (doc != null) {
            val codes = doc.getElementsByTagNameNS("*", "errorCode")
            if (codes.length > 0) {
                val code = codes.item(0).textContent?.trim()?.toIntOrNull()
                val desc = doc.getElementsByTagNameNS("*", "errorDescription").let { if (it.length > 0) it.item(0).textContent?.trim() else null }
                throw SoapException(code, "UPnP error ${code ?: "?"}: ${desc ?: "unknown"}")
            }
            if (doc.getElementsByTagNameNS("*", "Fault").length > 0) throw SoapException(null, "SOAP fault")
        }
        if (status !in 200..299) throw SoapException(null, "HTTP $status")
        return doc
    }

    // ------------------------------------------------------------------------------------------------- istemci

    private fun soap(service: IgdService, action: String, body: String, timeoutMillis: Int): org.w3c.dom.Document? {
        val response = SimpleHttp.request(
            url = service.controlUrl,
            method = "POST",
            headers = mapOf(
                "Content-Type" to "text/xml; charset=utf-8",
                "SOAPACTION" to "\"${service.serviceType}#$action\"",
            ),
            body = body.toByteArray(Charsets.UTF_8),
            timeoutMillis = timeoutMillis,
        )
        return checkSoapResponse(response.status, response.text)
    }

    /** Tanım adresini indirip hizmeti bulur. Bulunamazsa null. */
    fun fetchService(locationUrl: String, timeoutMillis: Int = 5_000): IgdService? {
        val response = SimpleHttp.request(locationUrl, "GET", mapOf("Accept" to "text/xml"), null, timeoutMillis)
        if (response.status !in 200..299) return null
        return parseDescription(response.text, locationUrl)
    }

    /**
     * Portu TCP için yönlendirir. 725 (yalnızca kalıcı kira) hatasında 0 kira süresiyle bir kez daha dener.
     * @return gerçekte kullanılan kira süresi (0: kalıcı).
     */
    fun addPortMapping(service: IgdService, port: Int, localIp: String, leaseSeconds: Int = LEASE_SECONDS, timeoutMillis: Int = 5_000): Int {
        try {
            soap(service, "AddPortMapping", addPortMappingBody(service.serviceType, port, localIp, leaseSeconds), timeoutMillis)
            return leaseSeconds
        } catch (e: SoapException) {
            if (e.errorCode != 725 || leaseSeconds == 0) throw e
        }
        soap(service, "AddPortMapping", addPortMappingBody(service.serviceType, port, localIp, 0), timeoutMillis)
        return 0
    }

    fun deletePortMapping(service: IgdService, port: Int, timeoutMillis: Int = 3_000) {
        soap(service, "DeletePortMapping", deletePortMappingBody(service.serviceType, port), timeoutMillis)
    }

    /** Dış IP adresi; hizmet bildirmezse null. */
    fun externalIp(service: IgdService, timeoutMillis: Int = 3_000): String? {
        val doc = soap(service, "GetExternalIPAddress", externalIpBody(service.serviceType), timeoutMillis) ?: return null
        val nodes = doc.getElementsByTagNameNS("*", "NewExternalIPAddress")
        if (nodes.length == 0) return null
        return nodes.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Verilen `LOCATION` adreslerinden en uygun hizmeti seçer. */
    fun pickService(locations: List<String>, logger: Logger): IgdService? {
        var best: IgdService? = null
        for (loc in locations) {
            val svc = try {
                fetchService(loc)
            } catch (e: IOException) {
                logger.debug("UPnP", "Cihaz tanımı alınamadı ($loc): ${e.message}")
                null
            } ?: continue
            if (best == null || SERVICE_PREFERENCE.indexOf(svc.serviceType) < SERVICE_PREFERENCE.indexOf(best.serviceType)) best = svc
        }
        return best
    }
}
