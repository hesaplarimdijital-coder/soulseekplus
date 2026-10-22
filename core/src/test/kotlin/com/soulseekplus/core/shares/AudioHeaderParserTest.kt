package com.soulseekplus.core.shares

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AudioHeaderParserTest {

    private fun parse(ext: String, bytes: ByteArray, size: Long = bytes.size.toLong()) =
        AudioHeaderParser.parse(ext, size, ByteArrayInputStream(bytes))

    private fun flac(sampleRate: Int, bits: Int, totalSamples: Long, id3: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        if (id3) {
            out.write("ID3".toByteArray()); out.write(byteArrayOf(4, 0, 0, 0, 0, 0, 5)); out.write(ByteArray(5))
        }
        out.write("fLaC".toByteArray())
        out.write(byteArrayOf(0x80.toByte(), 0, 0, 34))
        val d = ByteArray(34)
        d[10] = (sampleRate shr 12).toByte()
        d[11] = (sampleRate shr 4).toByte()
        d[12] = (((sampleRate and 0xF) shl 4) or (1 shl 1) or (((bits - 1) shr 4) and 1)).toByte()
        d[13] = ((((bits - 1) and 0xF) shl 4) or ((totalSamples shr 32).toInt() and 0xF)).toByte()
        d[14] = (totalSamples shr 24).toByte()
        d[15] = (totalSamples shr 16).toByte()
        d[16] = (totalSamples shr 8).toByte()
        d[17] = totalSamples.toByte()
        out.write(d)
        return out.toByteArray()
    }

    @Test
    fun flacStreamInfo() {
        val bytes = flac(44100, 16, 44100L * 200)
        val a = parse("flac", bytes, size = 30_000_000)!!
        assertEquals(44100, a.sampleRate)
        assertEquals(16, a.bitDepth)
        assertEquals(200, a.durationSeconds)
        assertEquals(1200, a.bitrate) // 30 MB * 8 / 200 s
    }

    @Test
    fun flacHiResAndId3Prefix() {
        val a = parse("FLAC", flac(96000, 24, 96000L * 60, id3 = true), size = 40_000_000)!!
        assertEquals(96000, a.sampleRate)
        assertEquals(24, a.bitDepth)
        assertEquals(60, a.durationSeconds)
    }

    @Test
    fun flacRejectsGarbage() {
        assertNull(parse("flac", "notflacatall_padding_padding_padding_padding_padding".toByteArray()))
        assertNull(parse("flac", ByteArray(3)))
    }

    private fun wav(sampleRate: Int, channels: Int, bits: Int, dataBytes: Long, extraChunk: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        fun le32(v: Long) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun le16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray()); le32(dataBytes + 36); out.write("WAVE".toByteArray())
        if (extraChunk) { out.write("LIST".toByteArray()); le32(5); out.write(ByteArray(5)); out.write(0) }
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(channels); le32(sampleRate.toLong())
        le32(sampleRate.toLong() * channels * bits / 8); le16(channels * bits / 8); le16(bits)
        out.write("data".toByteArray()); le32(dataBytes)
        return out.toByteArray()
    }

    @Test
    fun wavFormat() {
        val a = parse("wav", wav(48000, 2, 24, 48000L * 2 * 3 * 10))!!
        assertEquals(48000, a.sampleRate)
        assertEquals(24, a.bitDepth)
        assertEquals(10, a.durationSeconds)
        assertEquals(2304, a.bitrate)
        val b = parse("wav", wav(44100, 2, 16, 44100L * 4 * 3, extraChunk = true))!!
        assertEquals(3, b.durationSeconds)
        assertEquals(1411, b.bitrate)
    }

    private fun mp3Header(bitrateIdx: Int, rateIdx: Int, mpeg1: Boolean = true, stereo: Boolean = true, padding: Int = 0): ByteArray {
        val version = if (mpeg1) 3 else 2
        val h = (0x7FF shl 21) or (version shl 19) or (1 shl 17) or (1 shl 16) or (bitrateIdx shl 12) or (rateIdx shl 10) or
            (padding shl 9) or ((if (stereo) 0 else 3) shl 6)
        return byteArrayOf((h shr 24).toByte(), (h shr 16).toByte(), (h shr 8).toByte(), h.toByte())
    }

    @Test
    fun mp3ConstantBitrate() {
        // 128 kbps, 44.1 kHz → çerçeve 417 bayt; iki çerçeve arka arkaya
        val frame = ByteArray(417)
        mp3Header(9, 0).copyInto(frame)
        val bytes = frame + frame + ByteArray(100)
        val size = 4_000_000L
        val a = parse("mp3", bytes, size)!!
        assertEquals(128, a.bitrate)
        assertEquals(44100, a.sampleRate)
        assertEquals(false, a.vbr)
        assertEquals((size * 8 / 128_000).toInt(), a.durationSeconds)
    }

    @Test
    fun mp3WithId3AndXingVbr() {
        val frame = ByteArray(417)
        mp3Header(9, 0).copyInto(frame)
        val xingAt = 4 + 32
        "Xing".toByteArray().copyInto(frame, xingAt)
        frame[xingAt + 7] = 1 // flags: frames
        val frames = 38_282L // ≈ 1000 s
        frame[xingAt + 8] = (frames shr 24).toByte(); frame[xingAt + 9] = (frames shr 16).toByte()
        frame[xingAt + 10] = (frames shr 8).toByte(); frame[xingAt + 11] = frames.toByte()
        val id3 = "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, 20) + ByteArray(20)
        val bytes = id3 + frame + frame
        val size = 10_000_000L
        val a = parse("mp3", bytes, size)!!
        assertEquals(true, a.vbr)
        assertEquals(44100, a.sampleRate)
        assertEquals(1000, a.durationSeconds)
        val expected = ((size - 30) * 8.0 / (38_282.0 * 1152 / 44100) / 1000.0).toInt()
        assertEquals(expected, a.bitrate)
    }

    @Test
    fun mp3Mpeg2AndSkipsJunk() {
        val frame = ByteArray(200)
        mp3Header(8, 0, mpeg1 = false).copyInto(frame) // MPEG2 L3 idx 8 = 64 kbps @ 22.05 kHz
        val len = 72 * 64000 / 22050 // 576/8 * br / sr
        val bytes = ByteArray(37) + ByteArray(len).also { mp3Header(8, 0, mpeg1 = false).copyInto(it) } + ByteArray(len).also { mp3Header(8, 0, mpeg1 = false).copyInto(it) } + ByteArray(50)
        val a = parse("mp3", bytes, 640_000)!!
        assertEquals(64, a.bitrate)
        assertEquals(22050, a.sampleRate)
        assertNotNull(a.durationSeconds)
    }

    @Test
    fun mp3GarbageAndUnsupported() {
        assertNull(parse("mp3", ByteArray(5000)))
        assertNull(parse("ogg", ByteArray(100)))
        assertNull(parse("mp3", ByteArray(2)))
    }
}
