package com.soulseekplus.core.shares

import com.soulseekplus.core.model.FileAttributes
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Ses dosyası başlığından ucuz öznitelik okuyucu (FLAC, WAV, MP3). Dosyanın yalnızca başını okur (en çok ~70 KiB),
 * bu yüzden binlerce dosyada bile hızlıdır; diğer biçimler için çağıran daha yavaş bir yöntem (MediaMetadataRetriever) dener.
 *
 * Çıktı Soulseek öznitelikleridir: bitrate kbps, süre saniye, VBR, örnekleme hızı Hz, bit derinliği.
 */
object AudioHeaderParser {

    /** Bu uzantılar için [parse] anlamlı sonuç verebilir. */
    val SUPPORTED_EXTENSIONS: Set<String> = setOf("flac", "wav", "mp3")

    private const val MAX_SCAN_BYTES = 64 * 1024

    /**
     * [input] dosyanın 0. baytındadır; [fileSize] dosya boyutudur. Tanınmayan ya da bozuk başlıkta null.
     * Akışı kapatmaz. Okuma hataları null döndürür (bozuk/kesik dosya yüklemeyi engellemez).
     */
    fun parse(extension: String, fileSize: Long, input: InputStream): FileAttributes? = try {
        when (extension.lowercase()) {
            "flac" -> parseFlac(fileSize, input)
            "wav" -> parseWav(input)
            "mp3" -> parseMp3(fileSize, input)
            else -> null
        }
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    // ------------------------------------------------------------------ FLAC

    private fun parseFlac(fileSize: Long, input: InputStream): FileAttributes? {
        var id3Bytes = 0L
        var magic = input.readExactly(4)
        if (magic[0] == 'I'.code.toByte() && magic[1] == 'D'.code.toByte() && magic[2] == '3'.code.toByte()) {
            val rest = input.readExactly(6)
            val size = syncSafe(rest, 2)
            input.skipExactly(size.toLong())
            id3Bytes = 10L + size
            magic = input.readExactly(4)
        }
        if (magic[0] != 'f'.code.toByte() || magic[1] != 'L'.code.toByte() || magic[2] != 'a'.code.toByte() || magic[3] != 'C'.code.toByte()) return null
        // Meta veri blokları: STREAMINFO ilk blok olmak zorundadır.
        val header = input.readExactly(4)
        val type = header[0].toInt() and 0x7F
        val length = ((header[1].toInt() and 0xFF) shl 16) or ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        if (type != 0 || length < 34) return null
        val d = input.readExactly(34)
        val sampleRate = ((d[10].toInt() and 0xFF) shl 12) or ((d[11].toInt() and 0xFF) shl 4) or ((d[12].toInt() and 0xFF) shr 4)
        val bitsPerSample = (((d[12].toInt() and 1) shl 4) or ((d[13].toInt() and 0xFF) shr 4)) + 1
        val totalSamples = ((d[13].toLong() and 0x0F) shl 32) or
            ((d[14].toLong() and 0xFF) shl 24) or ((d[15].toLong() and 0xFF) shl 16) or
            ((d[16].toLong() and 0xFF) shl 8) or (d[17].toLong() and 0xFF)
        if (sampleRate <= 0) return null
        val duration = if (totalSamples > 0) (totalSamples / sampleRate).toInt() else null
        val bitrate = if (duration != null && duration > 0 && fileSize > id3Bytes) {
            (((fileSize - id3Bytes) * 8L) / duration / 1000L).toInt()
        } else {
            null
        }
        return FileAttributes(bitrate = bitrate, durationSeconds = duration, vbr = null, sampleRate = sampleRate, bitDepth = bitsPerSample)
    }

    // ------------------------------------------------------------------ WAV

    private fun parseWav(input: InputStream): FileAttributes? {
        val riff = input.readExactly(12)
        val ok = riff[0] == 'R'.code.toByte() && riff[1] == 'I'.code.toByte() && riff[2] == 'F'.code.toByte() &&
            (riff[3] == 'F'.code.toByte() || riff[3] == 'X'.code.toByte()) &&
            riff[8] == 'W'.code.toByte() && riff[9] == 'A'.code.toByte() && riff[10] == 'V'.code.toByte() && riff[11] == 'E'.code.toByte()
        if (!ok) return null
        var sampleRate = 0
        var byteRate = 0L
        var bits = 0
        var scanned = 12
        while (scanned < MAX_SCAN_BYTES) {
            val chunk = input.readExactly(8)
            val id = String(chunk, 0, 4, Charsets.ISO_8859_1)
            val size = le32(chunk, 4)
            scanned += 8
            if (id == "fmt ") {
                if (size < 16) return null
                val fmt = input.readExactly(16)
                sampleRate = le32(fmt, 4).toInt()
                byteRate = le32(fmt, 8)
                bits = le16(fmt, 14)
                val remaining = size - 16 + (size and 1L)
                input.skipExactly(remaining)
                scanned += 16 + remaining.toInt().coerceAtLeast(0)
            } else if (id == "data") {
                if (byteRate <= 0 || sampleRate <= 0) return null
                val duration = (size / byteRate).toInt()
                return FileAttributes(
                    bitrate = (byteRate * 8L / 1000L).toInt(),
                    durationSeconds = duration,
                    vbr = false,
                    sampleRate = sampleRate,
                    bitDepth = bits.takeIf { it > 0 },
                )
            } else {
                val skip = size + (size and 1L)
                if (skip > MAX_SCAN_BYTES) return null
                input.skipExactly(skip)
                scanned += skip.toInt()
            }
        }
        return null
    }

    // ------------------------------------------------------------------ MP3

    private val BITRATES_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val BITRATES_V2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    private val SAMPLE_RATES_V1 = intArrayOf(44100, 48000, 32000, 0)
    private val SAMPLE_RATES_V2 = intArrayOf(22050, 24000, 16000, 0)
    private val SAMPLE_RATES_V25 = intArrayOf(11025, 12000, 8000, 0)

    private fun parseMp3(fileSize: Long, input: InputStream): FileAttributes? {
        var audioStart = 0L
        val first = input.readExactly(10)
        val buffer: ByteArray
        if (first[0] == 'I'.code.toByte() && first[1] == 'D'.code.toByte() && first[2] == '3'.code.toByte()) {
            val size = syncSafe(first, 6) + (if ((first[5].toInt() and 0x10) != 0) 10 else 0)
            input.skipExactly(size.toLong())
            audioStart = 10L + size
            buffer = input.readUpTo(MAX_SCAN_BYTES)
        } else {
            val rest = input.readUpTo(MAX_SCAN_BYTES - 10)
            buffer = first + rest
        }
        // İlk geçerli çerçeve başlığını bul; yanlış eşleşmeyi önlemek için sonraki çerçeve de doğrulanır.
        var i = 0
        while (i + 4 <= buffer.size) {
            val frame = Mp3Frame.parse(buffer, i)
            if (frame != null) {
                val next = i + frame.length
                val confirmed = next + 4 > buffer.size || Mp3Frame.parse(buffer, next) != null
                if (confirmed) return mp3Attributes(frame, buffer, i, fileSize, audioStart + i)
            }
            i++
        }
        return null
    }

    private class Mp3Frame(
        val bitrateKbps: Int,
        val sampleRate: Int,
        val samplesPerFrame: Int,
        val length: Int,
        val mpeg1: Boolean,
        val mono: Boolean,
        val hasCrc: Boolean,
    ) {
        companion object {
            fun parse(b: ByteArray, at: Int): Mp3Frame? {
                if (at + 4 > b.size) return null
                val h = ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
                    ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
                if ((h ushr 21) and 0x7FF != 0x7FF) return null
                val versionBits = (h ushr 19) and 3
                val layerBits = (h ushr 17) and 3
                if (versionBits == 1 || layerBits != 1) return null // yalnızca Layer III
                val bitrateIndex = (h ushr 12) and 0xF
                val rateIndex = (h ushr 10) and 3
                if (bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
                val mpeg1 = versionBits == 3
                val bitrate = (if (mpeg1) BITRATES_V1_L3 else BITRATES_V2_L3)[bitrateIndex]
                val sampleRate = when (versionBits) {
                    3 -> SAMPLE_RATES_V1[rateIndex]
                    2 -> SAMPLE_RATES_V2[rateIndex]
                    else -> SAMPLE_RATES_V25[rateIndex]
                }
                if (bitrate == 0 || sampleRate == 0) return null
                val padding = (h ushr 9) and 1
                val spf = if (mpeg1) 1152 else 576
                val length = (spf / 8) * bitrate * 1000 / sampleRate + padding
                val channelMode = (h ushr 6) and 3
                return Mp3Frame(bitrate, sampleRate, spf, length, mpeg1, channelMode == 3, (h ushr 16) and 1 == 0)
            }
        }
    }

    private fun mp3Attributes(frame: Mp3Frame, buffer: ByteArray, frameAt: Int, fileSize: Long, frameOffsetInFile: Long): FileAttributes {
        val audioBytes = (fileSize - frameOffsetInFile).coerceAtLeast(0)
        val sideInfo = when {
            frame.mpeg1 && !frame.mono -> 32
            frame.mpeg1 -> 17
            !frame.mono -> 17
            else -> 9
        }
        val tagAt = frameAt + 4 + (if (frame.hasCrc) 2 else 0) + sideInfo
        var frames: Long? = null
        var vbr = false
        if (tagAt + 12 <= buffer.size) {
            val tag = String(buffer, tagAt, 4, Charsets.ISO_8859_1)
            if (tag == "Xing" || tag == "Info") {
                vbr = tag == "Xing"
                val flags = be32(buffer, tagAt + 4)
                if ((flags and 1L) != 0L) frames = be32(buffer, tagAt + 8)
            }
        }
        if (frames == null && frameAt + 36 + 18 <= buffer.size && String(buffer, frameAt + 36, 4, Charsets.ISO_8859_1) == "VBRI") {
            vbr = true
            frames = be32(buffer, frameAt + 36 + 14)
        }
        val duration: Int
        val bitrate: Int
        if (frames != null && frames > 0) {
            val seconds = frames.toDouble() * frame.samplesPerFrame / frame.sampleRate
            duration = seconds.toInt()
            bitrate = if (vbr && seconds > 0) ((audioBytes * 8.0) / seconds / 1000.0).toInt() else frame.bitrateKbps
        } else {
            bitrate = frame.bitrateKbps
            duration = ((audioBytes * 8L) / (frame.bitrateKbps * 1000L)).toInt()
        }
        return FileAttributes(bitrate = bitrate, durationSeconds = duration, vbr = vbr, sampleRate = frame.sampleRate, bitDepth = null)
    }

    // ------------------------------------------------------------------ yardımcılar

    private fun syncSafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun le16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun be32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun InputStream.readExactly(count: Int): ByteArray {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = read(out, read, count - read)
            if (n < 0) throw EOFException()
            read += n
        }
        return out
    }

    /** En çok [max] bayt okur; akış bitince daha azını döndürür. */
    private fun InputStream.readUpTo(max: Int): ByteArray {
        val out = ByteArray(max)
        var read = 0
        while (read < max) {
            val n = read(out, read, max - read)
            if (n < 0) break
            read += n
        }
        return out.copyOf(read)
    }

    private fun InputStream.skipExactly(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped > 0) {
                left -= skipped
            } else {
                if (read() < 0) throw EOFException()
                left--
            }
        }
    }
}
