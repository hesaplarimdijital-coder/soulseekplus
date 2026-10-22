package com.soulseekplus.core.geo

import com.soulseekplus.core.api.CountryResolver
import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.InflaterInputStream

/**
 * IP2Location LITE (CC BY-SA 4.0) verisinden IPv4 → ülke kodu çözümleyici.
 *
 * Varlık biçimi (`ip_country.bin`, zlib): "SPG1", int32 BE adet N, N × uint32 BE aralık sonu (artan),
 * N × 2 bayt ASCII ülke kodu ("--" = bilinmiyor). Bir IP, değeri ≥ IP olan ilk aralığın ülkesine aittir.
 */
class IpCountryResolver private constructor(
    private val ends: IntArray,
    private val codes: CharArray,
) : CountryResolver {

    val size: Int get() = ends.size

    override fun countryOf(ipv4: String): String? {
        val value = parse(ipv4) ?: return null
        if (value == 0L) return null
        var lo = 0
        var hi = ends.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val end = ends[mid].toLong() and 0xFFFFFFFFL
            if (end >= value) {
                found = mid; hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        if (found < 0) return null
        val a = codes[found * 2]
        val b = codes[found * 2 + 1]
        return if (a == '-' || a == ' ') null else "$a$b"
    }

    companion object {
        private val MAGIC = byteArrayOf('S'.code.toByte(), 'P'.code.toByte(), 'G'.code.toByte(), '1'.code.toByte())

        /** Sıkıştırılmış varlığı okur. Akışı kapatmaz. */
        fun load(compressed: InputStream): IpCountryResolver {
            val input = DataInputStream(InflaterInputStream(compressed).buffered(64 * 1024))
            val magic = ByteArray(4)
            input.readFully(magic)
            require(magic.contentEquals(MAGIC)) { "ip_country.bin biçimi tanınmadı" }
            val count = input.readInt()
            require(count in 1..5_000_000) { "Geçersiz aralık sayısı: $count" }
            val ends = IntArray(count) { input.readInt() }
            val raw = ByteArray(count * 2)
            input.readFully(raw)
            val codes = CharArray(raw.size) { raw[it].toInt().toChar() }
            return IpCountryResolver(ends, codes)
        }

        /** Test ve elle oluşturma için. */
        fun of(ranges: List<Pair<Long, String?>>): IpCountryResolver {
            val sorted = ranges.sortedBy { it.first }
            val ends = IntArray(sorted.size) { sorted[it].first.toInt() }
            val codes = CharArray(sorted.size * 2)
            sorted.forEachIndexed { i, (_, c) ->
                val code = c?.takeIf { it.length == 2 } ?: "--"
                codes[i * 2] = code[0]; codes[i * 2 + 1] = code[1]
            }
            return IpCountryResolver(ends, codes)
        }

        /** "a.b.c.d" → işaretsiz değer; geçersizse null. */
        fun parse(ipv4: String): Long? {
            val parts = ipv4.trim().split('.')
            if (parts.size != 4) return null
            var v = 0L
            for (p in parts) {
                val n = p.toIntOrNull() ?: return null
                if (n !in 0..255) return null
                v = (v shl 8) or n.toLong()
            }
            return v
        }
    }
}
