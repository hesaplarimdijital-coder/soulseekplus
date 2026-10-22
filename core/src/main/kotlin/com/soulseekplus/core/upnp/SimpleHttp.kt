package com.soulseekplus.core.upnp

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Yönlendirici (UPnP IGD) ile konuşmak için küçük bir HTTP/1.1 istemcisi.
 *
 * `HttpURLConnection` yerine ham soket kullanılır: Android 9+ varsayılan olarak açık metin HTTP'yi (LAN'daki
 * `http://192.168.x.x`) engeller ve manifest bunu açmaz. Yalnızca yerel ağ adreslerine bağlanılır (SSRF koruması).
 */
object SimpleHttp {

    const val MAX_BODY = 1024 * 1024

    class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    /** Yalnızca özel/yerel/geri döngü adreslerine izin verilir. */
    fun isLocalHost(address: InetAddress): Boolean =
        address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress || isCgnat(address)

    private fun isCgnat(a: InetAddress): Boolean {
        val b = a.address
        return b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
    }

    @Throws(IOException::class)
    fun request(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMillis: Int = 5_000,
    ): Response {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw IOException("Invalid URL: $url", e)
        }
        if (uri.scheme?.lowercase() != "http") throw IOException("Only http is supported: $url")
        val host = uri.host ?: throw IOException("No host: $url")
        val port = if (uri.port > 0) uri.port else 80
        val address = InetAddress.getByName(host)
        if (!isLocalHost(address)) throw IOException("Refusing non-local host $host")
        val path = buildString {
            append(uri.rawPath?.ifEmpty { "/" } ?: "/")
            if (uri.rawQuery != null) append('?').append(uri.rawQuery)
        }
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMillis
            socket.connect(InetSocketAddress(address, port), timeoutMillis)
            val head = StringBuilder()
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            head.append("Host: ").append(host).append(':').append(port).append("\r\n")
            head.append("Connection: close\r\n")
            for ((k, v) in headers) {
                if (k.equals("Host", true) || k.equals("Content-Length", true) || k.equals("Connection", true)) continue
                head.append(k).append(": ").append(v).append("\r\n")
            }
            if (body != null) head.append("Content-Length: ").append(body.size).append("\r\n")
            head.append("\r\n")
            val out = socket.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null) out.write(body)
            out.flush()
            return readResponse(socket.getInputStream())
        }
    }

    fun readResponse(input: InputStream): Response {
        val statusLine = readLine(input) ?: throw IOException("Empty HTTP response")
        val parts = statusLine.split(' ', limit = 3)
        if (parts.size < 2 || !parts[0].startsWith("HTTP/")) throw IOException("Bad status line: $statusLine")
        val status = parts[1].toIntOrNull() ?: throw IOException("Bad status code: $statusLine")
        val headers = LinkedHashMap<String, String>()
        var count = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            if (++count > 100) throw IOException("Too many headers")
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
            headers["content-length"] != null -> {
                val len = headers["content-length"]!!.toLongOrNull() ?: throw IOException("Bad Content-Length")
                if (len < 0 || len > MAX_BODY) throw IOException("Response too large")
                readExactly(input, len.toInt())
            }
            else -> readToEnd(input)
        }
        return Response(status, headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            if (sb.length > 16 * 1024) throw IOException("Header line too long")
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = input.read(out, done, length - done)
            if (n < 0) throw IOException("Unexpected end of body")
            done += n
        }
        return out
    }

    private fun readToEnd(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BODY) throw IOException("Response too large")
        }
        return out.toByteArray()
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: throw IOException("Unexpected end of chunked body")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("Bad chunk size")
            if (size == 0) break
            if (size < 0 || out.size() + size > MAX_BODY) throw IOException("Response too large")
            out.write(readExactly(input, size))
            readLine(input) // chunk sonu CRLF
        }
        return out.toByteArray()
    }
}
