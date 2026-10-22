package com.soulseekplus.core.links

import java.io.ByteArrayOutputStream

/** Ayrıştırılmış `slsk://` bağlantısı. [path] her zaman Soulseek sanal yoludur (`\` ayırıcılı, baş/son ayırıcısız). */
sealed interface SlskLink {
    val username: String

    /** `slsk://kullanıcı` → kullanıcının paylaşımlarına göz at. */
    data class User(override val username: String) : SlskLink

    /** `slsk://kullanıcı/yol/klasör/` (sondaki eğik çizgi) → klasörü aç / indir. */
    data class Folder(override val username: String, val path: String) : SlskLink

    /** `slsk://kullanıcı/yol/dosya.ext` → dosyayı indir. */
    data class File(override val username: String, val path: String) : SlskLink

    /** Bağlantıyı yeniden `slsk://` dizesine çevirir. */
    fun toUriString(): String = when (this) {
        is User -> SlskUri.forUser(username)
        is Folder -> SlskUri.forFolder(username, path)
        is File -> SlskUri.forFile(username, path)
    }
}

/**
 * Nicotine+ ile uyumlu `slsk://` bağlantıları: `slsk://` + yüzde-kodlanmış `kullanıcı/yol/yol/dosya`.
 * Python `urllib.parse.quote` gibi yalnızca `A-Z a-z 0-9 _ . - ~` ve `/` olduğu gibi bırakılır, gerisi UTF-8 baytları
 * üzerinden `%XX` olur. Sondaki `/` klasör demektir.
 */
object SlskUri {
    const val SCHEME = "slsk://"

    private val LINK_PATTERN = Regex("slsk://[^\\s<>\"']+", RegexOption.IGNORE_CASE)

    fun forUser(username: String): String = SCHEME + encode(username)

    fun forFile(username: String, virtualPath: String): String {
        val path = cleanPath(virtualPath)
        return if (path.isEmpty()) forUser(username) else SCHEME + encode(username) + "/" + encode(path.replace('\\', '/'))
    }

    fun forFolder(username: String, virtualPath: String): String {
        val path = cleanPath(virtualPath)
        return if (path.isEmpty()) forUser(username) else SCHEME + encode(username) + "/" + encode(path.replace('\\', '/')) + "/"
    }

    /** Geçerli değilse (şema yok, kullanıcı boş, `..` bileşeni) null. */
    fun parse(uri: String): SlskLink? {
        val trimmed = uri.trim()
        if (!trimmed.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)) return null
        val rest = trimmed.substring(SCHEME.length)
        val decoded = decode(rest)
        val slash = decoded.indexOf('/')
        val username = (if (slash < 0) decoded else decoded.substring(0, slash)).trim()
        if (username.isEmpty()) return null
        if (slash < 0) return SlskLink.User(username)
        val tail = decoded.substring(slash + 1)
        val isFolder = tail.endsWith("/")
        val segments = tail.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return SlskLink.User(username)
        if (segments.any { it == ".." || it == "." }) return null
        val path = segments.joinToString("\\")
        return if (isFolder) SlskLink.Folder(username, path) else SlskLink.File(username, path)
    }

    /** Serbest metindeki tüm `slsk://` bağlantıları ve metindeki aralıkları (sohbette tıklanabilir yapmak için). */
    fun findLinks(text: String): List<Pair<IntRange, SlskLink>> {
        val out = ArrayList<Pair<IntRange, SlskLink>>()
        for (match in LINK_PATTERN.findAll(text)) {
            // Cümle sonu noktalaması bağlantıya dahil edilmez.
            var value = match.value
            while (value.isNotEmpty() && value.last() in TRAILING_PUNCTUATION) value = value.dropLast(1)
            val link = parse(value) ?: continue
            out += (match.range.first until match.range.first + value.length) to link
        }
        return out
    }

    private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}')

    private fun cleanPath(virtualPath: String): String =
        virtualPath.replace('/', '\\').split('\\').filter { it.isNotEmpty() }.joinToString("\\")

    private fun isUnreserved(b: Int): Boolean =
        (b in 'A'.code..'Z'.code) || (b in 'a'.code..'z'.code) || (b in '0'.code..'9'.code) ||
            b == '_'.code || b == '.'.code || b == '-'.code || b == '~'.code || b == '/'.code

    private fun encode(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xFF
            if (isUnreserved(b)) {
                sb.append(b.toChar())
            } else {
                sb.append('%')
                sb.append(HEX[b shr 4])
                sb.append(HEX[b and 0xF])
            }
        }
        return sb.toString()
    }

    /** Geçersiz `%` dizileri olduğu gibi kalır (Python `unquote` davranışı); sonuç UTF-8 olarak çözülür. */
    private fun decode(text: String): String {
        if ('%' !in text) return text
        val out = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length && hexValue(text[i + 1]) >= 0 && hexValue(text[i + 2]) >= 0) {
                out.write(hexValue(text[i + 1]) * 16 + hexValue(text[i + 2]))
                i += 3
            } else {
                val bytes = c.toString().toByteArray(Charsets.UTF_8)
                if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                    out.write(text.substring(i, i + 2).toByteArray(Charsets.UTF_8))
                    i += 2
                } else {
                    out.write(bytes)
                    i++
                }
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
