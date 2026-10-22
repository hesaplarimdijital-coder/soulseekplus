package com.soulseekplus.core.protocol

import com.soulseekplus.core.model.UserStats
import java.security.MessageDigest

/** Tel biçimi yardımcıları: güvenli sayaç okuma ve liste okuma/yazma. */

/**
 * uint32 eleman sayısını okur. Her eleman en az [minElementSize] bayt kapladığından, kalan veriye sığmayan sayı
 * bozuk/saldırgan veridir (bellek tahsisini önler) ve [ProtocolException] fırlatır.
 */
fun MessageReader.count(minElementSize: Int = 1): Int {
    val n = uint32()
    if (n > remaining / minElementSize.coerceAtLeast(1)) throw ProtocolException("Geçersiz eleman sayısı: $n")
    return n.toInt()
}

inline fun <T> MessageReader.readList(minElementSize: Int = 1, read: MessageReader.() -> T): List<T> {
    val n = count(minElementSize)
    val out = ArrayList<T>(minOf(n, 1024))
    repeat(n) { out.add(read()) }
    return out
}

inline fun <T> MessageWriter.writeList(items: Collection<T>, write: MessageWriter.(T) -> Unit): MessageWriter {
    uint32(items.size.toLong())
    for (item in items) write(item)
    return this
}

/** uint32 → Int (taşarsa Int.MAX_VALUE). Hız ve sayaç alanları için. */
fun Long.clampToInt(): Int = if (this > Int.MAX_VALUE) Int.MAX_VALUE else if (this < 0) 0 else this.toInt()

/** Sunucu istatistik alanları: avgspeed, uploadnum, unknown, files, dirs. */
fun MessageReader.userStats(): UserStats {
    val speed = uint32().clampToInt()
    val uploads = uint32()
    uint32() // bilinmeyen
    val files = uint32().clampToInt()
    val dirs = uint32().clampToInt()
    return UserStats(speed, uploads, files, dirs)
}

fun MessageWriter.userStats(stats: UserStats): MessageWriter = apply {
    uint32(stats.averageSpeed.toLong() and 0xFFFFFFFFL)
    uint32(stats.uploadCount)
    uint32(0)
    uint32(stats.files.toLong() and 0xFFFFFFFFL)
    uint32(stats.folders.toLong() and 0xFFFFFFFFL)
}

/** Sunucu parolası sağlaması: küçük harf onaltılık MD5(kullanıcı + parola). */
fun md5Hex(text: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(32)
    for (b in digest) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return sb.toString()
}

private val HEX = "0123456789abcdef".toCharArray()

/** Token'ı işaretsiz sayı olarak yazdırır (günlük). */
fun tokenToString(token: Int): String = (token.toLong() and 0xFFFFFFFFL).toString()
