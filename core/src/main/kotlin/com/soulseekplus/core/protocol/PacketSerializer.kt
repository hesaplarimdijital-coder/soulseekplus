package com.soulseekplus.core.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/*
 * Soulseek tel biçimi (bkz. docs/slsk-protocol.md §1):
 *  - tüm tamsayılar little-endian; string = uint32 bayt uzunluğu + UTF-8 bayt (sonlandırıcı yok);
 *  - alınan string önce katı UTF-8, olmazsa ISO-8859-1 olarak çözülür;
 *  - IP 4 bayttır ve LE uint32 değeri noktalı biçimde büyük-endian okunur.
 */

/** Gelen yük okunurken veri bitti ya da bozuk. */
class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Mesaj yükü yazıcı. Kod ve uzunluk önekini [Frame] ekler; bu sınıf yalnızca yükü üretir. */
class MessageWriter(initialCapacity: Int = 64) {
    private var buf: ByteArray = ByteArray(initialCapacity.coerceAtLeast(16))
    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        val need = size + extra
        if (need > buf.size) buf = buf.copyOf(maxOf(need, buf.size * 2))
    }

    fun uint8(value: Int): MessageWriter = apply {
        ensure(1); buf[size++] = value.toByte()
    }

    fun bool(value: Boolean): MessageWriter = uint8(if (value) 1 else 0)

    fun int32(value: Int): MessageWriter = apply {
        ensure(4)
        buf[size++] = value.toByte()
        buf[size++] = (value ushr 8).toByte()
        buf[size++] = (value ushr 16).toByte()
        buf[size++] = (value ushr 24).toByte()
    }

    /** Tokenlar ve sayaçlar uint32'dir; [value] 0..0xFFFFFFFF aralığında olmalıdır. */
    fun uint32(value: Long): MessageWriter = int32(value.toInt())

    fun uint64(value: Long): MessageWriter = apply {
        ensure(8)
        var v = value
        repeat(8) { buf[size++] = v.toByte(); v = v ushr 8 }
    }

    fun string(value: String): MessageWriter = bytes(value.toByteArray(StandardCharsets.UTF_8))

    /** Eski Soulseek NS istemcileri için: Latin-1 ile kodlanabiliyorsa Latin-1, değilse UTF-8. */
    fun legacyString(value: String): MessageWriter {
        val encoder = StandardCharsets.ISO_8859_1.newEncoder()
        return if (encoder.canEncode(value)) bytes(value.toByteArray(StandardCharsets.ISO_8859_1)) else string(value)
    }

    /** uint32 uzunluk + ham baytlar. */
    fun bytes(value: ByteArray): MessageWriter = apply {
        int32(value.size)
        raw(value)
    }

    /** Uzunluk öneki olmadan ham baytlar. */
    fun raw(value: ByteArray, offset: Int = 0, length: Int = value.size - offset): MessageWriter = apply {
        ensure(length)
        System.arraycopy(value, offset, buf, size, length)
        size += length
    }

    /** "a.b.c.d" → 4 bayt (okumanın tersi). */
    fun ip(address: String): MessageWriter {
        val parts = address.split('.').map { it.toInt() and 0xFF }
        require(parts.size == 4) { "IPv4 değil: $address" }
        val value = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        return int32(value)
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Mesaj yükü okuyucu. Okuma sınırı aşılırsa [ProtocolException]. */
class MessageReader(private val data: ByteArray, private var pos: Int = 0, private val end: Int = data.size) {

    val remaining: Int get() = end - pos
    val position: Int get() = pos
    fun hasRemaining(): Boolean = pos < end

    private fun need(n: Int) {
        if (n < 0 || pos + n > end) throw ProtocolException("Beklenmeyen mesaj sonu (gereken $n, kalan ${end - pos})")
    }

    fun uint8(): Int {
        need(1); return data[pos++].toInt() and 0xFF
    }

    fun bool(): Boolean = uint8() != 0

    fun int32(): Int {
        need(4)
        val v = (data[pos].toInt() and 0xFF) or
            ((data[pos + 1].toInt() and 0xFF) shl 8) or
            ((data[pos + 2].toInt() and 0xFF) shl 16) or
            ((data[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun uint32(): Long = int32().toLong() and 0xFFFFFFFFL

    fun uint16(): Int {
        need(2)
        val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun uint64(): Long {
        need(8)
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
        pos += 8
        return v
    }

    fun bytes(): ByteArray {
        val len = uint32()
        if (len > remaining) throw ProtocolException("Geçersiz uzunluk $len (kalan $remaining)")
        val n = len.toInt()
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun string(): String {
        val len = uint32()
        if (len > remaining) throw ProtocolException("Geçersiz string uzunluğu $len (kalan $remaining)")
        val n = len.toInt()
        val s = decodeString(data, pos, n)
        pos += n
        return s
    }

    /** 4 bayt → "a.b.c.d". */
    fun ip(): String {
        val v = int32()
        return "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"
    }

    fun skip(n: Int) {
        need(n); pos += n
    }

    /** Kalan tüm baytlar. */
    fun rest(): ByteArray {
        val out = data.copyOfRange(pos, end)
        pos = end
        return out
    }

    companion object {
        /** Katı UTF-8; geçersizse ISO-8859-1 (Nicotine+ davranışı). */
        fun decodeString(data: ByteArray, offset: Int, length: Int): String {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(data, offset, length)).toString()
            } catch (_: CharacterCodingException) {
                String(data, offset, length, StandardCharsets.ISO_8859_1)
            }
        }
    }
}

/** Bağlantı türüne göre çerçeve yardımcıları. */
object Frame {
    /** Gelen mesaj için üst sınırlar (bkz. protokol §1). */
    const val MAX_SERVER_MESSAGE = 448 * 1024 * 1024
    const val MAX_INIT_MESSAGE = 16 * 1024
    const val MAX_DISTRIB_MESSAGE = 16 * 1024
    const val MAX_PEER_DEFAULT = 1 * 1024 * 1024
    const val MAX_PEER_LARGE = 16 * 1024 * 1024
    const val MAX_PEER_HUGE = 448 * 1024 * 1024

    /** uint32 uzunluk + uint32 kod + yük (sunucu ve "P" eş mesajları). */
    fun code32(code: Int, payload: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(4 + payload.size).putInt(code).put(payload)
        return out.array()
    }

    /** uint32 uzunluk + uint8 kod + yük (eş başlangıç ve dağıtık "D" mesajları). */
    fun code8(code: Int, payload: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(5 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(1 + payload.size).put(code.toByte()).put(payload)
        return out.array()
    }

    /** RFC1950 zlib (Nicotine+ seviye 4 kullanır; 6 da uyumludur). */
    fun compress(payload: ByteArray, level: Int = 6): ByteArray {
        val deflater = Deflater(level)
        try {
            deflater.setInput(payload)
            deflater.finish()
            val out = ByteArrayOutputStream(maxOf(64, payload.size / 3))
            val chunk = ByteArray(64 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(chunk)
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** zlib açma; [maxSize] aşılırsa (sıkıştırma bombası) [ProtocolException]. */
    fun decompress(payload: ByteArray, maxSize: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(payload)
            val out = ByteArrayOutputStream(maxOf(256, payload.size * 3))
            val chunk = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(chunk)
                } catch (e: DataFormatException) {
                    throw ProtocolException("Bozuk zlib verisi", e)
                }
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(chunk, 0, n)
                if (out.size() > maxSize) throw ProtocolException("Açılan veri sınırı aştı ($maxSize)")
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
