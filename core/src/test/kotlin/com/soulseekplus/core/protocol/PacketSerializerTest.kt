package com.soulseekplus.core.protocol

import com.soulseekplus.core.geo.IpCountryResolver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketSerializerTest {

    @Test
    fun primitivesRoundTrip() {
        val w = MessageWriter()
            .uint8(200).bool(true).int32(-5).uint32(0xFFFFFFF0L).uint64(1L shl 40 or 7)
            .string("Müzik\\Şarkı.flac").ip("82.94.1.200")
        val r = MessageReader(w.toByteArray())
        assertEquals(200, r.uint8())
        assertTrue(r.bool())
        assertEquals(-5, r.int32())
        assertEquals(0xFFFFFFF0L, r.uint32())
        assertEquals(1L shl 40 or 7, r.uint64())
        assertEquals("Müzik\\Şarkı.flac", r.string())
        assertEquals("82.94.1.200", r.ip())
        assertEquals(0, r.remaining)
    }

    @Test
    fun littleEndianLayout() {
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), MessageWriter().int32(1).toByteArray())
        // IP baytları ters sırada: 1.2.3.4 → 04 03 02 01
        assertArrayEquals(byteArrayOf(4, 3, 2, 1), MessageWriter().ip("1.2.3.4").toByteArray())
        val frame = Frame.code32(1, byteArrayOf(9))
        assertArrayEquals(byteArrayOf(5, 0, 0, 0, 1, 0, 0, 0, 9), frame)
        assertArrayEquals(byteArrayOf(2, 0, 0, 0, 1, 9), Frame.code8(1, byteArrayOf(9)))
    }

    @Test
    fun invalidUtf8FallsBackToLatin1() {
        val payload = MessageWriter().bytes(byteArrayOf(0x4D, 0xFC.toByte(), 0x73)).toByteArray() // "Müs" latin-1
        assertEquals("Müs", MessageReader(payload).string())
    }

    @Test(expected = ProtocolException::class)
    fun truncatedThrows() {
        MessageReader(byteArrayOf(10, 0, 0, 0, 65)).string()
    }

    @Test
    fun zlibRoundTripAndBombGuard() {
        val data = ByteArray(200_000) { (it % 7).toByte() }
        val packed = Frame.compress(data)
        assertArrayEquals(data, Frame.decompress(packed, 1_000_000))
        try {
            Frame.decompress(packed, 1000)
            throw AssertionError("sınır uygulanmadı")
        } catch (_: ProtocolException) {
        }
    }

    @Test
    fun countryLookup() {
        val r = IpCountryResolver.of(listOf(16777215L to null, 16777471L to "AU", 4294967295L to "TR"))
        assertNull(r.countryOf("0.255.255.255"))
        assertEquals("AU", r.countryOf("1.0.0.1"))
        assertEquals("TR", r.countryOf("88.1.2.3"))
        assertNull(r.countryOf("not.an.ip.x"))
    }

    @Test
    fun bundledCountryDataLoads() {
        val file = listOf("app/src/main/assets/ip_country.bin", "../app/src/main/assets/ip_country.bin")
            .map { java.io.File(it) }.firstOrNull { it.exists() } ?: return
        val r = file.inputStream().use { IpCountryResolver.load(it) }
        assertTrue(r.size > 100_000)
        assertEquals("US", r.countryOf("8.8.8.8"))
        assertEquals("DE", r.countryOf("85.214.132.117"))
        assertNull(r.countryOf("0.0.0.0"))
    }
}
